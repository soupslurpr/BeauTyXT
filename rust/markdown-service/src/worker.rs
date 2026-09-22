//! Bridges isolated Android Markdown jobs to the bounded Rust renderer.

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs::File;
use std::io::{ErrorKind, Read, Seek, SeekFrom, Write};
use std::os::fd::{AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::MetadataExt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::time::{Duration, Instant};

use beautyxt_markdown_core::{
    DOCUMENT_FLAG_RAW_HTML, MAX_INPUT_BYTES, MAX_PACKET_BYTES, RenderControl, RenderError,
    RenderInterruption, render_markdown_with_control,
};
const JOB_ACTIVE: u8 = 0;
const JOB_CANCELLED: u8 = 1;
const JOB_COMMITTED: u8 = 2;
const MAX_TIMEOUT_MILLIS: u64 = 5 * 60 * 1000;
const MAX_POLL_WAIT_MILLIS: u128 = 100;
const STREAM_BUFFER_BYTES: usize = 64 * 1024;

pub(crate) const RESULT_SUCCESS: i32 = 0;
pub(crate) const RESULT_CANCELLED: i32 = 1;
pub(crate) const RESULT_TIMEOUT: i32 = 2;
pub(crate) const RESULT_INPUT_LIMIT: i32 = 3;
pub(crate) const RESULT_INPUT_LENGTH_MISMATCH: i32 = 4;
pub(crate) const RESULT_INVALID_UTF8: i32 = 5;
pub(crate) const RESULT_RENDER_LIMIT: i32 = 6;
pub(crate) const RESULT_INVALID_DESCRIPTOR: i32 = 7;
pub(crate) const RESULT_INPUT_IO: i32 = 8;
pub(crate) const RESULT_OUTPUT_IO: i32 = 9;
pub(crate) const RESULT_INTERNAL: i32 = 10;

pub(crate) struct JobControl {
    started: AtomicBool,
    terminal_state: AtomicU8,
    cancellation_signal: OwnedFd,
}

impl JobControl {
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

    pub(crate) fn start(&self) -> Result<(), BridgeError> {
        self.started
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
            .map_err(|_| BridgeError::JobAlreadyStarted)
    }

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
            // SAFETY: the eventfd remains owned and the fixed byte slice is valid.
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

    fn checkpoint(&self, deadline: Instant) -> Result<(), RenderInterruption> {
        match self.terminal_state.load(Ordering::Acquire) {
            JOB_ACTIVE => {}
            JOB_CANCELLED => return Err(RenderInterruption::Cancelled),
            JOB_COMMITTED => return Ok(()),
            _ => unreachable!("job terminal state must be valid"),
        }
        if Instant::now() >= deadline {
            return Err(RenderInterruption::DeadlineExceeded);
        }
        Ok(())
    }

    fn commit(&self, deadline: Instant) -> Result<(), RenderInterruption> {
        self.checkpoint(deadline)?;
        match self.terminal_state.compare_exchange(
            JOB_ACTIVE,
            JOB_COMMITTED,
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => Ok(()),
            Err(JOB_CANCELLED) => Err(RenderInterruption::Cancelled),
            Err(JOB_COMMITTED) => unreachable!("job success must commit exactly once"),
            Err(_) => unreachable!("job terminal state must be valid"),
        }
    }
}

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

            Self::JobAlreadyStarted => formatter.write_str("markdown job already started"),
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

pub(crate) struct RenderOutcome {
    pub(crate) result_code: i32,
    pub(crate) input_bytes: u64,
    pub(crate) packet_bytes: u64,
    pub(crate) block_count: u32,
    pub(crate) span_count: u32,
    pub(crate) flags: u32,
}

impl RenderOutcome {
    pub(crate) const fn failure(result_code: i32) -> Self {
        Self {
            result_code,
            input_bytes: 0,
            packet_bytes: 0,
            block_count: 0,
            span_count: 0,
            flags: 0,
        }
    }
}
/// Runs one validated descriptor render to its terminal outcome.
pub(crate) fn run_render(
    job: &Arc<JobControl>,
    input_raw_fd: RawFd,
    output_raw_fd: RawFd,
    expected_input_bytes: i64,
    max_packet_bytes: i64,
    timeout_millis: i64,
) -> RenderOutcome {
    let Ok(expected_input_bytes) = nonnegative_usize(expected_input_bytes) else {
        return RenderOutcome::failure(RESULT_INPUT_LIMIT);
    };
    let Ok(max_packet_bytes) = positive_usize(max_packet_bytes) else {
        return RenderOutcome::failure(RESULT_RENDER_LIMIT);
    };
    let Ok(timeout_millis) = positive_u64(timeout_millis) else {
        return RenderOutcome::failure(RESULT_TIMEOUT);
    };
    if expected_input_bytes > MAX_INPUT_BYTES
        || max_packet_bytes > MAX_PACKET_BYTES
        || timeout_millis > MAX_TIMEOUT_MILLIS
    {
        return RenderOutcome::failure(if expected_input_bytes > MAX_INPUT_BYTES {
            RESULT_INPUT_LIMIT
        } else if max_packet_bytes > MAX_PACKET_BYTES {
            RESULT_RENDER_LIMIT
        } else {
            RESULT_TIMEOUT
        });
    }
    let Some(deadline) = Instant::now().checked_add(Duration::from_millis(timeout_millis)) else {
        return RenderOutcome::failure(RESULT_TIMEOUT);
    };
    let Ok((input, mut output)) = validated_files(input_raw_fd, output_raw_fd) else {
        return RenderOutcome::failure(RESULT_INVALID_DESCRIPTOR);
    };
    let mut reader = PollingReader::new(input, Arc::clone(job), deadline);
    let mut control = JobCheckpoint::new(Arc::clone(job), deadline);
    let input_bytes = match read_exact_input(&mut reader, expected_input_bytes, &mut control) {
        Ok(bytes) => bytes,
        Err(result_code) => {
            rollback_output(&mut output);
            return RenderOutcome::failure(result_code);
        }
    };
    let Ok(markdown) = String::from_utf8(input_bytes) else {
        rollback_output(&mut output);
        return RenderOutcome::failure(RESULT_INVALID_UTF8);
    };
    let packet = match render_markdown_with_control(&markdown, &mut control) {
        Ok(packet) => packet,
        Err(error) => {
            rollback_output(&mut output);
            return RenderOutcome::failure(result_code_for_render_error(error));
        }
    };
    if packet.as_bytes().len() > max_packet_bytes {
        rollback_output(&mut output);
        return RenderOutcome::failure(RESULT_RENDER_LIMIT);
    }
    if let Err(result_code) = write_packet(&mut output, packet.as_bytes(), &mut control) {
        rollback_output(&mut output);
        return RenderOutcome::failure(result_code);
    }
    if output.sync_all().is_err() {
        rollback_output(&mut output);
        return RenderOutcome::failure(RESULT_OUTPUT_IO);
    }
    if let Err(interruption) = job.commit(deadline) {
        rollback_output(&mut output);
        return RenderOutcome::failure(result_code_for_interruption(interruption));
    }
    RenderOutcome {
        result_code: RESULT_SUCCESS,
        input_bytes: u64::try_from(markdown.len()).expect("bounded input length should fit u64"),
        packet_bytes: u64::try_from(packet.as_bytes().len())
            .expect("bounded packet length should fit u64"),
        block_count: packet.block_count(),
        span_count: packet.span_count(),
        flags: u32::from(packet.contains_raw_html()) * DOCUMENT_FLAG_RAW_HTML,
    }
}

/// Reads exactly the declared snapshot length and rejects trailing bytes.
fn read_exact_input(
    input: &mut impl Read,
    expected_bytes: usize,
    control: &mut impl RenderControl,
) -> Result<Vec<u8>, i32> {
    let mut bytes = Vec::with_capacity(expected_bytes);
    let mut buffer = vec![0_u8; STREAM_BUFFER_BYTES].into_boxed_slice();
    while bytes.len() < expected_bytes {
        control.checkpoint().map_err(result_code_for_interruption)?;
        let remaining = expected_bytes - bytes.len();
        let requested = remaining.min(buffer.len());
        let bytes_read = input
            .read(&mut buffer[..requested])
            .map_err(|error| interruption_or_io(control, &error, RESULT_INPUT_IO))?;
        if bytes_read == 0 {
            return Err(RESULT_INPUT_LENGTH_MISMATCH);
        }
        bytes.extend_from_slice(&buffer[..bytes_read]);
    }
    control.checkpoint().map_err(result_code_for_interruption)?;
    match input.read(&mut buffer[..1]) {
        Ok(0) => Ok(bytes),
        Ok(_) => Err(RESULT_INPUT_LENGTH_MISMATCH),
        Err(error) => Err(interruption_or_io(control, &error, RESULT_INPUT_IO)),
    }
}

/// Writes the complete packet with cooperative interruption checkpoints.
fn write_packet(
    output: &mut impl Write,
    packet: &[u8],
    control: &mut impl RenderControl,
) -> Result<(), i32> {
    for chunk in packet.chunks(STREAM_BUFFER_BYTES) {
        control.checkpoint().map_err(result_code_for_interruption)?;
        output
            .write_all(chunk)
            .map_err(|error| interruption_or_io(control, &error, RESULT_OUTPUT_IO))?;
    }
    control.checkpoint().map_err(result_code_for_interruption)
}

/// Maps an interrupted I/O operation to the most specific result code.
fn interruption_or_io(
    control: &mut impl RenderControl,
    error: &std::io::Error,
    io_result: i32,
) -> i32 {
    if error.kind() == ErrorKind::Interrupted {
        control
            .checkpoint()
            .err()
            .map_or(io_result, result_code_for_interruption)
    } else {
        io_result
    }
}

/// Maps a core render error to the stable service result protocol.
const fn result_code_for_render_error(error: RenderError) -> i32 {
    match error {
        RenderError::Cancelled => RESULT_CANCELLED,
        RenderError::DeadlineExceeded => RESULT_TIMEOUT,
        RenderError::InputLimit => RESULT_INPUT_LIMIT,
        RenderError::EventLimit
        | RenderError::NestingLimit
        | RenderError::BlockLimit
        | RenderError::SpanLimit
        | RenderError::SourceMapLimit
        | RenderError::PacketLimit => RESULT_RENDER_LIMIT,
        RenderError::ArithmeticOverflow | RenderError::State => RESULT_INTERNAL,
    }
}

/// Maps a cooperative interruption to the stable service result protocol.
const fn result_code_for_interruption(interruption: RenderInterruption) -> i32 {
    match interruption {
        RenderInterruption::Cancelled => RESULT_CANCELLED,
        RenderInterruption::DeadlineExceeded => RESULT_TIMEOUT,
    }
}

/// Duplicates and validates the input and anonymous output descriptors.
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
    if !output_metadata.is_file() || output_metadata.nlink() != 0 {
        return Err(BridgeError::InvalidArgument("output descriptor type"));
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

/// Duplicates one service-owned descriptor into a Rust-owned file.
fn duplicate_file_descriptor(raw_fd: RawFd) -> Result<File, BridgeError> {
    if raw_fd < 0 {
        return Err(BridgeError::InvalidArgument("file descriptor"));
    }
    // SAFETY: the service retains this borrowed descriptor until the worker returns.
    let borrowed = unsafe { BorrowedFd::borrow_raw(raw_fd) };
    Ok(File::from(borrowed.try_clone_to_owned()?))
}

/// Reads the access flags for one open file description.
fn descriptor_flags(file: &File) -> Result<i32, BridgeError> {
    // SAFETY: F_GETFL reads flags from the valid descriptor owned by `file`.
    let flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
    if flags < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    Ok(flags)
}

/// Clears a partial packet after any failed or cancelled render.
fn rollback_output(output: &mut File) {
    let _ = output.set_len(0);
    let _ = output.seek(SeekFrom::Start(0));
}

/// Converts a nonnegative AIDL long to a platform size.
fn nonnegative_usize(value: i64) -> Result<usize, ()> {
    usize::try_from(value).map_err(|_| ())
}

/// Converts a positive AIDL long to a platform size.
fn positive_usize(value: i64) -> Result<usize, ()> {
    let value = nonnegative_usize(value)?;
    if value == 0 {
        return Err(());
    }
    Ok(value)
}

/// Converts a positive AIDL long to an unsigned timeout.
fn positive_u64(value: i64) -> Result<u64, ()> {
    let value = u64::try_from(value).map_err(|_| ())?;
    if value == 0 {
        return Err(());
    }
    Ok(value)
}

struct PollingReader {
    input: File,
    control: Arc<JobControl>,
    deadline: Instant,
}

impl PollingReader {
    const fn new(input: File, control: Arc<JobControl>, deadline: Instant) -> Self {
        Self {
            input,
            control,
            deadline,
        }
    }

    fn wait_until_readable(&self) -> std::io::Result<()> {
        loop {
            if self.control.checkpoint(self.deadline).is_err() {
                return Err(std::io::Error::new(
                    ErrorKind::Interrupted,
                    "markdown input interrupted",
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
            // SAFETY: the writable array and both owned descriptors remain valid.
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
                    "markdown input descriptor became invalid",
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

struct JobCheckpoint {
    control: Arc<JobControl>,
    deadline: Instant,
}

impl JobCheckpoint {
    const fn new(control: Arc<JobControl>, deadline: Instant) -> Self {
        Self { control, deadline }
    }
}

impl RenderControl for JobCheckpoint {
    fn checkpoint(&mut self) -> Result<(), RenderInterruption> {
        self.control.checkpoint(self.deadline)
    }
}

#[cfg(test)]
mod tests {
    //! Verifies descriptor validation, exact rendering, and cancellation races.

    use std::fs::{File, OpenOptions, read, remove_file};
    use std::io::{self, Seek, SeekFrom, Write};
    use std::os::fd::AsRawFd;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Barrier};
    use std::thread;
    use std::time::{Duration, Instant};

    use beautyxt_markdown_core::{PACKET_MAGIC, RenderError, RenderInterruption};

    use super::{
        BridgeError, JobControl, MAX_PACKET_BYTES, RESULT_CANCELLED, RESULT_INPUT_LENGTH_MISMATCH,
        RESULT_INTERNAL, RESULT_RENDER_LIMIT, RESULT_SUCCESS, RESULT_TIMEOUT,
        result_code_for_render_error, run_render, validated_files,
    };

    const TEST_DEADLINE_OFFSET: Duration = Duration::from_mins(1);
    const TEST_TIMEOUT_MILLIS: i64 = 60_000;
    const SIMULTANEOUS_RACE_CASES: usize = 128;

    #[test]
    fn renders_exact_markdown_into_an_anonymous_packet() {
        let input_path = test_path("render-input");
        let output_path = test_path("render-output");
        let markdown = b"# Heading\n\n<script>literal</script>\n";
        let input = create_file(&input_path, markdown);
        let output = create_anonymous_output(&output_path);
        let job = Arc::new(JobControl::new().expect("test job should be created"));
        job.start().expect("test job should start once");

        let outcome = run_render(
            &job,
            input.as_raw_fd(),
            output.as_raw_fd(),
            i64::try_from(markdown.len()).expect("input length should fit"),
            i64::try_from(MAX_PACKET_BYTES).expect("packet limit should fit"),
            TEST_TIMEOUT_MILLIS,
        );
        let packet = read_file(&output);

        assert_eq!(outcome.result_code, RESULT_SUCCESS);
        assert_eq!(outcome.input_bytes, markdown.len() as u64);
        assert_eq!(packet.len() as u64, outcome.packet_bytes);
        assert_eq!(&packet[..PACKET_MAGIC.len()], &PACKET_MAGIC);
        drop(input);
        remove_file(input_path).expect("test input should be removed");
    }

    #[test]
    fn rejects_snapshot_length_mismatch_without_output() {
        let input_path = test_path("short-input");
        let output_path = test_path("short-output");
        let input = create_file(&input_path, b"short");
        let output = create_anonymous_output(&output_path);
        let job = Arc::new(JobControl::new().expect("test job should be created"));
        job.start().expect("test job should start once");

        let outcome = run_render(
            &job,
            input.as_raw_fd(),
            output.as_raw_fd(),
            6,
            i64::try_from(MAX_PACKET_BYTES).expect("packet limit should fit"),
            TEST_TIMEOUT_MILLIS,
        );

        assert_eq!(outcome.result_code, RESULT_INPUT_LENGTH_MISMATCH);
        assert!(read_file(&output).is_empty());
        drop(input);
        remove_file(input_path).expect("test input should be removed");
    }

    #[test]
    fn rejects_a_linked_output_descriptor() {
        let input = File::open("/dev/null").expect("test input should open");
        let output_path = test_path("linked-output");
        let output = create_file(&output_path, b"");

        let result = validated_files(input.as_raw_fd(), output.as_raw_fd());

        assert!(matches!(
            result,
            Err(BridgeError::InvalidArgument("output descriptor type"))
        ));
        drop(output);
        remove_file(output_path).expect("linked output should be removed");
    }

    #[test]
    fn cancellation_before_commit_wins() {
        let job = JobControl::new().expect("test job should be created");
        job.start().expect("test job should start once");
        job.cancel().expect("test cancellation should succeed");

        assert_eq!(
            job.commit(Instant::now() + TEST_DEADLINE_OFFSET),
            Err(RenderInterruption::Cancelled)
        );
    }

    #[test]
    fn simultaneous_commit_and_cancellation_remain_consistent() {
        for _case_index in 0..SIMULTANEOUS_RACE_CASES {
            let job = Arc::new(JobControl::new().expect("test job should be created"));
            job.start().expect("test job should start once");
            let barrier = Arc::new(Barrier::new(3));
            let cancelling_job = Arc::clone(&job);
            let cancelling_barrier = Arc::clone(&barrier);
            let cancellation = thread::spawn(move || {
                cancelling_barrier.wait();
                cancelling_job.cancel()
            });
            let committing_job = Arc::clone(&job);
            let committing_barrier = Arc::clone(&barrier);
            let commit = thread::spawn(move || {
                committing_barrier.wait();
                committing_job.commit(Instant::now() + TEST_DEADLINE_OFFSET)
            });

            barrier.wait();
            cancellation
                .join()
                .expect("cancellation thread should finish")
                .expect("test cancellation should succeed");
            let commit_result = commit.join().expect("commit thread should finish");
            let checkpoint = job.checkpoint(Instant::now() + TEST_DEADLINE_OFFSET);
            match commit_result {
                Ok(()) => assert_eq!(checkpoint, Ok(())),
                Err(RenderInterruption::Cancelled) => {
                    assert_eq!(checkpoint, Err(RenderInterruption::Cancelled));
                }
                Err(RenderInterruption::DeadlineExceeded) => {
                    panic!("test deadline should not expire");
                }
            }
        }
    }

    #[test]
    fn maps_core_errors_to_stable_result_codes() {
        let cases = [
            (RenderError::Cancelled, RESULT_CANCELLED),
            (RenderError::DeadlineExceeded, RESULT_TIMEOUT),
            (RenderError::InputLimit, super::RESULT_INPUT_LIMIT),
            (RenderError::BlockLimit, RESULT_RENDER_LIMIT),
            (RenderError::State, RESULT_INTERNAL),
        ];

        for (error, expected) in cases {
            assert_eq!(result_code_for_render_error(error), expected);
        }
    }

    fn create_file(path: &Path, bytes: &[u8]) -> File {
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
            .expect("test file should be created");
        file.write_all(bytes).expect("test bytes should write");
        file.seek(SeekFrom::Start(0))
            .expect("test input should rewind");
        file
    }

    fn create_anonymous_output(path: &Path) -> File {
        let file = create_file(path, b"");
        remove_file(path).expect("test output should become anonymous");
        file
    }

    fn read_file(file: &File) -> Vec<u8> {
        let path = format!("/proc/self/fd/{}", file.as_raw_fd());
        read(path).expect("anonymous output should remain readable")
    }

    fn test_path(case_name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "beautyxt-markdown-service-{case_name}-{}.tmp",
            std::process::id()
        ))
    }
}
