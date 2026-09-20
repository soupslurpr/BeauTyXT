//! Align ER's centered SVG label and background with the connector before terminal validation.

use merman_render::svg::{SvgPostprocessContext, SvgPostprocessor};
use std::{borrow::Cow, fmt::Write};

pub(super) struct ErLabelAlignment(pub bool);

impl SvgPostprocessor for ErLabelAlignment {
    fn name(&self) -> &'static str {
        "beautyxt-er-label-alignment"
    }

    fn process<'a>(
        &self,
        source: Cow<'a, str>,
        _context: &SvgPostprocessContext<'_>,
    ) -> merman_render::Result<Cow<'a, str>> {
        if !self.0 {
            return Ok(source);
        }
        Ok(match align(&source)? {
            Some(aligned) => Cow::Owned(aligned),
            None => source,
        })
    }
}

fn invalid() -> merman_render::Error {
    merman_render::Error::InvalidModel {
        message: "invalid generated ER label geometry".into(),
    }
}

fn align(source: &str) -> merman_render::Result<Option<String>> {
    if source.len() > super::MAX_SVG_BYTES {
        return Err(invalid());
    }
    let xml = roxmltree::Document::parse_with_options(
        source,
        roxmltree::ParsingOptions {
            allow_dtd: false,
            nodes_limit: 8_192,
            ..Default::default()
        },
    )
    .map_err(|_| invalid())?;
    let mut output = String::new();
    let mut copied = 0;
    for node in xml.descendants().filter(|node| {
        node.has_tag_name("g")
            && node.attribute("class") == Some("label")
            && node.parent().is_some_and(|parent| {
                parent.has_tag_name("g") && parent.attribute("class") == Some("edgeLabel")
            })
    }) {
        // With HTML labels disabled, both text and background are already centered around x=0.
        // The parent's connector translation is correct; remove only the redundant half-width
        // translation on this inner label group. No authored text or other family is rewritten.
        let attribute = node
            .attributes()
            .find(|attribute| attribute.name() == "transform")
            .ok_or_else(invalid)?;
        let (x, y) = attribute
            .value()
            .strip_prefix("translate(")
            .and_then(|value| value.strip_suffix(')'))
            .and_then(|value| value.split_once(','))
            .ok_or_else(invalid)?;
        let x = x.trim().parse::<f64>().map_err(|_| invalid())?;
        let y = y.trim().parse::<f64>().map_err(|_| invalid())?;
        if !x.is_finite() || !y.is_finite() || x > 0.0 {
            return Err(invalid());
        }
        if x == 0.0 {
            continue;
        }
        let range = attribute.range_value();
        output.push_str(&source[copied..range.start]);
        write!(output, "translate(0,{y})").map_err(|_| invalid())?;
        copied = range.end;
        if output.len() > super::MAX_SVG_BYTES {
            return Err(invalid());
        }
    }
    if copied == 0 {
        return Ok(None);
    }
    if source.len() - copied > super::MAX_SVG_BYTES - output.len() {
        return Err(invalid());
    }
    output.push_str(&source[copied..]);
    Ok(Some(output))
}

#[cfg(test)]
mod tests {
    use super::align;

    #[test]
    fn removes_only_the_inner_horizontal_label_translation() {
        let source = r#"<svg><g class="edgeLabel" transform="translate(90,70)"><g class="label" transform="translate(-21.5, -9)"><rect x="-21.5"/><text text-anchor="middle">owns</text></g></g><g class="label" transform="translate(-10,-8)"/></svg>"#;
        let aligned = align(source).unwrap().unwrap();
        assert_eq!(
            aligned,
            source.replace("translate(-21.5, -9)", "translate(0,-9)")
        );
        assert_eq!(align(&aligned).unwrap(), None);
    }

    #[test]
    fn rejects_invalid_or_excessive_generated_geometry() {
        for transform in ["rotate(20)", "translate(NaN,1)", "translate(-2,inf)"] {
            let source = format!(
                r#"<svg><g class="edgeLabel"><g class="label" transform="{transform}"/></g></svg>"#
            );
            assert!(align(&source).is_err());
        }
        assert!(align(&" ".repeat(super::super::MAX_SVG_BYTES + 1)).is_err());
        assert!(align("<!DOCTYPE svg [<!ENTITY a 'b'>]><svg>&a;</svg>").is_err());
    }
}
