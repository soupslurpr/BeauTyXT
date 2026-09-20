//! Provides bounded, byte-preserving UTF-8 import for `BeauTyXT`.

#![forbid(unsafe_code)]

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::io::{ErrorKind, Read, Write};

use sha2::{Digest, Sha256};

/// Fixes each streaming buffer at 64 KiB.
pub const STREAM_BUFFER_BYTES: usize = 64 * 1024;

/// Fixes a SHA-256 digest at 32 bytes.
pub const SHA_256_BYTE_COUNT: usize = 32;

/// Limits a single imported source to 256 MiB.
pub const HARD_MAX_INPUT_BYTES: u64 = 256 * 1024 * 1024;

/// Limits a single copied document to 256 MiB.
pub const HARD_MAX_OUTPUT_BYTES: u64 = 256 * 1024 * 1024;

const BOM_PROBE_BYTES: usize = 4;
const UTF8_BOM: [u8; 3] = [0xef, 0xbb, 0xbf];
const UTF16_LITTLE_ENDIAN_BOM: [u8; 2] = [0xff, 0xfe];
const UTF16_BIG_ENDIAN_BOM: [u8; 2] = [0xfe, 0xff];
const UTF32_LITTLE_ENDIAN_BOM: [u8; 4] = [0xff, 0xfe, 0x00, 0x00];
const UTF32_BIG_ENDIAN_BOM: [u8; 4] = [0x00, 0x00, 0xfe, 0xff];
const CARRIAGE_RETURN: u8 = b'\r';
const LINE_FEED: u8 = b'\n';

/// Configures the caller-selected limits for one import.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ImportLimits {
    max_input_bytes: u64,
    max_output_bytes: u64,
}

impl ImportLimits {
    /// Creates limits bounded by the service-wide hard maxima.
    ///
    /// # Errors
    ///
    /// Returns [`ImportError::InputLimitTooHigh`] or
    /// [`ImportError::OutputLimitTooHigh`] when a requested limit exceeds its
    /// hard maximum.
    pub fn new(max_input_bytes: u64, max_output_bytes: u64) -> Result<Self, ImportError> {
        if max_input_bytes > HARD_MAX_INPUT_BYTES {
            return Err(ImportError::InputLimitTooHigh {
                requested: max_input_bytes,
                hard_maximum: HARD_MAX_INPUT_BYTES,
            });
        }
        if max_output_bytes > HARD_MAX_OUTPUT_BYTES {
            return Err(ImportError::OutputLimitTooHigh {
                requested: max_output_bytes,
                hard_maximum: HARD_MAX_OUTPUT_BYTES,
            });
        }
        Ok(Self {
            max_input_bytes,
            max_output_bytes,
        })
    }

    /// Returns the maximum number of raw source bytes.
    #[must_use]
    pub const fn max_input_bytes(self) -> u64 {
        self.max_input_bytes
    }

    /// Returns the maximum number of copied output bytes.
    #[must_use]
    pub const fn max_output_bytes(self) -> u64 {
        self.max_output_bytes
    }
}

/// Identifies source properties discovered during validation.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SourceFlags(u32);

impl SourceFlags {
    /// Indicates that the source began with a retained UTF-8 BOM.
    pub const UTF8_BOM: Self = Self(1 << 0);

    /// Indicates that the source contained at least one CRLF sequence.
    pub const CRLF: Self = Self(1 << 1);

    /// Indicates that the source contained at least one bare LF.
    pub const LF: Self = Self(1 << 2);

    /// Indicates that the source contained at least one bare CR.
    pub const CR: Self = Self(1 << 3);

    /// Returns whether all bits in `flag` are present.
    #[must_use]
    pub const fn contains(self, flag: Self) -> bool {
        self.0 & flag.0 == flag.0
    }

    /// Returns the stable integer representation used by platform bridges.
    #[must_use]
    pub const fn bits(self) -> u32 {
        self.0
    }

    /// Records a discovered source property.
    fn insert(&mut self, flag: Self) {
        self.0 |= flag.0;
    }
}

/// Identifies a non-UTF-8 byte-order mark.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UnsupportedBom {
    /// Identifies a UTF-16 little-endian BOM.
    Utf16LittleEndian,

    /// Identifies a UTF-16 big-endian BOM.
    Utf16BigEndian,

    /// Identifies a UTF-32 little-endian BOM.
    Utf32LittleEndian,

    /// Identifies a UTF-32 big-endian BOM.
    Utf32BigEndian,
}

impl Display for UnsupportedBom {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Utf16LittleEndian => formatter.write_str("UTF-16 little-endian"),
            Self::Utf16BigEndian => formatter.write_str("UTF-16 big-endian"),
            Self::Utf32LittleEndian => formatter.write_str("UTF-32 little-endian"),
            Self::Utf32BigEndian => formatter.write_str("UTF-32 big-endian"),
        }
    }
}

/// Identifies an external request to stop importing.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ImportInterruption {
    /// Indicates that the caller cancelled the job.
    Cancelled,

    /// Indicates that the caller's monotonic deadline elapsed.
    DeadlineExceeded,
}

/// Supplies cancellation and deadline decisions without platform dependencies.
pub trait ImportControl {
    /// Checks whether the current import may continue.
    ///
    /// # Errors
    ///
    /// Returns an [`ImportInterruption`] when the operation must stop.
    fn checkpoint(&mut self) -> Result<(), ImportInterruption>;
}

impl<Checkpoint> ImportControl for Checkpoint
where
    Checkpoint: FnMut() -> Result<(), ImportInterruption>,
{
    fn checkpoint(&mut self) -> Result<(), ImportInterruption> {
        self()
    }
}

/// Summarizes one successfully validated and copied source.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ImportSummary {
    /// Stores the raw source size.
    pub input_bytes: u64,

    /// Stores the byte-identical UTF-8 output size.
    pub output_bytes: u64,

    /// Stores the source properties discovered while streaming.
    pub source_flags: SourceFlags,

    /// Stores the SHA-256 digest of every raw source byte.
    pub sha256: [u8; SHA_256_BYTE_COUNT],
}

/// Reports a bounded import failure without exposing document content.
#[derive(Debug)]
pub enum ImportError {
    /// Reports a requested input limit above the hard maximum.
    InputLimitTooHigh {
        /// Stores the rejected requested limit.
        requested: u64,

        /// Stores the enforced hard maximum.
        hard_maximum: u64,
    },

    /// Reports a requested output limit above the hard maximum.
    OutputLimitTooHigh {
        /// Stores the rejected requested limit.
        requested: u64,

        /// Stores the enforced hard maximum.
        hard_maximum: u64,
    },

    /// Reports a source that exceeds its declared raw byte limit.
    InputTooLarge {
        /// Stores the enforced job limit.
        limit: u64,

        /// Stores the first observed byte count above the limit.
        observed: u64,
    },

    /// Reports copied text that exceeds its declared byte limit.
    OutputTooLarge {
        /// Stores the enforced job limit.
        limit: u64,

        /// Stores the first requested byte count above the limit.
        observed: u64,
    },

    /// Reports a non-UTF-8 byte-order mark.
    UnsupportedBom(UnsupportedBom),

    /// Reports malformed or truncated UTF-8.
    InvalidUtf8 {
        /// Stores the raw source offset of the first invalid byte.
        byte_offset: u64,
    },

    /// Reports a cancelled job.
    Cancelled,

    /// Reports an elapsed import deadline.
    DeadlineExceeded,

    /// Reports an overflowing raw input byte counter.
    InputByteCountOverflow,

    /// Reports an overflowing copied output byte counter.
    OutputByteCountOverflow,

    /// Reports a streaming input failure.
    InputIo(std::io::Error),

    /// Reports a streaming output failure.
    OutputIo(std::io::Error),
}

impl Display for ImportError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InputLimitTooHigh {
                requested,
                hard_maximum,
            } => write!(
                formatter,
                "input limit {requested} exceeds hard maximum {hard_maximum}"
            ),
            Self::OutputLimitTooHigh {
                requested,
                hard_maximum,
            } => write!(
                formatter,
                "output limit {requested} exceeds hard maximum {hard_maximum}"
            ),
            Self::InputTooLarge { limit, observed } => write!(
                formatter,
                "input byte count {observed} exceeds limit {limit}"
            ),
            Self::OutputTooLarge { limit, observed } => write!(
                formatter,
                "output byte count {observed} exceeds limit {limit}"
            ),
            Self::UnsupportedBom(encoding) => {
                write!(formatter, "unsupported {encoding} BOM")
            }
            Self::InvalidUtf8 { byte_offset } => {
                write!(formatter, "invalid UTF-8 at byte offset {byte_offset}")
            }
            Self::Cancelled => formatter.write_str("import cancelled"),
            Self::DeadlineExceeded => formatter.write_str("import deadline exceeded"),
            Self::InputByteCountOverflow => formatter.write_str("input byte count overflow"),
            Self::OutputByteCountOverflow => formatter.write_str("output byte count overflow"),
            Self::InputIo(error) => write!(formatter, "input failed: {error}"),
            Self::OutputIo(error) => write!(formatter, "output failed: {error}"),
        }
    }
}

impl Error for ImportError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::InputIo(error) | Self::OutputIo(error) => Some(error),
            _ => None,
        }
    }
}

/// Copies a strict UTF-8 stream without changing any source byte.
///
/// The function retains an optional UTF-8 BOM, rejects UTF-16 and UTF-32
/// byte-order marks, validates UTF-8 across arbitrary short reads, and records
/// every source newline style without rewriting it. It never allocates or
/// materializes the complete document.
///
/// The control hook runs before blocking I/O and between bounded pieces of
/// work. Platform readers remain responsible for making a blocked descriptor
/// interruptible, such as by polling it alongside a cancellation descriptor.
///
/// # Errors
///
/// Returns [`ImportError`] when limits, encoding, control, or streaming I/O
/// prevent a complete byte-identical result. The caller owns output rollback or
/// replacement atomicity after any error.
pub fn copy_raw_utf8<Reader, Writer, Control>(
    reader: &mut Reader,
    writer: &mut Writer,
    limits: ImportLimits,
    control: &mut Control,
) -> Result<ImportSummary, ImportError>
where
    Reader: Read,
    Writer: Write,
    Control: ImportControl,
{
    let mut input_state = InputState::new(limits.max_input_bytes());
    let prefix = read_prefix(reader, &mut input_state, control)?;
    let prefix_classification = classify_prefix(&prefix)?;
    let mut copier = ValidatedCopier::new(writer, limits.max_output_bytes());
    if prefix_classification.has_utf8_bom {
        copier.source_flags.insert(SourceFlags::UTF8_BOM);
    }

    let mut input_buffer = vec![0_u8; STREAM_BUFFER_BYTES].into_boxed_slice();
    let initial_bytes = &prefix.bytes[..prefix.len];
    input_buffer[..initial_bytes.len()].copy_from_slice(initial_bytes);

    let mut buffered_bytes = initial_bytes.len();
    let mut buffer_raw_offset = 0;
    let mut reached_eof = prefix.reached_eof;

    loop {
        if !reached_eof && buffered_bytes < input_buffer.len() {
            let read_raw_offset = input_state.bytes;
            let bytes_read = read_input(
                reader,
                &mut input_buffer[buffered_bytes..],
                &mut input_state,
                control,
            )?;
            if bytes_read == 0 {
                reached_eof = true;
            } else {
                if buffered_bytes == 0 {
                    buffer_raw_offset = read_raw_offset;
                }
                buffered_bytes += bytes_read;
            }
        }

        if buffered_bytes == 0 {
            if reached_eof {
                break;
            }
            continue;
        }

        match std::str::from_utf8(&input_buffer[..buffered_bytes]) {
            Ok(_) => {
                copier.copy_valid(&input_buffer[..buffered_bytes], control)?;
                buffered_bytes = 0;
            }
            Err(error) if error.error_len().is_some() => {
                let valid_bytes = u64::try_from(error.valid_up_to())
                    .map_err(|_| ImportError::InputByteCountOverflow)?;
                let byte_offset = buffer_raw_offset
                    .checked_add(valid_bytes)
                    .ok_or(ImportError::InputByteCountOverflow)?;
                return Err(ImportError::InvalidUtf8 { byte_offset });
            }
            Err(error) => {
                let valid_bytes = error.valid_up_to();
                if reached_eof {
                    let valid_bytes = u64::try_from(valid_bytes)
                        .map_err(|_| ImportError::InputByteCountOverflow)?;
                    let byte_offset = buffer_raw_offset
                        .checked_add(valid_bytes)
                        .ok_or(ImportError::InputByteCountOverflow)?;
                    return Err(ImportError::InvalidUtf8 { byte_offset });
                }

                copier.copy_valid(&input_buffer[..valid_bytes], control)?;
                input_buffer.copy_within(valid_bytes..buffered_bytes, 0);
                let valid_bytes_u64 =
                    u64::try_from(valid_bytes).map_err(|_| ImportError::InputByteCountOverflow)?;
                buffer_raw_offset = buffer_raw_offset
                    .checked_add(valid_bytes_u64)
                    .ok_or(ImportError::InputByteCountOverflow)?;
                buffered_bytes -= valid_bytes;
            }
        }
    }

    copier.finish(control)?;
    let input_bytes = input_state.bytes;
    Ok(ImportSummary {
        input_bytes,
        output_bytes: copier.output_bytes,
        source_flags: copier.source_flags,
        sha256: input_state.finish(),
    })
}

/// Tracks raw source bytes against one job limit.
struct InputState {
    bytes: u64,
    limit: u64,
    sha256: Sha256,
}

impl InputState {
    /// Creates an empty input counter.
    fn new(limit: u64) -> Self {
        Self {
            bytes: 0,
            limit,
            sha256: Sha256::new(),
        }
    }

    /// Finishes the exact raw-source SHA-256 digest.
    fn finish(self) -> [u8; SHA_256_BYTE_COUNT] {
        self.sha256.finalize().into()
    }
}

/// Stores the bounded BOM probe and its EOF state.
struct Prefix {
    bytes: [u8; BOM_PROBE_BYTES],
    len: usize,
    reached_eof: bool,
}

/// Describes properties discovered in the source prefix.
struct PrefixClassification {
    has_utf8_bom: bool,
}

/// Copies validated bytes and records source metadata.
struct ValidatedCopier<'writer, Writer> {
    writer: &'writer mut Writer,
    output_bytes: u64,
    max_output_bytes: u64,
    source_flags: SourceFlags,
    pending_carriage_return: bool,
}

impl<'writer, Writer> ValidatedCopier<'writer, Writer>
where
    Writer: Write,
{
    /// Creates an empty validated-byte copier.
    fn new(writer: &'writer mut Writer, max_output_bytes: u64) -> Self {
        Self {
            writer,
            output_bytes: 0,
            max_output_bytes,
            source_flags: SourceFlags::default(),
            pending_carriage_return: false,
        }
    }

    /// Copies one validated UTF-8 byte slice exactly.
    fn copy_valid<Control>(
        &mut self,
        bytes: &[u8],
        control: &mut Control,
    ) -> Result<(), ImportError>
    where
        Control: ImportControl,
    {
        checkpoint(control)?;
        self.record_newlines(bytes);
        self.write_output(bytes, control)
    }

    /// Records newline forms across arbitrary validated-slice boundaries.
    fn record_newlines(&mut self, bytes: &[u8]) {
        let mut position = 0;

        if self.pending_carriage_return && !bytes.is_empty() {
            if bytes[0] == LINE_FEED {
                self.source_flags.insert(SourceFlags::CRLF);
                position = 1;
            } else {
                self.source_flags.insert(SourceFlags::CR);
            }
            self.pending_carriage_return = false;
        }

        while position < bytes.len() {
            let remaining = &bytes[position..];
            let special_offset = remaining
                .iter()
                .position(|byte| matches!(*byte, CARRIAGE_RETURN | LINE_FEED));
            let Some(special_offset) = special_offset else {
                break;
            };

            position += special_offset;
            match bytes[position] {
                LINE_FEED => {
                    self.source_flags.insert(SourceFlags::LF);
                    position += 1;
                }
                CARRIAGE_RETURN => {
                    if position + 1 == bytes.len() {
                        self.pending_carriage_return = true;
                        position += 1;
                    } else if bytes[position + 1] == LINE_FEED {
                        self.source_flags.insert(SourceFlags::CRLF);
                        position += 2;
                    } else {
                        self.source_flags.insert(SourceFlags::CR);
                        position += 1;
                    }
                }
                _ => unreachable!("special-byte search returned an ordinary byte"),
            }
        }
    }

    /// Finishes pending newline classification and flushes output.
    fn finish<Control>(&mut self, control: &mut Control) -> Result<(), ImportError>
    where
        Control: ImportControl,
    {
        if self.pending_carriage_return {
            self.source_flags.insert(SourceFlags::CR);
            self.pending_carriage_return = false;
        }
        self.flush_writer(control)?;
        checkpoint(control)
    }

    /// Writes one bounded validated slice without changing its bytes.
    fn write_output<Control>(
        &mut self,
        bytes: &[u8],
        control: &mut Control,
    ) -> Result<(), ImportError>
    where
        Control: ImportControl,
    {
        if bytes.is_empty() {
            return Ok(());
        }

        let additional_bytes =
            u64::try_from(bytes.len()).map_err(|_| ImportError::OutputByteCountOverflow)?;
        let resulting_bytes = self
            .output_bytes
            .checked_add(additional_bytes)
            .ok_or(ImportError::OutputByteCountOverflow)?;
        if resulting_bytes > self.max_output_bytes {
            return Err(ImportError::OutputTooLarge {
                limit: self.max_output_bytes,
                observed: resulting_bytes,
            });
        }
        self.output_bytes = resulting_bytes;

        let mut written_bytes = 0;
        while written_bytes < bytes.len() {
            checkpoint(control)?;
            let remaining_output = &bytes[written_bytes..];
            match self.writer.write(remaining_output) {
                Ok(0) => {
                    return Err(ImportError::OutputIo(std::io::Error::new(
                        ErrorKind::WriteZero,
                        "failed to write validated output",
                    )));
                }
                Ok(bytes) if bytes > remaining_output.len() => {
                    return Err(ImportError::OutputIo(std::io::Error::new(
                        ErrorKind::InvalidData,
                        "writer returned an invalid byte count",
                    )));
                }
                Ok(bytes) => written_bytes += bytes,
                Err(error) if error.kind() == ErrorKind::Interrupted => {}
                Err(error) => return Err(ImportError::OutputIo(error)),
            }
        }
        Ok(())
    }

    /// Flushes the destination writer after all bytes are delivered.
    fn flush_writer<Control>(&mut self, control: &mut Control) -> Result<(), ImportError>
    where
        Control: ImportControl,
    {
        loop {
            checkpoint(control)?;
            match self.writer.flush() {
                Ok(()) => return Ok(()),
                Err(error) if error.kind() == ErrorKind::Interrupted => {}
                Err(error) => return Err(ImportError::OutputIo(error)),
            }
        }
    }
}

/// Reads the complete bounded BOM probe despite short reads.
fn read_prefix<Reader, Control>(
    reader: &mut Reader,
    input_state: &mut InputState,
    control: &mut Control,
) -> Result<Prefix, ImportError>
where
    Reader: Read,
    Control: ImportControl,
{
    let mut prefix = Prefix {
        bytes: [0; BOM_PROBE_BYTES],
        len: 0,
        reached_eof: false,
    };
    while prefix.len < prefix.bytes.len() {
        let bytes_read = read_input(
            reader,
            &mut prefix.bytes[prefix.len..],
            input_state,
            control,
        )?;
        if bytes_read == 0 {
            prefix.reached_eof = true;
            break;
        }
        prefix.len += bytes_read;
    }
    Ok(prefix)
}

/// Classifies a complete BOM probe.
fn classify_prefix(prefix: &Prefix) -> Result<PrefixClassification, ImportError> {
    let bytes = &prefix.bytes[..prefix.len];
    if bytes.starts_with(&UTF32_LITTLE_ENDIAN_BOM) {
        return Err(ImportError::UnsupportedBom(
            UnsupportedBom::Utf32LittleEndian,
        ));
    }
    if bytes.starts_with(&UTF32_BIG_ENDIAN_BOM) {
        return Err(ImportError::UnsupportedBom(UnsupportedBom::Utf32BigEndian));
    }
    if bytes.starts_with(&UTF16_LITTLE_ENDIAN_BOM) {
        return Err(ImportError::UnsupportedBom(
            UnsupportedBom::Utf16LittleEndian,
        ));
    }
    if bytes.starts_with(&UTF16_BIG_ENDIAN_BOM) {
        return Err(ImportError::UnsupportedBom(UnsupportedBom::Utf16BigEndian));
    }
    Ok(PrefixClassification {
        has_utf8_bom: bytes.starts_with(&UTF8_BOM),
    })
}

/// Reads one bounded source chunk and updates its checked byte count.
fn read_input<Reader, Control>(
    reader: &mut Reader,
    buffer: &mut [u8],
    input_state: &mut InputState,
    control: &mut Control,
) -> Result<usize, ImportError>
where
    Reader: Read,
    Control: ImportControl,
{
    debug_assert!(!buffer.is_empty());
    let remaining_bytes = input_state.limit - input_state.bytes;
    let probe_bytes = remaining_bytes
        .checked_add(1)
        .ok_or(ImportError::InputByteCountOverflow)?;
    let read_capacity = usize::try_from(probe_bytes)
        .unwrap_or(usize::MAX)
        .min(buffer.len());

    loop {
        checkpoint(control)?;
        match reader.read(&mut buffer[..read_capacity]) {
            Ok(bytes_read) => {
                if bytes_read > read_capacity {
                    return Err(ImportError::InputIo(std::io::Error::new(
                        ErrorKind::InvalidData,
                        "reader returned an invalid byte count",
                    )));
                }
                let bytes_read_u64 =
                    u64::try_from(bytes_read).map_err(|_| ImportError::InputByteCountOverflow)?;
                let observed = input_state
                    .bytes
                    .checked_add(bytes_read_u64)
                    .ok_or(ImportError::InputByteCountOverflow)?;
                if observed > input_state.limit {
                    return Err(ImportError::InputTooLarge {
                        limit: input_state.limit,
                        observed,
                    });
                }
                input_state.bytes = observed;
                input_state.sha256.update(&buffer[..bytes_read]);
                checkpoint(control)?;
                return Ok(bytes_read);
            }
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(ImportError::InputIo(error)),
        }
    }
}

/// Maps one external checkpoint decision into the public error contract.
fn checkpoint<Control>(control: &mut Control) -> Result<(), ImportError>
where
    Control: ImportControl,
{
    control
        .checkpoint()
        .map_err(|interruption| match interruption {
            ImportInterruption::Cancelled => ImportError::Cancelled,
            ImportInterruption::DeadlineExceeded => ImportError::DeadlineExceeded,
        })
}
