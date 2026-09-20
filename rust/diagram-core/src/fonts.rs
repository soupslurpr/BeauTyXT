//! Bounded platform fonts use the same shaping, fallback, and metrics for layout and final paths.

use beautyxt_illustration_core::DrawingError;
use merman_render::text::{TextMeasurer, TextMetrics, TextStyle};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use std::{borrow::Cow, collections::HashMap};
use unicode_segmentation::UnicodeSegmentation;

#[derive(Clone)]
pub(super) struct DiagramFonts {
    pub family: String,
    pub database: Arc<usvg::fontdb::Database>,
    failed_measurement: Arc<AtomicBool>,
    families: Arc<Mutex<HashMap<String, String>>>,
}

impl DiagramFonts {
    pub fn new(fonts: &[&[u8]]) -> Result<Self, DrawingError> {
        if fonts.is_empty() || fonts.len() > super::MAX_FONTS {
            return Err(DrawingError::Limit);
        }
        let mut database = usvg::fontdb::Database::new();
        let mut bytes = 0;
        for font in fonts {
            if font.is_empty() || font.len() > super::MAX_FONT_BYTES - bytes {
                return Err(DrawingError::Limit);
            }
            bytes += font.len();
            let before = database.faces().count();
            database.load_font_data(font.to_vec());
            let after = database.faces().count();
            if before == after {
                return Err(DrawingError::Unsupported);
            }
            if after > 64 {
                return Err(DrawingError::Limit);
            }
        }
        let family = database
            .faces()
            .next()
            .and_then(|face| face.families.first())
            .map(|(name, _)| name.clone())
            .ok_or(DrawingError::Unsupported)?;
        database.set_sans_serif_family(&family);
        database.set_serif_family(&family);
        database.set_monospace_family(&family);
        Ok(Self {
            family,
            database: Arc::new(database),
            failed_measurement: Arc::new(AtomicBool::new(false)),
            families: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub fn options(&self) -> usvg::Options<'_> {
        usvg::Options {
            font_family: self.family.clone(),
            fontdb: self.database.clone(),
            image_href_resolver: usvg::ImageHrefResolver {
                resolve_data: Box::new(|_, _, _| None),
                resolve_string: Box::new(|_, _| None),
            },
            ..usvg::Options::default()
        }
    }

    pub fn validate_measurements(&self) -> Result<(), DrawingError> {
        if self.failed_measurement.load(Ordering::Relaxed) {
            Err(DrawingError::Unsupported)
        } else {
            Ok(())
        }
    }

    /// Give complete grapheme runs their platform font before USVG shapes them. Its default
    /// fallback substitutes glyphs by index, which cannot handle differing ligature counts in a
    /// mixed Latin/Arabic label. Adjacent clusters using the same family remain one shaping run.
    fn label_body(&self, text: &str) -> Result<String, DrawingError> {
        let mut output = String::new();
        let mut family = String::new();
        let mut run = String::new();
        for grapheme in text.graphemes(true) {
            let next = self.grapheme_family(grapheme)?;
            if next != family && !run.is_empty() {
                append_run(&mut output, &family, &run)?;
                run.clear();
            }
            family = next;
            run.push_str(grapheme);
        }
        if !run.is_empty() {
            append_run(&mut output, &family, &run)?;
        }
        Ok(output)
    }

    fn grapheme_family(&self, grapheme: &str) -> Result<String, DrawingError> {
        let mut cache = self.families.lock().map_err(|_| DrawingError::Invalid)?;
        if let Some(family) = cache.get(grapheme) {
            return Ok(family.clone());
        }
        let mut selected = None;
        for face in self.database.faces() {
            let supported = self.database.with_face_data(face.id, |data, index| {
                let Ok(font) = ttf_parser::Face::parse(data, index) else { return false; };
                grapheme.chars().all(|character| {
                    // Joining and variation controls affect shaping but need no visible glyph.
                    character.is_control()
                        || matches!(character, '\u{200c}' | '\u{200d}' | '\u{fe00}'..='\u{fe0f}' | '\u{e0100}'..='\u{e01ef}')
                        || font.glyph_index(character).is_some()
                })
            }).unwrap_or(false);
            if supported {
                selected = face.families.first().map(|(name, _)| name.clone());
                break;
            }
        }
        let family = selected.ok_or(DrawingError::Unsupported)?;
        if cache.len() < 1024 && grapheme.len() <= 64 {
            cache.insert(grapheme.to_owned(), family.clone());
        }
        Ok(family)
    }

    /// Only generated SVG text leaves are changed; attributes, CSS, paths and authored alternatives
    /// are not reinterpreted. New text and family attributes are always escaped, never raw markup.
    pub fn prepare_svg<'a>(
        &self,
        source: &'a str,
        xml: &roxmltree::Document<'_>,
    ) -> Result<Cow<'a, str>, DrawingError> {
        let mut output = String::new();
        let mut copied = 0;
        for node in xml.descendants().filter(|node| {
            node.is_text() && node.ancestors().any(|parent| parent.has_tag_name("text"))
        }) {
            let text = node.text().unwrap_or_default();
            if text.is_empty() {
                continue;
            }
            let range = node.range();
            let replacement = self.label_body(text)?;
            append_bounded(&mut output, &source[copied..range.start])?;
            append_bounded(&mut output, &replacement)?;
            copied = range.end;
        }
        if copied == 0 {
            return Ok(Cow::Borrowed(source));
        }
        append_bounded(&mut output, &source[copied..])?;
        Ok(Cow::Owned(output))
    }

    fn width(&self, text: &str, style: &TextStyle) -> Option<f64> {
        if text.is_empty() {
            return Some(0.0);
        }
        if text.len() > super::MAX_SOURCE_BYTES
            || !style.font_size.is_finite()
            || !(0.0..=512.0).contains(&style.font_size)
        {
            return None;
        }
        let weight = match style.font_weight.as_deref() {
            Some("bold" | "bolder") => 700,
            Some(weight) => weight
                .parse::<u16>()
                .ok()
                .filter(|weight| (1..=1000).contains(weight))
                .unwrap_or(400),
            None => 400,
        };
        let italic = match style.font_style.as_deref() {
            Some("italic") => "italic",
            Some("oblique") => "oblique",
            _ => "normal",
        };
        // Only fixed attributes and escaped plain text enter this measurement document. It is not
        // authored SVG. USVG measures glyph advances, bidi, and fallback exactly as final output.
        let escaped = self.label_body(text).ok()?;
        let svg = format!(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"><text xml:space=\"preserve\" font-size=\"{}\" font-weight=\"{weight}\" font-style=\"{italic}\">{escaped}</text></svg>",
            style.font_size
        );
        let tree = usvg::Tree::from_str(&svg, &self.options()).ok()?;
        let usvg::Node::Text(text) = tree.root().children().first()? else {
            return None;
        };
        if !supported(text) {
            return None;
        }
        Some(f64::from(text.bounding_box().width()))
    }
}

fn append_run(output: &mut String, family: &str, text: &str) -> Result<(), DrawingError> {
    let escape = |value: &str| {
        value
            .replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
            .replace('"', "&quot;")
    };
    append_bounded(
        output,
        &format!(
            "<tspan font-family=\"{}\">{}</tspan>",
            escape(family),
            escape(text)
        ),
    )
}

fn append_bounded(output: &mut String, text: &str) -> Result<(), DrawingError> {
    if text.len() > super::MAX_SVG_BYTES - output.len() {
        return Err(DrawingError::Limit);
    }
    output.push_str(text);
    Ok(())
}

pub(super) fn supported(text: &usvg::Text) -> bool {
    text.layouted()
        .iter()
        .flat_map(|span| &span.positioned_glyphs)
        .all(|glyph| glyph.id.0 != 0)
}

impl TextMeasurer for DiagramFonts {
    fn measure(&self, text: &str, style: &TextStyle) -> TextMetrics {
        let mut width = 0.0_f64;
        let mut lines = 0_u32;
        for line in text.split('\n') {
            lines += 1;
            if let Some(advance) = self.width(line, style) {
                width = width.max(advance);
            } else {
                self.failed_measurement.store(true, Ordering::Relaxed);
            }
        }
        TextMetrics {
            width,
            height: style.font_size * 1.2 * f64::from(lines),
            line_count: lines as usize,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emitted_font_runs_cannot_become_markup_or_attributes() {
        let family = "Family \"<&>";
        let text = "<script> & \" ' e\u{301}";
        let mut output = String::new();
        append_run(&mut output, family, text).unwrap();
        let xml = roxmltree::Document::parse(&output).unwrap();
        let span = xml.root_element();
        assert_eq!(span.tag_name().name(), "tspan");
        assert_eq!(span.attributes().len(), 1);
        assert_eq!(span.attribute("font-family"), Some(family));
        assert_eq!(span.text(), Some(text));
        assert_eq!(
            span.children().filter(roxmltree::Node::is_element).count(),
            0
        );
    }

    #[test]
    fn expanded_font_runs_have_a_hard_output_budget() {
        let mut output = "a".repeat(super::super::MAX_SVG_BYTES - 1);
        assert_eq!(
            append_run(&mut output, "sans-serif", "x"),
            Err(DrawingError::Limit)
        );
        append_bounded(&mut output, "x").unwrap();
        assert_eq!(append_bounded(&mut output, "x"), Err(DrawingError::Limit));
        assert_eq!(output.len(), super::super::MAX_SVG_BYTES);
    }

    #[test]
    #[ignore = "requires an explicitly selected platform test font"]
    fn rewriters_preserve_escaped_labels_and_authored_alternatives() {
        let font = std::fs::read(std::env::var("BEAUTYXT_DIAGRAM_TEST_FONT").unwrap()).unwrap();
        let fonts = DiagramFonts::new(&[&font]).unwrap();
        let source = "<svg><title>Unchanged &amp; literal</title><g><text x=\"12\">A &amp; &lt;B&gt; e\u{301}<tspan dy=\"20\">Next</tspan></text></g></svg>";
        let xml = roxmltree::Document::parse(source).unwrap();
        let rewritten = fonts.prepare_svg(source, &xml).unwrap();
        let result = roxmltree::Document::parse(&rewritten).unwrap();
        let title = result
            .descendants()
            .find(|node| node.has_tag_name("title"))
            .unwrap();
        assert_eq!(
            &rewritten[title.range()],
            "<title>Unchanged &amp; literal</title>"
        );
        let text = result
            .descendants()
            .find(|node| node.has_tag_name("text"))
            .unwrap();
        assert_eq!(text.attribute("x"), Some("12"));
        let content: String = text
            .descendants()
            .filter(roxmltree::Node::is_text)
            .filter_map(|node| node.text())
            .collect();
        assert_eq!(content, "A & <B> e\u{301}Next");
        let combining = text
            .descendants()
            .find(|node| node.is_text() && node.text().unwrap_or_default().contains('\u{301}'))
            .unwrap();
        assert!(combining.text().unwrap().contains("e\u{301}"));
        assert!(
            text.descendants()
                .any(|node| node.attribute("dy") == Some("20"))
        );
    }
}
