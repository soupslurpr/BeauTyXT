//! Bridges Android's bounded editor requests to the Rust document core.

use std::collections::BTreeMap;
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs::File;
use std::io::{ErrorKind, Seek, Write};
use std::os::fd::{AsRawFd, BorrowedFd, IntoRawFd, RawFd};
use std::os::unix::fs::MetadataExt;
use std::sync::{LazyLock, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use beautyxt_editor_bridge_protocol::{
    EncodeError, encode_document_metrics, encode_edit_window, encode_find, encode_viewport,
};
use beautyxt_editor_core::{
    Document, DocumentError, DocumentSnapshot, EditWindowRequest, FindDirection,
    FindHighlightRequest, FindRequest, MAX_FIND_CANDIDATE_UTF16_UNITS, MAX_FIND_QUERY_BYTES,
    MAX_FIND_QUERY_UTF16_UNITS, PreparedSourceSave, PreviousViewportRequest,
    SourceSavePackageMetrics, Utf16Range, ViewportPosition, ViewportRequest,
};
use jni::EnvUnowned;
use jni::errors::{Error as JniError, ThrowRuntimeExAndDefault};
use jni::objects::{JByteArray, JClass, JLongArray, JString};
use jni::sys::{jboolean, jint, jlong};

const FIRST_DOCUMENT_HANDLE: i64 = 1;
const FIRST_SNAPSHOT_HANDLE: i64 = 1;
const FIND_DIRECTION_FORWARD: jint = 0;
const FIND_DIRECTION_BACKWARD: jint = 1;
const MIN_FIND_CANDIDATE_UTF16_UNITS: usize = 2;
const MAX_SNAPSHOT_TIMEOUT_MILLIS: u64 = 15 * 60 * 1000;
const MAX_SNAPSHOT_POLL_WAIT_MILLIS: u128 = 100;
const OUTPUT_DESCRIPTOR_INDEX: usize = 0;
const CANCELLATION_DESCRIPTOR_INDEX: usize = 1;
const SOURCE_SAVE_METRIC_COUNT: usize = 5;
const SOURCE_SAVE_PACKAGE_BYTES_INDEX: usize = 0;
const SOURCE_SAVE_OUTPUT_BYTES_INDEX: usize = 1;
const SOURCE_SAVE_PAYLOAD_BYTES_INDEX: usize = 2;
const SOURCE_SAVE_BACKING_BYTES_INDEX: usize = 3;
const SOURCE_SAVE_RECORD_COUNT_INDEX: usize = 4;
const NO_SOURCE_SAVE_BACKING_BYTES: jlong = -1;
const NO_SOURCE_SAVE_BACKING_FD: jint = -1;
const REQUIRED_SOURCE_SEALS: libc::c_int =
    libc::F_SEAL_WRITE | libc::F_SEAL_GROW | libc::F_SEAL_SHRINK | libc::F_SEAL_SEAL;

static DOCUMENTS: LazyLock<Mutex<DocumentRegistry>> =
    LazyLock::new(|| Mutex::new(DocumentRegistry::new()));
static SNAPSHOTS: LazyLock<Mutex<SnapshotRegistry>> =
    LazyLock::new(|| Mutex::new(SnapshotRegistry::new()));

/// Owns live documents behind opaque positive handles.
struct DocumentRegistry {
    next_handle: i64,
    documents: BTreeMap<i64, Document>,
}

impl DocumentRegistry {
    /// Creates an empty document registry.
    fn new() -> Self {
        Self {
            next_handle: FIRST_DOCUMENT_HANDLE,
            documents: BTreeMap::new(),
        }
    }

    /// Inserts a document and returns its opaque handle.
    fn insert(&mut self, document: Document) -> Result<i64, BridgeError> {
        let handle = self.next_handle;
        self.next_handle = self
            .next_handle
            .checked_add(1)
            .ok_or(BridgeError::DocumentHandleSpaceExhausted)?;
        self.documents.insert(handle, document);
        Ok(handle)
    }

    /// Returns a shared document for a valid handle.
    fn get(&self, handle: i64) -> Result<&Document, BridgeError> {
        self.documents
            .get(&handle)
            .ok_or(BridgeError::UnknownHandle(handle))
    }

    /// Returns an exclusive document for a valid handle.
    fn get_mut(&mut self, handle: i64) -> Result<&mut Document, BridgeError> {
        self.documents
            .get_mut(&handle)
            .ok_or(BridgeError::UnknownHandle(handle))
    }

    /// Removes a document for a valid handle.
    fn remove(&mut self, handle: i64) -> Result<(), BridgeError> {
        self.documents
            .remove(&handle)
            .map(|_| ())
            .ok_or(BridgeError::UnknownHandle(handle))
    }

    /// Captures an exact immutable revision for independent ownership.
    fn capture_snapshot(
        &self,
        handle: i64,
        expected_revision: u64,
    ) -> Result<DocumentSnapshot, BridgeError> {
        let document = self.get(handle)?;
        let actual_revision = document.metrics().revision;
        if expected_revision != actual_revision {
            return Err(DocumentError::StaleRevision {
                expected: expected_revision,
                actual: actual_revision,
            }
            .into());
        }
        Ok(document.snapshot())
    }
}

/// Owns immutable document snapshots behind opaque positive handles.
struct SnapshotRegistry {
    next_handle: i64,
    snapshots: BTreeMap<i64, SnapshotState>,
}

/// Owns one captured or package-prepared immutable revision.
enum SnapshotState {
    /// Retains a revision that can still choose either output path.
    Captured(DocumentSnapshot),

    /// Retains a revision committed to compact source-save output.
    Prepared(PreparedSourceSave),
}

impl SnapshotRegistry {
    /// Creates an empty snapshot registry.
    fn new() -> Self {
        Self {
            next_handle: FIRST_SNAPSHOT_HANDLE,
            snapshots: BTreeMap::new(),
        }
    }

    /// Inserts a snapshot and returns its monotonic opaque handle.
    fn insert(&mut self, snapshot: DocumentSnapshot) -> Result<i64, BridgeError> {
        let handle = self.next_handle;
        self.next_handle = self
            .next_handle
            .checked_add(1)
            .ok_or(BridgeError::SnapshotHandleSpaceExhausted)?;
        self.snapshots
            .insert(handle, SnapshotState::Captured(snapshot));
        Ok(handle)
    }

    /// Returns one captured snapshot without consuming its handle.
    fn get_captured(&self, handle: i64) -> Result<&DocumentSnapshot, BridgeError> {
        match self
            .snapshots
            .get(&handle)
            .ok_or(BridgeError::UnknownSnapshotHandle(handle))?
        {
            SnapshotState::Captured(snapshot) => Ok(snapshot),
            SnapshotState::Prepared(_) => Err(BridgeError::InvalidArgument(
                "prepared source-save snapshot state",
            )),
        }
    }

    /// Takes exclusive ownership of a snapshot handle for one-shot streaming.
    fn take_captured(&mut self, handle: i64) -> Result<DocumentSnapshot, BridgeError> {
        let state = self
            .snapshots
            .get(&handle)
            .ok_or(BridgeError::UnknownSnapshotHandle(handle))?;
        if !matches!(state, SnapshotState::Captured(_)) {
            return Err(BridgeError::InvalidArgument(
                "prepared source-save snapshot state",
            ));
        }
        match self
            .snapshots
            .remove(&handle)
            .expect("validated captured snapshot handle must remain present")
        {
            SnapshotState::Captured(snapshot) => Ok(snapshot),
            SnapshotState::Prepared(_) => {
                unreachable!("validated captured snapshot state must remain stable")
            }
        }
    }

    /// Stores one prepared source-save plan under its original handle.
    fn store_prepared(
        &mut self,
        handle: i64,
        prepared: PreparedSourceSave,
    ) -> Result<(), BridgeError> {
        if self.snapshots.contains_key(&handle) {
            return Err(BridgeError::InvalidArgument(
                "occupied source-save snapshot handle",
            ));
        }
        self.snapshots
            .insert(handle, SnapshotState::Prepared(prepared));
        Ok(())
    }

    /// Takes one prepared package for one-shot descriptor output.
    fn take_prepared(&mut self, handle: i64) -> Result<PreparedSourceSave, BridgeError> {
        let state = self
            .snapshots
            .get(&handle)
            .ok_or(BridgeError::UnknownSnapshotHandle(handle))?;
        if !matches!(state, SnapshotState::Prepared(_)) {
            return Err(BridgeError::InvalidArgument(
                "unprepared source-save snapshot state",
            ));
        }
        match self
            .snapshots
            .remove(&handle)
            .expect("validated prepared snapshot handle must remain present")
        {
            SnapshotState::Prepared(prepared) => Ok(prepared),
            SnapshotState::Captured(_) => {
                unreachable!("validated prepared snapshot state must remain stable")
            }
        }
    }

    /// Removes an unused snapshot for a valid handle.
    fn remove(&mut self, handle: i64) -> Result<(), BridgeError> {
        self.snapshots
            .remove(&handle)
            .map(drop)
            .ok_or(BridgeError::UnknownSnapshotHandle(handle))
    }
}

/// Reports failures at the Android-to-Rust boundary.
#[derive(Debug)]
enum BridgeError {
    Document(DocumentError),
    Encode(EncodeError),
    Io(std::io::Error),
    Jni(JniError),
    InvalidArgument(&'static str),
    UnknownHandle(i64),
    UnknownSnapshotHandle(i64),
    DocumentHandleSpaceExhausted,
    SnapshotHandleSpaceExhausted,
    DocumentRegistryPoisoned,
    SnapshotRegistryPoisoned,
}

impl Display for BridgeError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Document(error) => Display::fmt(error, formatter),
            Self::Encode(error) => Display::fmt(error, formatter),
            Self::Io(error) => write!(formatter, "descriptor operation failed: {error}"),
            Self::Jni(error) => write!(formatter, "jni operation failed: {error}"),
            Self::InvalidArgument(argument) => write!(formatter, "invalid {argument}"),
            Self::UnknownHandle(handle) => write!(formatter, "unknown document handle {handle}"),
            Self::UnknownSnapshotHandle(handle) => {
                write!(formatter, "unknown document snapshot handle {handle}")
            }
            Self::DocumentHandleSpaceExhausted => {
                formatter.write_str("document handle space exhausted")
            }
            Self::SnapshotHandleSpaceExhausted => {
                formatter.write_str("document snapshot handle space exhausted")
            }
            Self::DocumentRegistryPoisoned => formatter.write_str("document registry poisoned"),
            Self::SnapshotRegistryPoisoned => {
                formatter.write_str("document snapshot registry poisoned")
            }
        }
    }
}

impl Error for BridgeError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Document(error) => Some(error),
            Self::Encode(error) => Some(error),
            Self::Io(error) => Some(error),
            Self::Jni(error) => Some(error),
            _ => None,
        }
    }
}

impl From<DocumentError> for BridgeError {
    fn from(error: DocumentError) -> Self {
        Self::Document(error)
    }
}

impl From<EncodeError> for BridgeError {
    fn from(error: EncodeError) -> Self {
        Self::Encode(error)
    }
}

impl From<std::io::Error> for BridgeError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<JniError> for BridgeError {
    fn from(error: JniError) -> Self {
        Self::Jni(error)
    }
}

/// Creates an empty native document and returns its handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_createEmpty<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jlong {
    unowned_env
        .with_env(|_| -> Result<jlong, BridgeError> {
            let handle = lock_registry()?.insert(Document::new())?;
            Ok(handle)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Opens a format-preserving UTF-8 document from a duplicated descriptor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_openSource<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    raw_fd: jint,
    expected_bytes: jlong,
) -> jlong {
    unowned_env
        .with_env(|_| -> Result<jlong, BridgeError> {
            let file = duplicate_file_descriptor(raw_fd)?;
            let expected_bytes = nonnegative_usize(expected_bytes, "expected byte count")?;
            seal_and_validate_source(&file, expected_bytes)?;
            let document = Document::open_source(file)?;
            let handle = lock_registry()?.insert(document)?;
            Ok(handle)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns one encoded, revision-tagged viewport packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_viewport<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    start_line: jlong,
    start_utf16_offset: jlong,
    max_blocks: jint,
    max_block_utf16_units: jint,
    max_total_utf16_units: jint,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
            let request = viewport_request(
                revision,
                start_line,
                start_utf16_offset,
                max_blocks,
                max_block_utf16_units,
                max_total_utf16_units,
            )?;
            let snapshot = lock_registry()?.get(handle)?.viewport(request)?;
            let packet = encode_viewport(&snapshot)?;
            Ok(env.byte_array_from_slice(&packet)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns one encoded viewport from an immutable snapshot handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_snapshotViewport<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    snapshot_handle: jlong,
    revision: jlong,
    start_line: jlong,
    start_utf16_offset: jlong,
    max_blocks: jint,
    max_block_utf16_units: jint,
    max_total_utf16_units: jint,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
            let request = viewport_request(
                revision,
                start_line,
                start_utf16_offset,
                max_blocks,
                max_block_utf16_units,
                max_total_utf16_units,
            )?;
            let snapshot = lock_snapshot_registry()?
                .get_captured(snapshot_handle)?
                .viewport(request)?;
            let packet = encode_viewport(&snapshot)?;
            Ok(env.byte_array_from_slice(&packet)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns one encoded viewport packet immediately preceding an end position.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_previousViewport<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    end_line: jlong,
    end_utf16_offset: jlong,
    max_blocks: jint,
    max_block_utf16_units: jint,
    max_total_utf16_units: jint,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
            let request = PreviousViewportRequest {
                end: ViewportPosition {
                    revision: nonnegative_u64(revision, "viewport revision")?,
                    line: nonnegative_usize(end_line, "end line")?,
                    utf16_offset: nonnegative_usize(end_utf16_offset, "end utf-16 offset")?,
                },
                max_blocks: positive_usize(max_blocks, "maximum blocks")?,
                max_block_utf16_units: positive_usize(
                    max_block_utf16_units,
                    "maximum block utf-16 units",
                )?,
                max_total_utf16_units: positive_usize(
                    max_total_utf16_units,
                    "maximum total utf-16 units",
                )?,
            };
            let snapshot = lock_registry()?.get(handle)?.previous_viewport(request)?;
            let packet = encode_viewport(&snapshot)?;
            Ok(env.byte_array_from_slice(&packet)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns one fixed-size bounded literal find packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_find<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    query: JString<'caller>,
    match_case: jboolean,
    candidate_start_utf16: jlong,
    candidate_end_utf16: jlong,
    direction: jint,
    max_candidate_utf16_units: jint,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
            let revision = nonnegative_u64(revision, "find revision")?;
            let query: String = query.to_string();
            validate_find_query(&query)?;
            let request = FindRequest {
                revision,
                query: &query,
                match_case,
                candidate_range: Utf16Range::new(
                    nonnegative_usize(candidate_start_utf16, "candidate start")?,
                    nonnegative_usize(candidate_end_utf16, "candidate end")?,
                ),
                direction: find_direction(direction)?,
                max_candidate_utf16_units: find_candidate_limit(max_candidate_utf16_units)?,
            };
            let snapshot = {
                let registry = lock_registry()?;
                registry.capture_snapshot(handle, revision)?
            };
            let batch = snapshot.find(request)?;
            let packet = encode_find(&batch)?;
            Ok(env.byte_array_from_slice(&packet)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns sorted, merged global UTF-16 highlight pairs for one bounded displayed range.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_findHighlights<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    query: JString<'caller>,
    match_case: jboolean,
    range_start_utf16: jlong,
    range_end_utf16: jlong,
) -> JLongArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JLongArray<'caller>, BridgeError> {
            let revision = nonnegative_u64(revision, "highlight revision")?;
            let query: String = query.to_string();
            validate_find_query(&query)?;
            let request = FindHighlightRequest {
                revision,
                query: &query,
                match_case,
                range: Utf16Range::new(
                    nonnegative_usize(range_start_utf16, "highlight start")?,
                    nonnegative_usize(range_end_utf16, "highlight end")?,
                ),
            };
            let snapshot = lock_registry()?.capture_snapshot(handle, revision)?;
            let highlights = snapshot.find_highlights(request)?;
            let coordinates = highlights
                .into_iter()
                .flat_map(|range| [range.start, range.end])
                .map(|offset| {
                    jlong::try_from(offset)
                        .map_err(|_| BridgeError::InvalidArgument("highlight offset"))
                })
                .collect::<Result<Vec<_>, _>>()?;
            let result = env.new_long_array(coordinates.len())?;
            result.set_region(env, 0, &coordinates)?;
            Ok(result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns one exact logical-line start in global UTF-16 coordinates.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_lineStartUtf16<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    logical_line: jlong,
) -> jlong {
    unowned_env
        .with_env(|_| -> Result<jlong, BridgeError> {
            let offset = lock_registry()?.get(handle)?.line_start_utf16(
                nonnegative_u64(revision, "line-start revision")?,
                nonnegative_usize(logical_line, "logical line")?,
            )?;
            jlong::try_from(offset)
                .map_err(|_| BridgeError::InvalidArgument("line-start utf-16 offset"))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns one encoded, revision-tagged edit-window packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_editWindow<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    selection_start_utf16: jlong,
    selection_end_utf16: jlong,
    max_utf16_units: jint,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
            let request = EditWindowRequest {
                revision: nonnegative_u64(revision, "edit-window revision")?,
                selection: Utf16Range::new(
                    nonnegative_usize(selection_start_utf16, "selection start")?,
                    nonnegative_usize(selection_end_utf16, "selection end")?,
                ),
                max_utf16_units: positive_usize(
                    max_utf16_units,
                    "maximum edit-window utf-16 units",
                )?,
            };
            let snapshot = lock_registry()?.get(handle)?.edit_window(request)?;
            let packet = encode_edit_window(&snapshot)?;
            Ok(env.byte_array_from_slice(&packet)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Replaces an exact UTF-16 range and returns the resulting metrics packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_replace<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    expected_revision: jlong,
    start_utf16: jlong,
    end_utf16: jlong,
    replacement: JString<'caller>,
) -> JByteArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
            let replacement: String = replacement.to_string();
            let outcome = lock_registry()?.get_mut(handle)?.replace(
                nonnegative_u64(expected_revision, "expected revision")?,
                Utf16Range::new(
                    nonnegative_usize(start_utf16, "start utf-16 offset")?,
                    nonnegative_usize(end_utf16, "end utf-16 offset")?,
                ),
                &replacement,
            );
            let metrics = match outcome {
                Ok(metrics) => metrics,
                Err(DocumentError::DocumentTooLargeForSaving { .. }) => {
                    env.throw_new(
                        jni::jni_str!(
                            "dev/soupslurpr/beautyxt/document/DocumentSizeLimitException"
                        ),
                        jni::jni_str!("edit exceeds the serialized document size limit"),
                    )?;
                    return Ok(JByteArray::null());
                }
                Err(error) => return Err(error.into()),
            };
            let packet = encode_document_metrics(&metrics)?;
            Ok(env.byte_array_from_slice(&packet)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Captures one exact immutable document revision behind an independent handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_captureSnapshot<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    expected_revision: jlong,
) -> jlong {
    unowned_env
        .with_env(|_| -> Result<jlong, BridgeError> {
            let expected_revision =
                nonnegative_u64(expected_revision, "expected snapshot revision")?;
            let snapshot = {
                let registry = lock_registry()?;
                registry.capture_snapshot(handle, expected_revision)?
            };
            lock_snapshot_registry()?.insert(snapshot)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Prepares one immutable revision and returns its fixed package metrics.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_prepareSourceSavePackage<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    snapshot_handle: jlong,
) -> JLongArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JLongArray<'caller>, BridgeError> {
            let snapshot = lock_snapshot_registry()?.take_captured(snapshot_handle)?;
            let prepared = snapshot.prepare_source_save()?;
            let metrics = prepared.package_metrics();
            lock_snapshot_registry()?.store_prepared(snapshot_handle, prepared)?;
            let values = encode_source_save_metrics(metrics)?;
            let result = env.new_long_array(SOURCE_SAVE_METRIC_COUNT)?;
            result.set_region(env, 0, &values)?;
            Ok(result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Consumes one prepared revision into an interruptible sealed-package buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_writeSourceSavePackage<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    snapshot_handle: jlong,
    package_raw_fd: jint,
    cancellation_raw_fd: jint,
    timeout_millis: jlong,
) -> jint {
    unowned_env
        .with_env(|_| -> Result<jint, BridgeError> {
            let prepared = lock_snapshot_registry()?.take_prepared(snapshot_handle)?;
            let metrics = prepared.package_metrics();
            let mut package = duplicate_file_descriptor(package_raw_fd)?;
            let cancellation = duplicate_file_descriptor(cancellation_raw_fd)?;
            let package_flags = validate_source_save_descriptors(
                &mut package,
                &cancellation,
                metrics.package_bytes,
            )?;
            set_nonblocking(&package, package_flags)?;
            let timeout = snapshot_timeout(timeout_millis)?;
            let deadline = Instant::now()
                .checked_add(timeout)
                .ok_or(BridgeError::InvalidArgument("source-save package timeout"))?;
            let mut writer = SnapshotWriter::new(package, cancellation, deadline);
            prepared.write_package(&mut writer)?;
            writer.flush()?;
            validate_completed_source_save_package(&mut writer.output, metrics.package_bytes)?;
            Ok(prepared
                .into_source()
                .map_or(NO_SOURCE_SAVE_BACKING_FD, File::into_raw_fd))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Consumes one immutable snapshot into an interruptible descriptor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_writeSnapshot<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    snapshot_handle: jlong,
    output_raw_fd: jint,
    cancellation_raw_fd: jint,
    timeout_millis: jlong,
) -> jlong {
    unowned_env
        .with_env(|_| -> Result<jlong, BridgeError> {
            let snapshot = lock_snapshot_registry()?.take_captured(snapshot_handle)?;
            let output = duplicate_file_descriptor(output_raw_fd)?;
            let cancellation = duplicate_file_descriptor(cancellation_raw_fd)?;
            let output_flags = validate_snapshot_descriptors(&output, &cancellation)?;
            set_nonblocking(&output, output_flags)?;
            let timeout = snapshot_timeout(timeout_millis)?;
            let deadline = Instant::now()
                .checked_add(timeout)
                .ok_or(BridgeError::InvalidArgument("snapshot timeout"))?;
            let mut writer = SnapshotWriter::new(output, cancellation, deadline);
            snapshot.write_to(&mut writer)?;
            writer.flush()?;
            i64::try_from(snapshot.metrics().serialized_bytes)
                .map_err(|_| BridgeError::InvalidArgument("snapshot byte count"))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Closes one unused native snapshot handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_closeSnapshot<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    snapshot_handle: jlong,
) {
    unowned_env
        .with_env(|_| -> Result<(), BridgeError> {
            lock_snapshot_registry()?.remove(snapshot_handle)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Closes a native document handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_close<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_| -> Result<(), BridgeError> { lock_registry()?.remove(handle) })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Returns exclusive access to the global registry.
fn lock_registry() -> Result<MutexGuard<'static, DocumentRegistry>, BridgeError> {
    DOCUMENTS
        .lock()
        .map_err(|_| BridgeError::DocumentRegistryPoisoned)
}

/// Returns exclusive access to the global snapshot registry.
fn lock_snapshot_registry() -> Result<MutexGuard<'static, SnapshotRegistry>, BridgeError> {
    SNAPSHOTS
        .lock()
        .map_err(|_| BridgeError::SnapshotRegistryPoisoned)
}

/// Duplicates a borrowed Android descriptor with close-on-exec semantics.
fn duplicate_file_descriptor(raw_fd: RawFd) -> Result<File, BridgeError> {
    if raw_fd < 0 {
        return Err(BridgeError::InvalidArgument("file descriptor"));
    }

    // SAFETY: Android guarantees that ParcelFileDescriptor.getFd() remains a
    // valid borrowed descriptor for the duration of this synchronous call.
    let borrowed = unsafe { BorrowedFd::borrow_raw(raw_fd) };
    let owned = borrowed.try_clone_to_owned()?;
    Ok(File::from(owned))
}

/// Validates the distinct output and cancellation descriptor capabilities.
fn validate_snapshot_descriptors(
    output: &File,
    cancellation: &File,
) -> Result<libc::c_int, BridgeError> {
    let output_flags = descriptor_control(output, libc::F_GETFL, 0)?;
    let cancellation_flags = descriptor_control(cancellation, libc::F_GETFL, 0)?;
    if output_flags & libc::O_ACCMODE == libc::O_RDONLY
        || output_flags & libc::O_APPEND != 0
        || output_flags & libc::O_PATH != 0
    {
        return Err(BridgeError::InvalidArgument("snapshot output access mode"));
    }
    if cancellation_flags & libc::O_ACCMODE == libc::O_WRONLY
        || cancellation_flags & libc::O_PATH != 0
    {
        return Err(BridgeError::InvalidArgument(
            "snapshot cancellation access mode",
        ));
    }
    let output_metadata = output.metadata()?;
    let cancellation_metadata = cancellation.metadata()?;
    if output_metadata.ino() != 0
        && output_metadata.dev() == cancellation_metadata.dev()
        && output_metadata.ino() == cancellation_metadata.ino()
    {
        return Err(BridgeError::InvalidArgument("snapshot descriptor alias"));
    }
    Ok(output_flags)
}

/// Validates one pre-sized anonymous package and distinct cancellation input.
fn validate_source_save_descriptors(
    package: &mut File,
    cancellation: &File,
    expected_bytes: u64,
) -> Result<libc::c_int, BridgeError> {
    let package_flags = validate_snapshot_descriptors(package, cancellation)?;
    let metadata = package.metadata()?;
    if !metadata.is_file()
        || metadata.nlink() != 0
        || metadata.uid() != process_uid()
        || metadata.len() != expected_bytes
    {
        return Err(BridgeError::InvalidArgument(
            "source-save package descriptor",
        ));
    }
    if descriptor_control(package, libc::F_GET_SEALS, 0)? != libc::F_SEAL_GROW {
        return Err(BridgeError::InvalidArgument("source-save package seals"));
    }
    if package.stream_position()? != 0 {
        return Err(BridgeError::InvalidArgument("source-save package position"));
    }
    Ok(package_flags)
}

/// Verifies one package write reached its exact immutable allocation end.
fn validate_completed_source_save_package(
    package: &mut File,
    expected_bytes: u64,
) -> Result<(), BridgeError> {
    if package.stream_position()? != expected_bytes {
        return Err(BridgeError::InvalidArgument(
            "source-save package output length",
        ));
    }
    let metadata = package.metadata()?;
    if !metadata.is_file()
        || metadata.nlink() != 0
        || metadata.uid() != process_uid()
        || metadata.len() != expected_bytes
        || descriptor_control(package, libc::F_GET_SEALS, 0)? != libc::F_SEAL_GROW
    {
        return Err(BridgeError::InvalidArgument(
            "source-save package completion",
        ));
    }
    Ok(())
}

/// Returns the current process user identity.
fn process_uid() -> u32 {
    // SAFETY: getuid reads process identity without dereferencing pointers.
    unsafe { libc::getuid() }
}

/// Adds nonblocking behavior while preserving all existing descriptor flags.
fn set_nonblocking(file: &File, flags: libc::c_int) -> Result<(), BridgeError> {
    descriptor_control(file, libc::F_SETFL, flags | libc::O_NONBLOCK)?;
    Ok(())
}

/// Converts one bounded positive Java timeout into a duration.
fn snapshot_timeout(timeout_millis: jlong) -> Result<Duration, BridgeError> {
    let timeout_millis = u64::try_from(timeout_millis)
        .map_err(|_| BridgeError::InvalidArgument("snapshot timeout"))?;
    if timeout_millis == 0 || timeout_millis > MAX_SNAPSHOT_TIMEOUT_MILLIS {
        return Err(BridgeError::InvalidArgument("snapshot timeout"));
    }
    Ok(Duration::from_millis(timeout_millis))
}

/// Encodes one validated package description into the fixed Kotlin order.
fn encode_source_save_metrics(
    metrics: SourceSavePackageMetrics,
) -> Result<[jlong; SOURCE_SAVE_METRIC_COUNT], BridgeError> {
    let mut values = [0; SOURCE_SAVE_METRIC_COUNT];
    values[SOURCE_SAVE_PACKAGE_BYTES_INDEX] = jlong::try_from(metrics.package_bytes)
        .map_err(|_| BridgeError::InvalidArgument("source-save package byte count"))?;
    values[SOURCE_SAVE_OUTPUT_BYTES_INDEX] = jlong::try_from(metrics.output_bytes)
        .map_err(|_| BridgeError::InvalidArgument("source-save output byte count"))?;
    values[SOURCE_SAVE_PAYLOAD_BYTES_INDEX] = jlong::try_from(metrics.payload_bytes)
        .map_err(|_| BridgeError::InvalidArgument("source-save payload byte count"))?;
    values[SOURCE_SAVE_BACKING_BYTES_INDEX] =
        metrics
            .source_bytes
            .map_or(Ok(NO_SOURCE_SAVE_BACKING_BYTES), |byte_length| {
                jlong::try_from(byte_length)
                    .map_err(|_| BridgeError::InvalidArgument("source-save backing byte count"))
            })?;
    values[SOURCE_SAVE_RECORD_COUNT_INDEX] = jlong::try_from(metrics.record_count)
        .map_err(|_| BridgeError::InvalidArgument("source-save record count"))?;
    Ok(values)
}

/// Waits until snapshot output is writable, cancelled, or expired.
fn wait_for_snapshot_output(
    output: &File,
    cancellation: &File,
    deadline: Instant,
) -> std::io::Result<()> {
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(std::io::Error::new(
                ErrorKind::TimedOut,
                "snapshot deadline exceeded",
            ));
        }
        let timeout_millis = libc::c_int::try_from(
            remaining
                .as_millis()
                .clamp(1, MAX_SNAPSHOT_POLL_WAIT_MILLIS),
        )
        .expect("bounded snapshot poll timeout should fit");
        let mut descriptors = [
            libc::pollfd {
                fd: output.as_raw_fd(),
                events: libc::POLLOUT,
                revents: 0,
            },
            libc::pollfd {
                fd: cancellation.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        // SAFETY: `descriptors` remains a valid writable array, and both files
        // own their descriptors for the complete poll call.
        let result = unsafe {
            libc::poll(
                descriptors.as_mut_ptr(),
                libc::nfds_t::try_from(descriptors.len())
                    .expect("snapshot poll descriptor count should fit"),
                timeout_millis,
            )
        };
        if result < 0 {
            let error = std::io::Error::last_os_error();
            if error.kind() == ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if descriptors[CANCELLATION_DESCRIPTOR_INDEX].revents != 0 {
            return Err(std::io::Error::new(
                ErrorKind::ConnectionAborted,
                "snapshot cancelled",
            ));
        }
        if descriptors[OUTPUT_DESCRIPTOR_INDEX].revents & libc::POLLNVAL != 0 {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                "snapshot output became invalid",
            ));
        }
        if descriptors[OUTPUT_DESCRIPTOR_INDEX].revents != 0 {
            return Ok(());
        }
    }
}

/// Writes a document snapshot with bounded cancellation latency.
struct SnapshotWriter {
    output: File,
    cancellation: File,
    deadline: Instant,
}

impl SnapshotWriter {
    /// Creates an interruptible snapshot writer.
    const fn new(output: File, cancellation: File, deadline: Instant) -> Self {
        Self {
            output,
            cancellation,
            deadline,
        }
    }
}

impl Write for SnapshotWriter {
    /// Writes after output becomes ready or the cancellation boundary wins.
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        loop {
            wait_for_snapshot_output(&self.output, &self.cancellation, self.deadline)?;
            match self.output.write(buffer) {
                Err(error)
                    if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) => {}
                result => return result,
            }
        }
    }

    /// Flushes only while the snapshot deadline and cancellation remain live.
    fn flush(&mut self) -> std::io::Result<()> {
        wait_for_snapshot_output(&self.output, &self.cancellation, self.deadline)?;
        self.output.flush()
    }
}

/// Seals and validates one immutable source descriptor.
fn seal_and_validate_source(file: &File, expected_bytes: usize) -> Result<(), BridgeError> {
    let flags = descriptor_control(file, libc::F_GETFL, 0)?;
    if flags & libc::O_ACCMODE == libc::O_WRONLY {
        return Err(BridgeError::InvalidArgument("source access mode"));
    }
    let initial_metadata = file.metadata()?;
    validate_source_metadata(&initial_metadata, expected_bytes)?;

    let initial_seals = descriptor_control(file, libc::F_GET_SEALS, 0)?;
    if initial_seals & REQUIRED_SOURCE_SEALS != REQUIRED_SOURCE_SEALS {
        if initial_seals & libc::F_SEAL_SEAL != 0 {
            return Err(BridgeError::InvalidArgument("source seals"));
        }
        descriptor_control(file, libc::F_ADD_SEALS, REQUIRED_SOURCE_SEALS)?;
    }
    let final_seals = descriptor_control(file, libc::F_GET_SEALS, 0)?;
    if final_seals & REQUIRED_SOURCE_SEALS != REQUIRED_SOURCE_SEALS {
        return Err(BridgeError::InvalidArgument("source seals"));
    }

    let final_metadata = file.metadata()?;
    validate_source_metadata(&final_metadata, expected_bytes)?;
    if initial_metadata.dev() != final_metadata.dev()
        || initial_metadata.ino() != final_metadata.ino()
    {
        return Err(BridgeError::InvalidArgument("source identity"));
    }
    Ok(())
}

/// Validates one anonymous regular source and its exact byte length.
fn validate_source_metadata(
    metadata: &std::fs::Metadata,
    expected_bytes: usize,
) -> Result<(), BridgeError> {
    if !metadata.is_file() || metadata.nlink() != 0 {
        return Err(BridgeError::InvalidArgument("source type"));
    }
    if metadata.uid() != process_uid() {
        return Err(BridgeError::InvalidArgument("source owner"));
    }
    if metadata.len()
        != u64::try_from(expected_bytes)
            .map_err(|_| BridgeError::InvalidArgument("expected byte count"))?
    {
        return Err(BridgeError::InvalidArgument("source length"));
    }
    Ok(())
}

/// Runs one integer descriptor-control operation and preserves its OS error.
fn descriptor_control(
    file: &File,
    command: libc::c_int,
    argument: libc::c_int,
) -> Result<libc::c_int, BridgeError> {
    // SAFETY: fcntl receives the live descriptor owned by `file`, and every
    // selected command accepts one integer argument.
    let result = unsafe { libc::fcntl(file.as_raw_fd(), command, argument) };
    if result < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    Ok(result)
}

/// Converts a nonnegative Java long into an unsigned revision.
fn nonnegative_u64(value: jlong, argument: &'static str) -> Result<u64, BridgeError> {
    u64::try_from(value).map_err(|_| BridgeError::InvalidArgument(argument))
}

/// Converts a nonnegative Java long into a platform-sized offset.
fn nonnegative_usize(value: jlong, argument: &'static str) -> Result<usize, BridgeError> {
    usize::try_from(value).map_err(|_| BridgeError::InvalidArgument(argument))
}

/// Converts a positive Java integer into a platform-sized limit.
fn positive_usize(value: jint, argument: &'static str) -> Result<usize, BridgeError> {
    let value = usize::try_from(value).map_err(|_| BridgeError::InvalidArgument(argument))?;
    if value == 0 {
        return Err(BridgeError::InvalidArgument(argument));
    }
    Ok(value)
}

/// Converts Java viewport fields into one validated bounded request.
fn viewport_request(
    revision: jlong,
    start_line: jlong,
    start_utf16_offset: jlong,
    max_blocks: jint,
    max_block_utf16_units: jint,
    max_total_utf16_units: jint,
) -> Result<ViewportRequest, BridgeError> {
    Ok(ViewportRequest {
        start: ViewportPosition {
            revision: nonnegative_u64(revision, "viewport revision")?,
            line: nonnegative_usize(start_line, "start line")?,
            utf16_offset: nonnegative_usize(start_utf16_offset, "start utf-16 offset")?,
        },
        max_blocks: positive_usize(max_blocks, "maximum blocks")?,
        max_block_utf16_units: positive_usize(max_block_utf16_units, "maximum block utf-16 units")?,
        max_total_utf16_units: positive_usize(max_total_utf16_units, "maximum total utf-16 units")?,
    })
}

/// Converts one Java direction tag into the closed Rust find enum.
fn find_direction(value: jint) -> Result<FindDirection, BridgeError> {
    match value {
        FIND_DIRECTION_FORWARD => Ok(FindDirection::Forward),
        FIND_DIRECTION_BACKWARD => Ok(FindDirection::Backward),
        _ => Err(BridgeError::InvalidArgument("find direction")),
    }
}

/// Converts one Java find-work limit into the mirrored native range.
fn find_candidate_limit(value: jint) -> Result<usize, BridgeError> {
    let value =
        usize::try_from(value).map_err(|_| BridgeError::InvalidArgument("find candidate limit"))?;
    if !(MIN_FIND_CANDIDATE_UTF16_UNITS..=MAX_FIND_CANDIDATE_UTF16_UNITS).contains(&value) {
        return Err(BridgeError::InvalidArgument("find candidate limit"));
    }
    Ok(value)
}

/// Validates a Java query before it enters the bounded core request.
fn validate_find_query(query: &str) -> Result<(), BridgeError> {
    if query.is_empty()
        || query.contains('\r')
        || query.len() > MAX_FIND_QUERY_BYTES
        || query.encode_utf16().count() > MAX_FIND_QUERY_UTF16_UNITS
    {
        return Err(BridgeError::InvalidArgument("find query"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::ffi::CString;
    use std::fs::{File, OpenOptions, remove_file};
    use std::io::{ErrorKind, Seek, SeekFrom, Write};
    use std::os::fd::FromRawFd;
    use std::path::{Path, PathBuf};
    use std::sync::mpsc;
    use std::time::{Duration, Instant};

    use beautyxt_editor_core::{
        Document, DocumentError, FindDirection, FindRequest, MAX_FIND_CANDIDATE_UTF16_UNITS,
        Utf16Range, ViewportPosition, ViewportRequest,
    };

    use super::{
        BridgeError, DocumentRegistry, FIND_DIRECTION_BACKWARD, FIND_DIRECTION_FORWARD,
        REQUIRED_SOURCE_SEALS, SnapshotRegistry, SnapshotWriter, descriptor_control,
        find_candidate_limit, find_direction, seal_and_validate_source, set_nonblocking,
        validate_find_query,
    };

    const TEST_SOURCE_BYTES: &[u8] = b"sealed source";
    const TEST_DOCUMENT_TEXT: &str = "captured revision";
    const TEST_REPLACEMENT_TEXT: &str = "new";
    const TEST_INITIAL_REVISION: u64 = 0;
    const TEST_NEXT_REVISION: u64 = 1;
    const TEST_SNAPSHOT_TIMEOUT: Duration = Duration::from_secs(30);
    const TEST_COMPLETION_TIMEOUT: Duration = Duration::from_secs(5);

    /// Verifies snapshots keep exact bytes after edits and source closure.
    #[test]
    fn snapshots_outlive_mutable_documents() {
        let mut documents = DocumentRegistry::new();
        let document_handle = documents
            .insert(Document::from_text(TEST_DOCUMENT_TEXT))
            .expect("test document should receive a handle");
        let stale_result = documents.capture_snapshot(document_handle, TEST_NEXT_REVISION);
        assert!(matches!(
            stale_result,
            Err(BridgeError::Document(DocumentError::StaleRevision {
                expected: TEST_NEXT_REVISION,
                actual: TEST_INITIAL_REVISION,
            }))
        ));

        let mut snapshots = SnapshotRegistry::new();
        let unused_handle = snapshots
            .insert(
                documents
                    .capture_snapshot(document_handle, TEST_INITIAL_REVISION)
                    .expect("current revision should capture"),
            )
            .expect("unused snapshot should receive a handle");
        assert!(unused_handle > 0);
        snapshots
            .remove(unused_handle)
            .expect("unused snapshot should close");

        let snapshot_handle = snapshots
            .insert(
                documents
                    .capture_snapshot(document_handle, TEST_INITIAL_REVISION)
                    .expect("current revision should capture again"),
            )
            .expect("streamed snapshot should receive a handle");
        assert!(snapshot_handle > unused_handle);
        let edited_metrics = documents
            .get_mut(document_handle)
            .expect("test document should remain open")
            .replace(
                TEST_INITIAL_REVISION,
                Utf16Range::new(0, TEST_DOCUMENT_TEXT.len()),
                TEST_REPLACEMENT_TEXT,
            )
            .expect("test edit should succeed");
        assert_eq!(edited_metrics.revision, TEST_NEXT_REVISION);
        assert_eq!(edited_metrics.bytes, TEST_REPLACEMENT_TEXT.len());
        documents
            .remove(document_handle)
            .expect("mutable document should close");

        let viewport = snapshots
            .get_captured(snapshot_handle)
            .expect("detached snapshot should remain readable")
            .viewport(ViewportRequest {
                start: ViewportPosition::default(),
                max_blocks: 1,
                max_block_utf16_units: TEST_DOCUMENT_TEXT.len(),
                max_total_utf16_units: TEST_DOCUMENT_TEXT.len(),
            })
            .expect("detached snapshot viewport should succeed");
        assert_eq!(viewport.blocks[0].text, TEST_DOCUMENT_TEXT);

        let snapshot = snapshots
            .take_captured(snapshot_handle)
            .expect("snapshot should transfer into the stream");
        let mut output = Vec::new();
        snapshot
            .write_to(&mut output)
            .expect("detached snapshot should stream");

        assert_eq!(output, TEST_DOCUMENT_TEXT.as_bytes());
        assert!(matches!(
            snapshots.take_captured(snapshot_handle),
            Err(BridgeError::UnknownSnapshotHandle(handle)) if handle == snapshot_handle
        ));
    }

    /// Verifies source-save state mismatches never consume snapshot ownership.
    #[test]
    fn preserves_snapshot_ownership_across_state_validation() {
        let mut snapshots = SnapshotRegistry::new();
        let captured_handle = snapshots
            .insert(Document::from_text(TEST_DOCUMENT_TEXT).snapshot())
            .expect("captured snapshot should receive a handle");

        assert!(matches!(
            snapshots.take_prepared(captured_handle),
            Err(BridgeError::InvalidArgument(
                "unprepared source-save snapshot state"
            ))
        ));
        let captured = snapshots
            .take_captured(captured_handle)
            .expect("state mismatch should preserve the captured snapshot");
        let prepared = captured
            .prepare_source_save()
            .expect("captured snapshot should prepare");
        let expected_metrics = prepared.package_metrics();
        snapshots
            .store_prepared(captured_handle, prepared)
            .expect("prepared snapshot should retain its original handle");

        assert!(matches!(
            snapshots.take_captured(captured_handle),
            Err(BridgeError::InvalidArgument(
                "prepared source-save snapshot state"
            ))
        ));
        let prepared = snapshots
            .take_prepared(captured_handle)
            .expect("state mismatch should preserve the prepared snapshot");

        assert_eq!(prepared.package_metrics(), expected_metrics);
        assert!(matches!(
            snapshots.take_prepared(captured_handle),
            Err(BridgeError::UnknownSnapshotHandle(handle)) if handle == captured_handle
        ));
    }

    /// Verifies bounded find work uses an immutable revision detached from registry mutation.
    #[test]
    fn finds_in_detached_document_snapshot() {
        const TEXT: &str = "alpha target omega";
        const QUERY: &str = "target";
        const MATCH_START: usize = 6;
        const MATCH_END: usize = MATCH_START + QUERY.len();

        let mut documents = DocumentRegistry::new();
        let handle = documents
            .insert(Document::from_text(TEXT))
            .expect("test document should receive a handle");
        let snapshot = documents
            .capture_snapshot(handle, TEST_INITIAL_REVISION)
            .expect("current revision should capture");
        documents
            .get_mut(handle)
            .expect("test document should remain open")
            .replace(
                TEST_INITIAL_REVISION,
                Utf16Range::new(MATCH_START, MATCH_END),
                TEST_REPLACEMENT_TEXT,
            )
            .expect("mutable revision should change");

        let batch = snapshot
            .find(FindRequest {
                revision: TEST_INITIAL_REVISION,
                query: QUERY,
                match_case: true,
                candidate_range: Utf16Range::new(0, TEXT.len()),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: TEXT.len(),
            })
            .expect("detached snapshot should remain searchable");

        assert_eq!(
            batch
                .matched
                .expect("snapshot should retain its match")
                .range,
            Utf16Range::new(MATCH_START, MATCH_END)
        );
        assert_eq!(
            documents
                .get(handle)
                .expect("mutable document should remain registered")
                .metrics()
                .revision,
            TEST_NEXT_REVISION
        );
    }

    /// Verifies JNI direction and work-limit tags form a closed bounded set.
    #[test]
    fn validates_find_bridge_arguments() {
        assert_eq!(
            find_direction(FIND_DIRECTION_FORWARD).expect("forward tag should decode"),
            FindDirection::Forward
        );
        assert_eq!(
            find_direction(FIND_DIRECTION_BACKWARD).expect("backward tag should decode"),
            FindDirection::Backward
        );
        assert!(matches!(
            find_direction(2),
            Err(BridgeError::InvalidArgument("find direction"))
        ));

        assert_eq!(
            find_candidate_limit(2).expect("minimum work limit should decode"),
            2
        );
        assert_eq!(
            find_candidate_limit(
                i32::try_from(MAX_FIND_CANDIDATE_UTF16_UNITS)
                    .expect("native work limit should fit Java")
            )
            .expect("maximum work limit should decode"),
            MAX_FIND_CANDIDATE_UTF16_UNITS
        );
        assert!(matches!(
            find_candidate_limit(1),
            Err(BridgeError::InvalidArgument("find candidate limit"))
        ));
        assert!(matches!(
            find_candidate_limit(-1),
            Err(BridgeError::InvalidArgument("find candidate limit"))
        ));

        validate_find_query("literal").expect("bounded literal query should pass");
        validate_find_query("line\nbreak").expect("normalized line feed should pass");
        assert!(matches!(
            validate_find_query(""),
            Err(BridgeError::InvalidArgument("find query"))
        ));
        assert!(matches!(
            validate_find_query("line\rbreak"),
            Err(BridgeError::InvalidArgument("find query"))
        ));
    }

    /// Verifies validated source buffers reject every later mutation.
    #[test]
    fn seals_source_against_mutation() {
        let mut source = create_sealable_memfd();

        seal_and_validate_source(&source, TEST_SOURCE_BYTES.len())
            .expect("anonymous source should seal");

        let seals = descriptor_control(&source, libc::F_GET_SEALS, 0)
            .expect("source seals should remain readable");
        assert_eq!(seals & REQUIRED_SOURCE_SEALS, REQUIRED_SOURCE_SEALS);
        let write_error = source
            .write_all(b"x")
            .expect_err("sealed source should reject writes");
        assert_eq!(write_error.raw_os_error(), Some(libc::EPERM));
        let resize_error = source
            .set_len(0)
            .expect_err("sealed source should reject resizing");
        assert_eq!(resize_error.raw_os_error(), Some(libc::EPERM));
    }

    /// Verifies an incorrect terminal byte count rejects the source.
    #[test]
    fn rejects_incorrect_source_length() {
        let source = create_sealable_memfd();

        let result = seal_and_validate_source(&source, TEST_SOURCE_BYTES.len() + 1);

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("source length"))
        ));
    }

    /// Verifies an already permanent incomplete seal set is rejected.
    #[test]
    fn rejects_incomplete_permanent_source_seals() {
        let source = create_sealable_memfd();
        descriptor_control(&source, libc::F_ADD_SEALS, libc::F_SEAL_SEAL)
            .expect("test source should accept its permanent seal");

        let result = seal_and_validate_source(&source, TEST_SOURCE_BYTES.len());

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("source seals"))
        ));
    }

    /// Verifies a path-linked regular file cannot become a document source.
    #[test]
    fn rejects_linked_source() {
        let path = linked_source_path();
        let mut source = create_linked_source(&path);
        source
            .write_all(TEST_SOURCE_BYTES)
            .expect("linked source should be writable");

        let result = seal_and_validate_source(&source, TEST_SOURCE_BYTES.len());
        drop(source);
        remove_file(path).expect("linked test source should be removable");

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("source type"))
        ));
    }

    /// Verifies cancellation wakes a producer blocked by a saturated pipe.
    #[test]
    fn cancels_saturated_snapshot_output() {
        let (output_reader, mut output_writer) = create_pipe();
        let (cancellation_reader, mut cancellation_writer) = create_pipe();
        let output_flags = descriptor_control(&output_writer, libc::F_GETFL, 0)
            .expect("output flags should be readable");
        set_nonblocking(&output_writer, output_flags)
            .expect("test output should become nonblocking");
        let pipe_capacity = usize::try_from(
            descriptor_control(&output_writer, libc::F_GETPIPE_SZ, 0)
                .expect("pipe capacity should be readable"),
        )
        .expect("pipe capacity should be nonnegative");
        output_writer
            .write_all(&vec![0_u8; pipe_capacity])
            .expect("test pipe should accept its reported capacity");
        let (started_sender, started_receiver) = mpsc::channel();
        let (result_sender, result_receiver) = mpsc::channel();
        let producer = std::thread::spawn(move || {
            let deadline = Instant::now() + TEST_SNAPSHOT_TIMEOUT;
            let mut writer = SnapshotWriter::new(output_writer, cancellation_reader, deadline);
            started_sender
                .send(())
                .expect("producer start should be observable");
            result_sender
                .send(writer.write_all(b"blocked"))
                .expect("producer result should be observable");
        });
        started_receiver
            .recv_timeout(TEST_COMPLETION_TIMEOUT)
            .expect("snapshot producer should start promptly");

        cancellation_writer
            .write_all(&[1])
            .expect("cancellation signal should be writable");
        drop(cancellation_writer);
        let error = result_receiver
            .recv_timeout(TEST_COMPLETION_TIMEOUT)
            .expect("snapshot producer should finish promptly")
            .expect_err("cancelled snapshot output should fail");

        assert_eq!(error.kind(), ErrorKind::ConnectionAborted);
        producer.join().expect("snapshot producer should join");
        drop(output_reader);
    }

    /// Creates one anonymous, writable source with sealing enabled.
    fn create_sealable_memfd() -> File {
        let name = CString::new("beautyxt-editor-jni-test")
            .expect("test descriptor name should not contain nul bytes");
        // SAFETY: `name` is a live nul-terminated string, and the flags are
        // valid for Linux and Android memfd creation.
        let raw_file_descriptor = unsafe {
            libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC | libc::MFD_ALLOW_SEALING)
        };
        assert!(
            raw_file_descriptor >= 0,
            "test memfd creation failed: {}",
            std::io::Error::last_os_error()
        );
        // SAFETY: successful memfd creation returns one newly owned descriptor.
        let mut source = unsafe { File::from_raw_fd(raw_file_descriptor) };
        source
            .write_all(TEST_SOURCE_BYTES)
            .expect("test source should be writable");
        source
            .seek(SeekFrom::Start(0))
            .expect("test source should rewind");
        source
    }

    /// Creates one close-on-exec unidirectional pipe pair.
    fn create_pipe() -> (File, File) {
        let mut raw_file_descriptors = [-1; 2];
        // SAFETY: `raw_file_descriptors` is writable for two descriptors, and
        // O_CLOEXEC is valid for pipe2 on Linux and Android.
        let result = unsafe { libc::pipe2(raw_file_descriptors.as_mut_ptr(), libc::O_CLOEXEC) };
        assert_eq!(
            result,
            0,
            "test pipe creation failed: {}",
            std::io::Error::last_os_error()
        );
        // SAFETY: successful pipe2 returns two distinct newly owned handles.
        let reader = unsafe { File::from_raw_fd(raw_file_descriptors[0]) };
        // SAFETY: successful pipe2 returns two distinct newly owned handles.
        let writer = unsafe { File::from_raw_fd(raw_file_descriptors[1]) };
        (reader, writer)
    }

    /// Creates one uniquely named linked source file.
    fn create_linked_source(path: &Path) -> File {
        match remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => panic!("stale linked source should be removable: {error}"),
        }
        OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(path)
            .expect("linked test source should be created")
    }

    /// Returns one process-unique linked source path.
    fn linked_source_path() -> PathBuf {
        std::env::temp_dir().join(format!(
            "beautyxt-editor-jni-linked-{}.tmp",
            std::process::id()
        ))
    }
}
