//! Native semantic diagrams, reduced to an independently validated path packet.

#![forbid(unsafe_code)]

mod er_labels;
mod fonts;
mod svg;

use beautyxt_illustration_core::DrawingError;
use merman::{
    Engine, MermaidConfig, OperationControl, RenderOutput, RenderRequest, Renderer, SvgRequest,
};
use merman_render::environment::{
    MeasurementProfileId, TextMeasurementPolicy, TextMeasurementProfile,
    TextMeasurementProfileIdentity,
};
use std::{sync::Arc, time::Duration};

/// Maximum source bytes for one complete Mermaid fence.
pub const MAX_SOURCE_BYTES: usize = 16 * 1024;
/// Aggregate platform-font budget, including bounded CJK collections; no authored paths are opened.
pub const MAX_FONT_BYTES: usize = 48 * 1024 * 1024;
/// Maximum distinct platform font buffers in one request.
pub const MAX_FONTS: usize = 16;
const MAX_SVG_BYTES: usize = 512 * 1024;

/// Renders without browser execution, external resource resolution, or document-controlled policy.
///
/// # Errors
/// Unsupported families/resources, exceeded budgets, and incomplete drawings retain their source.
pub fn render_diagram(source: &str, font: &[u8]) -> Result<Vec<u8>, DrawingError> {
    render_diagram_with_fonts(source, &[font])
}

/// Renders with a closed collection of Android-selected platform fonts.
///
/// # Errors
/// Rejects unsupported content, missing glyphs, invalid font buffers, and exceeded budgets.
pub fn render_diagram_with_fonts(source: &str, fonts: &[&[u8]]) -> Result<Vec<u8>, DrawingError> {
    let family = admit_source(source)?;
    let fonts = fonts::DiagramFonts::new(fonts)?;
    let profile = TextMeasurementProfile::new(
        TextMeasurementProfileIdentity::new(
            MeasurementProfileId::new("beautyxt-platform-font")
                .map_err(|_| DrawingError::Invalid)?,
            "2",
        )
        .map_err(|_| DrawingError::Invalid)?,
        Arc::new(fonts.clone()),
    );
    let mut resources = merman_render::resources::RenderResourcePolicy::constrained();
    for (name, value) in [
        ("max_source_bytes", MAX_SOURCE_BYTES),
        ("max_model_items", 192),
        ("max_model_text_bytes", MAX_SOURCE_BYTES),
        ("max_model_nesting_depth", 12),
        ("max_svg_bytes", MAX_SVG_BYTES),
        ("max_layout_work_units", 1_000_000),
    ] {
        resources
            .apply_override(name, value)
            .map_err(|_| DrawingError::Invalid)?;
    }
    let config = MermaidConfig::from_value(serde_json::json!({
        "theme": "base", "securityLevel": "strict", "htmlLabels": false,
        // Opaque host-owned label surfaces keep theme contrast stable in screen and print.
        "themeCSS": host_css(family),
        "fontFamily": fonts.family, "fontSize": 16,
        "flowchart": {"htmlLabels": false, "curve": "linear"},
        "sequence": {"useMaxWidth": false, "actorFontFamily": fonts.family,
            "messageFontFamily": fonts.family, "noteFontFamily": fonts.family},
        "themeVariables": host_theme(&fonts.family)
    }));
    let request = SvgRequest {
        environment: merman::SvgEnvironment::deterministic()
            .without_math_renderer()
            .with_text_measurement_policy(TextMeasurementPolicy::uniform(profile))
            .with_resource_policy(resources),
        // Set the host's background before the terminal SVG safety/resource pass. Theme variables
        // style the diagram, but do not replace Merman's separate root canvas background.
        pipeline: Some(
            merman::svg::SvgPipeline::resvg_safe()
                .with_postprocessor(merman::svg::RootBackgroundPostprocessor::new("transparent"))
                .with_postprocessor(er_labels::ErLabelAlignment(family == "erDiagram")),
        ),
        ..SvgRequest::default()
    };
    let result = Renderer::new()
        .with_engine(Engine::new().with_site_config(config))
        .render(RenderRequest::svg(
            source,
            OperationControl::new().with_deadline(Duration::from_millis(1_500)),
            request,
        ))
        .map_err(|error| {
            #[cfg(test)]
            eprintln!("diagram engine: {error}");
            match error {
                merman::RenderError::ResourceLimitExceeded(_) => DrawingError::Limit,
                merman::RenderError::Cancelled(_) => DrawingError::TimedOut,
                _ => DrawingError::Unsupported,
            }
        })?;
    let RenderOutput::Svg(Some(output)) = result else {
        return Err(DrawingError::Unsupported);
    };
    fonts.validate_measurements()?;
    svg::drawing(output.svg(), &fonts)
}

fn host_css(family: &str) -> String {
    let mut css = concat!(
        ".edgeLabel rect { opacity: 1 !important; fill: #010101 !important; }",
        // ER's optional-cardinality markers have a fixed white presentation attribute.
        ".marker.er circle { fill: #010101 !important; }"
    )
    .to_owned();
    if family == "classDiagram" {
        // The generated class label group already shifts by half its measured width, so its
        // centered text anchor would apply that shift twice. Keep this correction family-local.
        css.push_str(".edgeLabel text, .edgeLabel tspan { text-anchor: start !important; }");
    }
    css
}

// These sentinel colors become semantic host color roles in the closed path packet. Grouping
// by role makes omissions and accidental document-independent contrast differences easier to see.
fn host_theme(family: &str) -> serde_json::Value {
    let mut values = serde_json::Map::new();
    values.insert("fontFamily".into(), family.into());
    values.insert("fontSize".into(), "16px".into());
    for (names, color) in [
        (
            &[
                "primaryBorderColor",
                "primaryTextColor",
                "lineColor",
                "textColor",
                "actorBorder",
                "actorTextColor",
                "actorLineColor",
                "signalColor",
                "signalTextColor",
                "labelBoxBorderColor",
                "labelTextColor",
                "loopTextColor",
                "noteTextColor",
                "noteBorderColor",
                "activationBorderColor",
                "clusterBorder",
                "titleColor",
                "labelColor",
                "transitionColor",
                "transitionLabelColor",
                "stateLabelColor",
                "compositeBorder",
                "innerEndBackground",
                "specialStateColor",
                "classText",
                "relationColor",
                "nodeBorder",
                "border1",
            ][..],
            "#010100",
        ),
        (
            &[
                "background",
                "secondaryColor",
                "edgeLabelBackground",
                "clusterBkg",
                "rowOdd",
                "attributeBackgroundColorOdd",
                "labelBackgroundColor",
                "compositeBackground",
            ][..],
            "#010101",
        ),
        (
            &[
                "mainBkg",
                "primaryColor",
                "tertiaryColor",
                "actorBkg",
                "labelBoxBkgColor",
                "noteBkgColor",
                "activationBkgColor",
                "rowEven",
                "attributeBackgroundColorEven",
                "stateBkg",
                "altBackground",
                "compositeTitleBackground",
            ][..],
            "#010103",
        ),
    ] {
        for name in names {
            values.insert((*name).into(), color.into());
        }
    }
    serde_json::Value::Object(values)
}

/// Validates direct-buffer capacities before borrowing fonts or allocating a font database.
///
/// # Errors
/// Rejects empty, excessive, or overflow-sized collections before any font parsing.
pub fn validate_font_lengths(lengths: &[usize]) -> Result<(), DrawingError> {
    if !(1..=MAX_FONTS).contains(&lengths.len()) {
        return Err(DrawingError::Limit);
    }
    let mut total = 0;
    for &length in lengths {
        if length == 0 || length > MAX_FONT_BYTES - total {
            return Err(DrawingError::Limit);
        }
        total += length;
    }
    Ok(())
}

fn admit_source(source: &str) -> Result<&str, DrawingError> {
    if source.is_empty() || source.len() > MAX_SOURCE_BYTES {
        return Err(DrawingError::Limit);
    }
    let header = source
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && !line.starts_with("%%"))
        .and_then(|line| line.split_whitespace().next())
        .ok_or(DrawingError::Unsupported)?;
    if !matches!(
        header,
        "flowchart"
            | "graph"
            | "sequenceDiagram"
            | "stateDiagram-v2"
            | "stateDiagram"
            | "classDiagram"
            | "erDiagram"
    ) {
        return Err(DrawingError::Unsupported);
    }
    // Mermaid directives/front matter are configuration, not diagram content. No authored policy,
    // acquisition, click callbacks, or alternative renderers enter this closed adapter.
    if source
        .split("%%")
        .skip(1)
        .any(|tail| tail.trim_start().starts_with('{'))
        || source.contains("@{")
        || source.contains("$$")
        || source.split(['\n', '\r', ';']).any(|line| {
            (header != "classDiagram" && line.split_whitespace().next() == Some("class"))
                || matches!(
                    line.split_whitespace().next(),
                    Some("---" | "click" | "link" | "links" | "classDef" | "style" | "linkStyle")
                )
        })
    {
        return Err(DrawingError::Unsupported);
    }
    Ok(header)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn font_lengths_reject_excess_before_borrowing_or_parsing() {
        assert!(validate_font_lengths(&[2, 1]).is_ok());
        assert!(validate_font_lengths(&[MAX_FONT_BYTES]).is_ok());
        for lengths in [
            vec![],
            vec![0],
            vec![1; MAX_FONTS + 1],
            vec![usize::MAX],
            vec![MAX_FONT_BYTES, 1],
            vec![MAX_FONT_BYTES / 2, MAX_FONT_BYTES],
        ] {
            assert_eq!(validate_font_lengths(&lengths), Err(DrawingError::Limit));
        }
    }

    #[test]
    fn bounds_source_and_declines_policy_and_resource_extensions() {
        assert_eq!(
            admit_source(&"x".repeat(MAX_SOURCE_BYTES + 1)),
            Err(DrawingError::Limit)
        );
        for source in [
            "pie\nA: 1",
            "---\nconfig: {}\n---\nflowchart TD\nA-->B",
            "flowchart TD\n%%{init: {}}%%\nA-->B",
            "flowchart TD\n%% \t {init: {}}%%\nA-->B",
            "flowchart TD\n%%\n{init: {}}%%\nA-->B",
            "flowchart TD\nA@{ img: 'https://example.org/a.png' }",
            "flowchart TD\nclick A call callback()",
            "flowchart TD; A-->B; click A call callback()",
            "flowchart TD; A-->B; style A fill:red",
            "flowchart TD; A-->B; classDef default fill:red",
        ] {
            assert_eq!(admit_source(source), Err(DrawingError::Unsupported));
        }
        assert!(admit_source("%% comment\nflowchart LR\nA-->B").is_ok());
    }

    #[test]
    #[ignore = "requires an explicitly selected platform test font"]
    fn retains_authored_accessibility_text_without_markup() {
        let font = std::fs::read(std::env::var("BEAUTYXT_DIAGRAM_TEST_FONT").unwrap()).unwrap();
        for description in [
            "accDescr: Read, then share.",
            "accDescr {\nRead, then share.\n}",
        ] {
            let source =
                format!("flowchart LR\naccTitle: Décisions\n{description}\nA[Read]-->B[Share]");
            let packet = render_diagram(&source, &font).unwrap();
            let title_bytes = u32::from_le_bytes(packet[40..44].try_into().unwrap()) as usize;
            let description_bytes = u32::from_le_bytes(packet[44..48].try_into().unwrap()) as usize;
            assert_eq!(&packet[48..48 + title_bytes], "Décisions".as_bytes());
            assert_eq!(
                &packet[48 + title_bytes..48 + title_bytes + description_bytes],
                b"Read, then share."
            );
        }
    }

    #[test]
    #[ignore = "requires an explicitly selected platform test font"]
    fn renders_real_flowcharts_and_sequence_diagrams() {
        let font = std::fs::read(std::env::var("BEAUTYXT_DIAGRAM_TEST_FONT").unwrap()).unwrap();
        for source in [
            "flowchart TD\nA[Read locally] --> B{Edit?}\nB -->|Yes| C[Save to source]\nB -->|No| D[Share]",
            "flowchart LR\nsubgraph Local\nA[One] --> B[Two]\nend\nB --> C[Three]",
            "sequenceDiagram\nparticipant A as Reader\nparticipant B as Editor\nA->>B: Open document\nB-->>A: Render locally",
            "sequenceDiagram\nAlice->>Bob: Hello\nactivate Bob\nNote right of Bob: Working locally\nBob-->>Alice: Done\ndeactivate Bob",
        ] {
            let packet =
                render_diagram(source, &font).unwrap_or_else(|error| panic!("{error}: {source}"));
            assert_eq!(&packet[..8], b"BTXTILL3");
            assert!(packet.len() > 200);
            assert_host_palette(&packet, source);
        }
    }

    #[test]
    #[ignore = "requires an explicitly selected platform test font"]
    fn evaluates_state_class_and_relationship_diagrams() {
        let font = std::fs::read(std::env::var("BEAUTYXT_DIAGRAM_TEST_FONT").unwrap()).unwrap();
        for source in [
            "stateDiagram-v2\n[*] --> Reading\nReading --> Editing: Refine\nEditing --> Reading: Preview\nReading --> [*]",
            "stateDiagram-v2\nstate Document {\n[*] --> Unsaved\nUnsaved --> Saved: Save\n}\nDocument --> [*]",
            "classDiagram\nclass Document {\n+String title\n+read() String\n}\nclass Reader\nReader --> Document: opens",
            "classDiagram\nDocument <|-- Markdown\nDocument <|-- PlainText\nclass Document {\n+save()\n}",
            "erDiagram\nDOCUMENT ||--o{ REVISION : has\nDOCUMENT {\nstring title\n}\nREVISION {\nint number\n}",
        ] {
            let packet =
                render_diagram(source, &font).unwrap_or_else(|error| panic!("{error}: {source}"));
            assert_eq!(&packet[..8], b"BTXTILL3");
            assert!(packet.len() > 200);
            assert_host_palette(&packet, source);
        }
    }

    fn assert_host_palette(packet: &[u8], source: &str) {
        let integer =
            |offset| u32::from_le_bytes(packet[offset..offset + 4].try_into().unwrap()) as usize;
        let mut offset = 48 + integer(40) + integer(44);
        for _ in 0..integer(16) {
            let color = integer(offset);
            assert!(color <= 3, "fixed color {color:x} in {source}");
            let components = integer(offset + 8);
            let clips = integer(offset + 12);
            offset += 16 + components * 4;
            for _ in 0..clips {
                let components = integer(offset + 4);
                offset += 8 + components * 4;
            }
        }
        assert_eq!(offset, packet.len());
    }
}
