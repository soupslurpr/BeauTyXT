//! Bridges isolated Android import requests to the Rust validator and copier.

use std::collections::BTreeMap;
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs::File;
use std::io::{ErrorKind, Read, Seek, SeekFrom};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::MetadataExt;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use beautyxt_import_core::{
    ImportControl, ImportError, ImportInterruption, ImportLimits, ImportSummary,
    SHA_256_BYTE_COUNT, copy_raw_utf8,
};
use jni::EnvUnowned;
use jni::errors::{Error as JniError, ThrowRuntimeExAndDefault};
use jni::objects::{JByteArray, JClass, JLongArray};
use jni::sys::{jint, jlong};

const FIRST_JOB_HANDLE: i64 = 1;
const JOB_ACTIVE: u8 = 0;
const JOB_CANCELLED: u8 = 1;
const JOB_COMMITTED: u8 = 2;
const RESULT_VALUE_COUNT: usize = 3;
const RESULT_INPUT_BYTES_INDEX: usize = 0;
const RESULT_OUTPUT_BYTES_INDEX: usize = 1;
const RESULT_SOURCE_FLAGS_INDEX: usize = 2;
const MAX_TIMEOUT_MILLIS: u64 = 15 * 60 * 1000;
const MAX_POLL_WAIT_MILLIS: u128 = 100;

const RESULT_SUCCESS: jint = 0;
const RESULT_CANCELLED: jint = 1;
const RESULT_INVALID_UTF8: jint = 2;
const RESULT_UNSUPPORTED_BOM: jint = 3;
const RESULT_INPUT_LIMIT: jint = 4;
const RESULT_OUTPUT_LIMIT: jint = 5;
const RESULT_TIMEOUT: jint = 6;
const RESULT_INVALID_DESCRIPTOR: jint = 7;
const RESULT_INPUT_IO: jint = 8;
const RESULT_OUTPUT_IO: jint = 9;
const RESULT_INTERNAL: jint = 10;

static JOBS: LazyLock<Mutex<JobRegistry>> = LazyLock::new(|| Mutex::new(JobRegistry::new()));

/// Owns live import controls behind opaque positive handles.
struct JobRegistry {
    next_handle: i64,
    jobs: BTreeMap<i64, Arc<JobControl>>,
}

impl JobRegistry {
    /// Creates an empty import job registry.
    fn new() -> Self {
        Self {
            next_handle: FIRST_JOB_HANDLE,
            jobs: BTreeMap::new(),
        }
    }

    /// Inserts a new control and returns its opaque handle.
    fn insert(&mut self, job: Arc<JobControl>) -> Result<i64, BridgeError> {
        let handle = self.next_handle;
        self.next_handle = self
            .next_handle
            .checked_add(1)
            .ok_or(BridgeError::HandleSpaceExhausted)?;
        self.jobs.insert(handle, job);
        Ok(handle)
    }

    /// Clones the control associated with a valid handle.
    fn get(&self, handle: i64) -> Result<Arc<JobControl>, BridgeError> {
        self.jobs
            .get(&handle)
            .cloned()
            .ok_or(BridgeError::UnknownHandle(handle))
    }

    /// Removes the control associated with a valid handle.
    fn remove(&mut self, handle: i64) -> Result<(), BridgeError> {
        self.jobs
            .remove(&handle)
            .map(|_| ())
            .ok_or(BridgeError::UnknownHandle(handle))
    }
}

/// Coordinates cancellation and single execution for one import.
struct JobControl {
    started: AtomicBool,
    terminal_state: AtomicU8,
    cancellation_signal: OwnedFd,
}

impl JobControl {
    /// Creates a control with a nonblocking close-on-exec event descriptor.
    fn new() -> Result<Self, BridgeError> {
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

    /// Cancels an uncommitted job and wakes a blocked descriptor poll.
    fn cancel(&self) -> Result<(), BridgeError> {
        match self.terminal_state.compare_exchange(
            JOB_ACTIVE,
            JOB_CANCELLED,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => {}
            Err(JOB_CANCELLED | JOB_COMMITTED) => return Ok(()),
            Err(_) => unreachable!("job terminal state must be valid"),
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
    fn start(&self) -> Result<(), BridgeError> {
        self.started
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
            .map_err(|_| BridgeError::JobAlreadyStarted)
    }

    /// Returns a cancellation or deadline decision for the core.
    fn checkpoint(&self, deadline: Instant) -> Result<(), ImportInterruption> {
        match self.terminal_state.load(Ordering::Acquire) {
            JOB_ACTIVE => {}
            JOB_CANCELLED => return Err(ImportInterruption::Cancelled),
            JOB_COMMITTED => return Ok(()),
            _ => unreachable!("job terminal state must be valid"),
        }
        if Instant::now() >= deadline {
            return Err(ImportInterruption::DeadlineExceeded);
        }
        Ok(())
    }

    /// Commits success unless cancellation has already won the race.
    fn commit(&self, deadline: Instant) -> Result<(), ImportInterruption> {
        self.checkpoint(deadline)?;
        match self.terminal_state.compare_exchange(
            JOB_ACTIVE,
            JOB_COMMITTED,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => Ok(()),
            Err(JOB_CANCELLED) => Err(ImportInterruption::Cancelled),
            Err(JOB_COMMITTED) => unreachable!("job success must commit exactly once"),
            Err(_) => unreachable!("job terminal state must be valid"),
        }
    }
}

/// Reports failures that indicate an invalid JNI call or bridge state.
#[derive(Debug)]
enum BridgeError {
    Io(std::io::Error),
    Jni(JniError),
    InvalidArgument(&'static str),
    UnknownHandle(i64),
    HandleSpaceExhausted,
    JobAlreadyStarted,
    RegistryPoisoned,
}

impl Display for BridgeError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "platform operation failed: {error}"),
            Self::Jni(error) => write!(formatter, "jni operation failed: {error}"),
            Self::InvalidArgument(argument) => write!(formatter, "invalid {argument}"),
            Self::UnknownHandle(handle) => write!(formatter, "unknown import handle {handle}"),
            Self::HandleSpaceExhausted => formatter.write_str("import handle space exhausted"),
            Self::JobAlreadyStarted => formatter.write_str("import job already started"),
            Self::RegistryPoisoned => formatter.write_str("import registry poisoned"),
        }
    }
}

impl Error for BridgeError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::Jni(error) => Some(error),
            _ => None,
        }
    }
}

impl From<JniError> for BridgeError {
    fn from(error: JniError) -> Self {
        Self::Jni(error)
    }
}

impl From<std::io::Error> for BridgeError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

/// Stores one fixed result code and optional success statistics.
struct ImportOutcome {
    result_code: jint,
    input_bytes: u64,
    output_bytes: u64,
    source_flags: u32,
    sha256: [u8; SHA_256_BYTE_COUNT],
}

impl ImportOutcome {
    /// Creates an outcome without partial document statistics.
    const fn failure(result_code: jint) -> Self {
        Self {
            result_code,
            input_bytes: 0,
            output_bytes: 0,
            source_flags: 0,
            sha256: [0; SHA_256_BYTE_COUNT],
        }
    }

    /// Creates a successful outcome from core statistics.
    const fn success(summary: ImportSummary) -> Self {
        Self {
            result_code: RESULT_SUCCESS,
            input_bytes: summary.input_bytes,
            output_bytes: summary.output_bytes,
            source_flags: summary.source_flags.bits(),
            sha256: summary.sha256,
        }
    }
}

/// Creates an import job control and returns its native handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_importing_NativeImportWorker_createJob<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jlong {
    unowned_env
        .with_env(|_| -> Result<jlong, BridgeError> {
            let job = Arc::new(JobControl::new()?);
            lock_registry()?.insert(job)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Runs one descriptor-based import and returns a fixed result code.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_importing_NativeImportWorker_runJob<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    input_raw_fd: jint,
    output_raw_fd: jint,
    max_input_bytes: jlong,
    max_output_bytes: jlong,
    timeout_millis: jlong,
    result_values: JLongArray<'caller>,
    result_sha256: JByteArray<'caller>,
) -> jint {
    unowned_env
        .with_env(|env| -> Result<jint, BridgeError> {
            if result_values.len(env)? != RESULT_VALUE_COUNT {
                return Err(BridgeError::InvalidArgument("result array length"));
            }
            if result_sha256.len(env)? != SHA_256_BYTE_COUNT {
                return Err(BridgeError::InvalidArgument("result SHA-256 array length"));
            }
            let job = lock_registry()?.get(handle)?;
            job.start()?;
            let outcome = run_import(
                &job,
                input_raw_fd,
                output_raw_fd,
                max_input_bytes,
                max_output_bytes,
                timeout_millis,
            );
            let mut values = [0; RESULT_VALUE_COUNT];
            values[RESULT_INPUT_BYTES_INDEX] = jlong::try_from(outcome.input_bytes)
                .map_err(|_| BridgeError::InvalidArgument("input byte count"))?;
            values[RESULT_OUTPUT_BYTES_INDEX] = jlong::try_from(outcome.output_bytes)
                .map_err(|_| BridgeError::InvalidArgument("output byte count"))?;
            values[RESULT_SOURCE_FLAGS_INDEX] = jlong::from(outcome.source_flags);
            result_values.set_region(env, 0, &values)?;
            let sha256 = outcome.sha256.map(u8::cast_signed);
            result_sha256.set_region(env, 0, &sha256)?;
            Ok(outcome.result_code)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Requests idempotent cancellation of an import job.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_importing_NativeImportWorker_cancelJob<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_| -> Result<(), BridgeError> { lock_registry()?.get(handle)?.cancel() })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Destroys an import job handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_importing_NativeImportWorker_destroyJob<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    unowned_env
        .with_env(|_| -> Result<(), BridgeError> { lock_registry()?.remove(handle) })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Runs one validated import without propagating document failures through JNI.
fn run_import(
    job: &Arc<JobControl>,
    input_raw_fd: RawFd,
    output_raw_fd: RawFd,
    max_input_bytes: jlong,
    max_output_bytes: jlong,
    timeout_millis: jlong,
) -> ImportOutcome {
    let Ok(max_input_bytes) = positive_u64(max_input_bytes) else {
        return ImportOutcome::failure(RESULT_INPUT_LIMIT);
    };
    let Ok(max_output_bytes) = positive_u64(max_output_bytes) else {
        return ImportOutcome::failure(RESULT_OUTPUT_LIMIT);
    };
    let Ok(timeout_millis) = positive_u64(timeout_millis) else {
        return ImportOutcome::failure(RESULT_TIMEOUT);
    };
    if timeout_millis > MAX_TIMEOUT_MILLIS {
        return ImportOutcome::failure(RESULT_TIMEOUT);
    }
    let Ok(limits) = ImportLimits::new(max_input_bytes, max_output_bytes) else {
        return if max_input_bytes > beautyxt_import_core::HARD_MAX_INPUT_BYTES {
            ImportOutcome::failure(RESULT_INPUT_LIMIT)
        } else {
            ImportOutcome::failure(RESULT_OUTPUT_LIMIT)
        };
    };
    let Some(deadline) = Instant::now().checked_add(Duration::from_millis(timeout_millis)) else {
        return ImportOutcome::failure(RESULT_TIMEOUT);
    };

    let Ok((input, mut output)) = validated_files(input_raw_fd, output_raw_fd) else {
        return ImportOutcome::failure(RESULT_INVALID_DESCRIPTOR);
    };
    let mut reader = PollingReader::new(input, Arc::clone(job), deadline);
    let mut checkpoint = JobCheckpoint::new(Arc::clone(job), deadline);
    let copied = copy_raw_utf8(&mut reader, &mut output, limits, &mut checkpoint);
    match copied {
        Ok(summary) => finish_success(&mut output, summary, job, deadline),
        Err(error) => finish_failure(&mut output, &error),
    }
}

/// Duplicates and validates the untrusted descriptor capabilities.
fn validated_files(input_raw_fd: RawFd, output_raw_fd: RawFd) -> Result<(File, File), BridgeError> {
    let input = duplicate_file_descriptor(input_raw_fd)?;
    let mut output = duplicate_file_descriptor(output_raw_fd)?;
    let input_flags = descriptor_flags(&input)?;
    let output_flags = descriptor_flags(&output)?;
    if input_flags & libc::O_ACCMODE == libc::O_WRONLY
        || output_flags & libc::O_ACCMODE == libc::O_RDONLY
        || output_flags & libc::O_APPEND != 0
    {
        return Err(BridgeError::InvalidArgument("descriptor access mode"));
    }

    let input_metadata = input.metadata()?;
    let output_metadata = output.metadata()?;
    if !output_metadata.is_file() {
        return Err(BridgeError::InvalidArgument("output descriptor type"));
    }
    if output_metadata.nlink() != 0 {
        return Err(BridgeError::InvalidArgument("output descriptor link count"));
    }
    if input_metadata.is_file()
        && input_metadata.dev() == output_metadata.dev()
        && input_metadata.ino() == output_metadata.ino()
    {
        return Err(BridgeError::InvalidArgument("aliased descriptors"));
    }
    output.set_len(0)?;
    output.seek(SeekFrom::Start(0))?;
    Ok((input, output))
}

/// Duplicates a borrowed Android descriptor with close-on-exec semantics.
fn duplicate_file_descriptor(raw_fd: RawFd) -> Result<File, BridgeError> {
    if raw_fd < 0 {
        return Err(BridgeError::InvalidArgument("file descriptor"));
    }
    // SAFETY: the Java ParcelFileDescriptor owns this valid borrowed descriptor
    // throughout the synchronous JNI call that duplicates it.
    let borrowed = unsafe { BorrowedFd::borrow_raw(raw_fd) };
    Ok(File::from(borrowed.try_clone_to_owned()?))
}

/// Returns the status flags for one open descriptor.
fn descriptor_flags(file: &File) -> Result<jint, BridgeError> {
    // SAFETY: F_GETFL reads flags from the valid descriptor owned by `file`.
    let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
    if flags < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    Ok(flags)
}

/// Syncs a successful buffer unless cancellation or its deadline won the race.
fn finish_success(
    output: &mut File,
    summary: ImportSummary,
    job: &JobControl,
    deadline: Instant,
) -> ImportOutcome {
    if let Err(interruption) = job.checkpoint(deadline) {
        return rollback_interruption(output, interruption);
    }
    if output.sync_all().is_err() {
        rollback_output(output);
        return ImportOutcome::failure(RESULT_OUTPUT_IO);
    }
    match job.commit(deadline) {
        Ok(()) => ImportOutcome::success(summary),
        Err(interruption) => rollback_interruption(output, interruption),
    }
}

/// Truncates a failed buffer and maps the typed core error.
fn finish_failure(output: &mut File, error: &ImportError) -> ImportOutcome {
    rollback_output(output);
    ImportOutcome::failure(result_code_for_error(error))
}

/// Truncates a buffer and maps one external interruption.
fn rollback_interruption(output: &mut File, interruption: ImportInterruption) -> ImportOutcome {
    rollback_output(output);
    ImportOutcome::failure(match interruption {
        ImportInterruption::Cancelled => RESULT_CANCELLED,
        ImportInterruption::DeadlineExceeded => RESULT_TIMEOUT,
    })
}

/// Best-effort resets a failed anonymous output to an empty file.
fn rollback_output(output: &mut File) {
    let _ = output.set_len(0);
    let _ = output.seek(SeekFrom::Start(0));
}

/// Maps one typed core failure onto the stable Android result protocol.
const fn result_code_for_error(error: &ImportError) -> jint {
    match error {
        ImportError::InputLimitTooHigh { .. } | ImportError::InputTooLarge { .. } => {
            RESULT_INPUT_LIMIT
        }
        ImportError::OutputLimitTooHigh { .. } | ImportError::OutputTooLarge { .. } => {
            RESULT_OUTPUT_LIMIT
        }
        ImportError::UnsupportedBom(_) => RESULT_UNSUPPORTED_BOM,
        ImportError::InvalidUtf8 { .. } => RESULT_INVALID_UTF8,
        ImportError::Cancelled => RESULT_CANCELLED,
        ImportError::DeadlineExceeded => RESULT_TIMEOUT,
        ImportError::InputIo(_) => RESULT_INPUT_IO,
        ImportError::OutputIo(_) => RESULT_OUTPUT_IO,
        ImportError::InputByteCountOverflow | ImportError::OutputByteCountOverflow => {
            RESULT_INTERNAL
        }
    }
}

/// Converts a positive Java long into an unsigned bounded value.
fn positive_u64(value: jlong) -> Result<u64, ()> {
    let value = u64::try_from(value).map_err(|_| ())?;
    if value == 0 {
        return Err(());
    }
    Ok(value)
}

/// Returns exclusive access to the global registry.
fn lock_registry() -> Result<MutexGuard<'static, JobRegistry>, BridgeError> {
    JOBS.lock().map_err(|_| BridgeError::RegistryPoisoned)
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

    /// Waits until input becomes readable or the job must stop.
    fn wait_until_readable(&self) -> std::io::Result<()> {
        loop {
            if self.control.checkpoint(self.deadline).is_err() {
                return Err(std::io::Error::new(
                    ErrorKind::Interrupted,
                    "import interrupted",
                ));
            }
            let remaining = self.deadline.saturating_duration_since(Instant::now());
            let maximum_poll_millis =
                u128::try_from(jint::MAX).expect("maximum poll timeout should fit");
            let timeout_millis = jint::try_from(
                remaining
                    .as_millis()
                    .clamp(1, maximum_poll_millis.min(MAX_POLL_WAIT_MILLIS)),
            )
            .expect("clamped poll timeout should fit");
            let mut poll_fds = [
                libc::pollfd {
                    fd: self.input.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: self.control.cancellation_signal.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                },
            ];
            // SAFETY: `poll_fds` is a valid writable array for its declared
            // length, and both descriptors remain owned for the call.
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
            if result == 0 || poll_fds[1].revents != 0 {
                continue;
            }
            if poll_fds[0].revents & libc::POLLNVAL != 0 {
                return Err(std::io::Error::new(
                    ErrorKind::InvalidInput,
                    "input descriptor became invalid",
                ));
            }
            return Ok(());
        }
    }
}

impl Read for PollingReader {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        self.wait_until_readable()?;
        self.input.read(buffer)
    }
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

impl ImportControl for JobCheckpoint {
    fn checkpoint(&mut self) -> Result<(), ImportInterruption> {
        self.control.checkpoint(self.deadline)
    }
}

#[cfg(test)]
mod tests {
    use std::fs::{File, OpenOptions, remove_file};
    use std::io;
    use std::os::fd::AsRawFd;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Barrier};
    use std::thread;
    use std::time::{Duration, Instant};

    use beautyxt_import_core::{
        ImportError, ImportInterruption, ImportSummary, SHA_256_BYTE_COUNT, SourceFlags,
        UnsupportedBom,
    };

    use super::{
        BridgeError, ImportOutcome, JobControl, RESULT_CANCELLED, RESULT_INPUT_IO,
        RESULT_INPUT_LIMIT, RESULT_INTERNAL, RESULT_INVALID_UTF8, RESULT_OUTPUT_IO,
        RESULT_OUTPUT_LIMIT, RESULT_SUCCESS, RESULT_TIMEOUT, RESULT_UNSUPPORTED_BOM,
        result_code_for_error, validated_files,
    };

    const TEST_DEADLINE_OFFSET: Duration = Duration::from_mins(1);
    const SIMULTANEOUS_RACE_CASES: usize = 128;
    const TEST_SHA256: [u8; SHA_256_BYTE_COUNT] = [7; SHA_256_BYTE_COUNT];

    /// Verifies the bridge carries only successful source digest bytes.
    #[test]
    fn carries_only_successful_source_sha256() {
        let success = ImportOutcome::success(ImportSummary {
            input_bytes: 3,
            output_bytes: 3,
            source_flags: SourceFlags::default(),
            sha256: TEST_SHA256,
        });
        let failure = ImportOutcome::failure(RESULT_INVALID_UTF8);

        assert_eq!(success.result_code, RESULT_SUCCESS);
        assert_eq!(success.sha256, TEST_SHA256);
        assert_eq!(failure.sha256, [0; SHA_256_BYTE_COUNT]);
    }

    /// Verifies a path-backed output descriptor is rejected.
    #[test]
    fn rejects_linked_output_descriptor() {
        let output_path = test_output_path("linked");
        let output = create_linked_output(&output_path);
        let input = File::open("/dev/null").expect("test input should open");

        let result = validated_files(input.as_raw_fd(), output.as_raw_fd());
        drop(output);
        remove_file(&output_path).expect("linked test output should be removed");

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("output descriptor link count"))
        ));
    }

    /// Verifies an unlinked seekable output descriptor is accepted.
    #[test]
    fn accepts_unlinked_output_descriptor() {
        let output_path = test_output_path("unlinked");
        let output = create_linked_output(&output_path);
        remove_file(&output_path).expect("test output should become anonymous");
        let input = File::open("/dev/null").expect("test input should open");

        let result = validated_files(input.as_raw_fd(), output.as_raw_fd());

        assert!(result.is_ok(), "unlinked output descriptor should be valid");
    }

    /// Creates one deterministic linked output for descriptor validation.
    fn create_linked_output(path: &Path) -> File {
        match remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => panic!("stale test output should be removable: {error}"),
        }
        OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(path)
            .expect("linked test output should be created")
    }

    /// Returns one process-unique deterministic test output path.
    fn test_output_path(case_name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "beautyxt-import-jni-{case_name}-{}.tmp",
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
            Err(ImportInterruption::Cancelled)
        );
    }

    /// Verifies cancellation becomes a no-op after the success commit.
    #[test]
    fn cancellation_after_commit_is_a_no_op() {
        let job = JobControl::new().expect("test job control should be created");
        job.start().expect("test job should start once");
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
                Err(ImportInterruption::Cancelled) => {
                    assert_eq!(checkpoint, Err(ImportInterruption::Cancelled));
                }
                Err(ImportInterruption::DeadlineExceeded) => {
                    panic!("test commit deadline should not expire");
                }
            }
        }
    }

    /// Verifies every core failure maps onto a stable platform result code.
    #[test]
    fn maps_core_errors_to_stable_results() {
        let cases = [
            (ImportError::Cancelled, RESULT_CANCELLED),
            (ImportError::DeadlineExceeded, RESULT_TIMEOUT),
            (
                ImportError::InvalidUtf8 { byte_offset: 0 },
                RESULT_INVALID_UTF8,
            ),
            (
                ImportError::UnsupportedBom(UnsupportedBom::Utf16LittleEndian),
                RESULT_UNSUPPORTED_BOM,
            ),
            (
                ImportError::InputTooLarge {
                    limit: 1,
                    observed: 2,
                },
                RESULT_INPUT_LIMIT,
            ),
            (
                ImportError::OutputTooLarge {
                    limit: 1,
                    observed: 2,
                },
                RESULT_OUTPUT_LIMIT,
            ),
            (
                ImportError::InputIo(io::Error::other("test input")),
                RESULT_INPUT_IO,
            ),
            (
                ImportError::OutputIo(io::Error::other("test output")),
                RESULT_OUTPUT_IO,
            ),
            (ImportError::InputByteCountOverflow, RESULT_INTERNAL),
        ];

        for (error, expected) in cases {
            assert_eq!(result_code_for_error(&error), expected);
        }
    }
}
