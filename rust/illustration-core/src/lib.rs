//! Closed, bounded drawing packets. No fonts, URLs, scripts, images, or SVG cross this boundary.

#![forbid(unsafe_code)]

use std::fmt;

use tiny_skia_path::{Path, PathSegment};

/// Maximum encoded packet for one illustration.
pub const MAX_PACKET_BYTES: usize = 512 * 1024;
/// Maximum filled paths for one illustration.
pub const MAX_PATHS: usize = 2_048;
/// Maximum combined path opcodes and coordinates for one illustration.
pub const MAX_COMPONENTS: usize = 100_000;
/// Maximum absolute coordinate or drawing dimension.
pub const MAX_COORDINATE: f64 = 8_192.0;
/// Bytes before the first path.
pub const HEADER_BYTES: usize = 48;
/// Maximum authored accessibility title, encoded as plain UTF-8.
pub const MAX_TITLE_BYTES: usize = 1_024;
/// Maximum authored accessibility description, encoded as plain UTF-8.
pub const MAX_DESCRIPTION_BYTES: usize = 4_096;
/// Bytes before a path's command components.
pub const PATH_HEADER_BYTES: usize = 16;
/// Maximum intersecting clips on one path; all share the aggregate command budget.
pub const MAX_PATH_CLIPS: usize = 8;
/// Bytes before one clip's path components.
pub const CLIP_HEADER_BYTES: usize = 8;
/// Foreground supplied by the screen or paper renderer.
pub const INK: u32 = 0;
/// Background supplied by the screen or paper renderer.
pub const SURFACE: u32 = 1;
/// Accent supplied by the screen or paper renderer.
pub const ACCENT: u32 = 2;
/// Quiet accent container supplied by the screen or paper renderer.
pub const TONE: u32 = 3;

/// A drawing cannot be represented faithfully within the native boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DrawingError {
    /// Input is outside the supported syntax or font repertoire.
    Unsupported,
    /// An input or output budget was exceeded.
    Limit,
    /// Geometry, commands, or packet structure are invalid.
    Invalid,
    /// A renderer reached its host-owned cooperative deadline.
    TimedOut,
}

impl fmt::Display for DrawingError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Unsupported => "unsupported native illustration",
            Self::Limit => "native illustration exceeds its resource limit",
            Self::Invalid => "invalid native illustration",
            Self::TimedOut => "native illustration exceeded its time limit",
        })
    }
}

impl std::error::Error for DrawingError {}

/// One validated filled path. Coordinates are already in drawing-local space.
#[derive(Clone, Debug)]
pub struct FilledPath {
    color: u32,
    even_odd: bool,
    components: Vec<f32>,
    clips: Vec<PathClip>,
}

/// One filled clip path, intersected with every preceding clip and the illustration bounds.
#[derive(Clone, Debug)]
pub struct PathClip {
    even_odd: bool,
    components: Vec<f32>,
}

impl PathClip {
    /// Converts a clip without admitting resources, transforms, or unbounded command streams.
    ///
    /// # Errors
    /// Rejects the same invalid coordinates/commands as visible filled paths.
    pub fn from_path(even_odd: bool, path: &Path) -> Result<Self, DrawingError> {
        let path = FilledPath::from_path(INK, even_odd, path)?;
        Ok(Self {
            even_odd,
            components: path.components,
        })
    }
}

impl FilledPath {
    /// Validates the complete command grammar before accepting one filled path.
    ///
    /// # Errors
    /// Returns an error for invalid colors, commands, coordinates, or resource use.
    pub fn new(color: u32, even_odd: bool, components: Vec<f32>) -> Result<Self, DrawingError> {
        if color > TONE && color >> 24 == 0 {
            return Err(DrawingError::Invalid);
        }
        validate_components(&components)?;
        Ok(Self {
            color,
            even_odd,
            components,
            clips: Vec::new(),
        })
    }

    /// Attaches an explicitly bounded intersection of already validated clips.
    ///
    /// # Errors
    /// Rejects excessive nesting or cumulative commands before publication.
    pub fn with_clips(mut self, clips: &[PathClip]) -> Result<Self, DrawingError> {
        if clips.len() > MAX_PATH_CLIPS
            || self.components.len()
                + clips
                    .iter()
                    .map(|clip| clip.components.len())
                    .sum::<usize>()
                > MAX_COMPONENTS
        {
            return Err(DrawingError::Limit);
        }
        self.clips = clips.to_vec();
        Ok(self)
    }

    /// Converts a checked native path into the closed packet grammar.
    ///
    /// # Errors
    /// Returns an error when any coordinate or command exceeds the boundary.
    pub fn from_path(color: u32, even_odd: bool, path: &Path) -> Result<Self, DrawingError> {
        let mut components = Vec::new();
        for segment in path.segments() {
            let values: &[f32] = match segment {
                PathSegment::MoveTo(point) => &[0.0, point.x, point.y],
                PathSegment::LineTo(point) => &[1.0, point.x, point.y],
                PathSegment::QuadTo(control, end) => &[2.0, control.x, control.y, end.x, end.y],
                PathSegment::CubicTo(first, second, end) => {
                    &[3.0, first.x, first.y, second.x, second.y, end.x, end.y]
                }
                PathSegment::Close => &[4.0],
            };
            if components.len() + values.len() > MAX_COMPONENTS {
                return Err(DrawingError::Limit);
            }
            components.extend_from_slice(values);
        }
        Self::new(color, even_odd, components)
    }
}

/// A bounded vector appearance with a baseline for inline placement.
pub struct Drawing {
    width: f32,
    height: f32,
    baseline: f32,
    paths: Vec<FilledPath>,
    components: usize,
    clips: usize,
    packet_bytes: usize,
    title: String,
    description: String,
}

impl Drawing {
    /// Creates an empty drawing with finite positive dimensions.
    ///
    /// # Errors
    /// Returns an error for invalid dimensions or an out-of-bounds baseline.
    pub fn new(width: f64, height: f64, baseline: f64) -> Result<Self, DrawingError> {
        if width <= 0.0 || height <= 0.0 || baseline < 0.0 || baseline > height {
            return Err(DrawingError::Invalid);
        }
        Ok(Self {
            width: coordinate(width)?,
            height: coordinate(height)?,
            baseline: coordinate(baseline)?,
            paths: Vec::new(),
            components: 0,
            clips: 0,
            packet_bytes: HEADER_BYTES,
            title: String::new(),
            description: String::new(),
        })
    }

    /// Attaches bounded authored alternatives without admitting markup or capabilities.
    ///
    /// # Errors
    /// Rejects excessive metadata before copying it into the retained drawing.
    pub fn describe(&mut self, title: &str, description: &str) -> Result<(), DrawingError> {
        if title.len() > MAX_TITLE_BYTES || description.len() > MAX_DESCRIPTION_BYTES {
            return Err(DrawingError::Limit);
        }
        let bytes = self.packet_bytes - self.title.len() - self.description.len()
            + title.len()
            + description.len();
        if bytes > MAX_PACKET_BYTES {
            return Err(DrawingError::Limit);
        }
        title.clone_into(&mut self.title);
        description.clone_into(&mut self.description);
        self.packet_bytes = bytes;
        Ok(())
    }

    /// Admits one path without letting aggregate output grow past the boundary.
    ///
    /// # Errors
    /// Returns an error before publishing a path that would exceed a drawing budget.
    pub fn push(&mut self, path: FilledPath) -> Result<(), DrawingError> {
        let path_components = path.components.len()
            + path
                .clips
                .iter()
                .map(|clip| clip.components.len())
                .sum::<usize>();
        let components = self.components + path_components;
        let bytes = self.packet_bytes
            + PATH_HEADER_BYTES
            + path_components * 4
            + path.clips.len() * CLIP_HEADER_BYTES;
        if self.paths.len() >= MAX_PATHS || components > MAX_COMPONENTS || bytes > MAX_PACKET_BYTES
        {
            return Err(DrawingError::Limit);
        }
        self.clips += path.clips.len();
        self.paths.push(path);
        self.components = components;
        self.packet_bytes = bytes;
        Ok(())
    }

    /// Encodes one exact version-three packet, without padding or trailing data.
    ///
    /// # Errors
    /// Returns an error if an encoded integer cannot represent the bounded output.
    pub fn encode(&self) -> Result<Vec<u8>, DrawingError> {
        let mut out = Vec::with_capacity(self.packet_bytes);
        out.extend_from_slice(b"BTXTILL3");
        for value in [
            3,
            packet_integer(self.packet_bytes)?,
            packet_integer(self.paths.len())?,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in [self.width, self.height, self.baseline] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&packet_integer(self.components)?.to_le_bytes());
        out.extend_from_slice(&packet_integer(self.clips)?.to_le_bytes());
        out.extend_from_slice(&packet_integer(self.title.len())?.to_le_bytes());
        out.extend_from_slice(&packet_integer(self.description.len())?.to_le_bytes());
        out.extend_from_slice(self.title.as_bytes());
        out.extend_from_slice(self.description.as_bytes());
        for path in &self.paths {
            for value in [
                path.color,
                u32::from(path.even_odd),
                packet_integer(path.components.len())?,
                packet_integer(path.clips.len())?,
            ] {
                out.extend_from_slice(&value.to_le_bytes());
            }
            for component in &path.components {
                out.extend_from_slice(&component.to_le_bytes());
            }
            for clip in &path.clips {
                out.extend_from_slice(&u32::from(clip.even_odd).to_le_bytes());
                out.extend_from_slice(&packet_integer(clip.components.len())?.to_le_bytes());
                for component in &clip.components {
                    out.extend_from_slice(&component.to_le_bytes());
                }
            }
        }
        Ok(out)
    }
}

fn packet_integer(value: usize) -> Result<u32, DrawingError> {
    u32::try_from(value).map_err(|_| DrawingError::Limit)
}

/// Narrows a finite, bounded coordinate to the packet's explicitly specified float precision.
///
/// # Errors
/// Rejects non-finite numbers and values outside the coordinate budget.
#[allow(clippy::cast_possible_truncation)] // The wire format deliberately uses bounded IEEE f32.
pub fn coordinate(value: f64) -> Result<f32, DrawingError> {
    if !value.is_finite() || value.abs() > MAX_COORDINATE {
        return Err(DrawingError::Invalid);
    }
    Ok(value as f32)
}

/// Validates every opcode and its arity, including subpath ownership.
///
/// # Errors
/// Returns an error for malformed commands or exceeded component/coordinate limits.
pub fn validate_components(components: &[f32]) -> Result<(), DrawingError> {
    if components.is_empty() || components.len() > MAX_COMPONENTS {
        return Err(DrawingError::Limit);
    }
    let mut offset = 0;
    let mut has_point = false;
    while offset < components.len() {
        let opcode = components[offset].to_bits();
        let arity = match opcode {
            0x0000_0000 => {
                has_point = true;
                2
            }
            0x3f80_0000 if has_point => 2,
            0x4000_0000 if has_point => 4,
            0x4040_0000 if has_point => 6,
            0x4080_0000 if has_point => {
                has_point = false;
                0
            }
            _ => return Err(DrawingError::Invalid),
        };
        offset += 1;
        if offset + arity > components.len() {
            return Err(DrawingError::Invalid);
        }
        for value in &components[offset..offset + arity] {
            coordinate(f64::from(*value))?;
        }
        offset += arity;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_command_grammar_and_geometry() {
        for components in [
            vec![1.0, 0.0, 0.0],
            vec![0.0, 2.0],
            vec![4.0],
            vec![0.0, f32::NAN, 0.0],
            vec![0.0, 9_000.0, 0.0],
            vec![0.0, 0.0, 0.0, 4.0, 4.0],
        ] {
            assert!(validate_components(&components).is_err());
        }
        assert!(Drawing::new(f64::INFINITY, 1.0, 0.0).is_err());
        assert!(Drawing::new(1.0, 1.0, 2.0).is_err());
        assert!(Drawing::new(0.0, 1.0, 0.0).is_err());
    }

    #[test]
    fn packet_has_exact_accounting_and_no_source_content() {
        let mut drawing = Drawing::new(3.0, 2.0, 1.5).unwrap();
        drawing
            .push(FilledPath::new(INK, false, vec![0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 4.0]).unwrap())
            .unwrap();
        let packet = drawing.encode().unwrap();
        assert_eq!(packet.len(), HEADER_BYTES + PATH_HEADER_BYTES + 7 * 4);
        assert_eq!(&packet[..8], b"BTXTILL3");
        assert_eq!(
            u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize,
            packet.len()
        );
    }

    #[test]
    fn accounts_clips_within_the_same_packet_and_command_limits() {
        let path = tiny_skia_path::PathBuilder::from_rect(
            tiny_skia_path::Rect::from_xywh(0.0, 0.0, 2.0, 1.0).unwrap(),
        );
        let clip = PathClip::from_path(false, &path).unwrap();
        let filled = FilledPath::from_path(INK, false, &path).unwrap();
        assert_eq!(
            filled
                .clone()
                .with_clips(&vec![clip.clone(); MAX_PATH_CLIPS + 1])
                .unwrap_err(),
            DrawingError::Limit
        );
        let mut drawing = Drawing::new(2.0, 1.0, 0.5).unwrap();
        drawing.push(filled.with_clips(&[clip]).unwrap()).unwrap();
        let packet = drawing.encode().unwrap();
        assert_eq!(packet.len(), drawing.packet_bytes);
        assert_eq!(u32::from_le_bytes(packet[36..40].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(packet[60..64].try_into().unwrap()), 1);
    }

    #[test]
    fn bounds_authored_alternatives_and_accounts_replacement_exactly() {
        let mut drawing = Drawing::new(1.0, 1.0, 0.0).unwrap();
        drawing
            .describe("Décisions", "Read → refine → share")
            .unwrap();
        let before = drawing.encode().unwrap();
        assert_eq!(
            before.len(),
            HEADER_BYTES + "DécisionsRead → refine → share".len()
        );
        assert_eq!(
            &before[HEADER_BYTES..],
            "DécisionsRead → refine → share".as_bytes()
        );
        assert_eq!(
            drawing.describe(&"é".repeat(MAX_TITLE_BYTES), ""),
            Err(DrawingError::Limit)
        );
        assert_eq!(
            drawing.describe("", &"x".repeat(MAX_DESCRIPTION_BYTES + 1)),
            Err(DrawingError::Limit)
        );
        assert_eq!(drawing.encode().unwrap(), before);
        drawing.describe("", "").unwrap();
        assert_eq!(drawing.encode().unwrap().len(), HEADER_BYTES);
    }

    #[test]
    fn aggregate_admission_does_not_publish_an_over_budget_path() {
        let mut drawing = Drawing::new(1.0, 1.0, 0.5).unwrap();
        let path = FilledPath::new(INK, false, vec![0.0, 0.0, 0.0, 4.0]).unwrap();
        for _ in 0..MAX_PATHS {
            drawing.push(path.clone()).unwrap();
        }
        let before = drawing.encode().unwrap();
        assert_eq!(drawing.push(path), Err(DrawingError::Limit));
        assert_eq!(drawing.encode().unwrap(), before);
    }
}
