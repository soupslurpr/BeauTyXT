//! Provides bounded byte-for-byte snapshot export for `BeauTyXT`.

#![forbid(unsafe_code)]

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::io::{ErrorKind, Read, Write};

/// Fixes the streaming buffer at 64 KiB.
pub const STREAM_BUFFER_BYTES: usize = 64 * 1024;

/// Limits one exported snapshot to 256 MiB.
pub const HARD_MAX_SNAPSHOT_BYTES: u64 = 256 * 1024 * 1024;

/// Configures the caller-selected bound for one export.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ExportLimits {
    max_snapshot_bytes: u64,
}

impl ExportLimits {
    /// Creates a snapshot bound within the service-wide hard maximum.
    ///
    /// # Errors
    ///
    /// Returns [`ExportError::LimitTooHigh`] when the requested limit exceeds
    /// [`HARD_MAX_SNAPSHOT_BYTES`].
    pub fn new(max_snapshot_bytes: u64) -> Result<Self, ExportError> {
        if max_snapshot_bytes > HARD_MAX_SNAPSHOT_BYTES {
            return Err(ExportError::LimitTooHigh {
                requested: max_snapshot_bytes,
                hard_maximum: HARD_MAX_SNAPSHOT_BYTES,
            });
        }
        Ok(Self { max_snapshot_bytes })
    }

    /// Returns the maximum number of snapshot bytes.
    #[must_use]
    pub const fn max_snapshot_bytes(self) -> u64 {
        self.max_snapshot_bytes
    }
}

/// Identifies an external request to stop exporting.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ExportInterruption {
    /// Indicates that the caller cancelled the job.
    Cancelled,

    /// Indicates that the caller's monotonic deadline elapsed.
    DeadlineExceeded,
}

/// Supplies cancellation and deadline decisions without platform dependencies.
pub trait ExportControl {
    /// Checks whether the current export may continue.
    ///
    /// # Errors
    ///
    /// Returns an [`ExportInterruption`] when the operation must stop.
    fn checkpoint(&mut self) -> Result<(), ExportInterruption>;
}

impl<Checkpoint> ExportControl for Checkpoint
where
    Checkpoint: FnMut() -> Result<(), ExportInterruption>,
{
    fn checkpoint(&mut self) -> Result<(), ExportInterruption> {
        self()
    }
}

/// Summarizes one successfully exported snapshot.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ExportSummary {
    /// Stores the number of bytes read from the snapshot.
    pub input_bytes: u64,

    /// Stores the number of bytes written to the destination.
    pub output_bytes: u64,
}

/// Reports a bounded export failure without exposing document content.
#[derive(Debug)]
pub enum ExportError {
    /// Reports a requested snapshot limit above the hard maximum.
    LimitTooHigh {
        /// Stores the rejected requested limit.
        requested: u64,

        /// Stores the enforced hard maximum.
        hard_maximum: u64,
    },

    /// Reports a snapshot that exceeds its declared byte limit.
    SnapshotTooLarge {
        /// Stores the enforced job limit.
        limit: u64,

        /// Stores the first observed byte count above the limit.
        observed: u64,
    },

    /// Reports a cancelled job.
    Cancelled,

    /// Reports an elapsed export deadline.
    DeadlineExceeded,

    /// Reports an overflowing input byte counter.
    InputByteCountOverflow,

    /// Reports an overflowing output byte counter.
    OutputByteCountOverflow,

    /// Reports a streaming input failure.
    InputIo(std::io::Error),

    /// Reports a streaming output failure.
    OutputIo(std::io::Error),
}

impl Display for ExportError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::LimitTooHigh {
                requested,
                hard_maximum,
            } => write!(
                formatter,
                "snapshot limit {requested} exceeds hard maximum {hard_maximum}"
            ),
            Self::SnapshotTooLarge { limit, observed } => write!(
                formatter,
                "snapshot byte count {observed} exceeds limit {limit}"
            ),
            Self::Cancelled => formatter.write_str("export cancelled"),
            Self::DeadlineExceeded => formatter.write_str("export deadline exceeded"),
            Self::InputByteCountOverflow => formatter.write_str("input byte count overflow"),
            Self::OutputByteCountOverflow => formatter.write_str("output byte count overflow"),
            Self::InputIo(error) => write!(formatter, "input failed: {error}"),
            Self::OutputIo(error) => write!(formatter, "output failed: {error}"),
        }
    }
}

impl Error for ExportError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::InputIo(error) | Self::OutputIo(error) => Some(error),
            _ => None,
        }
    }
}

/// Copies one serialized UTF-8 snapshot to a destination byte-for-byte.
///
/// The trusted input may retain a UTF-8 byte order mark and any mixture of LF,
/// CRLF, and bare CR line endings. It is copied without parsing, normalization,
/// or document-sized allocation. The control hook runs before blocking I/O and
/// between bounded pieces of work. Platform descriptor owners remain
/// responsible for making blocked I/O interruptible.
///
/// # Errors
///
/// Returns [`ExportError`] when the snapshot exceeds its limit, control stops
/// the operation, a byte count overflows, or streaming I/O fails. The caller
/// owns destination rollback or replacement atomicity after any error.
pub fn export_utf8_snapshot_bytes<Reader, Writer, Control>(
    reader: &mut Reader,
    writer: &mut Writer,
    limits: ExportLimits,
    control: &mut Control,
) -> Result<ExportSummary, ExportError>
where
    Reader: Read,
    Writer: Write,
    Control: ExportControl,
{
    let mut input_bytes = 0_u64;
    let mut output_bytes = 0_u64;
    let mut buffer = vec![0_u8; STREAM_BUFFER_BYTES].into_boxed_slice();

    loop {
        let bytes_read = read_input(
            reader,
            &mut buffer,
            input_bytes,
            limits.max_snapshot_bytes(),
            control,
        )?;
        if bytes_read == 0 {
            break;
        }
        input_bytes = checked_input_count(input_bytes, bytes_read)?;
        write_output(writer, &buffer[..bytes_read], &mut output_bytes, control)?;
    }

    flush_output(writer, control)?;
    debug_assert_eq!(input_bytes, output_bytes);
    Ok(ExportSummary {
        input_bytes,
        output_bytes,
    })
}

/// Reads one bounded source chunk without crossing more than one byte past its limit.
fn read_input<Reader, Control>(
    reader: &mut Reader,
    buffer: &mut [u8],
    input_bytes: u64,
    limit: u64,
    control: &mut Control,
) -> Result<usize, ExportError>
where
    Reader: Read,
    Control: ExportControl,
{
    debug_assert!(!buffer.is_empty());
    debug_assert!(input_bytes <= limit);
    let remaining_bytes = limit - input_bytes;
    let probe_bytes = remaining_bytes
        .checked_add(1)
        .ok_or(ExportError::InputByteCountOverflow)?;
    let read_capacity = usize::try_from(probe_bytes)
        .unwrap_or(usize::MAX)
        .min(buffer.len());

    loop {
        checkpoint(control)?;
        match reader.read(&mut buffer[..read_capacity]) {
            Ok(bytes_read) => {
                if bytes_read > read_capacity {
                    return Err(ExportError::InputIo(std::io::Error::new(
                        ErrorKind::InvalidData,
                        "reader returned an invalid byte count",
                    )));
                }
                let observed = checked_input_count(input_bytes, bytes_read)?;
                if observed > limit {
                    return Err(ExportError::SnapshotTooLarge { limit, observed });
                }
                checkpoint(control)?;
                return Ok(bytes_read);
            }
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(ExportError::InputIo(error)),
        }
    }
}

/// Writes one complete source chunk through cancellable short writes.
fn write_output<Writer, Control>(
    writer: &mut Writer,
    bytes: &[u8],
    output_bytes: &mut u64,
    control: &mut Control,
) -> Result<(), ExportError>
where
    Writer: Write,
    Control: ExportControl,
{
    let mut offset = 0;
    while offset < bytes.len() {
        checkpoint(control)?;
        match writer.write(&bytes[offset..]) {
            Ok(0) => {
                return Err(ExportError::OutputIo(std::io::Error::new(
                    ErrorKind::WriteZero,
                    "writer made no progress",
                )));
            }
            Ok(written_bytes) => {
                if written_bytes > bytes.len() - offset {
                    return Err(ExportError::OutputIo(std::io::Error::new(
                        ErrorKind::InvalidData,
                        "writer returned an invalid byte count",
                    )));
                }
                *output_bytes = checked_output_count(*output_bytes, written_bytes)?;
                offset += written_bytes;
                checkpoint(control)?;
            }
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(ExportError::OutputIo(error)),
        }
    }
    Ok(())
}

/// Flushes the completed destination through cancellable interrupted retries.
fn flush_output<Writer, Control>(
    writer: &mut Writer,
    control: &mut Control,
) -> Result<(), ExportError>
where
    Writer: Write,
    Control: ExportControl,
{
    loop {
        checkpoint(control)?;
        match writer.flush() {
            Ok(()) => {
                checkpoint(control)?;
                return Ok(());
            }
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(ExportError::OutputIo(error)),
        }
    }
}

/// Adds one read size to the checked input count.
fn checked_input_count(current: u64, additional: usize) -> Result<u64, ExportError> {
    let additional = u64::try_from(additional).map_err(|_| ExportError::InputByteCountOverflow)?;
    current
        .checked_add(additional)
        .ok_or(ExportError::InputByteCountOverflow)
}

/// Adds one write size to the checked output count.
fn checked_output_count(current: u64, additional: usize) -> Result<u64, ExportError> {
    let additional = u64::try_from(additional).map_err(|_| ExportError::OutputByteCountOverflow)?;
    current
        .checked_add(additional)
        .ok_or(ExportError::OutputByteCountOverflow)
}

/// Maps one external checkpoint decision into the public error contract.
fn checkpoint<Control>(control: &mut Control) -> Result<(), ExportError>
where
    Control: ExportControl,
{
    control
        .checkpoint()
        .map_err(|interruption| match interruption {
            ExportInterruption::Cancelled => ExportError::Cancelled,
            ExportInterruption::DeadlineExceeded => ExportError::DeadlineExceeded,
        })
}

#[cfg(test)]
mod tests {
    //! Verifies bounded snapshot export without platform dependencies.

    use super::*;
    use std::cell::Cell;
    use std::io::Cursor;
    use std::rc::Rc;

    const SHORT_IO_SCHEDULE: &[usize] = &[1, 2, 3, 5, 8, 13];
    const LIMIT_TEST_BYTES: u64 = 7;
    const INPUT_FAILURE_BYTES: usize = 4;
    const OUTPUT_FAILURE_BYTES: usize = 5;

    /// Produces deterministic short reads from one borrowed byte slice.
    struct ScheduledReader<'bytes> {
        bytes: &'bytes [u8],
        position: usize,
        schedule_position: usize,
        read_calls: usize,
    }

    impl<'bytes> ScheduledReader<'bytes> {
        /// Creates one reader at the start of its source bytes.
        const fn new(bytes: &'bytes [u8]) -> Self {
            Self {
                bytes,
                position: 0,
                schedule_position: 0,
                read_calls: 0,
            }
        }
    }

    impl Read for ScheduledReader<'_> {
        /// Reads one deterministically short source chunk.
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            self.read_calls += 1;
            if self.position == self.bytes.len() {
                return Ok(0);
            }
            let scheduled_bytes = SHORT_IO_SCHEDULE[self.schedule_position];
            self.schedule_position = (self.schedule_position + 1) % SHORT_IO_SCHEDULE.len();
            let bytes_read = scheduled_bytes
                .min(buffer.len())
                .min(self.bytes.len() - self.position);
            let source_end = self.position + bytes_read;
            buffer[..bytes_read].copy_from_slice(&self.bytes[self.position..source_end]);
            self.position = source_end;
            Ok(bytes_read)
        }
    }

    /// Accepts deterministic short writes into one in-memory destination.
    struct ScheduledWriter {
        bytes: Vec<u8>,
        schedule_position: usize,
        write_calls: usize,
        flush_calls: usize,
    }

    impl ScheduledWriter {
        /// Creates one empty scheduled writer.
        const fn new() -> Self {
            Self {
                bytes: Vec::new(),
                schedule_position: 0,
                write_calls: 0,
                flush_calls: 0,
            }
        }
    }

    impl Write for ScheduledWriter {
        /// Writes one deterministically short destination chunk.
        fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
            self.write_calls += 1;
            let scheduled_bytes = SHORT_IO_SCHEDULE[self.schedule_position];
            self.schedule_position = (self.schedule_position + 1) % SHORT_IO_SCHEDULE.len();
            let written_bytes = scheduled_bytes.min(buffer.len());
            self.bytes.extend_from_slice(&buffer[..written_bytes]);
            Ok(written_bytes)
        }

        /// Records one successful destination flush.
        fn flush(&mut self) -> std::io::Result<()> {
            self.flush_calls += 1;
            Ok(())
        }
    }

    /// Records written bytes and exposes their count to a cancellation control.
    struct SharedTrackingWriter {
        bytes: Vec<u8>,
        written_bytes: Rc<Cell<usize>>,
    }

    impl SharedTrackingWriter {
        /// Creates one empty writer and its shared byte counter.
        fn new() -> (Self, Rc<Cell<usize>>) {
            let written_bytes = Rc::new(Cell::new(0));
            (
                Self {
                    bytes: Vec::new(),
                    written_bytes: Rc::clone(&written_bytes),
                },
                written_bytes,
            )
        }
    }

    impl Write for SharedTrackingWriter {
        /// Writes one complete slice and updates the shared count.
        fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
            self.bytes.extend_from_slice(buffer);
            self.written_bytes.set(self.bytes.len());
            Ok(buffer.len())
        }

        /// Accepts one successful destination flush.
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    /// Fails after supplying one deterministic input prefix.
    struct FailingReader<'bytes> {
        bytes: &'bytes [u8],
        position: usize,
    }

    impl Read for FailingReader<'_> {
        /// Supplies a prefix before reporting one synthetic snapshot failure.
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            if self.position == INPUT_FAILURE_BYTES {
                return Err(std::io::Error::new(
                    ErrorKind::UnexpectedEof,
                    "synthetic input failure",
                ));
            }
            let bytes_read = (INPUT_FAILURE_BYTES - self.position).min(buffer.len());
            let source_end = self.position + bytes_read;
            buffer[..bytes_read].copy_from_slice(&self.bytes[self.position..source_end]);
            self.position = source_end;
            Ok(bytes_read)
        }
    }

    /// Fails after accepting one deterministic output prefix.
    struct FailingWriter {
        bytes: Vec<u8>,
    }

    impl Write for FailingWriter {
        /// Accepts a prefix before reporting one synthetic provider failure.
        fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
            if self.bytes.len() == OUTPUT_FAILURE_BYTES {
                return Err(std::io::Error::new(
                    ErrorKind::BrokenPipe,
                    "synthetic output failure",
                ));
            }
            let written_bytes = (OUTPUT_FAILURE_BYTES - self.bytes.len()).min(buffer.len());
            self.bytes.extend_from_slice(&buffer[..written_bytes]);
            Ok(written_bytes)
        }

        /// Accepts a flush only when output unexpectedly reaches it.
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    /// Verifies short I/O preserves exact BOM, mixed line endings, and non-ASCII bytes.
    #[test]
    fn copies_exact_serialized_utf8_bytes_across_short_io() {
        let expected = "\u{feff}heading\r\n👩🏽‍💻 βeta\r終わり\n".as_bytes();
        let mut reader = ScheduledReader::new(expected);
        let mut writer = ScheduledWriter::new();
        let limits =
            ExportLimits::new(expected.len() as u64).expect("exact test limit should be accepted");
        let mut control = || Ok::<(), ExportInterruption>(());

        let summary = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut control)
            .expect("short input and output should export successfully");

        assert_eq!(writer.bytes, expected);
        assert_eq!(summary.input_bytes, expected.len() as u64);
        assert_eq!(summary.output_bytes, expected.len() as u64);
        assert!(reader.read_calls > 1);
        assert!(writer.write_calls > 1);
        assert_eq!(writer.flush_calls, 1);
    }

    /// Verifies the first byte beyond a limit fails before that chunk is written.
    #[test]
    fn rejects_limit_plus_one() {
        let input = vec![b'x'; usize::try_from(LIMIT_TEST_BYTES + 1).unwrap()];
        let mut reader = Cursor::new(input);
        let mut writer = Vec::new();
        let limits =
            ExportLimits::new(LIMIT_TEST_BYTES).expect("bounded test limit should be accepted");
        let mut control = || Ok::<(), ExportInterruption>(());

        let error = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut control)
            .expect_err("limit plus one must fail");

        assert!(matches!(
            error,
            ExportError::SnapshotTooLarge {
                limit: LIMIT_TEST_BYTES,
                observed
            } if observed == LIMIT_TEST_BYTES + 1
        ));
        assert!(writer.is_empty());
    }

    /// Verifies limits above the hard maximum fail at construction.
    #[test]
    fn rejects_a_limit_above_the_hard_maximum() {
        let requested = HARD_MAX_SNAPSHOT_BYTES + 1;

        let error = ExportLimits::new(requested).expect_err("oversized limit must fail");

        assert!(matches!(
            error,
            ExportError::LimitTooHigh {
                requested: rejected,
                hard_maximum: HARD_MAX_SNAPSHOT_BYTES
            } if rejected == requested
        ));
    }

    /// Verifies cancellation stops before a final source byte is consumed.
    #[test]
    fn stops_at_a_cancellation_checkpoint() {
        let input = vec![b'x'; STREAM_BUFFER_BYTES + 1];
        let mut reader = Cursor::new(input);
        let (mut writer, written_bytes) = SharedTrackingWriter::new();
        let limits = ExportLimits::new((STREAM_BUFFER_BYTES + 1) as u64)
            .expect("bounded cancellation limit should be accepted");
        let mut control = || {
            if written_bytes.get() == STREAM_BUFFER_BYTES {
                Err(ExportInterruption::Cancelled)
            } else {
                Ok(())
            }
        };

        let error = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut control)
            .expect_err("cancelled output must fail");

        assert!(matches!(error, ExportError::Cancelled));
        assert_eq!(writer.bytes.len(), STREAM_BUFFER_BYTES);
    }

    /// Verifies an elapsed deadline maps to its distinct typed error.
    #[test]
    fn reports_an_elapsed_deadline() {
        let mut reader = Cursor::new(b"not read".as_slice());
        let mut writer = Vec::new();
        let limits =
            ExportLimits::new(HARD_MAX_SNAPSHOT_BYTES).expect("hard maximum should be accepted");
        let mut control = || Err(ExportInterruption::DeadlineExceeded);

        let error = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut control)
            .expect_err("elapsed deadline must fail");

        assert!(matches!(error, ExportError::DeadlineExceeded));
        assert_eq!(reader.position(), 0);
        assert!(writer.is_empty());
    }

    /// Verifies a snapshot read failure remains distinct from output failures.
    #[test]
    fn reports_an_input_failure_after_its_exact_prefix() {
        let input = b"snapshot input must fail";
        let mut reader = FailingReader {
            bytes: input,
            position: 0,
        };
        let mut writer = Vec::new();
        let limits = ExportLimits::new(input.len() as u64)
            .expect("bounded input-failure limit should be accepted");
        let mut control = || Ok::<(), ExportInterruption>(());

        let error = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut control)
            .expect_err("input failure must fail the export");

        assert!(matches!(
            error,
            ExportError::InputIo(ref cause) if cause.kind() == ErrorKind::UnexpectedEof
        ));
        assert_eq!(writer, input[..INPUT_FAILURE_BYTES]);
    }

    /// Verifies a provider write failure remains distinct from input failures.
    #[test]
    fn reports_an_output_failure_after_its_exact_prefix() {
        let input = b"provider output must fail";
        let mut reader = Cursor::new(input.as_slice());
        let mut writer = FailingWriter { bytes: Vec::new() };
        let limits = ExportLimits::new(input.len() as u64)
            .expect("bounded output-failure limit should be accepted");
        let mut control = || Ok::<(), ExportInterruption>(());

        let error = export_utf8_snapshot_bytes(&mut reader, &mut writer, limits, &mut control)
            .expect_err("output failure must fail the export");

        assert!(matches!(
            error,
            ExportError::OutputIo(ref cause) if cause.kind() == ErrorKind::BrokenPipe
        ));
        assert_eq!(writer.bytes, input[..OUTPUT_FAILURE_BYTES]);
    }

    /// Verifies checked counters expose distinct overflow variants.
    #[test]
    fn reports_distinct_count_overflows() {
        assert!(matches!(
            checked_input_count(u64::MAX, 1),
            Err(ExportError::InputByteCountOverflow)
        ));
        assert!(matches!(
            checked_output_count(u64::MAX, 1),
            Err(ExportError::OutputByteCountOverflow)
        ));
    }
}
