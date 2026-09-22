//! Validates and copies bounded imports using owned native capabilities.

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs::File;
use std::io::{ErrorKind, Read, Seek, SeekFrom};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::MetadataExt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::time::{Duration, Instant};

use beautyxt_import_core::{
    ImportControl, ImportError, ImportInterruption, ImportLimits, ImportSummary,
    SHA_256_BYTE_COUNT, copy_raw_utf8,
};
const JOB_ACTIVE: u8 = 0;
const JOB_CANCELLED: u8 = 1;
const JOB_COMMITTED: u8 = 2;
const MAX_TIMEOUT_MILLIS: u64 = 15 * 60 * 1000;
const MAX_POLL_WAIT_MILLIS: u128 = 100;

pub(crate) const RESULT_SUCCESS: i32 = 0;
pub(crate) const RESULT_CANCELLED: i32 = 1;
pub(crate) const RESULT_INVALID_UTF8: i32 = 2;
pub(crate) const RESULT_UNSUPPORTED_BOM: i32 = 3;
pub(crate) const RESULT_INPUT_LIMIT: i32 = 4;
pub(crate) const RESULT_OUTPUT_LIMIT: i32 = 5;
pub(crate) const RESULT_TIMEOUT: i32 = 6;
pub(crate) const RESULT_INVALID_DESCRIPTOR: i32 = 7;
pub(crate) const RESULT_INPUT_IO: i32 = 8;
pub(crate) const RESULT_OUTPUT_IO: i32 = 9;
pub(crate) const RESULT_INTERNAL: i32 = 10;

/// Coordinates cancellation and single execution for one import.
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

    /// Cancels an uncommitted job and wakes a blocked descriptor poll.
    pub(crate) fn cancel(&self) -> Result<(), BridgeError> {
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
    pub(crate) fn start(&self) -> Result<(), BridgeError> {
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
            Self::JobAlreadyStarted => formatter.write_str("import job already started"),
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

/// Stores one fixed result code and optional success statistics.
pub(crate) struct ImportOutcome {
    pub(crate) result_code: i32,
    pub(crate) input_bytes: u64,
    pub(crate) output_bytes: u64,
    pub(crate) source_flags: u32,
    pub(crate) sha256: [u8; SHA_256_BYTE_COUNT],
}

impl ImportOutcome {
    /// Creates an outcome without partial document statistics.
    pub(crate) const fn failure(result_code: i32) -> Self {
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

/// Runs one import and validates provider completion before committing success.
pub(crate) fn run_import(
    job: &Arc<JobControl>,
    input_raw_fd: RawFd,
    output_raw_fd: RawFd,
    max_input_bytes: i64,
    max_output_bytes: i64,
    timeout_millis: i64,
    input_has_error: impl FnOnce() -> bool,
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
        Ok(_) if input_has_error() => {
            rollback_output(&mut output);
            ImportOutcome::failure(RESULT_INPUT_IO)
        }
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
    // SAFETY: the caller retains its owned descriptor throughout the import.
    // Duplication gives this worker an independently owned descriptor.
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
const fn result_code_for_error(error: &ImportError) -> i32 {
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

/// Converts a positive protocol value into an unsigned limit.
fn positive_u64(value: i64) -> Result<u64, ()> {
    let value = u64::try_from(value).map_err(|_| ())?;
    if value == 0 {
        return Err(());
    }
    Ok(value)
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
                u128::try_from(i32::MAX).expect("maximum poll timeout should fit");
            let timeout_millis = i32::try_from(
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
    use std::io::{self, Read, Seek, SeekFrom, Write};
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
        result_code_for_error, run_import, validated_files,
    };

    const TEST_DEADLINE_OFFSET: Duration = Duration::from_mins(1);
    const SIMULTANEOUS_RACE_CASES: usize = 128;
    const TEST_SHA256: [u8; SHA_256_BYTE_COUNT] = [7; SHA_256_BYTE_COUNT];

    /// Exercises the provider check between copying bytes and committing success.
    #[test]
    fn provider_failure_and_cancellation_discard_complete_output() {
        for case in ["success", "provider-error", "cancel-during-provider-check"] {
            let mut input = anonymous_file(&format!("{case}-input"));
            let mut output = anonymous_file(&format!("{case}-output"));
            let bytes = b"\xef\xbb\xbfhello\r\nworld\r";
            input.write_all(bytes).unwrap();
            input.rewind().unwrap();
            let job = Arc::new(JobControl::new().unwrap());
            job.start().unwrap();
            let outcome = run_import(
                &job,
                input.as_raw_fd(),
                output.as_raw_fd(),
                1024,
                1024,
                1000,
                || {
                    if case == "cancel-during-provider-check" {
                        job.cancel().unwrap();
                    }
                    case == "provider-error"
                },
            );
            output.seek(SeekFrom::Start(0)).unwrap();
            let mut actual = Vec::new();
            output.read_to_end(&mut actual).unwrap();
            if case == "success" {
                assert_eq!(outcome.result_code, RESULT_SUCCESS);
                assert_eq!(actual, bytes);
                assert_eq!(outcome.input_bytes, bytes.len() as u64);
                assert_eq!(outcome.output_bytes, bytes.len() as u64);
                assert_ne!(outcome.sha256, [0; SHA_256_BYTE_COUNT]);
                assert_ne!(outcome.source_flags, 0);
            } else {
                assert_eq!(
                    outcome.result_code,
                    if case == "provider-error" {
                        RESULT_INPUT_IO
                    } else {
                        RESULT_CANCELLED
                    }
                );
                assert!(actual.is_empty());
                assert_eq!(outcome.input_bytes, 0);
                assert_eq!(outcome.output_bytes, 0);
                assert_eq!(outcome.source_flags, 0);
                assert_eq!(outcome.sha256, [0; SHA_256_BYTE_COUNT]);
            }
        }
    }

    fn anonymous_file(name: &str) -> File {
        let path = test_output_path(name);
        let file = create_linked_output(&path);
        remove_file(path).unwrap();
        file
    }

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
            "beautyxt-import-service-{case_name}-{}.tmp",
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
