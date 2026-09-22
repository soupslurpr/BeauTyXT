//! Bridges isolated Android export requests to the byte-preserving Rust snapshot copier.

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs::{File, Metadata};
use std::io::{ErrorKind, Read, Seek, SeekFrom, Write};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::{FileExt, MetadataExt};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::time::{Duration, Instant};

use beautyxt_export_core::{
    ExportControl, ExportError, ExportInterruption, ExportLimits, ExportSummary,
    HARD_MAX_SNAPSHOT_BYTES, STREAM_BUFFER_BYTES, export_utf8_snapshot_bytes,
};
use beautyxt_source_save_core::{
    MAX_PACKAGE_BYTES, PACKAGE_HEADER_BYTES, PackageHeader, PackageRecord, PackageValidator,
    RECORD_HEADER_BYTES, RECORD_KIND_PAYLOAD, RECORD_KIND_SOURCE, ValidatedPackage,
};
use sha2::{Digest, Sha256};
const JOB_ACTIVE: u8 = 0;
const JOB_WRITING: u8 = 1;
const JOB_CANCELLED: u8 = 2;
const JOB_COMMITTED: u8 = 3;
const SHA_256_BYTE_COUNT: usize = 32;
const NO_EXPECTED_SOURCE_BYTE_LENGTH: i64 = -1;
const MAX_TIMEOUT_MILLIS: u64 = 15 * 60 * 1000;
const MAX_POLL_WAIT_MILLIS: u128 = 100;
const IO_DESCRIPTOR_INDEX: usize = 0;
const CANCELLATION_DESCRIPTOR_INDEX: usize = 1;
const PACKAGE_RECORD_VALIDATION_BATCH: usize = 256;

pub(crate) const RESULT_SUCCESS: i32 = 0;
pub(crate) const RESULT_CANCELLED: i32 = 1;
pub(crate) const RESULT_INPUT_LIMIT: i32 = 2;
pub(crate) const RESULT_INPUT_LENGTH_MISMATCH: i32 = 3;
pub(crate) const RESULT_TIMEOUT: i32 = 4;
pub(crate) const RESULT_INVALID_DESCRIPTOR: i32 = 5;
pub(crate) const RESULT_INPUT_IO: i32 = 6;
pub(crate) const RESULT_OUTPUT_IO: i32 = 7;
pub(crate) const RESULT_INTERNAL: i32 = 8;
pub(crate) const RESULT_SOURCE_CONFLICT: i32 = 9;
pub(crate) const RESULT_SOURCE_UNCERTAIN: i32 = 10;
const REQUIRED_IMMUTABLE_INPUT_SEALS: libc::c_int =
    libc::F_SEAL_WRITE | libc::F_SEAL_GROW | libc::F_SEAL_SHRINK | libc::F_SEAL_SEAL;

pub(crate) struct JobControl {
    started: AtomicBool,
    terminal_state: AtomicU8,
    cancellation_signal: OwnedFd,
}

impl JobControl {
    /// Creates a control with a nonblocking close-on-exec event descriptor.
    pub(crate) fn new() -> Result<Self, BridgeError> {
        // SAFETY: eventfd receives valid flags and returns a new owned descriptor.
        let raw_fd = unsafe { libc::eventfd(0, libc::EFD_CLOEXEC | libc::EFD_NONBLOCK) };
        if raw_fd < 0 {
            return Err(BridgeError::Io(std::io::Error::last_os_error()));
        }
        // SAFETY: the successful eventfd call transferred one new descriptor.
        let cancellation_signal = unsafe { OwnedFd::from_raw_fd(raw_fd) };
        Ok(Self {
            started: AtomicBool::new(false),
            terminal_state: AtomicU8::new(JOB_ACTIVE),
            cancellation_signal,
        })
    }

    /// Cancels an uncommitted job and wakes blocked descriptor polls.
    pub(crate) fn cancel(&self) -> Result<(), BridgeError> {
        loop {
            let current_state = self.terminal_state.load(Ordering::Acquire);
            match current_state {
                JOB_ACTIVE | JOB_WRITING => {
                    if self
                        .terminal_state
                        .compare_exchange(
                            current_state,
                            JOB_CANCELLED,
                            Ordering::AcqRel,
                            Ordering::Acquire,
                        )
                        .is_ok()
                    {
                        break;
                    }
                }
                JOB_CANCELLED | JOB_COMMITTED => return Ok(()),
                _ => unreachable!("job terminal state must be valid"),
            }
        }
        let value = 1_u64.to_ne_bytes();
        loop {
            // SAFETY: the eventfd remains owned by this shared control and the
            // byte slice is valid for the complete fixed-width write.
            let written = unsafe {
                libc::write(
                    self.cancellation_signal.as_raw_fd(),
                    value.as_ptr().cast(),
                    value.len(),
                )
            };
            if written == isize::try_from(value.len()).expect("event value length should fit") {
                return Ok(());
            }
            let error = std::io::Error::last_os_error();
            if error.kind() == ErrorKind::WouldBlock {
                return Ok(());
            }
            if error.kind() != ErrorKind::Interrupted {
                return Err(BridgeError::Io(error));
            }
        }
    }

    /// Claims the only permitted execution of this job.
    pub(crate) fn start(&self) -> Result<(), BridgeError> {
        self.started
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
            .map_err(|_| BridgeError::JobAlreadyStarted)
    }

    /// Returns a cancellation or deadline decision for the core.
    fn checkpoint(&self, deadline: Instant) -> Result<(), ExportInterruption> {
        match self.terminal_state.load(Ordering::Acquire) {
            JOB_ACTIVE | JOB_WRITING => {}
            JOB_CANCELLED => return Err(ExportInterruption::Cancelled),
            JOB_COMMITTED => return Ok(()),
            _ => unreachable!("job terminal state must be valid"),
        }
        if Instant::now() >= deadline {
            return Err(ExportInterruption::DeadlineExceeded);
        }
        Ok(())
    }

    /// Claims the destructive output boundary unless cancellation already won.
    fn begin_output(&self, deadline: Instant) -> Result<(), ExportInterruption> {
        self.checkpoint(deadline)?;
        match self.terminal_state.compare_exchange(
            JOB_ACTIVE,
            JOB_WRITING,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => Ok(()),
            Err(JOB_CANCELLED) => Err(ExportInterruption::Cancelled),
            Err(JOB_COMMITTED) => unreachable!("job output cannot begin after commit"),
            Err(JOB_WRITING) => unreachable!("job output must begin exactly once"),
            Err(_) => unreachable!("job terminal state must be valid"),
        }
    }

    /// Commits success unless cancellation has already won the race.
    fn commit(&self, deadline: Instant) -> Result<(), ExportInterruption> {
        self.checkpoint(deadline)?;
        match self.terminal_state.compare_exchange(
            JOB_WRITING,
            JOB_COMMITTED,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => Ok(()),
            Err(JOB_CANCELLED) => Err(ExportInterruption::Cancelled),
            Err(JOB_COMMITTED) => unreachable!("job success must commit exactly once"),
            Err(_) => unreachable!("job terminal state must be valid"),
        }
    }

    /// Commits one successful read-only operation unless cancellation already won.
    fn commit_read_only(&self, deadline: Instant) -> Result<(), ExportInterruption> {
        self.checkpoint(deadline)?;
        match self.terminal_state.compare_exchange(
            JOB_ACTIVE,
            JOB_COMMITTED,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => Ok(()),
            Err(JOB_CANCELLED) => Err(ExportInterruption::Cancelled),
            Err(JOB_COMMITTED) => unreachable!("read-only success must commit exactly once"),
            Err(JOB_WRITING) => unreachable!("read-only operation cannot cross output boundary"),
            Err(_) => unreachable!("job terminal state must be valid"),
        }
    }
}

/// Reports invalid capabilities or worker state.
#[derive(Debug)]
pub(crate) enum BridgeError {
    Io(std::io::Error),
    InvalidArgument(&'static str),
    JobAlreadyStarted,
}

impl Display for BridgeError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "platform operation failed: {error}"),

            Self::InvalidArgument(argument) => write!(formatter, "invalid {argument}"),

            Self::JobAlreadyStarted => formatter.write_str("export job already started"),
        }
    }
}

impl Error for BridgeError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),

            _ => None,
        }
    }
}

impl From<std::io::Error> for BridgeError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

/// Stores one fixed result code and optional bounded statistics.
pub(crate) struct ExportOutcome {
    pub(crate) result_code: i32,
    pub(crate) input_bytes: u64,
    pub(crate) output_bytes: u64,
    pub(crate) output_started: bool,
    pub(crate) source_sha256: Option<[u8; SHA_256_BYTE_COUNT]>,
}

impl ExportOutcome {
    /// Creates an outcome without partial snapshot statistics.
    pub(crate) const fn failure(result_code: i32) -> Self {
        Self {
            result_code,
            input_bytes: 0,
            output_bytes: 0,
            output_started: false,
            source_sha256: None,
        }
    }

    /// Creates an outcome that preserves one core summary.
    const fn from_summary(result_code: i32, summary: ExportSummary) -> Self {
        Self {
            result_code,
            input_bytes: summary.input_bytes,
            output_bytes: summary.output_bytes,
            output_started: true,
            source_sha256: None,
        }
    }

    /// Creates a successful outcome from exact core statistics.
    const fn success(summary: ExportSummary) -> Self {
        Self::from_summary(RESULT_SUCCESS, summary)
    }

    /// Creates a post-boundary failure that cannot be retried conditionally.
    pub(crate) const fn source_uncertain() -> Self {
        Self {
            result_code: RESULT_SOURCE_UNCERTAIN,
            input_bytes: 0,
            output_bytes: 0,
            output_started: true,
            source_sha256: None,
        }
    }

    /// Creates a verified conditional source-save receipt.
    const fn source_success(
        summary: ExportSummary,
        source_sha256: [u8; SHA_256_BYTE_COUNT],
    ) -> Self {
        Self {
            result_code: RESULT_SUCCESS,
            input_bytes: summary.input_bytes,
            output_bytes: summary.output_bytes,
            output_started: true,
            source_sha256: Some(source_sha256),
        }
    }

    /// Creates a successful read-only source verification.
    const fn verification_success(input_bytes: u64) -> Self {
        Self {
            result_code: RESULT_SUCCESS,
            input_bytes,
            output_bytes: 0,
            output_started: false,
            source_sha256: None,
        }
    }

    /// Creates a successful read-only source inspection.
    const fn inspection_success(source_version: ExactSourceVersion) -> Self {
        Self {
            result_code: RESULT_SUCCESS,
            input_bytes: source_version.byte_length,
            output_bytes: 0,
            output_started: false,
            source_sha256: Some(source_version.sha256),
        }
    }
}

#[derive(Clone, Copy)]
pub(crate) struct ExactSourceVersion {
    byte_length: u64,
    sha256: [u8; SHA_256_BYTE_COUNT],
}

/// Reports one package failure before the provider write boundary.
enum PackageValidationError {
    Invalid,
    Interrupted(ExportInterruption),
    Io,
}

/// Reports one positioned package read failure.
#[derive(Clone, Copy)]
enum PackageReadError {
    Interrupted(ExportInterruption),
    Io,
}

/// Reconstructs one validated package through immutable positioned reads.
struct PackageInput<'input> {
    package: &'input File,
    source_backing: Option<&'input File>,
    validated_package: ValidatedPackage,
    job: &'input JobControl,
    deadline: Instant,
    next_record_index: u64,
    current_record: Option<PackageRecord>,
    current_record_bytes: u64,
    output_bytes: u64,
}

impl<'input> PackageInput<'input> {
    /// Creates one reader at the beginning of a fully validated package.
    const fn new(
        package: &'input File,
        source_backing: Option<&'input File>,
        validated_package: ValidatedPackage,
        job: &'input JobControl,
        deadline: Instant,
    ) -> Self {
        Self {
            package,
            source_backing,
            validated_package,
            job,
            deadline,
            next_record_index: 0,
            current_record: None,
            current_record_bytes: 0,
            output_bytes: 0,
        }
    }

    /// Loads the next immutable record without changing either input cursor.
    fn load_next_record(&mut self) -> std::io::Result<bool> {
        if self.next_record_index == self.validated_package.header().record_count() {
            return Ok(false);
        }
        let record_offset = package_record_offset(self.next_record_index)
            .map_err(|()| std::io::Error::other("package record offset overflowed"))?;
        let mut record_bytes = [0_u8; RECORD_HEADER_BYTES];
        read_exact_at_controlled(
            self.package,
            &mut record_bytes,
            record_offset,
            self.job,
            self.deadline,
        )
        .map_err(package_read_error)?;
        self.current_record = Some(
            PackageRecord::decode(&record_bytes)
                .map_err(|_error| std::io::Error::other("validated package record changed"))?,
        );
        self.current_record_bytes = 0;
        self.next_record_index += 1;
        Ok(true)
    }
}

impl Read for PackageInput<'_> {
    /// Reads reconstructed bytes without mutating package or backing cursors.
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        if buffer.is_empty() || self.output_bytes == self.validated_package.header().output_bytes()
        {
            return Ok(0);
        }
        if self
            .current_record
            .is_none_or(|record| self.current_record_bytes == record.byte_length())
            && !self.load_next_record()?
        {
            return Err(std::io::Error::new(
                ErrorKind::UnexpectedEof,
                "package records ended before output",
            ));
        }
        let record = self
            .current_record
            .expect("a package record was just loaded");
        let remaining_record_bytes = record
            .byte_length()
            .checked_sub(self.current_record_bytes)
            .ok_or_else(|| std::io::Error::other("package record position overflowed"))?;
        let read_capacity = usize::try_from(remaining_record_bytes)
            .unwrap_or(usize::MAX)
            .min(buffer.len());
        let backing_offset = record
            .offset()
            .checked_add(self.current_record_bytes)
            .ok_or_else(|| std::io::Error::other("package backing offset overflowed"))?;
        let (input, input_offset) = match record.kind() {
            RECORD_KIND_SOURCE => (
                self.source_backing
                    .ok_or_else(|| std::io::Error::other("package source backing is absent"))?,
                backing_offset,
            ),
            RECORD_KIND_PAYLOAD => (
                self.package,
                self.validated_package
                    .header()
                    .payload_start()
                    .checked_add(backing_offset)
                    .ok_or_else(|| std::io::Error::other("package payload offset overflowed"))?,
            ),
            _ => return Err(std::io::Error::other("package record kind is invalid")),
        };
        let bytes_read = read_at_controlled(
            input,
            &mut buffer[..read_capacity],
            input_offset,
            self.job,
            self.deadline,
        )
        .map_err(package_read_error)?;
        if bytes_read == 0 {
            return Err(std::io::Error::new(
                ErrorKind::UnexpectedEof,
                "package backing ended before its record",
            ));
        }
        let bytes_read = u64::try_from(bytes_read)
            .map_err(|_| std::io::Error::other("read count overflowed"))?;
        self.current_record_bytes = self
            .current_record_bytes
            .checked_add(bytes_read)
            .ok_or_else(|| std::io::Error::other("package record count overflowed"))?;
        self.output_bytes = self
            .output_bytes
            .checked_add(bytes_read)
            .ok_or_else(|| std::io::Error::other("package output count overflowed"))?;
        usize::try_from(bytes_read).map_err(|_| std::io::Error::other("read count exceeds usize"))
    }
}

/// Decodes the empty-new-target marker or one exact expected source version.
pub(crate) fn decode_source_expectation(
    expected_source_bytes: i64,
    expected_source_sha256: &[u8],
) -> Result<Option<ExactSourceVersion>, BridgeError> {
    if expected_source_bytes == NO_EXPECTED_SOURCE_BYTE_LENGTH {
        return if expected_source_sha256.is_empty() {
            Ok(None)
        } else {
            Err(BridgeError::InvalidArgument("new source digest"))
        };
    }
    decode_exact_source_version(expected_source_bytes, expected_source_sha256).map(Some)
}

/// Decodes one fixed-width exact expected source version.
pub(crate) fn decode_exact_source_version(
    expected_source_bytes: i64,
    expected_source_sha256: &[u8],
) -> Result<ExactSourceVersion, BridgeError> {
    let byte_length = nonnegative_u64(expected_source_bytes)
        .map_err(|()| BridgeError::InvalidArgument("expected source byte count"))?;
    if byte_length > HARD_MAX_SNAPSHOT_BYTES {
        return Err(BridgeError::InvalidArgument("expected source byte count"));
    }
    let sha256: [u8; SHA_256_BYTE_COUNT] = expected_source_sha256
        .try_into()
        .map_err(|_| BridgeError::InvalidArgument("expected source digest length"))?;
    Ok(ExactSourceVersion {
        byte_length,
        sha256,
    })
}

/// Runs one same-descriptor preflight and conditional source replacement.
pub(crate) fn run_conditional_source_save(
    job: &Arc<JobControl>,
    package_raw_fd: RawFd,
    source_backing_raw_fd: RawFd,
    source_raw_fd: RawFd,
    expected_bytes: i64,
    expected_source: Option<&ExactSourceVersion>,
    timeout_millis: i64,
) -> ExportOutcome {
    let Ok(expected_bytes) = bounded_snapshot_bytes(expected_bytes) else {
        return ExportOutcome::failure(RESULT_INPUT_LIMIT);
    };
    let Ok(deadline) = validated_deadline(timeout_millis) else {
        return ExportOutcome::failure(RESULT_TIMEOUT);
    };
    let Ok(descriptors) =
        validated_conditional_source_files(package_raw_fd, source_backing_raw_fd, source_raw_fd)
    else {
        return ExportOutcome::failure(RESULT_INVALID_DESCRIPTOR);
    };
    let validated_package = match validate_source_save_package(
        &descriptors.package,
        descriptors.package_bytes,
        descriptors.source_backing_bytes,
        expected_bytes,
        job,
        deadline,
    ) {
        Ok(header) => header,
        Err(PackageValidationError::Invalid) => {
            return ExportOutcome::failure(RESULT_INVALID_DESCRIPTOR);
        }
        Err(PackageValidationError::Interrupted(interruption)) => {
            return outcome_for_interruption(interruption);
        }
        Err(PackageValidationError::Io) => {
            return ExportOutcome::failure(RESULT_INPUT_IO);
        }
    };
    let mut source = PollingSource::new(descriptors.source, Arc::clone(job), deadline);

    let preflight_limit = expected_source.map_or(0, |version| version.byte_length);
    let observed_source = match hash_bounded_source(&mut source, preflight_limit, job, deadline) {
        Ok(version) => version,
        Err(SourceHashError::TooLarge) => {
            return ExportOutcome::failure(RESULT_SOURCE_CONFLICT);
        }
        Err(SourceHashError::Interrupted(interruption)) => {
            return outcome_for_interruption(interruption);
        }
        Err(SourceHashError::Io) => return ExportOutcome::failure(RESULT_INPUT_IO),
    };
    let source_matches = expected_source.map_or(observed_source.byte_length == 0, |expected| {
        source_versions_match(expected, &observed_source)
    });
    if !source_matches {
        return ExportOutcome::failure(RESULT_SOURCE_CONFLICT);
    }
    if let Err(interruption) = job.begin_output(deadline) {
        return outcome_for_interruption(interruption);
    }

    let mut input = PackageInput::new(
        &descriptors.package,
        descriptors.source_backing.as_ref(),
        validated_package,
        job,
        deadline,
    );
    let saved = replace_source_bytes(&mut input, &mut source, expected_bytes, job, deadline);
    finish_conditional_source_save(saved, job, deadline)
}

/// Commits one verified replacement or reports post-boundary uncertainty.
fn finish_conditional_source_save(
    saved: Result<(ExportSummary, [u8; SHA_256_BYTE_COUNT]), ()>,
    job: &JobControl,
    deadline: Instant,
) -> ExportOutcome {
    let Ok((summary, source_sha256)) = saved else {
        return ExportOutcome::source_uncertain();
    };
    if let Err(_interruption) = job.commit(deadline) {
        return ExportOutcome::source_uncertain();
    }
    ExportOutcome::source_success(summary, source_sha256)
}

/// Runs one bounded exact source verification without crossing a write boundary.
pub(crate) fn run_source_verification(
    job: &Arc<JobControl>,
    source_raw_fd: RawFd,
    expected_source: &ExactSourceVersion,
    timeout_millis: i64,
) -> ExportOutcome {
    let Ok(deadline) = validated_deadline(timeout_millis) else {
        return ExportOutcome::failure(RESULT_TIMEOUT);
    };
    let Ok(source) = validated_verification_source(source_raw_fd) else {
        return ExportOutcome::failure(RESULT_INVALID_DESCRIPTOR);
    };
    let mut source = PollingReader::new(source, Arc::clone(job), deadline);
    let observed_source =
        match hash_bounded_source(&mut source, expected_source.byte_length, job, deadline) {
            Ok(version) => version,
            Err(SourceHashError::TooLarge) => {
                return ExportOutcome::failure(RESULT_SOURCE_CONFLICT);
            }
            Err(SourceHashError::Interrupted(interruption)) => {
                return outcome_for_interruption(interruption);
            }
            Err(SourceHashError::Io) => return ExportOutcome::failure(RESULT_INPUT_IO),
        };
    if !source_versions_match(expected_source, &observed_source) {
        return ExportOutcome::failure(RESULT_SOURCE_CONFLICT);
    }
    if let Err(interruption) = job.commit_read_only(deadline) {
        return outcome_for_interruption(interruption);
    }
    ExportOutcome::verification_success(observed_source.byte_length)
}

/// Returns one exact bounded source version without crossing a write boundary.
pub(crate) fn run_source_inspection(
    job: &Arc<JobControl>,
    source_raw_fd: RawFd,
    maximum_source_bytes: i64,
    timeout_millis: i64,
) -> ExportOutcome {
    let Ok(maximum_source_bytes) = bounded_snapshot_bytes(maximum_source_bytes) else {
        return ExportOutcome::failure(RESULT_INPUT_LIMIT);
    };
    let Ok(deadline) = validated_deadline(timeout_millis) else {
        return ExportOutcome::failure(RESULT_TIMEOUT);
    };
    let Ok(source) = validated_verification_source(source_raw_fd) else {
        return ExportOutcome::failure(RESULT_INVALID_DESCRIPTOR);
    };
    let mut source = PollingReader::new(source, Arc::clone(job), deadline);
    let observed_source =
        match hash_bounded_source(&mut source, maximum_source_bytes, job, deadline) {
            Ok(version) => version,
            Err(SourceHashError::TooLarge) => {
                return ExportOutcome::failure(RESULT_INPUT_LIMIT);
            }
            Err(SourceHashError::Interrupted(interruption)) => {
                return outcome_for_interruption(interruption);
            }
            Err(SourceHashError::Io) => return ExportOutcome::failure(RESULT_INPUT_IO),
        };
    if let Err(interruption) = job.commit_read_only(deadline) {
        return outcome_for_interruption(interruption);
    }
    ExportOutcome::inspection_success(observed_source)
}

/// Validates one bounded snapshot byte count.
fn bounded_snapshot_bytes(expected_bytes: i64) -> Result<u64, ()> {
    let expected_bytes = nonnegative_u64(expected_bytes)?;
    if expected_bytes > HARD_MAX_SNAPSHOT_BYTES {
        return Err(());
    }
    Ok(expected_bytes)
}

/// Validates one complete immutable package before provider preflight begins.
fn validate_source_save_package(
    package: &File,
    package_bytes: u64,
    source_backing_bytes: Option<u64>,
    expected_output_bytes: u64,
    job: &JobControl,
    deadline: Instant,
) -> Result<ValidatedPackage, PackageValidationError> {
    let package_header_bytes =
        u64::try_from(PACKAGE_HEADER_BYTES).map_err(|_| PackageValidationError::Invalid)?;
    if package_bytes < package_header_bytes || package_bytes > MAX_PACKAGE_BYTES {
        return Err(PackageValidationError::Invalid);
    }
    let mut header_bytes = [0_u8; PACKAGE_HEADER_BYTES];
    read_exact_at_controlled(package, &mut header_bytes, 0, job, deadline)
        .map_err(package_validation_read_error)?;
    let header =
        PackageHeader::decode(&header_bytes).map_err(|_error| PackageValidationError::Invalid)?;
    let mut validator = PackageValidator::new(
        header,
        package_bytes,
        expected_output_bytes,
        source_backing_bytes,
    )
    .map_err(|_error| PackageValidationError::Invalid)?;
    let validation_buffer_bytes = RECORD_HEADER_BYTES
        .checked_mul(PACKAGE_RECORD_VALIDATION_BATCH)
        .ok_or(PackageValidationError::Invalid)?;
    let mut validation_buffer = vec![0_u8; validation_buffer_bytes].into_boxed_slice();
    let mut first_record_index = 0_u64;
    while first_record_index < header.record_count() {
        let remaining_records = header.record_count() - first_record_index;
        let batch_record_count = usize::try_from(remaining_records)
            .unwrap_or(usize::MAX)
            .min(PACKAGE_RECORD_VALIDATION_BATCH);
        let batch_bytes = batch_record_count
            .checked_mul(RECORD_HEADER_BYTES)
            .ok_or(PackageValidationError::Invalid)?;
        read_exact_at_controlled(
            package,
            &mut validation_buffer[..batch_bytes],
            package_record_offset(first_record_index)
                .map_err(|()| PackageValidationError::Invalid)?,
            job,
            deadline,
        )
        .map_err(package_validation_read_error)?;
        for record_index in 0..batch_record_count {
            let record_start = record_index * RECORD_HEADER_BYTES;
            let record_end = record_start + RECORD_HEADER_BYTES;
            let record_bytes: &[u8; RECORD_HEADER_BYTES] = validation_buffer
                [record_start..record_end]
                .try_into()
                .expect("record validation chunk must have fixed width");
            let record = PackageRecord::decode(record_bytes)
                .map_err(|_error| PackageValidationError::Invalid)?;
            validator
                .push(record)
                .map_err(|_error| PackageValidationError::Invalid)?;
        }
        first_record_index = first_record_index
            .checked_add(
                u64::try_from(batch_record_count).map_err(|_| PackageValidationError::Invalid)?,
            )
            .ok_or(PackageValidationError::Invalid)?;
    }
    validator
        .finish()
        .map_err(|_error| PackageValidationError::Invalid)
}

/// Returns the package-table offset of one fixed-width record.
fn package_record_offset(record_index: u64) -> Result<u64, ()> {
    let header_bytes = u64::try_from(PACKAGE_HEADER_BYTES).map_err(|_| ())?;
    let record_bytes = u64::try_from(RECORD_HEADER_BYTES).map_err(|_| ())?;
    header_bytes
        .checked_add(record_index.checked_mul(record_bytes).ok_or(())?)
        .ok_or(())
}

/// Reads one complete immutable range through bounded positioned operations.
fn read_exact_at_controlled(
    file: &File,
    mut buffer: &mut [u8],
    mut offset: u64,
    job: &JobControl,
    deadline: Instant,
) -> Result<(), PackageReadError> {
    while !buffer.is_empty() {
        let bytes_read = read_at_controlled(file, buffer, offset, job, deadline)?;
        if bytes_read == 0 {
            return Err(PackageReadError::Io);
        }
        let bytes_read = u64::try_from(bytes_read).map_err(|_| PackageReadError::Io)?;
        offset = offset.checked_add(bytes_read).ok_or(PackageReadError::Io)?;
        let bytes_read = usize::try_from(bytes_read).map_err(|_| PackageReadError::Io)?;
        buffer = &mut buffer[bytes_read..];
    }
    Ok(())
}

/// Reads one immutable range without changing its open-file-description cursor.
fn read_at_controlled(
    file: &File,
    buffer: &mut [u8],
    offset: u64,
    job: &JobControl,
    deadline: Instant,
) -> Result<usize, PackageReadError> {
    loop {
        job.checkpoint(deadline)
            .map_err(PackageReadError::Interrupted)?;
        match file.read_at(buffer, offset) {
            Ok(bytes_read) => {
                if bytes_read > buffer.len() {
                    return Err(PackageReadError::Io);
                }
                job.checkpoint(deadline)
                    .map_err(PackageReadError::Interrupted)?;
                return Ok(bytes_read);
            }
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(_error) => return Err(PackageReadError::Io),
        }
    }
}

/// Converts one positioned read failure into pre-boundary package validation.
const fn package_validation_read_error(error: PackageReadError) -> PackageValidationError {
    match error {
        PackageReadError::Interrupted(interruption) => {
            PackageValidationError::Interrupted(interruption)
        }
        PackageReadError::Io => PackageValidationError::Io,
    }
}

/// Converts one positioned read failure into a post-boundary I/O error.
fn package_read_error(error: PackageReadError) -> std::io::Error {
    match error {
        PackageReadError::Interrupted(interruption) => interrupted_io_error(interruption),
        PackageReadError::Io => std::io::Error::other("package positioned read failed"),
    }
}

/// Creates one validated monotonic deadline.
fn validated_deadline(timeout_millis: i64) -> Result<Instant, BridgeError> {
    let timeout_millis =
        positive_u64(timeout_millis).map_err(|()| BridgeError::InvalidArgument("timeout"))?;
    if timeout_millis > MAX_TIMEOUT_MILLIS {
        return Err(BridgeError::InvalidArgument("timeout"));
    }
    Instant::now()
        .checked_add(Duration::from_millis(timeout_millis))
        .ok_or(BridgeError::InvalidArgument("timeout"))
}

/// Reports bounded preflight hashing failures without source content.
enum SourceHashError {
    TooLarge,
    Interrupted(ExportInterruption),
    Io,
}

/// Hashes one source through a single bounded byte beyond its expected length.
fn hash_bounded_source<Reader>(
    reader: &mut Reader,
    byte_limit: u64,
    job: &JobControl,
    deadline: Instant,
) -> Result<ExactSourceVersion, SourceHashError>
where
    Reader: Read,
{
    let mut byte_length = 0_u64;
    let mut sha256 = Sha256::new();
    let mut buffer = vec![0_u8; STREAM_BUFFER_BYTES].into_boxed_slice();
    loop {
        job.checkpoint(deadline)
            .map_err(SourceHashError::Interrupted)?;
        let remaining_bytes = byte_limit
            .checked_sub(byte_length)
            .ok_or(SourceHashError::TooLarge)?;
        let probe_bytes = remaining_bytes.saturating_add(1);
        let read_capacity = usize::try_from(probe_bytes)
            .unwrap_or(usize::MAX)
            .min(buffer.len());
        let bytes_read = loop {
            match reader.read(&mut buffer[..read_capacity]) {
                Ok(bytes_read) => break bytes_read,
                Err(error) if error.kind() == ErrorKind::Interrupted => {
                    job.checkpoint(deadline)
                        .map_err(SourceHashError::Interrupted)?;
                }
                Err(_error) => return Err(SourceHashError::Io),
            }
        };
        if bytes_read == 0 {
            let digest: [u8; SHA_256_BYTE_COUNT] = sha256.finalize().into();
            return Ok(ExactSourceVersion {
                byte_length,
                sha256: digest,
            });
        }
        byte_length = byte_length
            .checked_add(u64::try_from(bytes_read).map_err(|_| SourceHashError::TooLarge)?)
            .ok_or(SourceHashError::TooLarge)?;
        if byte_length > byte_limit {
            return Err(SourceHashError::TooLarge);
        }
        sha256.update(&buffer[..bytes_read]);
        job.checkpoint(deadline)
            .map_err(SourceHashError::Interrupted)?;
    }
}

/// Compares complete source versions without digest-dependent early exit.
fn source_versions_match(expected: &ExactSourceVersion, observed: &ExactSourceVersion) -> bool {
    let mut digest_difference = 0_u8;
    for digest_index in 0..SHA_256_BYTE_COUNT {
        digest_difference |= expected.sha256[digest_index] ^ observed.sha256[digest_index];
    }
    expected.byte_length == observed.byte_length && digest_difference == 0
}

/// Defines the destructive operations needed for one seekable source.
trait RewritableSource: Read + Write + Seek {
    /// Truncates the source to an exact byte length.
    fn truncate(&mut self, byte_length: u64) -> std::io::Result<()>;

    /// Synchronizes completed writes before readback verification.
    fn synchronize(&mut self) -> std::io::Result<()>;
}

/// Replaces and verifies one source after its destructive boundary has begun.
fn replace_source_bytes<Input, Source>(
    input: &mut Input,
    source: &mut Source,
    expected_bytes: u64,
    job: &JobControl,
    deadline: Instant,
) -> Result<(ExportSummary, [u8; SHA_256_BYTE_COUNT]), ()>
where
    Input: Read,
    Source: RewritableSource,
{
    source.truncate(0).map_err(|_error| ())?;
    source.seek(SeekFrom::Start(0)).map_err(|_error| ())?;
    let mut input_bytes = 0_u64;
    let mut output_bytes = 0_u64;
    let mut sha256 = Sha256::new();
    let mut buffer = vec![0_u8; STREAM_BUFFER_BYTES].into_boxed_slice();
    loop {
        job.checkpoint(deadline).map_err(|_interruption| ())?;
        let remaining_bytes = expected_bytes.checked_sub(input_bytes).ok_or(())?;
        let probe_bytes = remaining_bytes.saturating_add(1);
        let read_capacity = usize::try_from(probe_bytes)
            .unwrap_or(usize::MAX)
            .min(buffer.len());
        let bytes_read = input
            .read(&mut buffer[..read_capacity])
            .map_err(|_error| ())?;
        if bytes_read == 0 {
            break;
        }
        input_bytes = input_bytes
            .checked_add(u64::try_from(bytes_read).map_err(|_error| ())?)
            .ok_or(())?;
        if input_bytes > expected_bytes {
            return Err(());
        }
        sha256.update(&buffer[..bytes_read]);
        let mut written_offset = 0;
        while written_offset < bytes_read {
            job.checkpoint(deadline).map_err(|_interruption| ())?;
            let written_bytes = source
                .write(&buffer[written_offset..bytes_read])
                .map_err(|_error| ())?;
            if written_bytes == 0 || written_bytes > bytes_read - written_offset {
                return Err(());
            }
            written_offset += written_bytes;
            output_bytes = output_bytes
                .checked_add(u64::try_from(written_bytes).map_err(|_error| ())?)
                .ok_or(())?;
        }
    }
    if input_bytes != expected_bytes || output_bytes != expected_bytes {
        return Err(());
    }
    source.truncate(expected_bytes).map_err(|_error| ())?;
    source.flush().map_err(|_error| ())?;
    source.synchronize().map_err(|_error| ())?;
    let written_sha256: [u8; SHA_256_BYTE_COUNT] = sha256.finalize().into();
    source.seek(SeekFrom::Start(0)).map_err(|_error| ())?;
    let readback =
        hash_bounded_source(source, expected_bytes, job, deadline).map_err(|_error| ())?;
    let written_version = ExactSourceVersion {
        byte_length: expected_bytes,
        sha256: written_sha256,
    };
    if !source_versions_match(&written_version, &readback) {
        return Err(());
    }
    job.checkpoint(deadline).map_err(|_interruption| ())?;
    Ok((
        ExportSummary {
            input_bytes,
            output_bytes,
        },
        written_sha256,
    ))
}

/// Runs one validated export with a bounded terminal outcome.
pub(crate) fn run_export(
    job: &Arc<JobControl>,
    input_raw_fd: RawFd,
    output_raw_fd: RawFd,
    expected_bytes: i64,
    timeout_millis: i64,
) -> ExportOutcome {
    let Ok(expected_bytes) = nonnegative_u64(expected_bytes) else {
        return ExportOutcome::failure(RESULT_INPUT_LIMIT);
    };
    if expected_bytes > HARD_MAX_SNAPSHOT_BYTES {
        return ExportOutcome::failure(RESULT_INPUT_LIMIT);
    }
    let Ok(timeout_millis) = positive_u64(timeout_millis) else {
        return ExportOutcome::failure(RESULT_TIMEOUT);
    };
    if timeout_millis > MAX_TIMEOUT_MILLIS {
        return ExportOutcome::failure(RESULT_TIMEOUT);
    }
    let Ok(limits) = ExportLimits::new(expected_bytes) else {
        return ExportOutcome::failure(RESULT_INPUT_LIMIT);
    };
    let Some(deadline) = Instant::now().checked_add(Duration::from_millis(timeout_millis)) else {
        return ExportOutcome::failure(RESULT_TIMEOUT);
    };

    let Ok(descriptors) = validated_files(input_raw_fd, output_raw_fd) else {
        return ExportOutcome::failure(RESULT_INVALID_DESCRIPTOR);
    };
    let mut reader = PollingReader::new(descriptors.input, Arc::clone(job), deadline);
    let mut writer = PollingWriter::new(
        descriptors.output,
        descriptors.output_resettable,
        Arc::clone(job),
        deadline,
    );
    if let Err(interruption) = job.begin_output(deadline) {
        return outcome_for_interruption(interruption);
    }
    if writer.prepare().is_err() {
        return ExportOutcome::failure(RESULT_OUTPUT_IO);
    }
    let mut checkpoint = JobCheckpoint::new(Arc::clone(job), deadline);
    let exported = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut checkpoint);
    match exported {
        Ok(summary) => finish_success(&mut writer, summary, expected_bytes, job, deadline),
        Err(error) => finish_failure(&mut writer, &error),
    }
}

/// Owns validated worker descriptors and output rollback metadata.
struct ValidatedDescriptors {
    input: File,
    output: File,
    output_resettable: bool,
}

/// Duplicates and validates the untrusted descriptor capabilities.
fn validated_files(
    input_raw_fd: RawFd,
    output_raw_fd: RawFd,
) -> Result<ValidatedDescriptors, BridgeError> {
    if input_raw_fd == output_raw_fd {
        return Err(BridgeError::InvalidArgument("aliased descriptors"));
    }
    let input = duplicate_file_descriptor(input_raw_fd)?;
    let mut output = duplicate_file_descriptor(output_raw_fd)?;
    let input_flags = descriptor_flags(&input)?;
    let output_flags = descriptor_flags(&output)?;
    if input_flags & libc::O_ACCMODE == libc::O_WRONLY
        || output_flags & libc::O_ACCMODE == libc::O_RDONLY
        || output_flags & libc::O_APPEND != 0
        || input_flags & libc::O_PATH != 0
        || output_flags & libc::O_PATH != 0
    {
        return Err(BridgeError::InvalidArgument("descriptor access mode"));
    }

    let input_metadata = input.metadata()?;
    let output_metadata = output.metadata()?;
    if identifiable_alias(&input_metadata, &output_metadata) {
        return Err(BridgeError::InvalidArgument("aliased descriptors"));
    }
    let output_resettable = output_metadata.is_file() && output.stream_position().is_ok();
    set_nonblocking(&input, input_flags)?;
    set_nonblocking(&output, output_flags)?;
    Ok(ValidatedDescriptors {
        input,
        output,
        output_resettable,
    })
}

/// Owns one validated package, optional backing, and rewritable provider source.
struct ValidatedConditionalSourceFiles {
    package: File,
    source_backing: Option<File>,
    source: File,
    package_bytes: u64,
    source_backing_bytes: Option<u64>,
}

/// Validates one sealed package bundle and one seekable read-write source.
fn validated_conditional_source_files(
    package_raw_fd: RawFd,
    source_backing_raw_fd: RawFd,
    source_raw_fd: RawFd,
) -> Result<ValidatedConditionalSourceFiles, BridgeError> {
    if package_raw_fd < 0 || source_raw_fd < 0 || source_backing_raw_fd < -1 {
        return Err(BridgeError::InvalidArgument("file descriptor"));
    }
    if package_raw_fd == source_raw_fd
        || source_backing_raw_fd == package_raw_fd
        || source_backing_raw_fd == source_raw_fd
    {
        return Err(BridgeError::InvalidArgument("aliased descriptors"));
    }
    let package = duplicate_file_descriptor(package_raw_fd)?;
    let source_backing = if source_backing_raw_fd == -1 {
        None
    } else {
        Some(duplicate_file_descriptor(source_backing_raw_fd)?)
    };
    let mut source = duplicate_file_descriptor(source_raw_fd)?;
    let package_flags = descriptor_flags(&package)?;
    let source_flags = descriptor_flags(&source)?;
    if package_flags & libc::O_ACCMODE == libc::O_WRONLY
        || package_flags & libc::O_PATH != 0
        || source_flags & libc::O_ACCMODE != libc::O_RDWR
        || source_flags & libc::O_APPEND != 0
        || source_flags & libc::O_PATH != 0
    {
        return Err(BridgeError::InvalidArgument("descriptor access mode"));
    }
    let package_metadata = package.metadata()?;
    let source_metadata = source.metadata()?;
    validate_sealed_anonymous_input(&package, &package_metadata)?;
    if !source_metadata.is_file() {
        return Err(BridgeError::InvalidArgument("descriptor type"));
    }
    if identifiable_alias(&package_metadata, &source_metadata) {
        return Err(BridgeError::InvalidArgument("aliased descriptors"));
    }

    let source_backing_bytes = if let Some(source_backing) = source_backing.as_ref() {
        let source_backing_flags = descriptor_flags(source_backing)?;
        if source_backing_flags & libc::O_ACCMODE == libc::O_WRONLY
            || source_backing_flags & libc::O_PATH != 0
        {
            return Err(BridgeError::InvalidArgument("descriptor access mode"));
        }
        let source_backing_metadata = source_backing.metadata()?;
        validate_sealed_anonymous_input(source_backing, &source_backing_metadata)?;
        if identifiable_alias(&package_metadata, &source_backing_metadata)
            || identifiable_alias(&source_backing_metadata, &source_metadata)
        {
            return Err(BridgeError::InvalidArgument("aliased descriptors"));
        }
        Some(source_backing_metadata.len())
    } else {
        None
    };

    source.seek(SeekFrom::Start(0))?;
    Ok(ValidatedConditionalSourceFiles {
        package,
        source_backing,
        source,
        package_bytes: package_metadata.len(),
        source_backing_bytes,
    })
}

/// Requires one immutable anonymous regular input capability.
fn validate_sealed_anonymous_input(file: &File, metadata: &Metadata) -> Result<(), BridgeError> {
    if !metadata.is_file() || metadata.nlink() != 0 {
        return Err(BridgeError::InvalidArgument("descriptor type"));
    }
    let seals = descriptor_seals(file)?;
    if seals & REQUIRED_IMMUTABLE_INPUT_SEALS != REQUIRED_IMMUTABLE_INPUT_SEALS {
        return Err(BridgeError::InvalidArgument("immutable input seals"));
    }
    Ok(())
}

/// Validates one fresh readable source descriptor for read-only verification.
fn validated_verification_source(source_raw_fd: RawFd) -> Result<File, BridgeError> {
    let mut source = duplicate_file_descriptor(source_raw_fd)?;
    let source_flags = descriptor_flags(&source)?;
    if source_flags & libc::O_ACCMODE != libc::O_RDONLY || source_flags & libc::O_PATH != 0 {
        return Err(BridgeError::InvalidArgument(
            "source descriptor access mode",
        ));
    }
    match source.seek(SeekFrom::Start(0)) {
        Ok(_) => {}
        Err(error) if error.raw_os_error() == Some(libc::ESPIPE) => {}
        Err(error) => return Err(BridgeError::Io(error)),
    }
    set_nonblocking(&source, source_flags)?;
    Ok(source)
}

/// Returns whether two descriptors identify the same kernel object.
fn identifiable_alias(input: &Metadata, output: &Metadata) -> bool {
    input.ino() != 0 && input.dev() == output.dev() && input.ino() == output.ino()
}

/// Duplicates a borrowed Android descriptor with close-on-exec semantics.
fn duplicate_file_descriptor(raw_fd: RawFd) -> Result<File, BridgeError> {
    if raw_fd < 0 {
        return Err(BridgeError::InvalidArgument("file descriptor"));
    }
    // SAFETY: the service retains the owned descriptor throughout the worker call.
    let borrowed = unsafe { BorrowedFd::borrow_raw(raw_fd) };
    Ok(File::from(borrowed.try_clone_to_owned()?))
}

/// Returns the status flags for one open descriptor.
fn descriptor_flags(file: &File) -> Result<i32, BridgeError> {
    // SAFETY: F_GETFL reads flags from the valid descriptor owned by `file`.
    let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
    if flags < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    Ok(flags)
}

/// Returns the immutable seals applied to one anonymous input descriptor.
fn descriptor_seals(file: &File) -> Result<i32, BridgeError> {
    // SAFETY: F_GET_SEALS reads seal flags from the valid descriptor owned by
    // `file` without modifying the underlying object.
    let seals = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GET_SEALS) };
    if seals < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    Ok(seals)
}

/// Adds nonblocking behavior to one worker-owned file description.
fn set_nonblocking(file: &File, flags: i32) -> Result<(), BridgeError> {
    // SAFETY: F_SETFL updates status flags on the valid descriptor owned by
    // `file`; the existing flags are preserved while O_NONBLOCK is added.
    let result = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) };
    if result < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    Ok(())
}

/// Finishes exact output unless cancellation or its deadline wins the race.
fn finish_success(
    output: &mut PollingWriter,
    summary: ExportSummary,
    expected_bytes: u64,
    job: &JobControl,
    deadline: Instant,
) -> ExportOutcome {
    if summary.input_bytes != expected_bytes || summary.output_bytes != expected_bytes {
        output.rollback();
        return ExportOutcome::from_summary(RESULT_INPUT_LENGTH_MISMATCH, summary);
    }
    if let Err(interruption) = job.checkpoint(deadline) {
        return rollback_interruption(output, interruption);
    }
    if output.synchronize().is_err() {
        if let Err(interruption) = job.checkpoint(deadline) {
            return rollback_interruption(output, interruption);
        }
        output.rollback();
        return ExportOutcome::failure(RESULT_OUTPUT_IO);
    }
    match job.commit(deadline) {
        Ok(()) => ExportOutcome::success(summary),
        Err(interruption) => rollback_interruption(output, interruption),
    }
}

/// Resets failed seekable output and maps the typed core error.
fn finish_failure(output: &mut PollingWriter, error: &ExportError) -> ExportOutcome {
    output.rollback();
    ExportOutcome::failure(result_code_for_error(error))
}

/// Resets failed seekable output and maps one external interruption.
fn rollback_interruption(
    output: &mut PollingWriter,
    interruption: ExportInterruption,
) -> ExportOutcome {
    output.rollback();
    outcome_for_interruption(interruption)
}

/// Maps one interruption without modifying an output that never began.
const fn outcome_for_interruption(interruption: ExportInterruption) -> ExportOutcome {
    ExportOutcome::failure(match interruption {
        ExportInterruption::Cancelled => RESULT_CANCELLED,
        ExportInterruption::DeadlineExceeded => RESULT_TIMEOUT,
    })
}

/// Maps one typed core failure onto the stable Android result protocol.
const fn result_code_for_error(error: &ExportError) -> i32 {
    match error {
        ExportError::LimitTooHigh { .. } => RESULT_INPUT_LIMIT,
        ExportError::SnapshotTooLarge { .. } => RESULT_INPUT_LENGTH_MISMATCH,
        ExportError::Cancelled => RESULT_CANCELLED,
        ExportError::DeadlineExceeded => RESULT_TIMEOUT,
        ExportError::InputIo(_) => RESULT_INPUT_IO,
        ExportError::OutputIo(_) => RESULT_OUTPUT_IO,
        ExportError::InputByteCountOverflow | ExportError::OutputByteCountOverflow => {
            RESULT_INTERNAL
        }
    }
}

/// Converts a nonnegative AIDL long into an unsigned value.
fn nonnegative_u64(value: i64) -> Result<u64, ()> {
    u64::try_from(value).map_err(|_| ())
}

/// Converts a positive AIDL long into an unsigned value.
fn positive_u64(value: i64) -> Result<u64, ()> {
    let value = nonnegative_u64(value)?;
    if value == 0 {
        return Err(());
    }
    Ok(value)
}

/// Waits for descriptor readiness alongside cancellation and deadline state.
fn wait_for_io(
    raw_fd: RawFd,
    events: libc::c_short,
    control: &JobControl,
    deadline: Instant,
) -> std::io::Result<()> {
    loop {
        if control.checkpoint(deadline).is_err() {
            return Err(std::io::Error::new(
                ErrorKind::Interrupted,
                "export interrupted",
            ));
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        let maximum_poll_millis =
            u128::try_from(i32::MAX).expect("maximum poll timeout should fit");
        let timeout_millis = i32::try_from(
            remaining
                .as_millis()
                .clamp(1, maximum_poll_millis.min(MAX_POLL_WAIT_MILLIS)),
        )
        .expect("clamped poll timeout should fit");
        let mut poll_fds = [
            libc::pollfd {
                fd: raw_fd,
                events,
                revents: 0,
            },
            libc::pollfd {
                fd: control.cancellation_signal.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        // SAFETY: `poll_fds` is a valid writable array for its declared length,
        // and both descriptors remain owned for the call.
        let result = unsafe {
            libc::poll(
                poll_fds.as_mut_ptr(),
                libc::nfds_t::try_from(poll_fds.len()).expect("poll count should fit"),
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
        if result == 0 || poll_fds[CANCELLATION_DESCRIPTOR_INDEX].revents != 0 {
            continue;
        }
        if poll_fds[IO_DESCRIPTOR_INDEX].revents & libc::POLLNVAL != 0 {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                "descriptor became invalid",
            ));
        }
        if poll_fds[IO_DESCRIPTOR_INDEX].revents != 0 {
            return Ok(());
        }
    }
}

/// Polls an input capability alongside its cancellation signal.
struct PollingReader {
    input: File,
    control: Arc<JobControl>,
    deadline: Instant,
}

impl PollingReader {
    /// Creates a cancellation-aware reader.
    const fn new(input: File, control: Arc<JobControl>, deadline: Instant) -> Self {
        Self {
            input,
            control,
            deadline,
        }
    }
}

impl Read for PollingReader {
    /// Reads after input becomes ready or reports an interruptible retry.
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        loop {
            wait_for_io(
                self.input.as_raw_fd(),
                libc::POLLIN,
                &self.control,
                self.deadline,
            )?;
            match self.input.read(buffer) {
                Err(error)
                    if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) => {}
                result => return result,
            }
        }
    }
}

/// Polls an output capability alongside its cancellation signal.
struct PollingWriter {
    output: File,
    resettable: bool,
    control: Arc<JobControl>,
    deadline: Instant,
}

impl PollingWriter {
    /// Creates a cancellation-aware writer.
    const fn new(
        output: File,
        resettable: bool,
        control: Arc<JobControl>,
        deadline: Instant,
    ) -> Self {
        Self {
            output,
            resettable,
            control,
            deadline,
        }
    }

    /// Truncates and rewinds one accepted regular destination exactly once.
    fn prepare(&mut self) -> std::io::Result<()> {
        if !self.resettable {
            return Ok(());
        }
        self.output.set_len(0)?;
        self.output.seek(SeekFrom::Start(0))?;
        Ok(())
    }

    /// Synchronizes regular output before acknowledging its complete contents.
    fn synchronize(&mut self) -> std::io::Result<()> {
        if !self.resettable {
            return self.flush();
        }
        loop {
            self.flush()?;
            match self.output.sync_all() {
                Err(error) if error.kind() == ErrorKind::Interrupted => {}
                result => return result,
            }
        }
    }

    /// Best-effort truncates and rewinds one regular seekable destination.
    fn rollback(&mut self) {
        if !self.resettable {
            return;
        }
        let _ = self.output.set_len(0);
        let _ = self.output.seek(SeekFrom::Start(0));
    }
}

impl Write for PollingWriter {
    /// Writes after output becomes ready or reports an interruptible retry.
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        loop {
            wait_for_io(
                self.output.as_raw_fd(),
                libc::POLLOUT,
                &self.control,
                self.deadline,
            )?;
            match self.output.write(buffer) {
                Err(error)
                    if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) => {}
                result => return result,
            }
        }
    }

    /// Flushes output while preserving cancellation and deadline semantics.
    fn flush(&mut self) -> std::io::Result<()> {
        if self.control.checkpoint(self.deadline).is_err() {
            return Err(std::io::Error::new(
                ErrorKind::Interrupted,
                "export interrupted",
            ));
        }
        self.output.flush()?;
        if self.control.checkpoint(self.deadline).is_err() {
            return Err(std::io::Error::new(
                ErrorKind::Interrupted,
                "export interrupted",
            ));
        }
        Ok(())
    }
}

/// Owns one seekable read-write source behind cancellation-aware I/O polling.
struct PollingSource {
    source: File,
    control: Arc<JobControl>,
    deadline: Instant,
}

impl PollingSource {
    /// Creates one cancellation-aware seekable source.
    const fn new(source: File, control: Arc<JobControl>, deadline: Instant) -> Self {
        Self {
            source,
            control,
            deadline,
        }
    }

    /// Checks cancellation and deadline state around one non-streaming operation.
    fn checked_operation<Result>(
        &mut self,
        operation: impl FnOnce(&mut File) -> std::io::Result<Result>,
    ) -> std::io::Result<Result> {
        self.control
            .checkpoint(self.deadline)
            .map_err(interrupted_io_error)?;
        let result = operation(&mut self.source)?;
        self.control
            .checkpoint(self.deadline)
            .map_err(interrupted_io_error)?;
        Ok(result)
    }
}

impl Read for PollingSource {
    /// Reads after source input becomes ready or reports an interruptible retry.
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        loop {
            wait_for_io(
                self.source.as_raw_fd(),
                libc::POLLIN,
                &self.control,
                self.deadline,
            )?;
            match self.source.read(buffer) {
                Err(error)
                    if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) => {}
                result => return result,
            }
        }
    }
}

impl Write for PollingSource {
    /// Writes after source output becomes ready or reports an interruptible retry.
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        loop {
            wait_for_io(
                self.source.as_raw_fd(),
                libc::POLLOUT,
                &self.control,
                self.deadline,
            )?;
            match self.source.write(buffer) {
                Err(error)
                    if matches!(error.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) => {}
                result => return result,
            }
        }
    }

    /// Flushes one source around explicit cancellation checkpoints.
    fn flush(&mut self) -> std::io::Result<()> {
        self.checked_operation(File::flush)
    }
}

impl Seek for PollingSource {
    /// Seeks one source around explicit cancellation checkpoints.
    fn seek(&mut self, position: SeekFrom) -> std::io::Result<u64> {
        self.checked_operation(|source| source.seek(position))
    }
}

impl RewritableSource for PollingSource {
    /// Truncates one source around explicit cancellation checkpoints.
    fn truncate(&mut self, byte_length: u64) -> std::io::Result<()> {
        self.checked_operation(|source| source.set_len(byte_length))
    }

    /// Synchronizes one source around explicit cancellation checkpoints.
    fn synchronize(&mut self) -> std::io::Result<()> {
        self.checked_operation(|source| source.sync_all())
    }
}

/// Converts one typed interruption into an I/O error for trait adapters.
fn interrupted_io_error(interruption: ExportInterruption) -> std::io::Error {
    std::io::Error::new(
        ErrorKind::Interrupted,
        match interruption {
            ExportInterruption::Cancelled => "source save cancelled",
            ExportInterruption::DeadlineExceeded => "source save deadline exceeded",
        },
    )
}

/// Adapts shared job state to the safe core's checkpoint contract.
struct JobCheckpoint {
    control: Arc<JobControl>,
    deadline: Instant,
}

impl JobCheckpoint {
    /// Creates a checkpoint adapter.
    const fn new(control: Arc<JobControl>, deadline: Instant) -> Self {
        Self { control, deadline }
    }
}

impl ExportControl for JobCheckpoint {
    /// Checks the shared cancellation and deadline state.
    fn checkpoint(&mut self) -> Result<(), ExportInterruption> {
        self.control.checkpoint(self.deadline)
    }
}

#[cfg(test)]
mod tests {
    //! Verifies native export policy without Android runtime dependencies.

    use std::ffi::CString;
    use std::fs::{File, OpenOptions, metadata, read, remove_file};
    use std::io::{self, Cursor, ErrorKind, Read, Seek, SeekFrom, Write};
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Barrier, mpsc};
    use std::thread;
    use std::time::{Duration, Instant};

    use beautyxt_export_core::{ExportError, ExportInterruption, ExportSummary};
    use beautyxt_source_save_core::{
        FLAG_SOURCE_PRESENT, MAX_OUTPUT_BYTES, PACKAGE_HEADER_BYTES, PACKAGE_MAGIC,
        PACKAGE_VERSION, RECORD_HEADER_BYTES, RECORD_KIND_PAYLOAD, RECORD_KIND_SOURCE,
    };
    use sha2::{Digest, Sha256};

    use super::{
        BridgeError, ExactSourceVersion, JobControl, PollingWriter, REQUIRED_IMMUTABLE_INPUT_SEALS,
        RESULT_CANCELLED, RESULT_INPUT_IO, RESULT_INPUT_LENGTH_MISMATCH, RESULT_INPUT_LIMIT,
        RESULT_INTERNAL, RESULT_OUTPUT_IO, RESULT_SOURCE_CONFLICT, RESULT_SOURCE_UNCERTAIN,
        RESULT_SUCCESS, RESULT_TIMEOUT, RewritableSource, descriptor_flags,
        finish_conditional_source_save, finish_success, replace_source_bytes,
        result_code_for_error, run_conditional_source_save, run_export, run_source_inspection,
        run_source_verification, set_nonblocking, validated_files,
    };

    const TEST_DEADLINE_OFFSET: Duration = Duration::from_mins(1);
    const TEST_COMPLETION_TIMEOUT: Duration = Duration::from_secs(5);
    const SIMULTANEOUS_RACE_CASES: usize = 128;
    const TEST_TIMEOUT_MILLIS: i64 = 60_000;
    const PIPE_FILL_BUFFER_BYTES: usize = 64 * 1024;
    const PARTIAL_SOURCE_BYTES: usize = 5;
    const TEST_LARGE_CONDITIONAL_BYTES: u64 = 100 * 1024 * 1024;
    const TEST_LARGE_CONDITIONAL_FILL: u8 = 0x5a;
    const TEST_LARGE_CONDITIONAL_REPLACEMENT: u8 = b'X';
    const PACKAGE_RESERVED_FIELD_OFFSET: usize = 20;
    const PACKAGE_DECLARED_BYTES_FIELD_OFFSET: usize = 32;
    const PACKAGE_RECORD_OFFSET_FIELD_BYTES: usize = 8;

    /// Describes one record encoded by a package test fixture.
    #[derive(Clone, Copy)]
    struct TestPackageRecord {
        kind: u32,
        offset: u64,
        byte_length: u64,
    }

    /// Verifies linked regular output remains a valid provider-like destination.
    #[test]
    fn accepts_a_linked_regular_output_descriptor() {
        let output_path = test_output_path("linked-output");
        let output = create_test_file(&output_path, b"existing");
        let input = File::open("/dev/null").expect("test input should open");

        let descriptors = validated_files(input.as_raw_fd(), output.as_raw_fd())
            .expect("linked writable output should be accepted");

        assert!(descriptors.output_resettable);
        assert_eq!(
            output
                .metadata()
                .expect("output metadata should remain available")
                .len(),
            u64::try_from(b"existing".len()).expect("test byte count should fit")
        );
        drop(descriptors);
        drop(output);
        remove_file(output_path).expect("linked test output should be removed");
    }

    /// Verifies nonseekable input and output capabilities remain valid.
    #[test]
    fn accepts_nonseekable_pipe_descriptors() {
        let (input, _input_writer) = create_pipe();
        let (_output_reader, output) = create_pipe();

        let descriptors = validated_files(input.as_raw_fd(), output.as_raw_fd())
            .expect("nonseekable descriptors should be accepted");

        assert!(!descriptors.output_resettable);
    }

    /// Verifies a write-only input capability is rejected before streaming.
    #[test]
    fn rejects_a_write_only_input_descriptor() {
        let input_path = test_output_path("write-only-input");
        let output_path = test_output_path("write-only-input-output");
        let created_input = create_test_file(&input_path, b"");
        drop(created_input);
        let input = OpenOptions::new()
            .write(true)
            .open(&input_path)
            .expect("write-only input should open");
        let output = create_test_file(&output_path, b"");

        let result = validated_files(input.as_raw_fd(), output.as_raw_fd());

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("descriptor access mode"))
        ));
        drop(input);
        drop(output);
        remove_file(input_path).expect("write-only test input should be removed");
        remove_file(output_path).expect("write-only test output should be removed");
    }

    /// Verifies a read-only output capability is rejected before streaming.
    #[test]
    fn rejects_a_read_only_output_descriptor() {
        let output_path = test_output_path("read-only-output");
        let created = create_test_file(&output_path, b"");
        drop(created);
        let output = File::open(&output_path).expect("read-only output should open");
        let input = File::open("/dev/null").expect("test input should open");

        let result = validated_files(input.as_raw_fd(), output.as_raw_fd());

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("descriptor access mode"))
        ));
        drop(output);
        remove_file(output_path).expect("read-only test output should be removed");
    }

    /// Verifies append output cannot violate exact destination positioning.
    #[test]
    fn rejects_an_append_output_descriptor() {
        let output_path = test_output_path("append-output");
        let created = create_test_file(&output_path, b"");
        drop(created);
        let output = OpenOptions::new()
            .append(true)
            .open(&output_path)
            .expect("append output should open");
        let input = File::open("/dev/null").expect("test input should open");

        let result = validated_files(input.as_raw_fd(), output.as_raw_fd());

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("descriptor access mode"))
        ));
        drop(output);
        remove_file(output_path).expect("append test output should be removed");
    }

    /// Verifies duplicated references to one kernel object are rejected.
    #[test]
    fn rejects_identifiable_descriptor_aliases() {
        let document_path = test_output_path("aliased");
        let document = create_test_file(&document_path, b"alias");
        let alias = document
            .try_clone()
            .expect("aliased test descriptor should duplicate");

        let result = validated_files(document.as_raw_fd(), alias.as_raw_fd());

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("aliased descriptors"))
        ));
        drop(alias);
        drop(document);
        remove_file(document_path).expect("aliased test document should be removed");
    }

    /// Verifies zero bytes form one valid exact export.
    #[test]
    fn exports_an_exact_empty_snapshot() {
        let input_path = test_output_path("empty-input");
        let output_path = test_output_path("empty-output");
        let input = create_test_file(&input_path, b"");
        let output = create_test_file(&output_path, b"stale output");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_export(
            &job,
            input.as_raw_fd(),
            output.as_raw_fd(),
            0,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(outcome.input_bytes, 0);
        assert_eq!(outcome.output_bytes, 0);
        assert!(
            read(&output_path)
                .expect("empty test output should be readable")
                .is_empty()
        );
        drop(input);
        drop(output);
        remove_file(input_path).expect("empty test input should be removed");
        remove_file(output_path).expect("empty test output should be removed");
    }

    /// Rejects a failed regular-output sync instead of acknowledging completion.
    #[test]
    fn rejects_output_synchronization_failure() {
        // Inject an unsynchronizable descriptor into the regular-output completion path.
        let output = File::options()
            .write(true)
            .open("/dev/null")
            .expect("test output should open");
        assert!(output.sync_all().is_err());
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");
        let deadline = Instant::now() + TEST_DEADLINE_OFFSET;
        job.begin_output(deadline)
            .expect("test output should start");
        let mut writer = PollingWriter::new(output, true, Arc::clone(&job), deadline);

        let outcome = finish_success(
            &mut writer,
            ExportSummary {
                input_bytes: 0,
                output_bytes: 0,
            },
            0,
            &job,
            deadline,
        );

        assert_eq!(outcome.result_code, RESULT_OUTPUT_IO);
    }

    /// Completes stream output without attempting filesystem synchronization.
    #[test]
    fn completes_pipe_output_without_synchronization() {
        let (_reader, output) = create_pipe();
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");
        let deadline = Instant::now() + TEST_DEADLINE_OFFSET;
        job.begin_output(deadline)
            .expect("test output should start");
        let mut writer = PollingWriter::new(output, false, Arc::clone(&job), deadline);

        let outcome = finish_success(
            &mut writer,
            ExportSummary {
                input_bytes: 0,
                output_bytes: 0,
            },
            0,
            &job,
            deadline,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
    }

    /// Verifies pre-start cancellation preserves one existing destination.
    #[test]
    fn cancellation_before_output_preserves_existing_bytes() {
        let input_path = test_output_path("cancelled-input");
        let output_path = test_output_path("cancelled-output");
        let input = create_test_file(&input_path, b"replacement");
        let output = create_test_file(&output_path, b"existing");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");
        job.cancel().expect("test cancellation should succeed");

        let outcome = run_export(
            &job,
            input.as_raw_fd(),
            output.as_raw_fd(),
            i64::try_from(b"replacement".len()).expect("test snapshot size should fit"),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_CANCELLED);
        assert_eq!(
            read(&output_path).expect("cancelled output should remain readable"),
            b"existing"
        );
        drop(input);
        drop(output);
        remove_file(input_path).expect("cancelled test input should be removed");
        remove_file(output_path).expect("cancelled test output should be removed");
    }

    /// Verifies a complete UTF-8 snapshot crosses the native boundary byte-for-byte.
    #[test]
    fn exports_an_exact_serialized_utf8_snapshot() {
        let input_path = test_output_path("exact-input");
        let output_path = test_output_path("exact-output");
        let expected = "\u{feff}heading\r\n👩🏽‍💻 βeta\r終わり\n".as_bytes();
        let stale_output =
            b"stale output that extends beyond the complete replacement document bytes";
        assert!(stale_output.len() > expected.len());
        let input = create_test_file(&input_path, expected);
        let output = create_test_file(&output_path, stale_output);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_export(
            &job,
            input.as_raw_fd(),
            output.as_raw_fd(),
            i64::try_from(expected.len()).expect("test snapshot size should fit"),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(outcome.input_bytes, expected.len() as u64);
        assert_eq!(outcome.output_bytes, expected.len() as u64);
        assert_eq!(
            read(&output_path).expect("exact test output should be readable"),
            expected
        );
        drop(input);
        drop(output);
        remove_file(input_path).expect("exact test input should be removed");
        remove_file(output_path).expect("exact test output should be removed");
    }

    /// Verifies a size mismatch best-effort resets regular seekable output.
    #[test]
    fn resets_seekable_output_after_a_length_mismatch() {
        let input_path = test_output_path("mismatch-input");
        let output_path = test_output_path("mismatch-output");
        let input = create_test_file(&input_path, b"abc");
        let output = create_test_file(&output_path, b"");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_export(
            &job,
            input.as_raw_fd(),
            output.as_raw_fd(),
            2,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_INPUT_LENGTH_MISMATCH);
        assert_eq!(
            metadata(&output_path)
                .expect("output metadata should load")
                .len(),
            0
        );
        drop(input);
        drop(output);
        remove_file(input_path).expect("mismatch test input should be removed");
        remove_file(output_path).expect("mismatch test output should be removed");
    }

    /// Verifies cancellation after a write resets one seekable destination.
    #[test]
    fn cancellation_after_output_resets_a_seekable_destination() {
        let output_path = test_output_path("cancelled-after-output");
        let (input, mut input_writer) = create_pipe();
        let output = create_test_file(&output_path, b"");
        let written_prefix = b"written before cancellation";
        let expected_bytes = i64::try_from(
            written_prefix
                .len()
                .checked_add(1)
                .expect("test expected byte count should not overflow"),
        )
        .expect("test expected byte count should fit");
        let written_prefix_bytes =
            u64::try_from(written_prefix.len()).expect("test prefix byte count should fit");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");
        let export_job = Arc::clone(&job);
        let (outcome_sender, outcome_receiver) = mpsc::channel();

        let export_thread = thread::spawn(move || {
            let outcome = run_export(
                &export_job,
                input.as_raw_fd(),
                output.as_raw_fd(),
                expected_bytes,
                TEST_TIMEOUT_MILLIS,
            );
            outcome_sender
                .send(outcome)
                .expect("export outcome should be observable");
        });
        input_writer
            .write_all(written_prefix)
            .expect("test input prefix should be written");
        await_test_file_length(&output_path, written_prefix_bytes);
        assert_eq!(
            read(&output_path).expect("partial test output should be readable"),
            written_prefix
        );

        job.cancel().expect("test cancellation should succeed");
        let outcome = outcome_receiver
            .recv_timeout(TEST_COMPLETION_TIMEOUT)
            .expect("cancelled export should finish promptly");

        assert_eq!(outcome.result_code, RESULT_CANCELLED);
        export_thread
            .join()
            .expect("cancelled export thread should finish");
        assert_eq!(
            metadata(&output_path)
                .expect("cancelled test output metadata should load")
                .len(),
            0
        );
        drop(input_writer);
        remove_file(output_path).expect("cancelled test output should be removed");
    }

    /// Verifies cancellation wakes a writer blocked on saturated output.
    #[test]
    fn cancellation_wakes_a_saturated_output_poll() {
        let (_provider_reader, mut output) = create_pipe();
        let output_flags =
            descriptor_flags(&output).expect("test output descriptor flags should load");
        set_nonblocking(&output, output_flags)
            .expect("test output descriptor should become nonblocking");
        fill_pipe(&mut output);
        let output_file = super::duplicate_file_descriptor(output.as_raw_fd())
            .expect("test output descriptor should duplicate");
        let output_flags =
            descriptor_flags(&output_file).expect("test output descriptor flags should load");
        set_nonblocking(&output_file, output_flags)
            .expect("test output descriptor should remain nonblocking");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");
        let writer_job = Arc::clone(&job);
        let (started_sender, started_receiver) = mpsc::channel();
        let (result_sender, result_receiver) = mpsc::channel();

        let writer_thread = thread::spawn(move || {
            let mut writer = PollingWriter::new(
                output_file,
                false,
                writer_job,
                Instant::now() + TEST_DEADLINE_OFFSET,
            );
            started_sender
                .send(())
                .expect("writer start should be observable");
            result_sender
                .send(writer.write(b"x").map(|_| ()))
                .expect("writer result should be observable");
        });
        started_receiver
            .recv_timeout(TEST_COMPLETION_TIMEOUT)
            .expect("writer should start promptly");

        job.cancel().expect("test cancellation should succeed");
        let result = result_receiver
            .recv_timeout(TEST_COMPLETION_TIMEOUT)
            .expect("cancelled writer should finish promptly");

        assert!(matches!(result, Err(error) if error.kind() == ErrorKind::Interrupted));
        writer_thread
            .join()
            .expect("cancelled writer thread should finish");
    }

    /// Verifies a same-length external edit conflicts without touching source bytes.
    #[test]
    fn same_length_source_change_conflicts_without_writing() {
        let source_path = test_output_path("conditional-same-length-conflict");
        let expected_source_bytes = b"original";
        let external_source_bytes = b"modified";
        assert_eq!(expected_source_bytes.len(), external_source_bytes.len());
        let input = create_sealed_input(b"replacement");
        let source = create_test_file(&source_path, external_source_bytes);
        let expected_source = source_version(expected_source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(b"replacement".len()).expect("replacement size should fit"),
            Some(&expected_source),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SOURCE_CONFLICT);
        assert!(!outcome.output_started);
        assert_eq!(
            read(&source_path).expect("conflicted source should remain readable"),
            external_source_bytes
        );
        drop(source);
        remove_file(source_path).expect("conflict test source should be removed");
    }

    /// Verifies shorter and longer external edits conflict before source output.
    #[test]
    fn source_length_changes_conflict_without_writing() {
        let expected_source_bytes = b"expected source";
        let expected_source = source_version(expected_source_bytes);
        let changed_sources: [(&str, &[u8]); 2] = [
            ("shorter", b"short"),
            ("longer", b"expected source with external suffix"),
        ];

        for (case_name, external_source_bytes) in changed_sources {
            let source_path = test_output_path(case_name);
            let input = create_sealed_input(b"replacement");
            let source = create_test_file(&source_path, external_source_bytes);
            let job = Arc::new(JobControl::new().expect("test job control should be created"));
            job.start().expect("test job should start once");

            let outcome = run_conditional_source_save(
                &job,
                input.as_raw_fd(),
                -1,
                source.as_raw_fd(),
                i64::try_from(b"replacement".len()).expect("replacement size should fit"),
                Some(&expected_source),
                TEST_TIMEOUT_MILLIS,
            );

            assert_eq!(outcome.result_code, RESULT_SOURCE_CONFLICT);
            assert!(!outcome.output_started);
            assert_eq!(
                read(&source_path).expect("conflicted source should remain readable"),
                external_source_bytes
            );
            drop(source);
            remove_file(source_path).expect("length-conflict test source should be removed");
        }
    }

    /// Verifies a new target without a baseline still returns one exact receipt.
    #[test]
    fn new_target_save_returns_an_exact_initial_receipt() {
        let source_path = test_output_path("conditional-new-target");
        let replacement = b"first saved revision";
        let input = create_sealed_input(replacement);
        let source = create_test_file(&source_path, b"");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(replacement.len()).expect("replacement size should fit"),
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(
            outcome.source_sha256,
            Some(source_version(replacement).sha256)
        );
        assert_eq!(
            read(&source_path).expect("new target should be readable"),
            replacement
        );
        drop(source);
        remove_file(source_path).expect("new target test source should be removed");
    }

    /// Verifies a new-target save never overwrites unexpected existing bytes.
    #[test]
    fn nonempty_new_target_conflicts_without_writing() {
        let source_path = test_output_path("conditional-nonempty-new-target");
        let unexpected_source_bytes = b"unexpected provider bytes";
        let input = create_sealed_input(b"first saved revision");
        let source = create_test_file(&source_path, unexpected_source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(b"first saved revision".len()).expect("replacement size should fit"),
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SOURCE_CONFLICT);
        assert!(!outcome.output_started);
        assert_eq!(
            read(&source_path).expect("nonempty new target should remain readable"),
            unexpected_source_bytes
        );
        drop(source);
        remove_file(source_path).expect("nonempty new-target test source should be removed");
    }

    /// Verifies an unsealed package is rejected before source mutation.
    #[test]
    fn unsealed_package_never_starts_source_output() {
        let input_path = test_output_path("conditional-unsealed-input");
        let source_path = test_output_path("conditional-unsealed-source");
        let initial_source_bytes = b"source remains intact";
        let replacement = b"replacement";
        let input = create_test_file(
            &input_path,
            &encode_source_save_package(
                &[TestPackageRecord {
                    kind: RECORD_KIND_PAYLOAD,
                    offset: 0,
                    byte_length: replacement.len() as u64,
                }],
                replacement,
                None,
                replacement.len() as u64,
            ),
        );
        let source = create_test_file(&source_path, initial_source_bytes);
        let expected_source = source_version(initial_source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(replacement.len()).expect("replacement size should fit"),
            Some(&expected_source),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
        assert!(!outcome.output_started);
        assert_eq!(
            read(&source_path).expect("rejected source should remain readable"),
            initial_source_bytes
        );
        drop(input);
        drop(source);
        remove_file(input_path).expect("unsealed test input should be removed");
        remove_file(source_path).expect("unsealed test source should be removed");
    }

    /// Verifies malformed sealed packages cannot cross the provider boundary.
    #[test]
    fn malformed_sealed_packages_never_start_source_output() {
        let replacement = b"replacement";
        let valid_package = encode_source_save_package(
            &[TestPackageRecord {
                kind: RECORD_KIND_PAYLOAD,
                offset: 0,
                byte_length: replacement.len() as u64,
            }],
            replacement,
            None,
            replacement.len() as u64,
        );
        let mut bad_magic = valid_package.clone();
        bad_magic[0] ^= u8::MAX;
        let mut reserved_header = valid_package.clone();
        reserved_header[PACKAGE_RESERVED_FIELD_OFFSET..PACKAGE_RESERVED_FIELD_OFFSET + 4]
            .copy_from_slice(&1_u32.to_le_bytes());
        let mut wrong_declared_bytes = valid_package.clone();
        let wrong_package_bytes =
            u64::try_from(valid_package.len() + 1).expect("test package length should fit u64");
        wrong_declared_bytes
            [PACKAGE_DECLARED_BYTES_FIELD_OFFSET..PACKAGE_DECLARED_BYTES_FIELD_OFFSET + 8]
            .copy_from_slice(&wrong_package_bytes.to_le_bytes());
        let mut noncontiguous_payload = valid_package;
        let first_record_offset_field = PACKAGE_HEADER_BYTES + PACKAGE_RECORD_OFFSET_FIELD_BYTES;
        noncontiguous_payload[first_record_offset_field..first_record_offset_field + 8]
            .copy_from_slice(&1_u64.to_le_bytes());
        let malformed_packages = [
            ("bad-magic", bad_magic),
            ("reserved-header", reserved_header),
            ("wrong-declared-bytes", wrong_declared_bytes),
            ("noncontiguous-payload", noncontiguous_payload),
        ];

        for (case_name, package_bytes) in malformed_packages {
            let source_path = test_output_path(case_name);
            let initial_source_bytes = b"provider bytes remain intact";
            let package = create_sealed_memfd(case_name, &package_bytes);
            let source = create_test_file(&source_path, initial_source_bytes);
            let expected_source = source_version(initial_source_bytes);
            let job = Arc::new(JobControl::new().expect("test job control should be created"));
            job.start().expect("test job should start once");

            let outcome = run_conditional_source_save(
                &job,
                package.as_raw_fd(),
                -1,
                source.as_raw_fd(),
                i64::try_from(replacement.len()).expect("replacement size should fit"),
                Some(&expected_source),
                TEST_TIMEOUT_MILLIS,
            );

            assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
            assert!(!outcome.output_started);
            assert_eq!(
                read(&source_path).expect("rejected source should remain readable"),
                initial_source_bytes
            );
            drop(source);
            remove_file(source_path).expect("malformed-package source should be removed");
        }
    }

    /// Verifies an unsealed source backing cannot cross the provider boundary.
    #[test]
    fn unsealed_source_backing_never_starts_source_output() {
        let source_path = test_output_path("conditional-unsealed-backing-source");
        let backing_bytes = b"immutable source bytes";
        let package_bytes = encode_source_save_package(
            &[TestPackageRecord {
                kind: RECORD_KIND_SOURCE,
                offset: 0,
                byte_length: backing_bytes.len() as u64,
            }],
            b"",
            Some(backing_bytes.len() as u64),
            backing_bytes.len() as u64,
        );
        let package = create_sealed_memfd("unsealed-backing-package", &package_bytes);
        let backing = create_unsealed_memfd("unsealed-backing", backing_bytes);
        let source = create_test_file(&source_path, b"");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            package.as_raw_fd(),
            backing.as_raw_fd(),
            source.as_raw_fd(),
            i64::try_from(backing_bytes.len()).expect("output size should fit"),
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
        assert!(!outcome.output_started);
        assert!(
            read(&source_path)
                .expect("rejected source should remain readable")
                .is_empty()
        );
        drop(source);
        remove_file(source_path).expect("unsealed-backing source should be removed");
    }

    /// Verifies an oversized source backing cannot cross the provider boundary.
    #[test]
    fn oversized_source_backing_never_starts_source_output() {
        let source_path = test_output_path("conditional-oversized-backing-source");
        let initial_source_bytes = b"provider bytes remain intact";
        let oversized_backing_bytes = MAX_OUTPUT_BYTES + 1;
        let package_bytes = encode_source_save_package(
            &[TestPackageRecord {
                kind: RECORD_KIND_SOURCE,
                offset: 0,
                byte_length: 1,
            }],
            b"",
            Some(oversized_backing_bytes),
            1,
        );
        let package = create_sealed_memfd("oversized-backing-package", &package_bytes);
        let backing = create_unsealed_memfd("oversized-backing", b"");
        backing
            .set_len(oversized_backing_bytes)
            .expect("oversized test backing should resize sparsely");
        seal_memfd(&backing);
        let source = create_test_file(&source_path, initial_source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            package.as_raw_fd(),
            backing.as_raw_fd(),
            source.as_raw_fd(),
            1,
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
        assert!(!outcome.output_started);
        assert_eq!(
            read(&source_path).expect("rejected source should remain readable"),
            initial_source_bytes
        );
        drop(source);
        remove_file(source_path).expect("oversized-backing source should be removed");
    }

    /// Verifies distinct descriptors for one package object are rejected.
    #[test]
    fn aliased_package_and_source_backing_are_rejected() {
        let source_path = test_output_path("conditional-package-backing-alias");
        let package_length = u64::try_from(PACKAGE_HEADER_BYTES)
            .and_then(|header_bytes| {
                u64::try_from(RECORD_HEADER_BYTES).map(|record_bytes| header_bytes + record_bytes)
            })
            .expect("source-only package length should fit u64");
        let package_bytes = encode_source_save_package(
            &[TestPackageRecord {
                kind: RECORD_KIND_SOURCE,
                offset: 0,
                byte_length: 1,
            }],
            b"",
            Some(package_length),
            1,
        );
        assert_eq!(package_bytes.len() as u64, package_length);
        let package = create_sealed_memfd("aliased-source-package", &package_bytes);
        let backing_alias = package
            .try_clone()
            .expect("test package descriptor should duplicate");
        let source = create_test_file(&source_path, b"");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            package.as_raw_fd(),
            backing_alias.as_raw_fd(),
            source.as_raw_fd(),
            1,
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
        assert!(!outcome.output_started);
        assert!(
            read(&source_path)
                .expect("aliased source should remain readable")
                .is_empty()
        );
        drop(source);
        remove_file(source_path).expect("aliased source should be removed");
    }

    /// Verifies positioned package reads preserve caller cursors and flags.
    #[test]
    fn source_package_reads_preserve_input_cursors_and_flags() {
        const PACKAGE_CURSOR: u64 = 7;
        const SOURCE_BACKING_CURSOR: u64 = 3;

        let source_path = test_output_path("conditional-positioned-inputs");
        let backing_bytes = b"alpha beta";
        let replacement = b"alpha-beta";
        let payload = b"-";
        let package_bytes = encode_source_save_package(
            &[
                TestPackageRecord {
                    kind: RECORD_KIND_SOURCE,
                    offset: 0,
                    byte_length: 5,
                },
                TestPackageRecord {
                    kind: RECORD_KIND_PAYLOAD,
                    offset: 0,
                    byte_length: payload.len() as u64,
                },
                TestPackageRecord {
                    kind: RECORD_KIND_SOURCE,
                    offset: 6,
                    byte_length: 4,
                },
            ],
            payload,
            Some(backing_bytes.len() as u64),
            replacement.len() as u64,
        );
        let mut package = create_sealed_memfd("positioned-input-package", &package_bytes);
        let mut backing = create_sealed_memfd("positioned-input-backing", backing_bytes);
        package
            .seek(SeekFrom::Start(PACKAGE_CURSOR))
            .expect("test package cursor should move");
        backing
            .seek(SeekFrom::Start(SOURCE_BACKING_CURSOR))
            .expect("test backing cursor should move");
        let package_flags_before =
            descriptor_flags(&package).expect("test package flags should load");
        let backing_flags_before =
            descriptor_flags(&backing).expect("test backing flags should load");
        let source = create_test_file(&source_path, b"");
        let source_flags_before = descriptor_flags(&source).expect("test source flags should load");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            package.as_raw_fd(),
            backing.as_raw_fd(),
            source.as_raw_fd(),
            i64::try_from(replacement.len()).expect("replacement size should fit"),
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(
            read(&source_path).expect("mixed replacement should be readable"),
            replacement
        );
        assert_eq!(
            package
                .stream_position()
                .expect("test package cursor should load"),
            PACKAGE_CURSOR
        );
        assert_eq!(
            backing
                .stream_position()
                .expect("test backing cursor should load"),
            SOURCE_BACKING_CURSOR
        );
        assert_eq!(
            descriptor_flags(&package).expect("test package flags should reload"),
            package_flags_before
        );
        assert_eq!(
            descriptor_flags(&backing).expect("test backing flags should reload"),
            backing_flags_before
        );
        assert_eq!(
            descriptor_flags(&source).expect("test source flags should reload"),
            source_flags_before
        );
        drop(source);
        remove_file(source_path).expect("positioned-input source should be removed");
    }

    /// Verifies conditional saving requires an exact read-write source capability.
    #[test]
    fn read_only_source_is_rejected_before_output() {
        let source_path = test_output_path("conditional-read-only-source");
        let initial_source_bytes = b"read-only source";
        drop(create_test_file(&source_path, initial_source_bytes));
        let source = File::open(&source_path).expect("read-only source should open");
        let input = create_sealed_input(b"replacement");
        let expected_source = source_version(initial_source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(b"replacement".len()).expect("replacement size should fit"),
            Some(&expected_source),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
        assert!(!outcome.output_started);
        assert_eq!(
            read(&source_path).expect("read-only source should remain readable"),
            initial_source_bytes
        );
        drop(source);
        remove_file(source_path).expect("read-only test source should be removed");
    }

    /// Verifies a receipt becomes the exact baseline for the next conditional save.
    #[test]
    fn successful_receipt_advances_the_next_source_baseline() {
        let source_path = test_output_path("conditional-baseline-update");
        let initial_source_bytes = b"initial source";
        let first_replacement = b"first replacement";
        let second_replacement = b"second replacement";
        let source = create_test_file(&source_path, initial_source_bytes);
        let first_input = create_sealed_input(first_replacement);
        let first_expected_source = source_version(initial_source_bytes);
        let first_job =
            Arc::new(JobControl::new().expect("first test job control should be created"));
        first_job.start().expect("first test job should start once");

        let first_outcome = run_conditional_source_save(
            &first_job,
            first_input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(first_replacement.len()).expect("first replacement size should fit"),
            Some(&first_expected_source),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(first_outcome.result_code, RESULT_SUCCESS);
        let advanced_source = ExactSourceVersion {
            byte_length: first_outcome.output_bytes,
            sha256: first_outcome
                .source_sha256
                .expect("successful source save should return a digest"),
        };
        assert!(super::source_versions_match(
            &advanced_source,
            &source_version(first_replacement)
        ));
        assert_eq!(
            read(&source_path).expect("first replacement should be readable"),
            first_replacement
        );

        let second_input = create_sealed_input(second_replacement);
        let second_job =
            Arc::new(JobControl::new().expect("second test job control should be created"));
        second_job
            .start()
            .expect("second test job should start once");
        let second_outcome = run_conditional_source_save(
            &second_job,
            second_input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(second_replacement.len()).expect("second replacement size should fit"),
            Some(&advanced_source),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(second_outcome.result_code, RESULT_SUCCESS);
        assert_eq!(
            second_outcome.source_sha256,
            Some(source_version(second_replacement).sha256)
        );
        assert_eq!(
            read(&source_path).expect("second replacement should be readable"),
            second_replacement
        );
        drop(source);
        remove_file(source_path).expect("baseline test source should be removed");
    }

    /// Verifies a sparse 100 MiB source save remains exact with fixed buffers.
    #[test]
    #[ignore = "runs the sparse 100 MiB source-save acceptance test"]
    fn streams_large_sparse_source_save_with_exact_receipt() {
        let source_path = test_output_path("large-conditional-source");
        let (package, source_backing, expected_sha256) =
            create_large_sparse_package(TEST_LARGE_CONDITIONAL_BYTES);
        assert!(
            package
                .metadata()
                .expect("large test package metadata should load")
                .len()
                < super::STREAM_BUFFER_BYTES as u64
        );
        let source = create_test_file(&source_path, b"");
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_conditional_source_save(
            &job,
            package.as_raw_fd(),
            source_backing.as_raw_fd(),
            source.as_raw_fd(),
            i64::try_from(TEST_LARGE_CONDITIONAL_BYTES).expect("large byte count should fit"),
            None,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(outcome.input_bytes, TEST_LARGE_CONDITIONAL_BYTES);
        assert_eq!(outcome.output_bytes, TEST_LARGE_CONDITIONAL_BYTES);
        assert_eq!(outcome.source_sha256, Some(expected_sha256));
        assert_eq!(
            metadata(&source_path)
                .expect("large source metadata should load")
                .len(),
            TEST_LARGE_CONDITIONAL_BYTES
        );
        assert_eq!(file_sha256(&source_path), expected_sha256);
        drop(source);
        remove_file(source_path).expect("large conditional test source should be removed");
    }

    /// Verifies preflight cancellation preserves the complete original source.
    #[test]
    fn cancellation_during_source_preflight_never_starts_output() {
        let source_path = test_output_path("conditional-preflight-cancellation");
        let initial_source_bytes = b"source must remain exact";
        let input = create_sealed_input(b"replacement");
        let source = create_test_file(&source_path, initial_source_bytes);
        let expected_source = source_version(initial_source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");
        job.cancel().expect("preflight cancellation should succeed");

        let outcome = run_conditional_source_save(
            &job,
            input.as_raw_fd(),
            -1,
            source.as_raw_fd(),
            i64::try_from(b"replacement".len()).expect("replacement size should fit"),
            Some(&expected_source),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_CANCELLED);
        assert!(!outcome.output_started);
        assert_eq!(
            read(&source_path).expect("cancelled source should remain readable"),
            initial_source_bytes
        );
        drop(source);
        remove_file(source_path).expect("cancellation test source should be removed");
    }

    /// Verifies a partial post-boundary write is always classified as uncertain.
    #[test]
    fn partial_source_write_becomes_uncertain() {
        let replacement = b"replacement must fail after a prefix";
        let mut input = Cursor::new(replacement.as_slice());
        let mut source = FailingRewritableSource::new(PARTIAL_SOURCE_BYTES);
        let job = JobControl::new().expect("test job control should be created");
        job.start().expect("test job should start once");
        job.begin_output(Instant::now() + TEST_DEADLINE_OFFSET)
            .expect("test output should begin");

        let saved = replace_source_bytes(
            &mut input,
            &mut source,
            replacement.len() as u64,
            &job,
            Instant::now() + TEST_DEADLINE_OFFSET,
        );
        let outcome =
            finish_conditional_source_save(saved, &job, Instant::now() + TEST_DEADLINE_OFFSET);

        assert_eq!(outcome.result_code, RESULT_SOURCE_UNCERTAIN);
        assert!(outcome.output_started);
        assert_eq!(
            source.accepted_bytes(),
            &replacement[..PARTIAL_SOURCE_BYTES]
        );
    }

    /// Verifies readback corruption after a complete write becomes uncertain.
    #[test]
    fn corrupted_source_readback_becomes_uncertain() {
        let replacement = b"complete bytes that fail readback";
        let mut input = Cursor::new(replacement.as_slice());
        let mut source = CorruptingRewritableSource::new();
        let job = JobControl::new().expect("test job control should be created");
        job.start().expect("test job should start once");
        job.begin_output(Instant::now() + TEST_DEADLINE_OFFSET)
            .expect("test output should begin");

        let saved = replace_source_bytes(
            &mut input,
            &mut source,
            replacement.len() as u64,
            &job,
            Instant::now() + TEST_DEADLINE_OFFSET,
        );
        let outcome =
            finish_conditional_source_save(saved, &job, Instant::now() + TEST_DEADLINE_OFFSET);

        assert_eq!(outcome.result_code, RESULT_SOURCE_UNCERTAIN);
        assert!(outcome.output_started);
        assert_ne!(source.corrupted_bytes(), replacement);
    }

    /// Verifies read-only verification accepts only the returned exact receipt.
    #[test]
    fn read_only_source_verification_detects_same_size_changes() {
        let source_path = test_output_path("read-only-source-verification");
        let source_bytes = b"verified";
        let changed_bytes = b"conflict";
        assert_eq!(source_bytes.len(), changed_bytes.len());
        drop(create_test_file(&source_path, source_bytes));
        let source = File::open(&source_path).expect("verification source should open read-only");
        let expected_source = source_version(source_bytes);
        let matching_job =
            Arc::new(JobControl::new().expect("matching job control should be created"));
        matching_job
            .start()
            .expect("matching job should start once");

        let matching_outcome = run_source_verification(
            &matching_job,
            source.as_raw_fd(),
            &expected_source,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(matching_outcome.result_code, RESULT_SUCCESS);
        drop(source);
        drop(create_test_file(&source_path, changed_bytes));
        let changed_source =
            File::open(&source_path).expect("changed verification source should open read-only");
        let conflicting_job =
            Arc::new(JobControl::new().expect("conflicting job control should be created"));
        conflicting_job
            .start()
            .expect("conflicting job should start once");

        let conflicting_outcome = run_source_verification(
            &conflicting_job,
            changed_source.as_raw_fd(),
            &expected_source,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(conflicting_outcome.result_code, RESULT_SOURCE_CONFLICT);
        assert!(!conflicting_outcome.output_started);
        drop(changed_source);
        remove_file(source_path).expect("verification test source should be removed");
    }

    /// Rejects a wider source capability from the read-only verification worker.
    #[test]
    fn read_only_source_verification_rejects_a_read_write_descriptor() {
        let source_path = test_output_path("read-write-source-verification");
        let source_bytes = b"least authority";
        let source = create_test_file(&source_path, source_bytes);
        let expected_source = source_version(source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_source_verification(
            &job,
            source.as_raw_fd(),
            &expected_source,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, super::RESULT_INVALID_DESCRIPTOR);
        assert!(!outcome.output_started);
        drop(source);
        remove_file(source_path).expect("read-write verification source should be removed");
    }

    /// Verifies a fresh nonseekable provider stream can confirm an exact receipt.
    #[test]
    fn read_only_source_verification_accepts_a_fresh_pipe() {
        let source_bytes = b"fresh provider stream";
        let (source, mut provider) = create_pipe();
        provider
            .write_all(source_bytes)
            .expect("provider bytes should be written");
        drop(provider);
        let expected_source = source_version(source_bytes);
        let job = Arc::new(JobControl::new().expect("test job control should be created"));
        job.start().expect("test job should start once");

        let outcome = run_source_verification(
            &job,
            source.as_raw_fd(),
            &expected_source,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(outcome.input_bytes, source_bytes.len() as u64);
        assert!(!outcome.output_started);
    }

    /// Returns the exact version of a bounded read-only source.
    #[test]
    fn read_only_source_inspection_returns_exact_version() {
        let source_path = test_output_path("read-only-source-inspection");
        let source_bytes = b"inspect this source";
        drop(create_test_file(&source_path, source_bytes));
        let source = File::open(&source_path).expect("inspection source should open read-only");
        let job = Arc::new(JobControl::new().expect("inspection job control should be created"));
        job.start().expect("inspection job should start once");

        let outcome = run_source_inspection(
            &job,
            source.as_raw_fd(),
            i64::try_from(source_bytes.len()).expect("test source length should fit i64"),
            TEST_TIMEOUT_MILLIS,
        );

        let expected = source_version(source_bytes);
        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(outcome.input_bytes, expected.byte_length);
        assert_eq!(outcome.source_sha256, Some(expected.sha256));
        assert!(!outcome.output_started);
        drop(source);
        remove_file(source_path).expect("inspection test source should be removed");
    }

    /// Rejects a source that exceeds the inspection byte bound.
    #[test]
    fn read_only_source_inspection_enforces_byte_limit() {
        let source_bytes = b"one byte too many";
        let (source, mut provider) = create_pipe();
        provider
            .write_all(source_bytes)
            .expect("provider bytes should be written");
        drop(provider);
        let job = Arc::new(JobControl::new().expect("inspection job control should be created"));
        job.start().expect("inspection job should start once");

        let outcome = run_source_inspection(
            &job,
            source.as_raw_fd(),
            i64::try_from(source_bytes.len()).expect("test source length should fit i64") - 1,
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_INPUT_LIMIT);
        assert_eq!(outcome.input_bytes, 0);
        assert_eq!(outcome.source_sha256, None);
        assert!(!outcome.output_started);
    }

    /// Fails every source write after one deterministic accepted prefix.
    struct FailingRewritableSource {
        bytes: Cursor<Vec<u8>>,
        maximum_written_bytes: usize,
    }

    impl FailingRewritableSource {
        /// Creates one empty source with a deterministic write failure boundary.
        fn new(maximum_written_bytes: usize) -> Self {
            Self {
                bytes: Cursor::new(Vec::new()),
                maximum_written_bytes,
            }
        }

        /// Returns every byte accepted before the synthetic failure.
        fn accepted_bytes(&self) -> &[u8] {
            self.bytes.get_ref()
        }
    }

    impl Read for FailingRewritableSource {
        /// Reads source bytes through the in-memory cursor.
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            self.bytes.read(buffer)
        }
    }

    impl Write for FailingRewritableSource {
        /// Accepts one prefix before returning a synthetic partial-write failure.
        fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
            let written_bytes =
                usize::try_from(self.bytes.position()).expect("test cursor position should fit");
            if written_bytes == self.maximum_written_bytes {
                return Err(std::io::Error::new(
                    ErrorKind::BrokenPipe,
                    "synthetic source write failure",
                ));
            }
            let accepted_bytes = (self.maximum_written_bytes - written_bytes).min(buffer.len());
            self.bytes.write(&buffer[..accepted_bytes])
        }

        /// Accepts one in-memory flush.
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl Seek for FailingRewritableSource {
        /// Seeks through the in-memory source cursor.
        fn seek(&mut self, position: SeekFrom) -> std::io::Result<u64> {
            self.bytes.seek(position)
        }
    }

    impl RewritableSource for FailingRewritableSource {
        /// Resizes the in-memory source to an exact byte length.
        fn truncate(&mut self, byte_length: u64) -> std::io::Result<()> {
            let byte_length = usize::try_from(byte_length).map_err(|_error| {
                std::io::Error::new(ErrorKind::InvalidInput, "test byte length is too large")
            })?;
            self.bytes.get_mut().resize(byte_length, 0);
            if self.bytes.position() > byte_length as u64 {
                self.bytes.set_position(byte_length as u64);
            }
            Ok(())
        }

        /// Accepts one in-memory synchronization.
        fn synchronize(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    /// Corrupts one completed source immediately before readback verification.
    struct CorruptingRewritableSource {
        bytes: Cursor<Vec<u8>>,
    }

    impl CorruptingRewritableSource {
        /// Creates one empty source that corrupts its first synchronized byte.
        fn new() -> Self {
            Self {
                bytes: Cursor::new(Vec::new()),
            }
        }

        /// Returns every source byte after synthetic corruption.
        fn corrupted_bytes(&self) -> &[u8] {
            self.bytes.get_ref()
        }
    }

    impl Read for CorruptingRewritableSource {
        /// Reads source bytes through the in-memory cursor.
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            self.bytes.read(buffer)
        }
    }

    impl Write for CorruptingRewritableSource {
        /// Writes complete source bytes through the in-memory cursor.
        fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
            self.bytes.write(buffer)
        }

        /// Accepts one in-memory flush.
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl Seek for CorruptingRewritableSource {
        /// Seeks through the in-memory source cursor.
        fn seek(&mut self, position: SeekFrom) -> std::io::Result<u64> {
            self.bytes.seek(position)
        }
    }

    impl RewritableSource for CorruptingRewritableSource {
        /// Resizes the in-memory source to an exact byte length.
        fn truncate(&mut self, byte_length: u64) -> std::io::Result<()> {
            let byte_length = usize::try_from(byte_length).map_err(|_error| {
                std::io::Error::new(ErrorKind::InvalidInput, "test byte length is too large")
            })?;
            self.bytes.get_mut().resize(byte_length, 0);
            if self.bytes.position() > byte_length as u64 {
                self.bytes.set_position(byte_length as u64);
            }
            Ok(())
        }

        /// Corrupts the first byte before the worker's readback pass.
        fn synchronize(&mut self) -> std::io::Result<()> {
            let first_byte = self
                .bytes
                .get_mut()
                .first_mut()
                .ok_or_else(|| std::io::Error::other("test source unexpectedly empty"))?;
            *first_byte ^= u8::MAX;
            Ok(())
        }
    }

    /// Creates one exact source version for deterministic test bytes.
    fn source_version(bytes: &[u8]) -> ExactSourceVersion {
        let sha256: [u8; super::SHA_256_BYTE_COUNT] = Sha256::digest(bytes).into();
        ExactSourceVersion {
            byte_length: bytes.len() as u64,
            sha256,
        }
    }

    /// Creates one sealed anonymous payload-only source-save package.
    fn create_sealed_input(bytes: &[u8]) -> File {
        let byte_length = u64::try_from(bytes.len()).expect("test input byte count should fit u64");
        let package_bytes = encode_source_save_package(
            &[TestPackageRecord {
                kind: RECORD_KIND_PAYLOAD,
                offset: 0,
                byte_length,
            }],
            bytes,
            None,
            byte_length,
        );
        create_sealed_memfd("beautyxt-export-service-test", &package_bytes)
    }

    /// Creates one sealed anonymous descriptor containing exact test bytes.
    fn create_sealed_memfd(name: &str, bytes: &[u8]) -> File {
        let input = create_unsealed_memfd(name, bytes);
        seal_memfd(&input);
        input
    }

    /// Creates one unsealed anonymous descriptor containing exact test bytes.
    fn create_unsealed_memfd(name: &str, bytes: &[u8]) -> File {
        let name = CString::new(name).expect("test memfd name should not contain null bytes");
        // SAFETY: memfd_create receives one valid C string and fixed creation flags.
        let raw_fd = unsafe {
            libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC | libc::MFD_ALLOW_SEALING)
        };
        assert!(raw_fd >= 0, "test memfd should be created");
        // SAFETY: successful memfd_create transferred one new owned descriptor.
        let mut input = File::from(unsafe { OwnedFd::from_raw_fd(raw_fd) });
        input
            .write_all(bytes)
            .expect("test memfd bytes should be written");
        input
            .seek(SeekFrom::Start(0))
            .expect("test memfd should rewind");
        input
    }

    /// Applies every required immutable seal to one test memfd.
    fn seal_memfd(input: &File) {
        // SAFETY: F_ADD_SEALS applies fixed immutable seals to the owned memfd.
        let seal_result = unsafe {
            libc::fcntl(
                input.as_raw_fd(),
                libc::F_ADD_SEALS,
                REQUIRED_IMMUTABLE_INPUT_SEALS,
            )
        };
        assert_eq!(seal_result, 0, "test memfd should seal");
    }

    /// Creates one small package over a large immutable source backing.
    fn create_large_sparse_package(
        byte_length: u64,
    ) -> (File, File, [u8; super::SHA_256_BYTE_COUNT]) {
        assert!(
            byte_length > 1,
            "large sparse fixture should have two source runs"
        );
        let replacement_offset = byte_length / 2;
        let suffix_offset = replacement_offset + 1;
        let package_bytes = encode_source_save_package(
            &[
                TestPackageRecord {
                    kind: RECORD_KIND_SOURCE,
                    offset: 0,
                    byte_length: replacement_offset,
                },
                TestPackageRecord {
                    kind: RECORD_KIND_PAYLOAD,
                    offset: 0,
                    byte_length: 1,
                },
                TestPackageRecord {
                    kind: RECORD_KIND_SOURCE,
                    offset: suffix_offset,
                    byte_length: byte_length - suffix_offset,
                },
            ],
            &[TEST_LARGE_CONDITIONAL_REPLACEMENT],
            Some(byte_length),
            byte_length,
        );
        let package = create_sealed_memfd("beautyxt-export-service-large-package", &package_bytes);
        let mut source_backing =
            create_unsealed_memfd("beautyxt-export-service-large-backing", b"");
        let buffer = vec![TEST_LARGE_CONDITIONAL_FILL; super::STREAM_BUFFER_BYTES];
        let mut remaining_bytes = byte_length;
        while remaining_bytes != 0 {
            let bytes_to_write = usize::try_from(remaining_bytes)
                .unwrap_or(usize::MAX)
                .min(buffer.len());
            source_backing
                .write_all(&buffer[..bytes_to_write])
                .expect("large source-backing bytes should be written");
            remaining_bytes -=
                u64::try_from(bytes_to_write).expect("large test write size should fit");
        }
        source_backing
            .seek(SeekFrom::Start(0))
            .expect("large source backing should rewind");
        seal_memfd(&source_backing);
        let expected_sha256 = sparse_output_sha256(byte_length, replacement_offset);
        (package, source_backing, expected_sha256)
    }

    /// Hashes one repeated-byte source with one single-byte replacement.
    fn sparse_output_sha256(
        byte_length: u64,
        replacement_offset: u64,
    ) -> [u8; super::SHA_256_BYTE_COUNT] {
        let buffer = vec![TEST_LARGE_CONDITIONAL_FILL; super::STREAM_BUFFER_BYTES];
        let mut sha256 = Sha256::new();
        update_repeated_digest(&mut sha256, &buffer, replacement_offset);
        sha256.update([TEST_LARGE_CONDITIONAL_REPLACEMENT]);
        update_repeated_digest(&mut sha256, &buffer, byte_length - replacement_offset - 1);
        sha256.finalize().into()
    }

    /// Adds one repeated-byte range to a test digest.
    fn update_repeated_digest(sha256: &mut Sha256, buffer: &[u8], mut remaining_bytes: u64) {
        while remaining_bytes != 0 {
            let bytes_to_hash = usize::try_from(remaining_bytes)
                .unwrap_or(usize::MAX)
                .min(buffer.len());
            sha256.update(&buffer[..bytes_to_hash]);
            remaining_bytes -=
                u64::try_from(bytes_to_hash).expect("large test hash size should fit");
        }
    }

    /// Encodes one complete source-save package fixture.
    fn encode_source_save_package(
        records: &[TestPackageRecord],
        payload: &[u8],
        source_bytes: Option<u64>,
        output_bytes: u64,
    ) -> Vec<u8> {
        let record_count = u64::try_from(records.len()).expect("test record count should fit u64");
        let header_bytes =
            u64::try_from(PACKAGE_HEADER_BYTES).expect("header byte count should fit u64");
        let record_header_bytes =
            u64::try_from(RECORD_HEADER_BYTES).expect("record byte count should fit u64");
        let record_bytes = record_count * record_header_bytes;
        let payload_bytes =
            u64::try_from(payload.len()).expect("test payload length should fit u64");
        let package_bytes = header_bytes
            .checked_add(record_bytes)
            .and_then(|byte_length| byte_length.checked_add(payload_bytes))
            .expect("test package length should fit u64");
        let mut package =
            Vec::with_capacity(usize::try_from(package_bytes).expect("package should fit usize"));
        package.extend_from_slice(&PACKAGE_MAGIC);
        package.extend_from_slice(&PACKAGE_VERSION.to_le_bytes());
        package.extend_from_slice(
            &u32::try_from(PACKAGE_HEADER_BYTES)
                .expect("header byte count should fit u32")
                .to_le_bytes(),
        );
        package.extend_from_slice(
            &source_bytes
                .map_or(0, |_byte_length| FLAG_SOURCE_PRESENT)
                .to_le_bytes(),
        );
        package.extend_from_slice(&0_u32.to_le_bytes());
        package.extend_from_slice(&record_count.to_le_bytes());
        package.extend_from_slice(&package_bytes.to_le_bytes());
        package.extend_from_slice(&output_bytes.to_le_bytes());
        package.extend_from_slice(&payload_bytes.to_le_bytes());
        package.extend_from_slice(&source_bytes.unwrap_or(0).to_le_bytes());
        for record in records {
            package.extend_from_slice(&record.kind.to_le_bytes());
            package.extend_from_slice(&0_u32.to_le_bytes());
            package.extend_from_slice(&record.offset.to_le_bytes());
            package.extend_from_slice(&record.byte_length.to_le_bytes());
        }
        assert_eq!(
            package.len(),
            PACKAGE_HEADER_BYTES
                + usize::try_from(record_bytes).expect("record bytes should fit usize")
        );
        package.extend_from_slice(payload);
        assert_eq!(
            package.len(),
            usize::try_from(package_bytes).expect("package byte count should fit usize")
        );
        package
    }

    /// Hashes one linked test output through a fixed-size buffer.
    fn file_sha256(path: &Path) -> [u8; super::SHA_256_BYTE_COUNT] {
        let mut file = File::open(path).expect("hashed test file should open");
        let mut buffer = vec![0_u8; super::STREAM_BUFFER_BYTES];
        let mut sha256 = Sha256::new();
        loop {
            let bytes_read = file
                .read(&mut buffer)
                .expect("hashed test file should remain readable");
            if bytes_read == 0 {
                return sha256.finalize().into();
            }
            sha256.update(&buffer[..bytes_read]);
        }
    }

    /// Fills one nonblocking pipe until another write would block.
    fn fill_pipe(output: &mut File) {
        let buffer = vec![0_u8; PIPE_FILL_BUFFER_BYTES].into_boxed_slice();
        loop {
            match output.write(&buffer) {
                Ok(0) => panic!("test pipe made no write progress"),
                Ok(_) => {}
                Err(error) if error.kind() == ErrorKind::WouldBlock => return,
                Err(error) => panic!("test pipe fill failed: {error}"),
            }
        }
    }

    /// Creates one close-on-exec pipe with explicit endpoint ownership.
    fn create_pipe() -> (File, File) {
        let mut raw_fds = [-1; 2];
        // SAFETY: `raw_fds` provides writable storage for both descriptors, and
        // successful pipe2 transfers one new descriptor into each slot.
        let result = unsafe { libc::pipe2(raw_fds.as_mut_ptr(), libc::O_CLOEXEC) };
        assert_eq!(result, 0, "test pipe should be created");
        // SAFETY: successful pipe2 returned two distinct owned descriptors.
        let input = File::from(unsafe { OwnedFd::from_raw_fd(raw_fds[0]) });
        // SAFETY: successful pipe2 returned two distinct owned descriptors.
        let output = File::from(unsafe { OwnedFd::from_raw_fd(raw_fds[1]) });
        (input, output)
    }

    /// Creates one deterministic linked test file with exact initial bytes.
    fn create_test_file(path: &Path, initial_bytes: &[u8]) -> File {
        match remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => panic!("stale test file should be removable: {error}"),
        }
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(path)
            .expect("linked test file should be created");
        file.write_all(initial_bytes)
            .expect("initial test bytes should be written");
        file.seek(SeekFrom::Start(0))
            .expect("test file should rewind");
        file
    }

    /// Waits until one test file reaches its exact expected byte length.
    fn await_test_file_length(path: &Path, expected_bytes: u64) {
        let deadline = Instant::now() + TEST_COMPLETION_TIMEOUT;
        loop {
            let actual_bytes = metadata(path)
                .expect("observed test output metadata should load")
                .len();
            if actual_bytes == expected_bytes {
                return;
            }
            assert!(
                actual_bytes < expected_bytes,
                "observed test output exceeded its expected byte length"
            );
            assert!(
                Instant::now() < deadline,
                "test output did not reach its expected byte length"
            );
            thread::yield_now();
        }
    }

    /// Returns one process-unique deterministic test path.
    fn test_output_path(case_name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "beautyxt-export-service-{case_name}-{}.tmp",
            std::process::id()
        ))
    }

    /// Verifies cancellation wins when it precedes the success commit.
    #[test]
    fn cancellation_before_commit_wins() {
        let job = JobControl::new().expect("test job control should be created");
        job.start().expect("test job should start once");
        job.cancel().expect("test cancellation should succeed");

        assert_eq!(
            job.commit(Instant::now() + TEST_DEADLINE_OFFSET),
            Err(ExportInterruption::Cancelled)
        );
    }

    /// Verifies cancellation becomes a no-op after the success commit.
    #[test]
    fn cancellation_after_commit_is_a_no_op() {
        let job = JobControl::new().expect("test job control should be created");
        job.start().expect("test job should start once");
        job.begin_output(Instant::now() + TEST_DEADLINE_OFFSET)
            .expect("test output should begin");
        job.commit(Instant::now() + TEST_DEADLINE_OFFSET)
            .expect("test success should commit");

        job.cancel()
            .expect("post-commit cancellation should be a no-op");
        assert_eq!(
            job.checkpoint(Instant::now() + TEST_DEADLINE_OFFSET),
            Ok(())
        );
    }

    /// Verifies every simultaneous commit-cancel race has one atomic winner.
    #[test]
    fn simultaneous_commit_and_cancellation_remain_consistent() {
        for _case_index in 0..SIMULTANEOUS_RACE_CASES {
            let job = Arc::new(JobControl::new().expect("test job control should be created"));
            job.start().expect("test job should start once");
            job.begin_output(Instant::now() + TEST_DEADLINE_OFFSET)
                .expect("test output should begin");
            let start_barrier = Arc::new(Barrier::new(3));

            let cancellation_job = Arc::clone(&job);
            let cancellation_barrier = Arc::clone(&start_barrier);
            let cancellation = thread::spawn(move || {
                cancellation_barrier.wait();
                cancellation_job.cancel()
            });

            let commit_job = Arc::clone(&job);
            let commit_barrier = Arc::clone(&start_barrier);
            let commit = thread::spawn(move || {
                commit_barrier.wait();
                commit_job.commit(Instant::now() + TEST_DEADLINE_OFFSET)
            });

            start_barrier.wait();
            cancellation
                .join()
                .expect("cancellation thread should finish")
                .expect("test cancellation should succeed");
            let commit_result = commit.join().expect("commit thread should finish");
            let checkpoint = job.checkpoint(Instant::now() + TEST_DEADLINE_OFFSET);
            match commit_result {
                Ok(()) => assert_eq!(checkpoint, Ok(())),
                Err(ExportInterruption::Cancelled) => {
                    assert_eq!(checkpoint, Err(ExportInterruption::Cancelled));
                }
                Err(ExportInterruption::DeadlineExceeded) => {
                    panic!("test commit deadline should not expire");
                }
            }
        }
    }

    /// Verifies every core failure maps onto a stable platform result code.
    #[test]
    fn maps_core_errors_to_stable_results() {
        let cases = [
            (ExportError::Cancelled, RESULT_CANCELLED),
            (ExportError::DeadlineExceeded, RESULT_TIMEOUT),
            (
                ExportError::LimitTooHigh {
                    requested: 2,
                    hard_maximum: 1,
                },
                RESULT_INPUT_LIMIT,
            ),
            (
                ExportError::SnapshotTooLarge {
                    limit: 1,
                    observed: 2,
                },
                RESULT_INPUT_LENGTH_MISMATCH,
            ),
            (
                ExportError::InputIo(io::Error::other("test input")),
                RESULT_INPUT_IO,
            ),
            (
                ExportError::OutputIo(io::Error::other("test output")),
                RESULT_OUTPUT_IO,
            ),
            (ExportError::InputByteCountOverflow, RESULT_INTERNAL),
            (ExportError::OutputByteCountOverflow, RESULT_INTERNAL),
        ];

        for (error, expected) in cases {
            assert_eq!(result_code_for_error(&error), expected);
        }
    }
}
