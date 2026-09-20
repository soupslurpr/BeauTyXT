//! SVG exists only inside the isolated worker; no XML or resource reference crosses into Android.

use super::fonts::DiagramFonts;
use beautyxt_illustration_core::{
    Drawing, DrawingError, FilledPath, INK, MAX_PATH_CLIPS, PathClip, SURFACE, TONE,
};

pub(super) fn drawing(source: &str, fonts: &DiagramFonts) -> Result<Vec<u8>, DrawingError> {
    if source.len() > super::MAX_SVG_BYTES {
        return Err(DrawingError::Limit);
    }
    let xml = roxmltree::Document::parse_with_options(
        source,
        roxmltree::ParsingOptions {
            allow_dtd: false,
            nodes_limit: 8_192,
            ..Default::default()
        },
    )
    .map_err(|_| DrawingError::Invalid)?;
    for node in xml.descendants().filter(roxmltree::Node::is_element) {
        if node.ancestors().count() > 40 {
            return Err(DrawingError::Limit);
        }
        if matches!(
            node.tag_name().name(),
            "image" | "feImage" | "use" | "foreignObject" | "script" | "a" | "animate" | "set"
        ) {
            #[cfg(test)]
            eprintln!("unsupported SVG element: {}", node.tag_name().name());
            return Err(DrawingError::Unsupported);
        }
    }
    let options = fonts.options();
    let prepared = fonts.prepare_svg(source, &xml)?;
    let tree = usvg::Tree::from_str(&prepared, &options).map_err(|_| DrawingError::Invalid)?;
    // The shared protocol uses em units. Diagram labels are laid out at the fixed 16px base size.
    let mut drawing = Drawing::new(
        f64::from(tree.size().width()) / 16.0,
        f64::from(tree.size().height()) / 16.0,
        0.0,
    )?;
    let alternative = |name| -> Result<String, DrawingError> {
        let Some(element) = xml
            .root_element()
            .children()
            .find(|node| node.is_element() && node.tag_name().name() == name)
        else {
            return Ok(String::new());
        };
        let limit = if name == "title" {
            beautyxt_illustration_core::MAX_TITLE_BYTES
        } else {
            beautyxt_illustration_core::MAX_DESCRIPTION_BYTES
        };
        let mut text = String::new();
        for node in element.descendants().filter(roxmltree::Node::is_text) {
            let part = node.text().unwrap_or_default();
            if part.len() > limit - text.len() {
                return Err(DrawingError::Limit);
            }
            text.push_str(part);
        }
        Ok(text.trim().to_owned())
    };
    drawing.describe(&alternative("title")?, &alternative("desc")?)?;
    visit(tree.root(), &mut drawing, 0, &[])?;
    drawing.encode()
}

fn visit(
    group: &usvg::Group,
    drawing: &mut Drawing,
    depth: usize,
    clips: &[PathClip],
) -> Result<(), DrawingError> {
    if depth > 40 {
        return Err(DrawingError::Limit);
    }
    if group.opacity().get() == 0.0 {
        return Ok(());
    }
    if group.opacity().get().to_bits() != 1.0_f32.to_bits()
        || group.mask().is_some()
        || !group.filters().is_empty()
    {
        #[cfg(test)]
        eprintln!(
            "unsupported group opacity {} mask {} filters {}",
            group.opacity().get(),
            group.mask().is_some(),
            group.filters().len()
        );
        return Err(DrawingError::Unsupported);
    }
    let mut clips = clips.to_vec();
    if let Some(clip) = group.clip_path() {
        if clips.len() == MAX_PATH_CLIPS {
            return Err(DrawingError::Limit);
        }
        // Standard Mermaid marker clips contain one filled path. Complex clip unions stay source.
        let [usvg::Node::Path(path)] = clip.root().children() else {
            #[cfg(test)]
            eprintln!("unsupported clip children {:?}", clip.root().children());
            return Err(DrawingError::Unsupported);
        };
        if clip.clip_path().is_some() || path.stroke().is_some() {
            #[cfg(test)]
            eprintln!("unsupported nested/stroked clip");
            return Err(DrawingError::Unsupported);
        }
        let transform = group
            .abs_transform()
            .pre_concat(clip.transform())
            .pre_concat(path.abs_transform());
        let geometry = normalized(path.data().clone(), transform)?;
        clips.push(PathClip::from_path(
            path.fill()
                .is_some_and(|fill| fill.rule() == usvg::FillRule::EvenOdd),
            &geometry,
        )?);
    }
    for node in group.children() {
        match node {
            usvg::Node::Group(group) => {
                visit(group, drawing, depth + 1, &clips)?;
            }
            usvg::Node::Text(text) => {
                if !super::fonts::supported(text) {
                    return Err(DrawingError::Unsupported);
                }
                // usvg 0.48 propagates the text's absolute transform to its outlined paths.
                // Applying it again here would displace or rescale every nested diagram label.
                visit(text.flattened(), drawing, depth + 1, &clips)?;
            }
            usvg::Node::Path(path) => path_drawing(path, drawing, &clips)?,
            usvg::Node::Image(_) => return Err(DrawingError::Unsupported),
        }
    }
    Ok(())
}

fn path_drawing(
    path: &usvg::Path,
    drawing: &mut Drawing,
    clips: &[PathClip],
) -> Result<(), DrawingError> {
    if !path.is_visible() {
        return Ok(());
    }
    let add_fill = |drawing: &mut Drawing| -> Result<(), DrawingError> {
        if let Some(fill) = path.fill().filter(|fill| fill.opacity().get() > 0.0) {
            add(
                path.data().clone(),
                path.abs_transform(),
                color(fill.paint(), fill.opacity().get())?,
                fill.rule() == usvg::FillRule::EvenOdd,
                drawing,
                clips,
            )?;
        }
        Ok(())
    };
    let add_stroke = |drawing: &mut Drawing| -> Result<(), DrawingError> {
        if let Some(stroke) = path.stroke().filter(|stroke| stroke.opacity().get() > 0.0) {
            let mut geometry = path.data().clone();
            if let Some(array) = stroke.dasharray() {
                let dash = tiny_skia_path::StrokeDash::new(array.to_vec(), stroke.dashoffset())
                    .ok_or(DrawingError::Invalid)?;
                geometry = geometry.dash(&dash, 1.0).ok_or(DrawingError::Limit)?;
            }
            let outline = geometry
                .stroke(&stroke.to_tiny_skia(), 1.0)
                .ok_or(DrawingError::Limit)?;
            add(
                outline,
                path.abs_transform(),
                color(stroke.paint(), stroke.opacity().get())?,
                false,
                drawing,
                clips,
            )?;
        }
        Ok(())
    };
    if path.paint_order() == usvg::PaintOrder::StrokeAndFill {
        add_stroke(drawing)?;
        add_fill(drawing)
    } else {
        add_fill(drawing)?;
        add_stroke(drawing)
    }
}

fn color(paint: &usvg::Paint, opacity: f32) -> Result<u32, DrawingError> {
    let usvg::Paint::Color(color) = paint else {
        #[cfg(test)]
        eprintln!("unsupported paint {paint:?}");
        return Err(DrawingError::Unsupported);
    };
    if opacity.to_bits() != 1.0_f32.to_bits() {
        #[cfg(test)]
        eprintln!("unsupported paint opacity {opacity}");
        return Err(DrawingError::Unsupported);
    }
    Ok(match (color.red, color.green, color.blue) {
        (1, 1, 0) => INK,
        (1, 1, 1) => SURFACE,
        (1, 1, 3) => TONE,
        (red, green, blue) => {
            0xff00_0000 | u32::from(red) << 16 | u32::from(green) << 8 | u32::from(blue)
        }
    })
}

fn add(
    path: tiny_skia_path::Path,
    transform: tiny_skia_path::Transform,
    color: u32,
    even_odd: bool,
    drawing: &mut Drawing,
    clips: &[PathClip],
) -> Result<(), DrawingError> {
    let path = normalized(path, transform)?;
    drawing.push(FilledPath::from_path(color, even_odd, &path)?.with_clips(clips)?)
}

fn normalized(
    path: tiny_skia_path::Path,
    transform: tiny_skia_path::Transform,
) -> Result<tiny_skia_path::Path, DrawingError> {
    path.transform(transform)
        .and_then(|path| {
            path.transform(tiny_skia_path::Transform::from_scale(
                1.0 / 16.0,
                1.0 / 16.0,
            ))
        })
        .ok_or(DrawingError::Invalid)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tiny_skia_path::Transform;

    #[test]
    #[ignore = "requires an explicitly selected platform test font"]
    fn flattened_labels_apply_nested_transforms_once() {
        let bytes = std::fs::read(std::env::var("BEAUTYXT_DIAGRAM_TEST_FONT").unwrap()).unwrap();
        let fonts = DiagramFonts::new(&[&bytes]).unwrap();
        let label = r#"<text font-size="16">Read locally</text>"#;
        let source = format!(
            r#"<svg xmlns="http://www.w3.org/2000/svg" width="320" height="320">{label}</svg>"#
        );
        let xml = roxmltree::Document::parse(&source).unwrap();
        let prepared = fonts.prepare_svg(&source, &xml).unwrap();
        let tree = usvg::Tree::from_str(&prepared, &fonts.options()).unwrap();
        let [usvg::Node::Text(text)] = tree.root().children() else {
            panic!("fixture must contain one outlined text node");
        };
        assert!(!text.flattened().children().is_empty());

        for (outer, inner, transform) in [
            (
                "translate(80,40)",
                "translate(0,0)",
                Transform::from_translate(80.0, 40.0),
            ),
            (
                "translate(80,40)",
                "scale(2,0.5)",
                Transform::from_row(2.0, 0.0, 0.0, 0.5, 80.0, 40.0),
            ),
            (
                "translate(100,80)",
                "matrix(0,1,-1,0,0,0)",
                Transform::from_row(0.0, 1.0, -1.0, 0.0, 100.0, 80.0),
            ),
        ] {
            let mut expected = Drawing::new(20.0, 20.0, 0.0).unwrap();
            for node in text.flattened().children() {
                let usvg::Node::Path(path) = node else {
                    panic!("fixture font must use outline glyphs");
                };
                add(
                    path.data().clone(),
                    transform,
                    0xff00_0000,
                    false,
                    &mut expected,
                    &[],
                )
                .unwrap();
            }
            let source = format!(
                r#"<svg xmlns="http://www.w3.org/2000/svg" width="320" height="320"><g transform="{outer}"><g transform="{inner}">{label}</g></g></svg>"#
            );
            assert_eq!(
                drawing(&source, &fonts).unwrap(),
                expected.encode().unwrap(),
                "label transform {outer} / {inner} must be applied exactly once"
            );
        }
    }
}
