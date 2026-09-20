//! Math layout lives only in the isolated math worker; callers receive bounded filled paths.

#![forbid(unsafe_code)]

mod admission;

use beautyxt_illustration_core::{Drawing, DrawingError, FilledPath, INK, coordinate};
use ratex_layout::{LayoutOptions, layout, to_display_list};
use ratex_types::display_item::DisplayItem;
use ratex_types::math_style::MathStyle;
use ratex_types::path_command::PathCommand;
use skrifa::{FontRef, MetadataProvider};
use tiny_skia_path::{PathBuilder, Rect, Stroke};

/// Maximum UTF-8 input for one formula, independent of the document budget.
pub const MAX_SOURCE_BYTES: usize = 4 * 1024;
const MAX_ITEMS: usize = 1_024;
const MAX_MATH_EXTENT: f64 = 256.0;

/// Produces fixed-font outlines in em units, without file or network access.
///
/// # Errors
/// Rejects unsupported syntax/fonts, invalid geometry, and input/output budget violations.
pub fn render_math(source: &str, display: bool) -> Result<Vec<u8>, DrawingError> {
    if source.is_empty() || source.len() > MAX_SOURCE_BYTES {
        return Err(DrawingError::Limit);
    }
    reject_document_macros(source)?;
    let nodes = ratex_parser::parse(source).map_err(|_| DrawingError::Unsupported)?;
    admission::validate(&nodes)?;
    let options = LayoutOptions::default().with_style(if display {
        MathStyle::Display
    } else {
        MathStyle::Text
    });
    let root = layout(&nodes, &options);
    let list = to_display_list(&root);
    if list.items.len() > MAX_ITEMS
        || list.width > MAX_MATH_EXTENT
        || list.total_height() > MAX_MATH_EXTENT
    {
        return Err(DrawingError::Limit);
    }
    let mut drawing = Drawing::new(list.width, list.total_height(), list.height)?;
    for item in list.items {
        match item {
            DisplayItem::GlyphPath {
                x,
                y,
                scale,
                font,
                char_code,
                color,
            } => {
                let color = math_color(color)?;
                let filename = format!("KaTeX_{font}.ttf");
                let data =
                    ratex_katex_fonts::ttf_bytes(&filename).ok_or(DrawingError::Unsupported)?;
                let face = FontRef::new(&data).map_err(|_| DrawingError::Invalid)?;
                let glyph = face
                    .charmap()
                    .map(char_code)
                    .ok_or(DrawingError::Unsupported)?;
                let outline = beautyxt_print_core::glyph_outline(&data, 0, glyph.to_u32(), &[])
                    .map_err(|_| DrawingError::Invalid)?
                    .ok_or(DrawingError::Unsupported)?;
                if outline.is_empty() {
                    continue;
                }
                let components = place_outline(outline, x, y, scale)?;
                drawing.push(FilledPath::new(color, false, components)?)?;
            }
            DisplayItem::Line {
                x,
                y,
                width,
                thickness,
                color,
                dashed,
            } => {
                if dashed {
                    return Err(DrawingError::Unsupported);
                }
                push_rect(
                    &mut drawing,
                    x,
                    y - thickness / 2.0,
                    width,
                    thickness,
                    math_color(color)?,
                )?;
            }
            DisplayItem::Rect {
                x,
                y,
                width,
                height,
                color,
            } => push_rect(&mut drawing, x, y, width, height, math_color(color)?)?,
            DisplayItem::Path {
                x,
                y,
                commands,
                fill,
                color,
            } => {
                append_path(&mut drawing, x, y, commands, fill, math_color(color)?)?;
            }
        }
    }
    drawing.encode()
}

fn append_path(
    drawing: &mut Drawing,
    x: f64,
    y: f64,
    commands: Vec<PathCommand>,
    fill: bool,
    color: u32,
) -> Result<(), DrawingError> {
    let mut path = PathBuilder::new();
    for command in commands {
        match command {
            PathCommand::MoveTo { x: dx, y: dy } => {
                path.move_to(coordinate(x + dx)?, coordinate(y + dy)?);
            }
            PathCommand::LineTo { x: dx, y: dy } => {
                path.line_to(coordinate(x + dx)?, coordinate(y + dy)?);
            }
            PathCommand::QuadTo {
                x1,
                y1,
                x: dx,
                y: dy,
            } => path.quad_to(
                coordinate(x + x1)?,
                coordinate(y + y1)?,
                coordinate(x + dx)?,
                coordinate(y + dy)?,
            ),
            PathCommand::CubicTo {
                x1,
                y1,
                x2,
                y2,
                x: dx,
                y: dy,
            } => path.cubic_to(
                coordinate(x + x1)?,
                coordinate(y + y1)?,
                coordinate(x + x2)?,
                coordinate(y + y2)?,
                coordinate(x + dx)?,
                coordinate(y + dy)?,
            ),
            PathCommand::Close => path.close(),
        }
    }
    let path = path.finish().ok_or(DrawingError::Invalid)?;
    let path = if fill {
        path
    } else {
        path.stroke(
            &Stroke {
                width: 0.04,
                ..Stroke::default()
            },
            32.0,
        )
        .ok_or(DrawingError::Invalid)?
    };
    drawing.push(FilledPath::from_path(color, false, &path)?)?;
    Ok(())
}

// Macro definitions are outside this renderer's contract. This is admission policy,
// not a substitute for RaTeX's actual parser/expansion depth checks.
fn reject_document_macros(source: &str) -> Result<(), DrawingError> {
    let mut characters = source.chars().peekable();
    while let Some(character) = characters.next() {
        if character != '\\' {
            continue;
        }
        let mut command = String::new();
        while characters.peek().is_some_and(char::is_ascii_alphabetic) {
            command.push(characters.next().ok_or(DrawingError::Invalid)?);
        }
        if command == "begin" || command == "end" {
            admission::environment(&mut characters)?;
        }
        if matches!(
            command.as_str(),
            "def"
                | "gdef"
                | "edef"
                | "xdef"
                | "let"
                | "futurelet"
                | "global"
                | "newcommand"
                | "renewcommand"
                | "providecommand"
                | "csname"
                | "expandafter"
        ) {
            return Err(DrawingError::Unsupported);
        }
    }
    Ok(())
}

fn place_outline(
    mut components: Vec<f32>,
    x: f64,
    y: f64,
    scale: f64,
) -> Result<Vec<f32>, DrawingError> {
    if !scale.is_finite() || scale <= 0.0 || scale > MAX_MATH_EXTENT {
        return Err(DrawingError::Invalid);
    }
    let mut offset = 0;
    while offset < components.len() {
        let arity = match components[offset].to_bits() {
            0x0000_0000 | 0x3f80_0000 => 2,
            0x4000_0000 => 4,
            0x4040_0000 => 6,
            0x4080_0000 => 0,
            _ => return Err(DrawingError::Invalid),
        };
        offset += 1;
        let coordinates = components
            .get_mut(offset..offset + arity)
            .ok_or(DrawingError::Invalid)?;
        for pair in coordinates.as_chunks_mut::<2>().0 {
            pair[0] = coordinate(x + f64::from(pair[0]) * scale / 1000.0)?;
            pair[1] = coordinate(y + f64::from(pair[1]) * scale / 1000.0)?;
        }
        offset += arity;
    }
    Ok(components)
}

fn push_rect(
    drawing: &mut Drawing,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
    color: u32,
) -> Result<(), DrawingError> {
    let rect = Rect::from_xywh(
        coordinate(x)?,
        coordinate(y)?,
        coordinate(width)?,
        coordinate(height)?,
    )
    .ok_or(DrawingError::Invalid)?;
    drawing.push(FilledPath::from_path(
        color,
        false,
        &PathBuilder::from_rect(rect),
    )?)
}

fn math_color(color: ratex_types::color::Color) -> Result<u32, DrawingError> {
    // Math uses the surrounding text color. Explicit document styling is not
    // silently discarded: non-default colors retain the formula as source for now.
    if color.r.to_bits() == 0
        && color.g.to_bits() == 0
        && color.b.to_bits() == 0
        && color.a.to_bits() == 1.0_f32.to_bits()
    {
        Ok(INK)
    } else {
        Err(DrawingError::Unsupported)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_the_supported_formula_families_as_closed_paths() {
        for source in [
            r"x^2+y_1",
            r"\frac{a+b}{c}",
            r"\sqrt[3]{x+1}",
            r"\sum_{i=1}^{n} i",
            r"\int_0^1 x^2\,dx",
            r"\begin{pmatrix}a&b\\c&d\end{pmatrix}",
            r"\begin{aligned} a&=b+c\\ d&=e+f\end{aligned}",
            r"\begin{cases}x&x>0\\-x&x<0\end{cases}",
            r"\overline{x}+\boxed{y}+\hat{z}",
        ] {
            for display in [false, true] {
                let packet =
                    render_math(source, display).unwrap_or_else(|e| panic!("{source}: {e}"));
                assert_eq!(&packet[..8], b"BTXTILL3");
                assert!(packet.len() > beautyxt_illustration_core::HEADER_BYTES);
            }
        }
    }

    #[test]
    fn rejects_oversized_unsupported_and_deep_inputs() {
        for source in [
            "x".repeat(MAX_SOURCE_BYTES + 1),
            format!("{}x{}", "{".repeat(40), "}".repeat(40)),
            r"\def\foo{x}\foo".to_owned(),
            r"\includegraphics{https://example.invalid/image}".to_owned(),
            r"\color{red}{x}".to_owned(),
            r"\rule{999999999em}{1em}".to_owned(),
            r"\begin{alignat}{99999999999999}x&=y\end{alignat}".to_owned(),
            r"\begin{\char97 lignat}{9999999}x\end{alignat}".to_owned(),
            format!(r"\begin{{matrix}}{}\end{{matrix}}", "a&".repeat(65)),
            format!(r"\begin{{matrix}}{}\end{{matrix}}", "a\\\\".repeat(65)),
        ] {
            assert!(render_math(&source, true).is_err(), "{source}");
        }
    }
}
