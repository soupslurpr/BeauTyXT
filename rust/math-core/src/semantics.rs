//! Semantic grouping uses layout structure; the renderer still owns all positions and glyphs.

use beautyxt_illustration_core::{DrawingError, MAX_TEXT_RUNS, TextBox, TextRun};
use ratex_layout::{
    LayoutBox,
    layout_box::{BoxContent, VBoxChildKind},
    to_display_list,
};
use ratex_types::{color::Color, display_item::DisplayItem};

pub(super) struct Semantics {
    groups: Vec<Option<usize>>,
    runs: Vec<TextRun>,
    pub complete: bool,
    spaces: Vec<(usize, usize, [f32; 4])>,
    invalid_groups: std::collections::BTreeSet<usize>,
}

impl Semantics {
    pub fn from_nodes(
        nodes: &mut [ratex_parser::parse_node::ParseNode],
        options: &ratex_layout::LayoutOptions,
        original: &[DisplayItem],
    ) -> Result<Self, DrawingError> {
        crate::text_spaces::promote(nodes);
        Self::new(&ratex_layout::layout(nodes, options), original)
    }

    pub fn new(root: &LayoutBox, original: &[DisplayItem]) -> Result<Self, DrawingError> {
        let mut tagged = root.clone();
        let mut next = 1;
        let mut current = 0;
        let mut complete = true;
        annotate(&mut tagged, &mut current, &mut next, &mut complete, 0)?;
        let tagged = to_display_list(&tagged);
        let mut groups = vec![None; original.len()];
        let mut original_glyphs = original
            .iter()
            .enumerate()
            .filter(|(_, item)| matches!(item, DisplayItem::GlyphPath { .. }))
            .peekable();
        let mut spaces = Vec::new();
        for tagged in tagged.items {
            if !matches!(tagged, DisplayItem::GlyphPath { .. }) {
                continue;
            }
            let actual = original_glyphs.peek().map(|(_, item)| *item);
            let group = match (actual, tagged) {
                (
                    Some(DisplayItem::GlyphPath {
                        x,
                        y,
                        scale,
                        font,
                        char_code,
                        ..
                    }),
                    DisplayItem::GlyphPath {
                        x: tx,
                        y: ty,
                        scale: ts,
                        font: tf,
                        char_code: tc,
                        color,
                    },
                ) if x.to_bits() == tx.to_bits()
                    && y.to_bits() == ty.to_bits()
                    && scale.to_bits() == ts.to_bits()
                    && font == &tf
                    && *char_code == tc =>
                {
                    let group = color.r.to_bits() as usize;
                    if group >= next as usize {
                        return Err(DrawingError::Invalid);
                    }
                    group
                }
                (
                    _,
                    DisplayItem::GlyphPath {
                        char_code: 160,
                        x,
                        y,
                        scale,
                        color,
                        ..
                    },
                ) => {
                    let before = original_glyphs
                        .peek()
                        .map_or(original.len(), |(index, _)| *index);
                    let bounds = [x, y - scale * 0.8, x + scale * 0.25, y + scale * 0.2]
                        .map(beautyxt_illustration_core::coordinate);
                    spaces.push((
                        before,
                        color.r.to_bits() as usize,
                        [bounds[0]?, bounds[1]?, bounds[2]?, bounds[3]?],
                    ));
                    continue;
                }
                _ => return Err(DrawingError::Invalid),
            };
            let (index, _) = original_glyphs.next().ok_or(DrawingError::Invalid)?;
            groups[index] = Some(group);
        }
        if original_glyphs.next().is_some() {
            return Err(DrawingError::Invalid);
        }
        Ok(Self {
            groups,
            runs: (0..next)
                .map(|_| TextRun {
                    text: String::new(),
                    boxes: Vec::new(),
                })
                .collect(),
            complete,
            spaces,
            invalid_groups: std::collections::BTreeSet::new(),
        })
    }

    pub fn glyph(
        &mut self,
        item: usize,
        code: u32,
        components: &[f32],
    ) -> Result<(), DrawingError> {
        self.insert_spaces(item)?;
        let group = self.groups[item].ok_or(DrawingError::Invalid)?;
        let Some(character) = char::from_u32(code).filter(|c| !c.is_control() &&
            !matches!(u32::from(*c), 0xe000..=0xf8ff | 0x000f_0000..=0x000f_fffd | 0x0010_0000..=0x0010_fffd))
        else { self.complete = false; self.invalid_groups.insert(group); return Ok(()); };
        let run = &mut self.runs[group];
        let start =
            u32::try_from(run.text.encode_utf16().count()).map_err(|_| DrawingError::Limit)?;
        let mut bounds = [
            f32::INFINITY,
            f32::INFINITY,
            f32::NEG_INFINITY,
            f32::NEG_INFINITY,
        ];
        let mut index = 0;
        while index < components.len() {
            let count = match components[index].to_bits() {
                0x0000_0000 | 0x3f80_0000 => 2,
                0x4000_0000 => 4,
                0x4040_0000 => 6,
                0x4080_0000 => 0,
                _ => return Err(DrawingError::Invalid),
            };
            index += 1;
            for pair in components
                .get(index..index + count)
                .ok_or(DrawingError::Invalid)?
                .as_chunks::<2>()
                .0
            {
                bounds[0] = bounds[0].min(pair[0]);
                bounds[1] = bounds[1].min(pair[1]);
                bounds[2] = bounds[2].max(pair[0]);
                bounds[3] = bounds[3].max(pair[1]);
            }
            index += count;
        }
        if bounds[0] >= bounds[2] || bounds[1] >= bounds[3] {
            self.complete = false;
            self.invalid_groups.insert(group);
            return Ok(());
        }
        run.text.push(character);
        run.boxes.push(TextBox {
            start,
            end: start + if character.len_utf16() == 2 { 2 } else { 1 },
            bounds,
        });
        Ok(())
    }

    fn insert_spaces(&mut self, before: usize) -> Result<(), DrawingError> {
        let count = self
            .spaces
            .iter()
            .take_while(|(index, _, _)| *index <= before)
            .count();
        for (_, group, bounds) in self.spaces.drain(..count) {
            let run = self.runs.get_mut(group).ok_or(DrawingError::Invalid)?;
            let start =
                u32::try_from(run.text.encode_utf16().count()).map_err(|_| DrawingError::Limit)?;
            run.text.push(' ');
            run.boxes.push(TextBox {
                start,
                end: start + 1,
                bounds,
            });
        }
        Ok(())
    }

    pub fn finish(mut self) -> Result<(bool, Vec<TextRun>), DrawingError> {
        self.insert_spaces(usize::MAX)?;
        Ok((
            self.complete,
            self.runs
                .into_iter()
                .enumerate()
                .filter_map(|(group, run)| {
                    (!run.text.is_empty() && !self.invalid_groups.contains(&group)).then_some(run)
                })
                .collect(),
        ))
    }

    #[cfg(test)]
    pub fn runs(self) -> impl Iterator<Item = TextRun> {
        self.finish().unwrap().1.into_iter()
    }
}

fn fresh(next: &mut u32) -> Result<u32, DrawingError> {
    if *next as usize >= MAX_TEXT_RUNS {
        return Err(DrawingError::Limit);
    }
    let value = *next;
    *next += 1;
    Ok(value)
}

// Tags are exact bit identities in a temporary copy. No tagged color reaches a drawing or font.
// Calling the renderer again preserves its transforms, shaping, clipping, and traversal order.
fn annotate(
    node: &mut LayoutBox,
    current: &mut u32,
    next: &mut u32,
    complete: &mut bool,
    depth: usize,
) -> Result<(), DrawingError> {
    if depth > 128 {
        return Err(DrawingError::Limit);
    }
    node.color = Color::rgb(f32::from_bits(*current), 0.0, 0.0);
    let mut visit =
        |child: &mut LayoutBox, group: &mut u32| annotate(child, group, next, complete, depth + 1);
    match &mut node.content {
        BoxContent::HBox(children) => {
            for child in children {
                visit(child, current)?;
            }
        }
        BoxContent::Glyph { .. }
        | BoxContent::GlyphRun { .. }
        | BoxContent::Kern
        | BoxContent::Empty
        | BoxContent::Rule { .. } => {}
        BoxContent::Framed { body, .. }
        | BoxContent::RaiseBox { body, .. }
        | BoxContent::Scaled { body, .. }
        | BoxContent::Angl { body, .. }
        | BoxContent::Overline { body, .. }
        | BoxContent::Underline { body, .. } => visit(body, current)?,
        BoxContent::LeftRight { left, right, inner } => {
            visit(left, current)?;
            visit(inner, current)?;
            visit(right, current)?;
        }
        BoxContent::SvgPath { .. } => {
            *complete = false;
            *current = fresh(next)?;
        }
        // Independent mathematical runs must not acquire invented horizontal adjacency.
        content => {
            let children: Vec<&mut LayoutBox> = match content {
                BoxContent::VBox(children) => children
                    .iter_mut()
                    .filter_map(|child| match &mut child.kind {
                        VBoxChildKind::Box(child) => Some(child.as_mut()),
                        VBoxChildKind::Kern(_) => None,
                    })
                    .collect(),
                BoxContent::Fraction { numer, denom, .. } => vec![numer, denom],
                BoxContent::SupSub { base, sup, sub, .. }
                | BoxContent::OpLimits { base, sup, sub, .. } => std::iter::once(base.as_mut())
                    .chain(sup.as_deref_mut())
                    .chain(sub.as_deref_mut())
                    .collect(),
                BoxContent::Radical { body, index, .. } => std::iter::once(body.as_mut())
                    .chain(index.as_deref_mut())
                    .collect(),
                BoxContent::Accent { base, accent, .. } => vec![base, accent],
                BoxContent::Array {
                    cells, row_tags, ..
                } => cells
                    .iter_mut()
                    .flatten()
                    .chain(row_tags.iter_mut().flatten())
                    .collect(),
                BoxContent::ProofTree { children, .. } => {
                    children.iter_mut().map(|child| &mut child.box_).collect()
                }
                _ => return Err(DrawingError::Invalid),
            };
            node.color = Color::rgb(f32::from_bits(fresh(next)?), 0.0, 0.0);
            for child in children {
                let mut group = fresh(next)?;
                annotate(child, &mut group, next, complete, depth + 1)?;
            }
            *current = fresh(next)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratex_layout::{LayoutOptions, layout};

    fn runs(source: &str) -> Vec<String> {
        let mut nodes = ratex_parser::parse(source).unwrap();
        let root = layout(&nodes, &LayoutOptions::default());
        let list = to_display_list(&root);
        crate::text_spaces::promote(&mut nodes);
        let semantic_root = layout(&nodes, &LayoutOptions::default());
        let mut semantics = Semantics::new(&semantic_root, &list.items).unwrap();
        for (index, item) in list.items.iter().enumerate() {
            if let DisplayItem::GlyphPath { char_code, .. } = item {
                semantics
                    .glyph(index, *char_code, &[0.0, 0.0, 0.0, 1.0, 1.0, 1.0])
                    .unwrap();
            }
        }
        semantics.runs().map(|run| run.text).collect()
    }

    #[test]
    fn formula_runs_never_flatten_scripts_fractions_or_matrix_cells() {
        assert_eq!(runs("a+b"), ["a+b"]);
        assert_eq!(runs(r"x^{22}+y"), ["x", "22", "+y"]);
        assert_eq!(runs(r"\frac{ab}{cd}"), ["ab", "cd"]);
        assert_eq!(
            runs(r"\begin{matrix}ab&cd\\ef&gh\end{matrix}"),
            ["ab", "cd", "ef", "gh"]
        );
        assert_eq!(runs(r"\alpha+\beta"), ["α+β"]);
        assert_eq!(runs(r"\text{net gain}"), ["net gain"]);
        assert_eq!(runs(r"\frac{x}{x}"), ["x", "x"]);
    }

    #[test]
    fn unavailable_glyph_never_concatenates_its_visible_neighbors() {
        let nodes = ratex_parser::parse("abc").unwrap();
        let root = layout(&nodes, &LayoutOptions::default());
        let list = to_display_list(&root);
        let mut semantics = Semantics::new(&root, &list.items).unwrap();
        for (index, item) in list.items.iter().enumerate() {
            if let DisplayItem::GlyphPath { char_code, .. } = item {
                let code = if *char_code == u32::from('b') {
                    0xe000
                } else {
                    *char_code
                };
                semantics
                    .glyph(index, code, &[0.0, 0.0, 0.0, 1.0, 1.0, 1.0])
                    .unwrap();
            }
        }
        let (complete, runs) = semantics.finish().unwrap();
        assert!(!complete);
        assert!(runs.is_empty());
    }
}
