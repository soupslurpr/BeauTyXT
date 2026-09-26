//! Retains authored text spaces in a metadata-only layout; math layout kerns stay unsearchable.

use ratex_parser::parse_node::{ArrayTag, Mode, ParseNode};

pub(super) fn promote(nodes: &mut [ParseNode]) {
    let mut pending: Vec<_> = nodes.iter_mut().collect();
    while let Some(node) = pending.pop() {
        if matches!(node, ParseNode::SpacingNode { mode: Mode::Text, text, .. } if text == " ") {
            // Main font NBSP has the same advance as the literal text space. The semantic
            // adapter verifies every original glyph's position before accepting this layout.
            *node = ParseNode::TextOrd {
                mode: Mode::Text,
                text: "\u{a0}".into(),
                loc: None,
            };
        }
        match node {
            ParseNode::OrdGroup { body, .. }
            | ParseNode::Text { body, .. }
            | ParseNode::Color { body, .. }
            | ParseNode::Styling { body, .. }
            | ParseNode::LeftRight { body, .. }
            | ParseNode::OperatorName { body, .. }
            | ParseNode::MClass { body, .. }
            | ParseNode::Phantom { body, .. }
            | ParseNode::HBox { body, .. }
            | ParseNode::Sizing { body, .. }
            | ParseNode::Op {
                body: Some(body), ..
            } => pending.extend(body),
            ParseNode::SupSub { base, sup, sub, .. } => {
                pending.extend(
                    [base, sup, sub]
                        .into_iter()
                        .filter_map(Option::as_deref_mut),
                );
            }
            ParseNode::GenFrac { numer, denom, .. } => {
                pending.extend([numer.as_mut(), denom.as_mut()]);
            }
            ParseNode::Sqrt { body, index, .. } => {
                pending.push(body);
                pending.extend(index.as_deref_mut());
            }
            ParseNode::Accent { base, .. }
            | ParseNode::AccentUnder { base, .. }
            | ParseNode::HorizBrace { base, .. } => pending.push(base),
            ParseNode::Font { body, .. }
            | ParseNode::Overline { body, .. }
            | ParseNode::Underline { body, .. }
            | ParseNode::VPhantom { body, .. }
            | ParseNode::VCenter { body, .. }
            | ParseNode::Enclose { body, .. }
            | ParseNode::RaiseBox { body, .. } => pending.push(body),
            ParseNode::XArrow { body, below, .. } => {
                pending.push(body);
                pending.extend(below.as_deref_mut());
            }
            ParseNode::Array { body, tags, .. } => {
                pending.extend(body.iter_mut().flatten());
                for tag in tags.iter_mut().flatten() {
                    if let ArrayTag::Explicit(nodes) = tag {
                        pending.extend(nodes);
                    }
                }
            }
            _ => {}
        }
    }
}
