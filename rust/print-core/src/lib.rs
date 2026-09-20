//! Bounded outlines from Android-selected platform fonts for streamed vector printing.
//!
//! Android retains ownership of shaping, fallback, glyph positions, and text selection.
//! No font program supplied by a document is accepted by the Android bridge.

#![forbid(unsafe_code)]

use std::fmt;

use skrifa::instance::Size;
use skrifa::outline::{DrawSettings, Hinting, OutlinePen};
use skrifa::raw::TableProvider;
use skrifa::{FontRef, GlyphId, MetadataProvider, Tag};

/// Maximum mapped platform font accepted by the boundary.
pub const MAX_FONT_BYTES: usize = 64 * 1024 * 1024;
/// Maximum variation axes accepted for one platform font instance.
pub const MAX_FONT_AXES: usize = 64;
/// Maximum command and coordinate components in one glyph outline.
pub const MAX_OUTLINE_COMPONENTS: usize = 32 * 1024;
/// Output coordinates use this em size and Android's downward-positive y axis.
pub const OUTLINE_EM_SIZE: f32 = 1000.0;
const MAX_OUTLINE_SCRATCH_BYTES: usize = 4 * 1024 * 1024;
const MAX_OUTLINE_COORDINATE: f32 = 1_000_000.0;

#[derive(Clone, Copy)]
enum Command {
    Move = 0,
    Line = 1,
    Quadratic = 2,
    Cubic = 3,
    Close = 4,
}

/// A platform font could not be rendered within the print boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutlineError {
    /// Font data or outline work exceeded a fixed resource limit.
    Limit,
    /// Font bytes, face index, glyph index, or variations were invalid.
    InvalidFont,
    /// A non-bitmap glyph could not provide an outline.
    UnsupportedOutline,
}

impl fmt::Display for OutlineError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::Limit => "print font outline exceeds its resource limit",
            Self::InvalidFont => "invalid platform print font",
            Self::UnsupportedOutline => "platform print font outline is unavailable",
        })
    }
}

impl std::error::Error for OutlineError {}

#[derive(Default)]
struct BoundedOutline {
    components: Vec<f32>,
    rejected: bool,
}

impl BoundedOutline {
    fn append(&mut self, command: Command, coordinates: &[f32]) {
        if self.rejected {
            return;
        }
        if self.components.len() + 1 + coordinates.len() > MAX_OUTLINE_COMPONENTS
            || coordinates
                .iter()
                .any(|value| !value.is_finite() || value.abs() > MAX_OUTLINE_COORDINATE)
        {
            self.rejected = true;
            return;
        }
        self.components.push(f32::from(command as u8));
        self.components.extend_from_slice(coordinates);
    }
}

impl OutlinePen for BoundedOutline {
    fn move_to(&mut self, x: f32, y: f32) {
        self.append(Command::Move, &[x, -y]);
    }

    fn line_to(&mut self, x: f32, y: f32) {
        self.append(Command::Line, &[x, -y]);
    }

    fn quad_to(&mut self, cx: f32, cy: f32, x: f32, y: f32) {
        self.append(Command::Quadratic, &[cx, -cy, x, -y]);
    }

    fn curve_to(&mut self, cx0: f32, cy0: f32, cx1: f32, cy1: f32, x: f32, y: f32) {
        self.append(Command::Cubic, &[cx0, -cy0, cx1, -cy1, x, -y]);
    }

    fn close(&mut self) {
        self.append(Command::Close, &[]);
    }
}

/// Extracts one vector glyph without retaining any borrowed font bytes.
///
/// `None` means Android must rasterize this color/bitmap font locally. An empty
/// outline is valid for whitespace. Commands are move(0), line(1), quad(2),
/// cubic(3), and close(4), followed by 2, 2, 4, 6, or 0 coordinates respectively.
///
/// # Errors
/// Returns an error for invalid fonts, unsupported outlines, or exceeded budgets.
pub fn glyph_outline(
    data: &[u8],
    face_index: u32,
    glyph_id: u32,
    variations: &[(Tag, f32)],
) -> Result<Option<Vec<f32>>, OutlineError> {
    if data.len() > MAX_FONT_BYTES || variations.len() > MAX_FONT_AXES {
        return Err(OutlineError::Limit);
    }
    if variations
        .iter()
        .any(|(_, value)| !value.is_finite() || value.abs() > MAX_OUTLINE_COORDINATE)
    {
        return Err(OutlineError::InvalidFont);
    }
    let font = FontRef::from_index(data, face_index).map_err(|_| OutlineError::InvalidFont)?;
    let glyph_count = font
        .maxp()
        .map_err(|_| OutlineError::InvalidFont)?
        .num_glyphs();
    if glyph_id >= u32::from(glyph_count) {
        return Err(OutlineError::InvalidFont);
    }
    if [b"CBDT", b"sbix", b"COLR", b"SVG "]
        .iter()
        .any(|bytes| font.data_for_tag(Tag::new(bytes)).is_some())
    {
        return Ok(None);
    }
    let location = font.axes().location(variations.iter().copied());
    let glyphs = font.outline_glyphs();
    let glyph = glyphs
        .get(GlyphId::new(glyph_id))
        .ok_or(OutlineError::UnsupportedOutline)?;
    let scratch_size = glyph.draw_memory_size(Hinting::None);
    if scratch_size > MAX_OUTLINE_SCRATCH_BYTES {
        return Err(OutlineError::Limit);
    }
    let mut scratch = vec![0; scratch_size];
    let settings = DrawSettings::unhinted(Size::new(OUTLINE_EM_SIZE), &location)
        .with_memory(Some(&mut scratch));
    let mut outline = BoundedOutline::default();
    glyph
        .draw(settings, &mut outline)
        .map_err(|_| OutlineError::UnsupportedOutline)?;
    if outline.rejected {
        return Err(OutlineError::Limit);
    }
    Ok(Some(outline.components))
}

/// Converts the four-byte OpenType axis tag used at the JNI boundary.
#[must_use]
pub fn variation_tag(bytes: [u8; 4]) -> Tag {
    Tag::new(&bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_fonts_and_variations() {
        assert_eq!(
            glyph_outline(&[], 0, 0, &[]),
            Err(OutlineError::InvalidFont)
        );
        assert_eq!(
            glyph_outline(&[], 0, 0, &[(Tag::new(b"wght"), f32::NAN)]),
            Err(OutlineError::InvalidFont)
        );
        assert_eq!(
            glyph_outline(&[], 0, 0, &vec![(Tag::new(b"wght"), 400.0); 65]),
            Err(OutlineError::Limit)
        );
    }

    #[test]
    fn outline_uses_android_coordinates_and_rejects_nonfinite_values() {
        let mut outline = BoundedOutline::default();
        outline.move_to(3.0, 4.0);
        assert_eq!(outline.components, vec![0.0, 3.0, -4.0]);
        outline.line_to(f32::INFINITY, 0.0);
        outline.close();
        assert!(outline.rejected);
        assert_eq!(outline.components.len(), 3);
    }

    #[test]
    fn outline_budget_does_not_grow_after_rejection() {
        let mut outline = BoundedOutline::default();
        for _ in 0..=MAX_OUTLINE_COMPONENTS {
            outline.close();
        }
        assert!(outline.rejected);
        assert_eq!(outline.components.len(), MAX_OUTLINE_COMPONENTS);
    }
}
