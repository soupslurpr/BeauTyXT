//! Defines `BeauTyXT`'s bounded immutable source-save package protocol.

#![forbid(unsafe_code)]

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::io::{ErrorKind, Read, Write};
use std::mem::size_of;

/// Identifies every version-one source-save package.
pub const PACKAGE_MAGIC: [u8; 8] = *b"BTXTSAVE";

/// Identifies the only supported package format version.
pub const PACKAGE_VERSION: u32 = 1;

/// Fixes the version-one package header width.
pub const PACKAGE_HEADER_BYTES: usize = 64;

/// Fixes every version-one record header width.
pub const RECORD_HEADER_BYTES: usize = 24;

/// Indicates that a package references one immutable source backing.
pub const FLAG_SOURCE_PRESENT: u32 = 1;

/// Identifies one immutable source-backing extent.
pub const RECORD_KIND_SOURCE: u32 = 1;

/// Identifies one extent in the package's trailing payload.
pub const RECORD_KIND_PAYLOAD: u32 = 2;

/// Limits reconstructed output to 256 MiB.
pub const MAX_OUTPUT_BYTES: u64 = 256 * 1024 * 1024;

/// Limits one package to 65,536 ordered extents.
pub const MAX_RECORD_COUNT: u64 = 65_536;

/// Limits package storage to one full output and maximum record overhead.
pub const MAX_PACKAGE_BYTES: u64 =
    MAX_OUTPUT_BYTES + PACKAGE_HEADER_BYTES_U64 + MAX_RECORD_COUNT * RECORD_HEADER_BYTES_U64;

const PACKAGE_HEADER_BYTES_U32: u32 = 64;
const PACKAGE_HEADER_BYTES_U64: u64 = 64;
const RECORD_HEADER_BYTES_U64: u64 = 24;
const MAGIC_OFFSET: usize = 0;
const VERSION_OFFSET: usize = 8;
const HEADER_BYTES_OFFSET: usize = 12;
const FLAGS_OFFSET: usize = 16;
const RESERVED_OFFSET: usize = 20;
const RECORD_COUNT_OFFSET: usize = 24;
const PACKAGE_BYTES_OFFSET: usize = 32;
const OUTPUT_BYTES_OFFSET: usize = 40;
const PAYLOAD_BYTES_OFFSET: usize = 48;
const SOURCE_BYTES_OFFSET: usize = 56;
const RECORD_KIND_OFFSET: usize = 0;
const RECORD_RESERVED_OFFSET: usize = 4;
const RECORD_BACKING_OFFSET: usize = 8;
const RECORD_BYTE_LENGTH_OFFSET: usize = 16;

/// Reports one stable malformed-package condition.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PackageFormatError {
    /// Reports a package with the wrong fixed magic.
    Magic,

    /// Reports an unsupported package version.
    Version,

    /// Reports an unexpected fixed header width.
    HeaderSize,

    /// Reports unknown package flag bits.
    Flags,

    /// Reports a nonzero reserved field.
    Reserved,

    /// Reports a record count outside the protocol bound.
    RecordCount,

    /// Reports a package length outside the protocol bound.
    PackageLength,

    /// Reports a reconstructed output outside the protocol bound.
    OutputLength,

    /// Reports payload metadata inconsistent with the output.
    PayloadLength,

    /// Reports source metadata inconsistent with its presence flag.
    SourceLength,

    /// Reports an unsupported record kind.
    RecordKind,

    /// Reports an empty record.
    EmptyRecord,

    /// Reports a backing range that overflows or exceeds its descriptor.
    RecordRange,

    /// Reports payload records that do not cover payload bytes canonically.
    PayloadCoverage,

    /// Reports records whose reconstructed byte sum is not exact.
    OutputCoverage,

    /// Reports a descriptor bundle that conflicts with package metadata.
    DescriptorBundle,

    /// Reports arithmetic that exceeds the fixed protocol representation.
    ArithmeticOverflow,

    /// Reports an encoder or validator called in an invalid phase.
    State,
}

impl Display for PackageFormatError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::Magic => "source-save package magic is invalid",
            Self::Version => "source-save package version is invalid",
            Self::HeaderSize => "source-save package header size is invalid",
            Self::Flags => "source-save package flags are invalid",
            Self::Reserved => "source-save package reserved field is nonzero",
            Self::RecordCount => "source-save package record count is invalid",
            Self::PackageLength => "source-save package length is invalid",
            Self::OutputLength => "source-save package output length is invalid",
            Self::PayloadLength => "source-save package payload length is invalid",
            Self::SourceLength => "source-save package source length is invalid",
            Self::RecordKind => "source-save package record kind is invalid",
            Self::EmptyRecord => "source-save package record is empty",
            Self::RecordRange => "source-save package record range is invalid",
            Self::PayloadCoverage => "source-save package payload coverage is invalid",
            Self::OutputCoverage => "source-save package output coverage is invalid",
            Self::DescriptorBundle => "source-save package descriptor bundle is invalid",
            Self::ArithmeticOverflow => "source-save package arithmetic overflowed",
            Self::State => "source-save package state is invalid",
        })
    }
}

impl Error for PackageFormatError {}

/// Reports package encoding failures without exposing document bytes.
#[derive(Debug)]
pub enum PackageWriteError {
    /// Reports malformed metrics or an invalid encoding sequence.
    Format(PackageFormatError),

    /// Reports an underlying output failure.
    Io(std::io::Error),
}

impl Display for PackageWriteError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Format(error) => Display::fmt(error, formatter),
            Self::Io(error) => write!(formatter, "source-save package output failed: {error}"),
        }
    }
}

impl Error for PackageWriteError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Format(error) => Some(error),
            Self::Io(error) => Some(error),
        }
    }
}

impl From<PackageFormatError> for PackageWriteError {
    fn from(error: PackageFormatError) -> Self {
        Self::Format(error)
    }
}

impl From<std::io::Error> for PackageWriteError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

/// Reports package validation failures without exposing document bytes.
#[derive(Debug)]
pub enum PackageReadError {
    /// Reports malformed package metadata or a conflicting descriptor bundle.
    Format(PackageFormatError),

    /// Reports an underlying positioned-read failure.
    Io(std::io::Error),
}

impl Display for PackageReadError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Format(error) => Display::fmt(error, formatter),
            Self::Io(error) => write!(formatter, "source-save package input failed: {error}"),
        }
    }
}

impl Error for PackageReadError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Format(error) => Some(error),
            Self::Io(error) => Some(error),
        }
    }
}

impl From<PackageFormatError> for PackageReadError {
    fn from(error: PackageFormatError) -> Self {
        Self::Format(error)
    }
}

impl From<std::io::Error> for PackageReadError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

/// Supplies immutable bytes through descriptor-style positioned reads.
pub trait ReadAt {
    /// Returns the immutable descriptor's exact byte length.
    ///
    /// # Errors
    ///
    /// Returns an error when descriptor metadata cannot be read.
    fn byte_length(&self) -> std::io::Result<u64>;

    /// Reads bytes beginning at one absolute descriptor offset.
    ///
    /// # Errors
    ///
    /// Returns an error when the descriptor cannot be read.
    fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize>;
}

impl ReadAt for [u8] {
    fn byte_length(&self) -> std::io::Result<u64> {
        u64::try_from(self.len())
            .map_err(|_| std::io::Error::other("byte slice length exceeds u64"))
    }

    fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize> {
        let offset = usize::try_from(offset)
            .map_err(|_| std::io::Error::other("byte slice offset exceeds usize"))?;
        let Some(available) = self.get(offset..) else {
            return Ok(0);
        };
        let byte_count = available.len().min(bytes.len());
        bytes[..byte_count].copy_from_slice(&available[..byte_count]);
        Ok(byte_count)
    }
}

impl ReadAt for Vec<u8> {
    fn byte_length(&self) -> std::io::Result<u64> {
        self.as_slice().byte_length()
    }

    fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize> {
        self.as_slice().read_at(bytes, offset)
    }
}

impl<const BYTE_COUNT: usize> ReadAt for [u8; BYTE_COUNT] {
    fn byte_length(&self) -> std::io::Result<u64> {
        self.as_slice().byte_length()
    }

    fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize> {
        self.as_slice().read_at(bytes, offset)
    }
}

#[cfg(unix)]
impl ReadAt for std::fs::File {
    fn byte_length(&self) -> std::io::Result<u64> {
        self.metadata().map(|metadata| metadata.len())
    }

    fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize> {
        std::os::unix::fs::FileExt::read_at(self, bytes, offset)
    }
}

#[cfg(windows)]
impl ReadAt for std::fs::File {
    fn byte_length(&self) -> std::io::Result<u64> {
        self.metadata().map(|metadata| metadata.len())
    }

    fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize> {
        std::os::windows::fs::FileExt::seek_read(self, bytes, offset)
    }
}

/// Describes one complete validated package allocation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PackageHeader {
    flags: u32,
    record_count: u64,
    package_bytes: u64,
    output_bytes: u64,
    payload_bytes: u64,
    source_bytes: u64,
}

impl PackageHeader {
    /// Creates one canonical bounded package header.
    ///
    /// # Errors
    ///
    /// Returns an error when any length, record count, source marker, or
    /// checked package-size calculation violates the version-one protocol.
    pub fn new(
        record_count: u64,
        output_bytes: u64,
        payload_bytes: u64,
        source_bytes: Option<u64>,
    ) -> Result<Self, PackageFormatError> {
        if record_count > MAX_RECORD_COUNT || (output_bytes == 0) != (record_count == 0) {
            return Err(PackageFormatError::RecordCount);
        }
        if output_bytes > MAX_OUTPUT_BYTES {
            return Err(PackageFormatError::OutputLength);
        }
        if payload_bytes > output_bytes {
            return Err(PackageFormatError::PayloadLength);
        }
        if source_bytes.is_some() != (payload_bytes < output_bytes) {
            return Err(PackageFormatError::SourceLength);
        }
        if source_bytes
            .is_some_and(|byte_length| byte_length == 0 || byte_length > MAX_OUTPUT_BYTES)
        {
            return Err(PackageFormatError::SourceLength);
        }
        let record_bytes = record_count
            .checked_mul(RECORD_HEADER_BYTES_U64)
            .ok_or(PackageFormatError::ArithmeticOverflow)?;
        let package_bytes = PACKAGE_HEADER_BYTES_U64
            .checked_add(record_bytes)
            .and_then(|bytes| bytes.checked_add(payload_bytes))
            .ok_or(PackageFormatError::ArithmeticOverflow)?;
        if package_bytes > MAX_PACKAGE_BYTES {
            return Err(PackageFormatError::PackageLength);
        }
        Ok(Self {
            flags: u32::from(source_bytes.is_some()) * FLAG_SOURCE_PRESENT,
            record_count,
            package_bytes,
            output_bytes,
            payload_bytes,
            source_bytes: source_bytes.unwrap_or(0),
        })
    }

    /// Decodes one exact fixed-width version-one header.
    ///
    /// # Errors
    ///
    /// Returns an error when any fixed field or derived metric is noncanonical.
    pub fn decode(bytes: &[u8; PACKAGE_HEADER_BYTES]) -> Result<Self, PackageFormatError> {
        if bytes[MAGIC_OFFSET..VERSION_OFFSET] != PACKAGE_MAGIC {
            return Err(PackageFormatError::Magic);
        }
        if decode_u32(bytes, VERSION_OFFSET) != PACKAGE_VERSION {
            return Err(PackageFormatError::Version);
        }
        if decode_u32(bytes, HEADER_BYTES_OFFSET) != PACKAGE_HEADER_BYTES_U32 {
            return Err(PackageFormatError::HeaderSize);
        }
        let flags = decode_u32(bytes, FLAGS_OFFSET);
        if flags & !FLAG_SOURCE_PRESENT != 0 {
            return Err(PackageFormatError::Flags);
        }
        if decode_u32(bytes, RESERVED_OFFSET) != 0 {
            return Err(PackageFormatError::Reserved);
        }
        let record_count = decode_u64(bytes, RECORD_COUNT_OFFSET);
        let package_bytes = decode_u64(bytes, PACKAGE_BYTES_OFFSET);
        let output_bytes = decode_u64(bytes, OUTPUT_BYTES_OFFSET);
        let payload_bytes = decode_u64(bytes, PAYLOAD_BYTES_OFFSET);
        let encoded_source_bytes = decode_u64(bytes, SOURCE_BYTES_OFFSET);
        let source_bytes = if flags & FLAG_SOURCE_PRESENT != 0 {
            Some(encoded_source_bytes)
        } else {
            if encoded_source_bytes != 0 {
                return Err(PackageFormatError::SourceLength);
            }
            None
        };
        let header = Self::new(record_count, output_bytes, payload_bytes, source_bytes)?;
        if header.package_bytes != package_bytes || header.flags != flags {
            return Err(PackageFormatError::PackageLength);
        }
        Ok(header)
    }

    /// Encodes this canonical header into its exact fixed-width form.
    #[must_use]
    pub fn encode(self) -> [u8; PACKAGE_HEADER_BYTES] {
        let mut bytes = [0_u8; PACKAGE_HEADER_BYTES];
        bytes[MAGIC_OFFSET..VERSION_OFFSET].copy_from_slice(&PACKAGE_MAGIC);
        encode_u32(&mut bytes, VERSION_OFFSET, PACKAGE_VERSION);
        encode_u32(&mut bytes, HEADER_BYTES_OFFSET, PACKAGE_HEADER_BYTES_U32);
        encode_u32(&mut bytes, FLAGS_OFFSET, self.flags);
        encode_u64(&mut bytes, RECORD_COUNT_OFFSET, self.record_count);
        encode_u64(&mut bytes, PACKAGE_BYTES_OFFSET, self.package_bytes);
        encode_u64(&mut bytes, OUTPUT_BYTES_OFFSET, self.output_bytes);
        encode_u64(&mut bytes, PAYLOAD_BYTES_OFFSET, self.payload_bytes);
        encode_u64(&mut bytes, SOURCE_BYTES_OFFSET, self.source_bytes);
        bytes
    }

    /// Returns whether this package references one source backing.
    #[must_use]
    pub const fn has_source(self) -> bool {
        self.flags & FLAG_SOURCE_PRESENT != 0
    }

    /// Returns the ordered record count.
    #[must_use]
    pub const fn record_count(self) -> u64 {
        self.record_count
    }

    /// Returns the exact complete package byte length.
    #[must_use]
    pub const fn package_bytes(self) -> u64 {
        self.package_bytes
    }

    /// Returns the exact reconstructed output byte length.
    #[must_use]
    pub const fn output_bytes(self) -> u64 {
        self.output_bytes
    }

    /// Returns the exact trailing payload byte length.
    #[must_use]
    pub const fn payload_bytes(self) -> u64 {
        self.payload_bytes
    }

    /// Returns the exact optional source-backing byte length.
    #[must_use]
    pub const fn source_bytes(self) -> Option<u64> {
        if self.has_source() {
            Some(self.source_bytes)
        } else {
            None
        }
    }

    /// Returns the package offset of the trailing payload.
    #[must_use]
    pub const fn payload_start(self) -> u64 {
        self.package_bytes - self.payload_bytes
    }
}

/// Identifies one ordered source or payload extent.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PackageRecord {
    kind: u32,
    offset: u64,
    byte_length: u64,
}

impl PackageRecord {
    /// Creates one nonempty source extent.
    ///
    /// # Errors
    ///
    /// Returns an error when the range is empty or overflows.
    pub fn source(offset: u64, byte_length: u64) -> Result<Self, PackageFormatError> {
        Self::new(RECORD_KIND_SOURCE, offset, byte_length)
    }

    /// Creates one nonempty payload extent.
    ///
    /// # Errors
    ///
    /// Returns an error when the range is empty or overflows.
    pub fn payload(offset: u64, byte_length: u64) -> Result<Self, PackageFormatError> {
        Self::new(RECORD_KIND_PAYLOAD, offset, byte_length)
    }

    /// Decodes one exact fixed-width record header.
    ///
    /// # Errors
    ///
    /// Returns an error for nonzero reserved fields, unknown kinds, empty
    /// records, or overflowing ranges.
    pub fn decode(bytes: &[u8; RECORD_HEADER_BYTES]) -> Result<Self, PackageFormatError> {
        if decode_u32(bytes, RECORD_RESERVED_OFFSET) != 0 {
            return Err(PackageFormatError::Reserved);
        }
        Self::new(
            decode_u32(bytes, RECORD_KIND_OFFSET),
            decode_u64(bytes, RECORD_BACKING_OFFSET),
            decode_u64(bytes, RECORD_BYTE_LENGTH_OFFSET),
        )
    }

    /// Encodes this record into its exact fixed-width form.
    #[must_use]
    pub fn encode(self) -> [u8; RECORD_HEADER_BYTES] {
        let mut bytes = [0_u8; RECORD_HEADER_BYTES];
        encode_u32(&mut bytes, RECORD_KIND_OFFSET, self.kind);
        encode_u64(&mut bytes, RECORD_BACKING_OFFSET, self.offset);
        encode_u64(&mut bytes, RECORD_BYTE_LENGTH_OFFSET, self.byte_length);
        bytes
    }

    /// Returns the stable record kind.
    #[must_use]
    pub const fn kind(self) -> u32 {
        self.kind
    }

    /// Returns the range start within its backing.
    #[must_use]
    pub const fn offset(self) -> u64 {
        self.offset
    }

    /// Returns the nonzero range byte length.
    #[must_use]
    pub const fn byte_length(self) -> u64 {
        self.byte_length
    }

    /// Creates one checked record for a known kind.
    fn new(kind: u32, offset: u64, byte_length: u64) -> Result<Self, PackageFormatError> {
        if !matches!(kind, RECORD_KIND_SOURCE | RECORD_KIND_PAYLOAD) {
            return Err(PackageFormatError::RecordKind);
        }
        if byte_length == 0 {
            return Err(PackageFormatError::EmptyRecord);
        }
        offset
            .checked_add(byte_length)
            .ok_or(PackageFormatError::RecordRange)?;
        Ok(Self {
            kind,
            offset,
            byte_length,
        })
    }
}

/// Validates one package table incrementally without retaining its records.
pub struct PackageValidator {
    header: PackageHeader,
    next_record_index: u64,
    observed_output_bytes: u64,
    observed_payload_bytes: u64,
    observed_source: bool,
}

impl PackageValidator {
    /// Creates one validator for exact descriptors and caller output.
    ///
    /// # Errors
    ///
    /// Returns an error when descriptor lengths or source presence conflict
    /// with the canonical header.
    pub fn new(
        header: PackageHeader,
        package_bytes: u64,
        expected_output_bytes: u64,
        source_bytes: Option<u64>,
    ) -> Result<Self, PackageFormatError> {
        if header.package_bytes != package_bytes {
            return Err(PackageFormatError::PackageLength);
        }
        if header.output_bytes != expected_output_bytes {
            return Err(PackageFormatError::OutputLength);
        }
        if header.source_bytes() != source_bytes {
            return Err(PackageFormatError::DescriptorBundle);
        }
        Ok(Self {
            header,
            next_record_index: 0,
            observed_output_bytes: 0,
            observed_payload_bytes: 0,
            observed_source: false,
        })
    }

    /// Validates the next ordered package record.
    ///
    /// # Errors
    ///
    /// Returns an error when the record exceeds its backing, payload order, or
    /// declared record and output bounds.
    pub fn push(&mut self, record: PackageRecord) -> Result<(), PackageFormatError> {
        if self.next_record_index >= self.header.record_count {
            return Err(PackageFormatError::RecordCount);
        }
        let record_end = record
            .offset
            .checked_add(record.byte_length)
            .ok_or(PackageFormatError::RecordRange)?;
        match record.kind {
            RECORD_KIND_SOURCE => {
                let source_bytes = self
                    .header
                    .source_bytes()
                    .ok_or(PackageFormatError::DescriptorBundle)?;
                if record_end > source_bytes {
                    return Err(PackageFormatError::RecordRange);
                }
                self.observed_source = true;
            }
            RECORD_KIND_PAYLOAD => {
                if record.offset != self.observed_payload_bytes
                    || record_end > self.header.payload_bytes
                {
                    return Err(PackageFormatError::PayloadCoverage);
                }
                self.observed_payload_bytes = record_end;
            }
            _ => return Err(PackageFormatError::RecordKind),
        }
        self.observed_output_bytes = self
            .observed_output_bytes
            .checked_add(record.byte_length)
            .ok_or(PackageFormatError::ArithmeticOverflow)?;
        if self.observed_output_bytes > self.header.output_bytes {
            return Err(PackageFormatError::OutputCoverage);
        }
        self.next_record_index += 1;
        Ok(())
    }

    /// Finishes exact record, payload, source, and output coverage validation.
    ///
    /// # Errors
    ///
    /// Returns an error when the observed table does not exactly match its
    /// canonical header.
    pub fn finish(self) -> Result<ValidatedPackage, PackageFormatError> {
        if self.next_record_index != self.header.record_count {
            return Err(PackageFormatError::RecordCount);
        }
        if self.observed_payload_bytes != self.header.payload_bytes {
            return Err(PackageFormatError::PayloadCoverage);
        }
        if self.observed_output_bytes != self.header.output_bytes {
            return Err(PackageFormatError::OutputCoverage);
        }
        if self.observed_source != self.header.has_source() {
            return Err(PackageFormatError::DescriptorBundle);
        }
        Ok(ValidatedPackage {
            header: self.header,
        })
    }
}

/// Describes one completely validated package without retaining its records.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ValidatedPackage {
    header: PackageHeader,
}

impl ValidatedPackage {
    /// Returns the validated canonical header.
    #[must_use]
    pub const fn header(self) -> PackageHeader {
        self.header
    }
}

/// Returns the absolute byte offset of one record in the package table.
fn package_record_offset(record_index: u64) -> Result<u64, PackageFormatError> {
    record_index
        .checked_mul(RECORD_HEADER_BYTES_U64)
        .and_then(|record_offset| PACKAGE_HEADER_BYTES_U64.checked_add(record_offset))
        .ok_or(PackageFormatError::ArithmeticOverflow)
}

/// Reads one exact range without changing the descriptor cursor.
fn read_exact_at(
    descriptor: &dyn ReadAt,
    mut bytes: &mut [u8],
    mut offset: u64,
) -> std::io::Result<()> {
    while !bytes.is_empty() {
        let bytes_read = descriptor.read_at(bytes, offset)?;
        if bytes_read == 0 {
            return Err(std::io::Error::new(
                ErrorKind::UnexpectedEof,
                "source-save descriptor ended before its declared length",
            ));
        }
        if bytes_read > bytes.len() {
            return Err(invalid_data(PackageFormatError::RecordRange));
        }
        offset = offset
            .checked_add(
                u64::try_from(bytes_read)
                    .map_err(|_| invalid_data(PackageFormatError::ArithmeticOverflow))?,
            )
            .ok_or_else(|| invalid_data(PackageFormatError::ArithmeticOverflow))?;
        bytes = &mut bytes[bytes_read..];
    }
    Ok(())
}

/// Converts one package format failure into a positioned-reader failure.
fn invalid_data(error: PackageFormatError) -> std::io::Error {
    std::io::Error::new(ErrorKind::InvalidData, error)
}

/// Validates a complete package and optional source using fixed-size buffers.
///
/// # Errors
///
/// Returns an error when positioned I/O fails or any header, record, backing
/// range, descriptor length, or coverage invariant is invalid.
pub fn validate_package(
    package: &dyn ReadAt,
    source: Option<&dyn ReadAt>,
) -> Result<ValidatedPackage, PackageReadError> {
    let package_bytes = package.byte_length()?;
    if !(PACKAGE_HEADER_BYTES_U64..=MAX_PACKAGE_BYTES).contains(&package_bytes) {
        return Err(PackageFormatError::PackageLength.into());
    }

    let mut header_bytes = [0_u8; PACKAGE_HEADER_BYTES];
    read_exact_at(package, &mut header_bytes, 0)?;
    let header = PackageHeader::decode(&header_bytes)?;
    let source_bytes = source.map(ReadAt::byte_length).transpose()?;
    let mut validator =
        PackageValidator::new(header, package_bytes, header.output_bytes(), source_bytes)?;

    let mut record_bytes = [0_u8; RECORD_HEADER_BYTES];
    for record_index in 0..header.record_count() {
        read_exact_at(
            package,
            &mut record_bytes,
            package_record_offset(record_index)?,
        )?;
        validator.push(PackageRecord::decode(&record_bytes)?)?;
    }
    validator.finish().map_err(Into::into)
}

/// Streams reconstructed bytes from one fully validated descriptor bundle.
pub struct ValidatedPackageReader<'descriptor> {
    package: &'descriptor dyn ReadAt,
    source: Option<&'descriptor dyn ReadAt>,
    validated: ValidatedPackage,
    stream_validator: Option<PackageValidator>,
    next_record_index: u64,
    current_record: Option<PackageRecord>,
    current_record_bytes_read: u64,
    output_bytes_read: u64,
}

impl<'descriptor> ValidatedPackageReader<'descriptor> {
    /// Validates descriptor metadata and creates one output reader.
    ///
    /// # Errors
    ///
    /// Returns an error when positioned I/O fails or the package and source
    /// descriptor bundle is malformed.
    pub fn new(
        package: &'descriptor dyn ReadAt,
        source: Option<&'descriptor dyn ReadAt>,
    ) -> Result<Self, PackageReadError> {
        let validated = validate_package(package, source)?;
        let header = validated.header();
        let stream_validator = PackageValidator::new(
            header,
            header.package_bytes(),
            header.output_bytes(),
            header.source_bytes(),
        )?;
        Ok(Self {
            package,
            source,
            validated,
            stream_validator: Some(stream_validator),
            next_record_index: 0,
            current_record: None,
            current_record_bytes_read: 0,
            output_bytes_read: 0,
        })
    }

    /// Returns the validated canonical package header.
    #[must_use]
    pub const fn header(&self) -> PackageHeader {
        self.validated.header()
    }

    /// Returns the reconstructed bytes not yet read.
    #[must_use]
    pub const fn remaining_bytes(&self) -> u64 {
        self.header()
            .output_bytes()
            .saturating_sub(self.output_bytes_read)
    }

    /// Loads and revalidates the next ordered record.
    ///
    /// Returns idempotent end-of-input after the complete table validates.
    fn load_next_record(&mut self) -> std::io::Result<bool> {
        let header = self.header();
        if self.next_record_index == header.record_count() {
            if let Some(validator) = self.stream_validator.take() {
                validator.finish().map_err(invalid_data)?;
            }
            return Ok(false);
        }

        let mut bytes = [0_u8; RECORD_HEADER_BYTES];
        read_exact_at(
            self.package,
            &mut bytes,
            package_record_offset(self.next_record_index).map_err(invalid_data)?,
        )?;
        let record = PackageRecord::decode(&bytes).map_err(invalid_data)?;
        self.stream_validator
            .as_mut()
            .ok_or_else(|| invalid_data(PackageFormatError::State))?
            .push(record)
            .map_err(invalid_data)?;
        self.current_record = Some(record);
        self.current_record_bytes_read = 0;
        self.next_record_index = self
            .next_record_index
            .checked_add(1)
            .ok_or_else(|| invalid_data(PackageFormatError::ArithmeticOverflow))?;
        Ok(true)
    }

    /// Reads the current record through its immutable positioned backing.
    fn read_current_record(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
        let record = self
            .current_record
            .ok_or_else(|| invalid_data(PackageFormatError::State))?;
        let remaining_record_bytes = record
            .byte_length()
            .checked_sub(self.current_record_bytes_read)
            .ok_or_else(|| invalid_data(PackageFormatError::State))?;
        let byte_count = usize::try_from(remaining_record_bytes)
            .unwrap_or(usize::MAX)
            .min(output.len());
        let backing_offset = record
            .offset()
            .checked_add(self.current_record_bytes_read)
            .ok_or_else(|| invalid_data(PackageFormatError::ArithmeticOverflow))?;
        let bytes_read = match record.kind() {
            RECORD_KIND_SOURCE => {
                let source = self
                    .source
                    .ok_or_else(|| invalid_data(PackageFormatError::DescriptorBundle))?;
                source.read_at(&mut output[..byte_count], backing_offset)?
            }
            RECORD_KIND_PAYLOAD => {
                let package_offset = self
                    .header()
                    .payload_start()
                    .checked_add(backing_offset)
                    .ok_or_else(|| invalid_data(PackageFormatError::ArithmeticOverflow))?;
                self.package
                    .read_at(&mut output[..byte_count], package_offset)?
            }
            _ => return Err(invalid_data(PackageFormatError::RecordKind)),
        };
        if bytes_read == 0 {
            return Err(std::io::Error::new(
                ErrorKind::UnexpectedEof,
                "source-save backing ended before its declared range",
            ));
        }
        if bytes_read > byte_count {
            return Err(invalid_data(PackageFormatError::RecordRange));
        }
        let bytes_read = u64::try_from(bytes_read)
            .map_err(|_| invalid_data(PackageFormatError::ArithmeticOverflow))?;
        self.current_record_bytes_read = self
            .current_record_bytes_read
            .checked_add(bytes_read)
            .ok_or_else(|| invalid_data(PackageFormatError::ArithmeticOverflow))?;
        self.output_bytes_read = self
            .output_bytes_read
            .checked_add(bytes_read)
            .ok_or_else(|| invalid_data(PackageFormatError::ArithmeticOverflow))?;
        if self.current_record_bytes_read == record.byte_length() {
            self.current_record = None;
            self.current_record_bytes_read = 0;
        }
        usize::try_from(bytes_read)
            .map_err(|_| invalid_data(PackageFormatError::ArithmeticOverflow))
    }
}

impl Read for ValidatedPackageReader<'_> {
    /// Returns each successful backing read before attempting more fallible I/O.
    fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
        if output.is_empty() {
            return Ok(0);
        }
        if self.current_record.is_none() && !self.load_next_record()? {
            return Ok(0);
        }
        self.read_current_record(output)
    }
}

/// Writes one package table followed by its exact payload.
pub struct PackageEncoder<Writer> {
    writer: Writer,
    header: PackageHeader,
    validator: Option<PackageValidator>,
    payload_bytes_written: u64,
    records_finished: bool,
}

impl<Writer> PackageEncoder<Writer>
where
    Writer: Write,
{
    /// Starts one exact package and writes its fixed header.
    ///
    /// # Errors
    ///
    /// Returns an error when writing the header fails.
    pub fn new(mut writer: Writer, header: PackageHeader) -> Result<Self, PackageWriteError> {
        writer.write_all(&header.encode())?;
        Ok(Self {
            writer,
            header,
            validator: Some(PackageValidator::new(
                header,
                header.package_bytes,
                header.output_bytes,
                header.source_bytes(),
            )?),
            payload_bytes_written: 0,
            records_finished: false,
        })
    }

    /// Writes one ordered source record.
    ///
    /// # Errors
    ///
    /// Returns an error when the record is invalid, out of sequence, or cannot
    /// be written.
    pub fn write_source(&mut self, offset: u64, byte_length: u64) -> Result<(), PackageWriteError> {
        self.write_record(PackageRecord::source(offset, byte_length)?)
    }

    /// Writes one ordered payload record.
    ///
    /// # Errors
    ///
    /// Returns an error when the record is invalid, out of sequence, or cannot
    /// be written.
    pub fn write_payload(
        &mut self,
        offset: u64,
        byte_length: u64,
    ) -> Result<(), PackageWriteError> {
        self.write_record(PackageRecord::payload(offset, byte_length)?)
    }

    /// Writes the next canonical payload record.
    ///
    /// # Errors
    ///
    /// Returns an error when the record is invalid, out of sequence, or cannot
    /// be written.
    pub fn write_payload_record(&mut self, byte_length: u64) -> Result<(), PackageWriteError> {
        let offset = self
            .validator
            .as_ref()
            .ok_or(PackageFormatError::State)?
            .observed_payload_bytes;
        self.write_payload(offset, byte_length)
    }

    /// Finishes the record table before payload bytes begin.
    ///
    /// # Errors
    ///
    /// Returns an error unless the complete canonical table has been written.
    pub fn finish_records(&mut self) -> Result<(), PackageWriteError> {
        if self.records_finished {
            return Err(PackageFormatError::State.into());
        }
        let validator = self.validator.take().ok_or(PackageFormatError::State)?;
        validator.finish()?;
        self.records_finished = true;
        Ok(())
    }

    /// Finishes exact payload output and returns the wrapped writer.
    ///
    /// # Errors
    ///
    /// Returns an error unless records and every declared payload byte have
    /// been written.
    pub fn finish(self) -> Result<Writer, PackageWriteError> {
        if !self.records_finished || self.validator.is_some() {
            return Err(PackageFormatError::State.into());
        }
        if self.payload_bytes_written != self.header.payload_bytes {
            return Err(PackageFormatError::PayloadCoverage.into());
        }
        Ok(self.writer)
    }

    /// Writes one validated table record.
    fn write_record(&mut self, record: PackageRecord) -> Result<(), PackageWriteError> {
        if self.records_finished {
            return Err(PackageFormatError::State.into());
        }
        self.validator
            .as_mut()
            .ok_or(PackageFormatError::State)?
            .push(record)?;
        self.writer.write_all(&record.encode())?;
        Ok(())
    }
}

impl<Writer> Write for PackageEncoder<Writer>
where
    Writer: Write,
{
    /// Writes payload bytes after the complete record table.
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if !self.records_finished {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                PackageFormatError::State,
            ));
        }
        let byte_length = u64::try_from(bytes.len()).map_err(|_| {
            std::io::Error::new(
                ErrorKind::InvalidInput,
                PackageFormatError::ArithmeticOverflow,
            )
        })?;
        let written_end = self
            .payload_bytes_written
            .checked_add(byte_length)
            .ok_or_else(|| {
                std::io::Error::new(
                    ErrorKind::InvalidInput,
                    PackageFormatError::ArithmeticOverflow,
                )
            })?;
        if written_end > self.header.payload_bytes {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                PackageFormatError::PayloadCoverage,
            ));
        }
        let written_bytes = self.writer.write(bytes)?;
        self.payload_bytes_written = self
            .payload_bytes_written
            .checked_add(u64::try_from(written_bytes).map_err(|_| {
                std::io::Error::new(
                    ErrorKind::InvalidData,
                    PackageFormatError::ArithmeticOverflow,
                )
            })?)
            .ok_or_else(|| {
                std::io::Error::new(
                    ErrorKind::InvalidData,
                    PackageFormatError::ArithmeticOverflow,
                )
            })?;
        Ok(written_bytes)
    }

    /// Flushes the wrapped output.
    fn flush(&mut self) -> std::io::Result<()> {
        self.writer.flush()
    }
}

/// Decodes one fixed little-endian unsigned integer.
fn decode_u32(bytes: &[u8], offset: usize) -> u32 {
    let end = offset + size_of::<u32>();
    u32::from_le_bytes(
        bytes[offset..end]
            .try_into()
            .expect("u32 package field must have fixed width"),
    )
}

/// Decodes one fixed little-endian unsigned integer.
fn decode_u64(bytes: &[u8], offset: usize) -> u64 {
    let end = offset + size_of::<u64>();
    u64::from_le_bytes(
        bytes[offset..end]
            .try_into()
            .expect("u64 package field must have fixed width"),
    )
}

/// Encodes one fixed little-endian unsigned integer.
fn encode_u32(bytes: &mut [u8], offset: usize, value: u32) {
    let end = offset + size_of::<u32>();
    bytes[offset..end].copy_from_slice(&value.to_le_bytes());
}

/// Encodes one fixed little-endian unsigned integer.
fn encode_u64(bytes: &mut [u8], offset: usize, value: u64) {
    let end = offset + size_of::<u64>();
    bytes[offset..end].copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    //! Verifies canonical package encoding and fixed-memory validation.

    use std::cell::Cell;
    use std::io::{ErrorKind, Read, Write};

    use super::{
        FLAG_SOURCE_PRESENT, MAX_OUTPUT_BYTES, PACKAGE_HEADER_BYTES, PACKAGE_MAGIC,
        PACKAGE_VERSION, PackageEncoder, PackageFormatError, PackageHeader, PackageReadError,
        PackageRecord, PackageValidator, RECORD_HEADER_BYTES, ReadAt, ValidatedPackageReader,
        validate_package,
    };

    /// Interrupts one source read after returning its first byte.
    struct InterruptedSource {
        interrupted: Cell<bool>,
    }

    impl ReadAt for InterruptedSource {
        fn byte_length(&self) -> std::io::Result<u64> {
            Ok(2)
        }

        fn read_at(&self, bytes: &mut [u8], offset: u64) -> std::io::Result<usize> {
            if offset == 1 && !self.interrupted.replace(true) {
                return Err(std::io::Error::from(ErrorKind::Interrupted));
            }
            let read_capacity = bytes.len().min(1);
            b"ab".read_at(&mut bytes[..read_capacity], offset)
        }
    }

    /// Verifies a retried interruption never skips successfully consumed bytes.
    #[test]
    fn preserves_partial_reads_before_interruption() {
        let source = InterruptedSource {
            interrupted: Cell::new(false),
        };
        let header = PackageHeader::new(1, 2, 0, Some(2)).expect("source header should be valid");
        let mut encoder =
            PackageEncoder::new(Vec::new(), header).expect("package encoder should start");
        encoder
            .write_source(0, 2)
            .expect("source record should write");
        encoder
            .finish_records()
            .expect("record table should finish");
        let package = encoder.finish().expect("package should finish");
        let mut reader = ValidatedPackageReader::new(&package, Some(&source))
            .expect("source package should validate");
        let mut reconstructed = Vec::new();

        reader
            .read_to_end(&mut reconstructed)
            .expect("interrupted read should retry");

        assert!(source.interrupted.get());
        assert_eq!(reconstructed, b"ab");
        assert_eq!(reader.remaining_bytes(), 0);
    }

    /// Verifies one mixed package encodes, validates, and reconstructs exactly.
    #[test]
    fn encodes_validates_and_reads_mixed_package() {
        const READ_BUFFER_BYTES: usize = 2;

        let source = *b"0123456789abcdef";
        let header =
            PackageHeader::new(3, 7, 2, Some(16)).expect("mixed package header should be valid");
        let mut package = Vec::new();
        let mut encoder =
            PackageEncoder::new(&mut package, header).expect("package encoder should start");
        encoder
            .write_source(2, 3)
            .expect("first source record should write");
        encoder
            .write_payload(0, 2)
            .expect("payload record should write");
        encoder
            .write_source(8, 2)
            .expect("last source record should write");
        encoder
            .finish_records()
            .expect("complete record table should finish");
        encoder
            .write_all(b"xy")
            .expect("declared payload should write");
        encoder.finish().expect("complete package should finish");

        assert_eq!(
            package.len(),
            usize::try_from(header.package_bytes()).expect("package byte count should fit usize")
        );
        assert_eq!(
            validate_package(&package, Some(&source))
                .expect("mixed package should validate")
                .header(),
            header,
        );
        let mut reader = ValidatedPackageReader::new(&package, Some(&source))
            .expect("mixed package reader should start");
        let mut reconstructed = Vec::new();
        let mut buffer = [0_u8; READ_BUFFER_BYTES];
        loop {
            let bytes_read = reader
                .read(&mut buffer)
                .expect("mixed package should remain readable");
            if bytes_read == 0 {
                break;
            }
            reconstructed.extend_from_slice(&buffer[..bytes_read]);
        }

        assert_eq!(reconstructed, b"234xy89");
        assert_eq!(reader.remaining_bytes(), 0);
    }

    /// Verifies a source-only package reconstructs without payload bytes.
    #[test]
    fn validates_and_reads_source_only_package() {
        let source = *b"immutable source";
        let source_bytes =
            u64::try_from(source.len()).expect("source-only byte length should fit u64");
        let header = PackageHeader::new(1, 6, 0, Some(source_bytes))
            .expect("source-only package header should be valid");
        let mut package = Vec::new();
        let mut encoder =
            PackageEncoder::new(&mut package, header).expect("package encoder should start");
        encoder
            .write_source(4, 6)
            .expect("source-only record should write");
        encoder
            .finish_records()
            .expect("source-only record table should finish");
        encoder.finish().expect("source-only package should finish");
        let mut reader = ValidatedPackageReader::new(&package, Some(&source))
            .expect("source-only package should validate");
        let mut reconstructed = Vec::new();

        reader
            .read_to_end(&mut reconstructed)
            .expect("source-only package should remain readable");

        assert_eq!(reconstructed, b"table ");
        assert_eq!(reader.header(), header);
    }

    /// Verifies header identity and reserved fields reject mutation.
    #[test]
    fn rejects_noncanonical_header_fields() {
        let header = PackageHeader::new(1, 1, 1, None).expect("header should be valid");
        let mut bytes = header.encode();
        bytes[0] ^= 1;
        assert_eq!(
            PackageHeader::decode(&bytes),
            Err(PackageFormatError::Magic)
        );

        bytes = header.encode();
        bytes[8..12].copy_from_slice(&(PACKAGE_VERSION + 1).to_le_bytes());
        assert_eq!(
            PackageHeader::decode(&bytes),
            Err(PackageFormatError::Version)
        );

        bytes = header.encode();
        bytes[16..20].copy_from_slice(&(FLAG_SOURCE_PRESENT << 1).to_le_bytes());
        assert_eq!(
            PackageHeader::decode(&bytes),
            Err(PackageFormatError::Flags)
        );

        bytes = header.encode();
        bytes[20] = 1;
        assert_eq!(
            PackageHeader::decode(&bytes),
            Err(PackageFormatError::Reserved)
        );
        assert_eq!(
            PackageHeader::new(1, 1, 0, Some(MAX_OUTPUT_BYTES + 1)),
            Err(PackageFormatError::SourceLength)
        );
        assert_eq!(
            PackageHeader::new(1, 1, 1, Some(1)),
            Err(PackageFormatError::SourceLength)
        );
        assert_eq!(
            PackageHeader::new(1, 1, 0, None),
            Err(PackageFormatError::SourceLength)
        );
        assert_eq!(
            PackageHeader::new(0, 0, 0, Some(1)),
            Err(PackageFormatError::SourceLength)
        );
        assert_eq!(&header.encode()[..PACKAGE_MAGIC.len()], &PACKAGE_MAGIC);
    }

    /// Verifies records reject unknown, empty, reserved, and overflowing values.
    #[test]
    fn rejects_malformed_records() {
        let mut bytes = PackageRecord::payload(0, 1)
            .expect("payload record should be valid")
            .encode();
        bytes[4] = 1;
        assert_eq!(
            PackageRecord::decode(&bytes),
            Err(PackageFormatError::Reserved)
        );

        bytes = [0; RECORD_HEADER_BYTES];
        bytes[..4].copy_from_slice(&9_u32.to_le_bytes());
        bytes[16..24].copy_from_slice(&1_u64.to_le_bytes());
        assert_eq!(
            PackageRecord::decode(&bytes),
            Err(PackageFormatError::RecordKind)
        );
        assert_eq!(
            PackageRecord::source(0, 0),
            Err(PackageFormatError::EmptyRecord)
        );
        assert_eq!(
            PackageRecord::source(u64::MAX, 1),
            Err(PackageFormatError::RecordRange)
        );
    }

    /// Verifies incremental validation requires exact payload and output coverage.
    #[test]
    fn rejects_inexact_record_coverage() {
        let header = PackageHeader::new(2, 4, 2, Some(8)).expect("mixed header should be valid");
        let mut validator = PackageValidator::new(header, header.package_bytes(), 4, Some(8))
            .expect("descriptor bundle should be valid");
        validator
            .push(PackageRecord::source(0, 2).expect("source record should be valid"))
            .expect("source record should validate");
        assert_eq!(
            validator.push(PackageRecord::payload(1, 2).expect("payload record should be valid")),
            Err(PackageFormatError::PayloadCoverage)
        );

        assert_eq!(
            PackageValidator::new(header, header.package_bytes(), 4, None).map(drop),
            Err(PackageFormatError::DescriptorBundle)
        );
    }

    /// Verifies the canonical empty package encodes and reads no output.
    #[test]
    fn accepts_canonical_empty_package() {
        let header = PackageHeader::new(0, 0, 0, None).expect("empty header should be valid");
        let mut package = Vec::new();
        let mut encoder =
            PackageEncoder::new(&mut package, header).expect("empty package encoder should start");
        encoder
            .finish_records()
            .expect("empty record table should finish");
        encoder.finish().expect("empty package should finish");
        assert_eq!(header.package_bytes(), PACKAGE_HEADER_BYTES as u64);
        assert_eq!(
            package.len(),
            usize::try_from(header.package_bytes()).expect("empty package length should fit usize"),
        );
        let mut reader =
            ValidatedPackageReader::new(&package, None).expect("empty package should validate");
        let mut reconstructed = Vec::new();

        reader
            .read_to_end(&mut reconstructed)
            .expect("empty package should remain readable");

        assert!(reconstructed.is_empty());
        assert_eq!(reader.header(), header);
        assert_eq!(reader.remaining_bytes(), 0);
    }

    /// Verifies truncated package and source backings fail validation.
    #[test]
    fn rejects_truncated_package_and_source_backing() {
        let payload_header =
            PackageHeader::new(1, 4, 4, None).expect("payload header should be valid");
        let mut complete_package = Vec::new();
        let mut payload_encoder = PackageEncoder::new(&mut complete_package, payload_header)
            .expect("payload package encoder should start");
        payload_encoder
            .write_payload_record(4)
            .expect("payload record should write");
        payload_encoder
            .finish_records()
            .expect("payload record table should finish");
        payload_encoder
            .write_all(b"text")
            .expect("payload bytes should write");
        payload_encoder
            .finish()
            .expect("payload package should finish");
        let truncated_package = complete_package[..complete_package.len() - 1].to_vec();

        assert!(matches!(
            validate_package(&truncated_package, None),
            Err(PackageReadError::Format(PackageFormatError::PackageLength))
        ));

        let complete_source = *b"source";
        let source_header =
            PackageHeader::new(1, 6, 0, Some(6)).expect("source package header should be valid");
        let mut source_package = Vec::new();
        let mut source_encoder = PackageEncoder::new(&mut source_package, source_header)
            .expect("source package encoder should start");
        source_encoder
            .write_source(0, 6)
            .expect("source record should write");
        source_encoder
            .finish_records()
            .expect("source record table should finish");
        source_encoder
            .finish()
            .expect("source package should finish");
        let truncated_source = complete_source[..complete_source.len() - 1].to_vec();

        assert!(matches!(
            validate_package(&source_package, Some(&truncated_source)),
            Err(PackageReadError::Format(
                PackageFormatError::DescriptorBundle
            ))
        ));
    }
}
