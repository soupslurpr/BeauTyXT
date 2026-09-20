//! Independent AST admission before any layout amplification or geometry allocation.

use beautyxt_illustration_core::DrawingError;
use ratex_parser::parse_node::{ArrayTag, Measurement, ParseNode};

const MAX_NODES: usize = 4_096;
const MAX_ARRAY_SIDE: usize = 64;
const MAX_ARRAY_CELLS: usize = 1_024;
const MAX_MEASUREMENT: f64 = 64.0;

pub(crate) fn validate(nodes: &[ParseNode]) -> Result<(), DrawingError> {
    let mut pending = Vec::new();
    let mut count = 0;
    let mut cells = 0;
    extend(&mut pending, nodes, count)?;
    while let Some(node) = pending.pop() {
        count += 1;
        if count > MAX_NODES {
            return Err(DrawingError::Limit);
        }
        admit_node(node, &mut pending, count, &mut cells)?;
        if count + pending.len() > MAX_NODES {
            return Err(DrawingError::Limit);
        }
    }
    Ok(())
}

fn admit_node<'a>(
    node: &'a ParseNode,
    pending: &mut Vec<&'a ParseNode>,
    count: usize,
    cells: &mut usize,
) -> Result<(), DrawingError> {
    match node {
        ParseNode::Atom { .. }
        | ParseNode::MathOrd { .. }
        | ParseNode::TextOrd { .. }
        | ParseNode::OpToken { .. }
        | ParseNode::AccentToken { .. }
        | ParseNode::SpacingNode { .. }
        | ParseNode::Middle { .. }
        | ParseNode::NoNumber { .. }
        | ParseNode::Internal { .. } => {}
        ParseNode::OrdGroup { body, .. }
        | ParseNode::Text { body, .. }
        | ParseNode::Color { body, .. }
        | ParseNode::Styling { body, .. }
        | ParseNode::LeftRight { body, .. }
        | ParseNode::OperatorName { body, .. }
        | ParseNode::MClass { body, .. }
        | ParseNode::Phantom { body, .. }
        | ParseNode::HBox { body, .. } => extend(pending, body, count)?,
        ParseNode::SupSub { base, sup, sub, .. } => {
            pending.extend(
                [base, sup, sub]
                    .into_iter()
                    .filter_map(|node| node.as_deref()),
            );
        }
        ParseNode::GenFrac {
            numer,
            denom,
            bar_size,
            ..
        } => {
            optional_measurement(bar_size.as_ref())?;
            pending.extend([numer.as_ref(), denom.as_ref()]);
        }
        ParseNode::Sqrt { body, index, .. } => {
            pending.push(body);
            pending.extend(index.as_deref());
        }
        ParseNode::Accent { base, .. }
        | ParseNode::AccentUnder { base, .. }
        | ParseNode::HorizBrace { base, .. } => pending.push(base),
        ParseNode::Font { body, .. }
        | ParseNode::Overline { body, .. }
        | ParseNode::Underline { body, .. }
        | ParseNode::VPhantom { body, .. }
        | ParseNode::VCenter { body, .. }
        | ParseNode::Enclose { body, .. } => pending.push(body),
        ParseNode::Op { body, .. } => {
            if let Some(body) = body {
                extend(pending, body, count)?;
            }
        }
        ParseNode::Sizing { size, body, .. } => {
            if !(1..=11).contains(size) {
                return Err(DrawingError::Unsupported);
            }
            extend(pending, body, count)?;
        }
        ParseNode::DelimSizing { size, .. } => {
            if !(1..=4).contains(size) {
                return Err(DrawingError::Unsupported);
            }
        }
        ParseNode::Rule {
            shift,
            width,
            height,
            ..
        } => {
            optional_measurement(shift.as_ref())?;
            measurement(width)?;
            measurement(height)?;
        }
        ParseNode::Kern { dimension, .. } => measurement(dimension)?,
        ParseNode::RaiseBox { dy, body, .. } => {
            measurement(dy)?;
            pending.push(body);
        }
        ParseNode::XArrow { body, below, .. } => {
            pending.push(body);
            pending.extend(below.as_deref());
        }
        ParseNode::Cr { size, .. } => optional_measurement(size.as_ref())?,
        ParseNode::Array { .. } => admit_array(node, pending, count, cells)?,
        // A new upstream node must be reviewed, not automatically gain rendering authority.
        _ => return Err(DrawingError::Unsupported),
    }
    Ok(())
}

fn admit_array<'a>(
    node: &'a ParseNode,
    pending: &mut Vec<&'a ParseNode>,
    count: usize,
    cells: &mut usize,
) -> Result<(), DrawingError> {
    let ParseNode::Array {
        body,
        row_gaps,
        cols,
        arraystretch,
        tags,
        is_cd,
        ..
    } = node
    else {
        return Err(DrawingError::Invalid);
    };
    let columns = body
        .iter()
        .map(Vec::len)
        .max()
        .unwrap_or(0)
        .max(cols.as_ref().map_or(0, Vec::len));
    if body.len() > MAX_ARRAY_SIDE
        || columns > MAX_ARRAY_SIDE
        || !arraystretch.is_finite()
        || !(0.0..=4.0).contains(arraystretch)
        || is_cd == &Some(true)
    {
        return Err(DrawingError::Limit);
    }
    *cells += body.len() * columns;
    if *cells > MAX_ARRAY_CELLS {
        return Err(DrawingError::Limit);
    }
    for gap in row_gaps {
        optional_measurement(gap.as_ref())?;
    }
    for row in body {
        extend(pending, row, count)?;
    }
    for tag in tags.iter().flatten() {
        if let ArrayTag::Explicit(nodes) = tag {
            extend(pending, nodes, count)?;
        }
    }
    Ok(())
}

fn extend<'a>(
    pending: &mut Vec<&'a ParseNode>,
    nodes: &'a [ParseNode],
    seen: usize,
) -> Result<(), DrawingError> {
    if nodes.len() > MAX_NODES - seen - pending.len() {
        return Err(DrawingError::Limit);
    }
    pending.extend(nodes);
    Ok(())
}

fn optional_measurement(value: Option<&Measurement>) -> Result<(), DrawingError> {
    value.map_or(Ok(()), measurement)
}

fn measurement(value: &Measurement) -> Result<(), DrawingError> {
    if !value.number.is_finite() || value.number.abs() > MAX_MEASUREMENT {
        return Err(DrawingError::Limit);
    }
    Ok(())
}

/// Environment names are literal, never macro-expanded into a different parser family.
pub(crate) fn environment(
    characters: &mut std::iter::Peekable<std::str::Chars<'_>>,
) -> Result<(), DrawingError> {
    while characters.peek().is_some_and(char::is_ascii_whitespace) {
        characters.next();
    }
    if characters.next() != Some('{') {
        return Err(DrawingError::Unsupported);
    }
    let mut name = String::new();
    loop {
        match characters.next() {
            Some('}') => break,
            Some(character)
                if (character.is_ascii_alphabetic() || character == '*') && name.len() < 16 =>
            {
                name.push(character);
            }
            _ => return Err(DrawingError::Unsupported),
        }
    }
    if matches!(
        name.as_str(),
        "matrix"
            | "pmatrix"
            | "bmatrix"
            | "Bmatrix"
            | "vmatrix"
            | "Vmatrix"
            | "smallmatrix"
            | "array"
            | "aligned"
            | "align"
            | "align*"
            | "gathered"
            | "gather"
            | "gather*"
            | "cases"
            | "dcases"
            | "rcases"
            | "drcases"
            | "split"
    ) {
        Ok(())
    } else {
        Err(DrawingError::Unsupported)
    }
}
