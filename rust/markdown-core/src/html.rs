//! Parses a narrow, non-executable HTML subset for Markdown preview.

use std::cell::RefCell;

use html5ever::TokenizerResult;
use html5ever::tendril::StrTendril;
use html5ever::tokenizer::{
    BufferQueue, EndTag, StartTag, TagToken, Token, TokenSink, TokenSinkResult, Tokenizer,
    TokenizerOpts,
};

use super::{
    BLOCK_FLAG_LIST_ITEM_CONTINUATION, BLOCK_FLAG_ORDERED_LIST, BLOCK_FLAG_TABLE_HEADER,
    BLOCK_KIND_CODE, BLOCK_KIND_HEADING, BLOCK_KIND_LIST_ITEM, BLOCK_KIND_PARAGRAPH,
    BLOCK_KIND_RULE, BLOCK_KIND_TABLE_ROW, BlockBuffer, BlockSpec, IMAGE_PREFIX,
    InlineDestinationKind, MAX_LINK_DESTINATION_BYTES, MAX_LIST_DEPTH, MAX_NESTING_DEPTH,
    MAX_PACKET_BYTES, MAX_QUOTE_DEPTH, RenderBlock, RenderError, SPAN_STYLE_CODE,
    SPAN_STYLE_EMPHASIS, SPAN_STYLE_STRIKETHROUGH, SPAN_STYLE_STRONG, SPAN_STYLE_SUBSCRIPT,
    SPAN_STYLE_SUPERSCRIPT, TABLE_ALIGNMENT_NONE, TABLE_CELL_SEPARATOR,
};

const MAX_SAFE_HTML_TOKEN_COUNT: usize = 131_072;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum SafeHtmlInlineTag {
    Bold,
    Strong,
    Emphasis,
    Italic,
    Delete,
    Strikethrough,
    Strike,
    Code,
    Keyboard,
    Superscript,
    Subscript,
    Span,
    Anchor,
}

impl SafeHtmlInlineTag {
    /// Returns the safe tag represented by one normalized HTML name.
    fn from_name(name: &str) -> Option<Self> {
        match name {
            "b" => Some(Self::Bold),
            "strong" => Some(Self::Strong),
            "em" => Some(Self::Emphasis),
            "i" => Some(Self::Italic),
            "del" => Some(Self::Delete),
            "s" => Some(Self::Strikethrough),
            "strike" => Some(Self::Strike),
            "code" => Some(Self::Code),
            "kbd" => Some(Self::Keyboard),
            "sup" => Some(Self::Superscript),
            "sub" => Some(Self::Subscript),
            "span" => Some(Self::Span),
            "a" => Some(Self::Anchor),
            _ => None,
        }
    }

    /// Returns the existing Markdown span style for one safe HTML tag.
    pub(super) const fn styles(self) -> u32 {
        match self {
            Self::Bold | Self::Strong => SPAN_STYLE_STRONG,
            Self::Emphasis | Self::Italic => SPAN_STYLE_EMPHASIS,
            Self::Delete | Self::Strikethrough | Self::Strike => SPAN_STYLE_STRIKETHROUGH,
            Self::Code | Self::Keyboard => SPAN_STYLE_CODE,
            Self::Superscript => SPAN_STYLE_SUPERSCRIPT,
            Self::Subscript => SPAN_STYLE_SUBSCRIPT,
            Self::Span | Self::Anchor => 0,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) enum SafeHtmlInlineToken {
    Start {
        tag: SafeHtmlInlineTag,
        destination: Option<String>,
    },
    End(SafeHtmlInlineTag),
    Break,
    Image(String),
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SafeHtmlAttribute {
    name: String,
    value: String,
}

#[derive(Debug)]
enum SafeHtmlToken {
    Start {
        name: String,
        self_closing: bool,
        attributes: Vec<SafeHtmlAttribute>,
    },
    End {
        name: String,
    },
    Text(String),
}

#[derive(Debug, Default)]
struct SafeHtmlTokenization {
    tokens: Vec<SafeHtmlToken>,
    invalid: bool,
}

#[derive(Debug, Default)]
struct SafeHtmlTokenSink {
    tokenization: RefCell<SafeHtmlTokenization>,
}

impl TokenSink for SafeHtmlTokenSink {
    type Handle = ();

    fn process_token(&self, token: Token, _line_number: u64) -> TokenSinkResult<Self::Handle> {
        let mut tokenization = self.tokenization.borrow_mut();
        if tokenization.tokens.len() >= MAX_SAFE_HTML_TOKEN_COUNT {
            tokenization.invalid = true;
            return TokenSinkResult::Continue;
        }
        match token {
            TagToken(tag) => {
                if tag.had_duplicate_attributes {
                    tokenization.invalid = true;
                }
                if tag
                    .attrs
                    .iter()
                    .any(|attribute| attribute.name.prefix.is_some())
                {
                    tokenization.invalid = true;
                }
                let name = tag.name.to_string();
                match tag.kind {
                    StartTag => {
                        let attributes = tag
                            .attrs
                            .into_iter()
                            .map(|attribute| SafeHtmlAttribute {
                                name: attribute.name.local.to_string(),
                                value: attribute.value.to_string(),
                            })
                            .collect();
                        tokenization.tokens.push(SafeHtmlToken::Start {
                            name,
                            self_closing: tag.self_closing,
                            attributes,
                        });
                    }
                    EndTag => tokenization.tokens.push(SafeHtmlToken::End { name }),
                }
            }
            Token::CharacterTokens(text) => {
                if !text.is_empty() {
                    tokenization
                        .tokens
                        .push(SafeHtmlToken::Text(text.to_string()));
                }
            }
            Token::EOFToken => {}
            Token::CommentToken(_)
            | Token::DoctypeToken(_)
            | Token::NullCharacterToken
            | Token::ParseError(_) => tokenization.invalid = true,
        }
        TokenSinkResult::Continue
    }
}

#[derive(Clone, Debug)]
enum SafeHtmlNode {
    Element(SafeHtmlElement),
    Text(String),
}

#[derive(Clone, Debug)]
struct SafeHtmlElement {
    name: String,
    attributes: Vec<SafeHtmlAttribute>,
    children: Vec<SafeHtmlNode>,
}

#[derive(Clone, Copy, Debug)]
struct SafeHtmlBlockContext {
    quote_depth: u32,
    max_packet_bytes: usize,
}

/// Tokenizes one bounded HTML fragment with browser-grade entity handling.
fn tokenize_safe_html(source: &str) -> Option<Vec<SafeHtmlToken>> {
    let input = BufferQueue::default();
    input.push_back(StrTendril::from(source));
    let tokenizer = Tokenizer::new(SafeHtmlTokenSink::default(), TokenizerOpts::default());
    if !matches!(tokenizer.feed(&input), TokenizerResult::Done) || !input.is_empty() {
        return None;
    }
    tokenizer.end();
    let tokenization = tokenizer.sink.tokenization.into_inner();
    (!tokenization.invalid).then_some(tokenization.tokens)
}

/// Parses one bounded token sequence into a strictly balanced fragment tree.
fn parse_safe_html_nodes(source: &str) -> Option<Vec<SafeHtmlNode>> {
    let tokens = tokenize_safe_html(source)?;
    let mut token_index = 0_usize;
    let nodes = parse_safe_html_children(&tokens, &mut token_index, None, 0)?;
    (token_index == tokens.len()).then_some(nodes)
}

/// Parses children until one exact closing tag or the fragment root.
fn parse_safe_html_children(
    tokens: &[SafeHtmlToken],
    token_index: &mut usize,
    closing_name: Option<&str>,
    depth: usize,
) -> Option<Vec<SafeHtmlNode>> {
    if depth > MAX_NESTING_DEPTH {
        return None;
    }
    let mut nodes = Vec::new();
    while let Some(token) = tokens.get(*token_index) {
        match token {
            SafeHtmlToken::Text(text) => {
                nodes.push(SafeHtmlNode::Text(text.clone()));
                *token_index += 1;
            }
            SafeHtmlToken::Start {
                name,
                self_closing,
                attributes,
            } => {
                let name = name.clone();
                let attributes = attributes.clone();
                let is_void = is_safe_html_void_element(&name);
                if *self_closing && !is_void {
                    return None;
                }
                *token_index += 1;
                let children = if is_void {
                    Vec::new()
                } else {
                    parse_safe_html_children(
                        tokens,
                        token_index,
                        Some(&name),
                        depth.checked_add(1)?,
                    )?
                };
                nodes.push(SafeHtmlNode::Element(SafeHtmlElement {
                    name,
                    attributes,
                    children,
                }));
            }
            SafeHtmlToken::End { name } if closing_name == Some(name.as_str()) => {
                *token_index += 1;
                return Some(nodes);
            }
            SafeHtmlToken::End { .. } => return None,
        }
    }
    closing_name.is_none().then_some(nodes)
}

/// Returns whether one allowlisted element never has children.
fn is_safe_html_void_element(name: &str) -> bool {
    matches!(name, "br" | "hr" | "img")
}

/// Parses one standalone allowlisted inline tag emitted by the Markdown parser.
pub(super) fn parse_safe_inline_tag(source: &str) -> Option<SafeHtmlInlineToken> {
    let tokens = tokenize_safe_html(source)?;
    let mut significant_tokens = tokens
        .iter()
        .filter(|token| !is_inter_element_whitespace_token(token));
    let token = significant_tokens.next()?;
    if significant_tokens.next().is_some() {
        return None;
    }
    match token {
        SafeHtmlToken::Start {
            name, attributes, ..
        } if name == "br" && attributes.is_empty() => Some(SafeHtmlInlineToken::Break),
        SafeHtmlToken::Start {
            name, attributes, ..
        } if name == "img" => image_label(attributes).map(SafeHtmlInlineToken::Image),
        SafeHtmlToken::Start {
            name,
            self_closing: false,
            attributes,
        } => {
            let tag = SafeHtmlInlineTag::from_name(name)?;
            let destination = if tag == SafeHtmlInlineTag::Anchor {
                Some(anchor_destination(attributes)?)
            } else {
                if !attributes.is_empty() {
                    return None;
                }
                None
            };
            Some(SafeHtmlInlineToken::Start { tag, destination })
        }
        SafeHtmlToken::End { name } => {
            SafeHtmlInlineTag::from_name(name).map(SafeHtmlInlineToken::End)
        }
        _ => None,
    }
}

/// Converts complete safe HTML block elements into ordinary bounded render blocks.
pub(super) fn render_safe_html_blocks(
    source: &str,
    inherited_quote_depth: u32,
) -> Result<Option<BlockBuffer>, RenderError> {
    let Some(nodes) = parse_safe_html_nodes(source) else {
        return Ok(None);
    };
    let Some(blocks) = render_flow_nodes(
        &nodes,
        SafeHtmlBlockContext {
            quote_depth: inherited_quote_depth,
            max_packet_bytes: MAX_PACKET_BYTES,
        },
    )?
    else {
        return Ok(None);
    };
    if blocks.is_empty() {
        Ok(None)
    } else {
        Ok(Some(blocks))
    }
}

/// Renders one mixed flow-content sequence without browser execution.
fn render_flow_nodes(
    nodes: &[SafeHtmlNode],
    context: SafeHtmlBlockContext,
) -> Result<Option<BlockBuffer>, RenderError> {
    let mut blocks = BlockBuffer::with_byte_limit(context.max_packet_bytes);
    let mut node_index = 0_usize;
    while node_index < nodes.len() {
        if is_safe_html_inline_node(&nodes[node_index]) {
            let first_inline = node_index;
            while node_index < nodes.len() && is_safe_html_inline_node(&nodes[node_index]) {
                node_index += 1;
            }
            let Some(block) = render_inline_block(
                &nodes[first_inline..node_index],
                ordinary_block_spec(BLOCK_KIND_PARAGRAPH, 0, context),
            )?
            else {
                return Ok(None);
            };
            if !block.text.is_empty() {
                blocks.push(block)?;
            }
            continue;
        }
        let SafeHtmlNode::Element(element) = &nodes[node_index] else {
            return Ok(None);
        };
        let child_context = SafeHtmlBlockContext {
            max_packet_bytes: blocks.remaining_packet_bytes(),
            ..context
        };
        let Some(rendered) = render_block_element(element, child_context)? else {
            return Ok(None);
        };
        blocks.append(rendered)?;
        node_index += 1;
    }
    Ok(Some(blocks))
}

/// Renders one allowlisted block element into one or more presentation blocks.
fn render_block_element(
    element: &SafeHtmlElement,
    context: SafeHtmlBlockContext,
) -> Result<Option<BlockBuffer>, RenderError> {
    if !element.attributes.is_empty() && element.name != "ol" {
        return Ok(None);
    }
    let blocks = match element.name.as_str() {
        "p" => {
            let Some(block) = render_inline_block(
                &element.children,
                ordinary_block_spec(BLOCK_KIND_PARAGRAPH, 0, context),
            )?
            else {
                return Ok(None);
            };
            BlockBuffer::from_block(block)?
        }
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            let Some(heading_level) = element.name[1..].parse::<u32>().ok() else {
                return Ok(None);
            };
            let Some(block) = render_inline_block(
                &element.children,
                ordinary_block_spec(BLOCK_KIND_HEADING, heading_level, context),
            )?
            else {
                return Ok(None);
            };
            BlockBuffer::from_block(block)?
        }
        "div" | "section" | "article" | "header" | "footer" | "main" | "aside" => {
            let Some(blocks) = render_flow_nodes(&element.children, context)? else {
                return Ok(None);
            };
            blocks
        }
        "blockquote" => {
            let quote_depth = context
                .quote_depth
                .checked_add(1)
                .ok_or(RenderError::ArithmeticOverflow)?;
            if quote_depth > MAX_QUOTE_DEPTH {
                return Err(RenderError::NestingLimit);
            }
            let Some(blocks) = render_flow_nodes(
                &element.children,
                SafeHtmlBlockContext {
                    quote_depth,
                    ..context
                },
            )?
            else {
                return Ok(None);
            };
            blocks
        }
        "ul" | "ol" => {
            let Some(blocks) = render_list(element, context, 1)? else {
                return Ok(None);
            };
            blocks
        }
        "pre" => {
            let Some(block) = render_preformatted_block(
                element,
                ordinary_block_spec(BLOCK_KIND_CODE, 0, context),
                0,
            )?
            else {
                return Ok(None);
            };
            BlockBuffer::from_block(block)?
        }
        "table" => {
            let Some(blocks) = render_table(element, context)? else {
                return Ok(None);
            };
            blocks
        }
        "hr" if element.children.is_empty() => BlockBuffer::from_block(RenderBlock::new(
            ordinary_block_spec(BLOCK_KIND_RULE, 0, context),
        ))?,
        _ => return Ok(None),
    };
    Ok(Some(blocks))
}

/// Returns ordinary block fields under one inherited quote context.
fn ordinary_block_spec(kind: u32, heading_level: u32, context: SafeHtmlBlockContext) -> BlockSpec {
    BlockSpec {
        kind,
        flags: 0,
        heading_level,
        quote_depth: context.quote_depth,
        list_depth: 0,
        list_number: 0,
        metadata: String::new(),
    }
}

/// Renders one inline-content sequence with collapsed HTML whitespace.
fn render_inline_block(
    nodes: &[SafeHtmlNode],
    spec: BlockSpec,
) -> Result<Option<RenderBlock>, RenderError> {
    if !nodes.iter().all(is_safe_html_inline_node) {
        return Ok(None);
    }
    let mut renderer = SafeHtmlInlineRenderer::new(spec);
    if !renderer.render_nodes(nodes, 0, None)? {
        return Ok(None);
    }
    Ok(Some(renderer.finish()))
}

/// Renders one ordered or unordered HTML list at a bounded depth.
fn render_list(
    element: &SafeHtmlElement,
    context: SafeHtmlBlockContext,
    list_depth: u32,
) -> Result<Option<BlockBuffer>, RenderError> {
    if list_depth == 0 || list_depth > MAX_LIST_DEPTH {
        return Err(RenderError::NestingLimit);
    }
    let ordered = element.name == "ol";
    let Some(start_number) = list_start_number(element, ordered) else {
        return Ok(None);
    };
    let mut blocks = BlockBuffer::with_byte_limit(context.max_packet_bytes);
    let mut item_index = 0_u64;
    for node in &element.children {
        if is_inter_element_whitespace_node(node) {
            continue;
        }
        let SafeHtmlNode::Element(item) = node else {
            return Ok(None);
        };
        if item.name != "li" || !item.attributes.is_empty() {
            return Ok(None);
        }
        let number = start_number
            .checked_add(item_index)
            .ok_or(RenderError::ArithmeticOverflow)?;
        let item_context = SafeHtmlBlockContext {
            max_packet_bytes: blocks.remaining_packet_bytes(),
            ..context
        };
        let Some(item_blocks) = render_list_item(item, item_context, list_depth, ordered, number)?
        else {
            return Ok(None);
        };
        blocks.append(item_blocks)?;
        item_index = item_index
            .checked_add(1)
            .ok_or(RenderError::ArithmeticOverflow)?;
    }
    if blocks.is_empty() {
        Ok(None)
    } else {
        Ok(Some(blocks))
    }
}

/// Renders one list item and any directly nested lists in source order.
fn render_list_item(
    item: &SafeHtmlElement,
    context: SafeHtmlBlockContext,
    list_depth: u32,
    ordered: bool,
    number: u64,
) -> Result<Option<BlockBuffer>, RenderError> {
    let mut renderer = SafeHtmlListItemRenderer::new(context, list_depth, ordered, number);
    if !renderer.render_nodes(&item.children)? {
        return Ok(None);
    }
    Ok(Some(renderer.finish()?))
}

#[derive(Debug)]
struct SafeHtmlListItemRenderer {
    context: SafeHtmlBlockContext,
    list_depth: u32,
    ordered: bool,
    number: u64,
    first_content: bool,
    blocks: BlockBuffer,
}

impl SafeHtmlListItemRenderer {
    /// Creates one renderer that retains a single marker across item content.
    fn new(context: SafeHtmlBlockContext, list_depth: u32, ordered: bool, number: u64) -> Self {
        Self {
            context,
            list_depth,
            ordered,
            number,
            first_content: true,
            blocks: BlockBuffer::with_byte_limit(context.max_packet_bytes),
        }
    }

    /// Renders direct item children and transparent division wrappers.
    fn render_nodes(&mut self, nodes: &[SafeHtmlNode]) -> Result<bool, RenderError> {
        let mut node_index = 0_usize;
        while node_index < nodes.len() {
            if is_safe_html_inline_node(&nodes[node_index]) {
                let first_inline = node_index;
                while node_index < nodes.len() && is_safe_html_inline_node(&nodes[node_index]) {
                    node_index += 1;
                }
                if !self.render_inline_nodes(&nodes[first_inline..node_index])? {
                    return Ok(false);
                }
                continue;
            }
            let SafeHtmlNode::Element(element) = &nodes[node_index] else {
                return Ok(false);
            };
            if !self.render_block_element(element)? {
                return Ok(false);
            }
            node_index += 1;
        }
        Ok(true)
    }

    /// Renders one direct inline run unless it is only source indentation.
    fn render_inline_nodes(&mut self, nodes: &[SafeHtmlNode]) -> Result<bool, RenderError> {
        if nodes.iter().all(is_inter_element_whitespace_node) {
            return Ok(true);
        }
        let Some(block) = render_inline_block(nodes, self.block_spec())? else {
            return Ok(false);
        };
        if self.first_content || !block.text.is_empty() {
            self.blocks.push(block)?;
            self.first_content = false;
        }
        Ok(true)
    }

    /// Renders one allowlisted direct block child.
    fn render_block_element(&mut self, element: &SafeHtmlElement) -> Result<bool, RenderError> {
        match element.name.as_str() {
            "p" => {
                if !element.attributes.is_empty() {
                    return Ok(false);
                }
                let Some(block) = render_inline_block(&element.children, self.block_spec())? else {
                    return Ok(false);
                };
                self.blocks.push(block)?;
                self.first_content = false;
            }
            "div" if element.attributes.is_empty() => {
                if !self.render_nodes(&element.children)? {
                    return Ok(false);
                }
            }
            "pre" if element.attributes.is_empty() => {
                let Some(block) =
                    render_preformatted_block(element, self.block_spec(), SPAN_STYLE_CODE)?
                else {
                    return Ok(false);
                };
                self.blocks.push(block)?;
                self.first_content = false;
            }
            "ul" | "ol" => {
                if self.first_content {
                    self.blocks.push(RenderBlock::new(self.block_spec()))?;
                    self.first_content = false;
                }
                let nested_depth = self
                    .list_depth
                    .checked_add(1)
                    .ok_or(RenderError::ArithmeticOverflow)?;
                let nested_context = SafeHtmlBlockContext {
                    max_packet_bytes: self.blocks.remaining_packet_bytes(),
                    ..self.context
                };
                let Some(nested) = render_list(element, nested_context, nested_depth)? else {
                    return Ok(false);
                };
                self.blocks.append(nested)?;
            }
            _ => return Ok(false),
        }
        Ok(true)
    }

    /// Returns the current flattened direct-content block fields.
    fn block_spec(&self) -> BlockSpec {
        list_item_spec(
            self.context,
            self.list_depth,
            self.ordered,
            self.number,
            self.first_content,
        )
    }

    /// Returns every rendered block, adding an empty marker for an empty item.
    fn finish(mut self) -> Result<BlockBuffer, RenderError> {
        if self.first_content {
            self.blocks.push(RenderBlock::new(self.block_spec()))?;
        }
        Ok(self.blocks)
    }
}

/// Returns flattened list-item fields for one direct content block.
fn list_item_spec(
    context: SafeHtmlBlockContext,
    list_depth: u32,
    ordered: bool,
    number: u64,
    first_content: bool,
) -> BlockSpec {
    let mut flags = if ordered { BLOCK_FLAG_ORDERED_LIST } else { 0 };
    if !first_content {
        flags |= BLOCK_FLAG_LIST_ITEM_CONTINUATION;
    }
    BlockSpec {
        kind: BLOCK_KIND_LIST_ITEM,
        flags,
        heading_level: 0,
        quote_depth: context.quote_depth,
        list_depth,
        list_number: if ordered && first_content { number } else { 0 },
        metadata: String::new(),
    }
}

/// Returns the first list number after validating the element's attributes.
fn list_start_number(element: &SafeHtmlElement, ordered: bool) -> Option<u64> {
    if !ordered {
        return element.attributes.is_empty().then_some(1);
    }
    if !attributes_are_allowlisted(&element.attributes, &["start"]) {
        return None;
    }
    match attribute_value(&element.attributes, "start") {
        Some(value) => value.parse().ok(),
        None => Some(1),
    }
}

/// Renders preformatted HTML text without collapsing whitespace.
fn render_preformatted_block(
    element: &SafeHtmlElement,
    spec: BlockSpec,
    styles: u32,
) -> Result<Option<RenderBlock>, RenderError> {
    if !element.attributes.is_empty() {
        return Ok(None);
    }
    let text = if let [SafeHtmlNode::Element(code)] = element.children.as_slice() {
        if code.name != "code"
            || !code.attributes.is_empty()
            || !code
                .children
                .iter()
                .all(|node| matches!(node, SafeHtmlNode::Text(_)))
        {
            return Ok(None);
        }
        concatenate_text_nodes(&code.children)
    } else {
        if !element
            .children
            .iter()
            .all(|node| matches!(node, SafeHtmlNode::Text(_)))
        {
            return Ok(None);
        }
        concatenate_text_nodes(&element.children)
    };
    let mut block = RenderBlock::new(spec);
    block.append(&text, styles, None)?;
    Ok(Some(block))
}

/// Concatenates an already validated sequence of text-only nodes.
fn concatenate_text_nodes(nodes: &[SafeHtmlNode]) -> String {
    let mut text = String::new();
    for node in nodes {
        if let SafeHtmlNode::Text(fragment) = node {
            text.push_str(fragment);
        }
    }
    text
}

/// Renders one semantic HTML table and optional caption.
fn render_table(
    table: &SafeHtmlElement,
    context: SafeHtmlBlockContext,
) -> Result<Option<BlockBuffer>, RenderError> {
    if !table.attributes.is_empty() {
        return Ok(None);
    }
    let mut blocks = BlockBuffer::with_byte_limit(context.max_packet_bytes);
    for node in &table.children {
        if is_inter_element_whitespace_node(node) {
            continue;
        }
        let SafeHtmlNode::Element(element) = node else {
            return Ok(None);
        };
        match element.name.as_str() {
            "caption" if element.attributes.is_empty() => {
                let Some(caption) = render_inline_block(
                    &element.children,
                    ordinary_block_spec(BLOCK_KIND_PARAGRAPH, 0, context),
                )?
                else {
                    return Ok(None);
                };
                blocks.push(caption)?;
            }
            "tr" => {
                let Some(row) = render_table_row(element, context, false)? else {
                    return Ok(None);
                };
                blocks.push(row)?;
            }
            "thead" | "tbody" | "tfoot" if element.attributes.is_empty() => {
                let section_header = element.name == "thead";
                for row_node in &element.children {
                    if is_inter_element_whitespace_node(row_node) {
                        continue;
                    }
                    let SafeHtmlNode::Element(row) = row_node else {
                        return Ok(None);
                    };
                    let Some(row) = render_table_row(row, context, section_header)? else {
                        return Ok(None);
                    };
                    blocks.push(row)?;
                }
            }
            _ => return Ok(None),
        }
    }
    if let Some(first_row) = blocks
        .blocks
        .iter_mut()
        .find(|block| block.spec.kind == BLOCK_KIND_TABLE_ROW)
    {
        first_row.spec.flags |= super::BLOCK_FLAG_TABLE_START;
        Ok(Some(blocks))
    } else {
        Ok(None)
    }
}

/// Renders one HTML table row with safe inline cell content.
fn render_table_row(
    row: &SafeHtmlElement,
    context: SafeHtmlBlockContext,
    section_header: bool,
) -> Result<Option<RenderBlock>, RenderError> {
    if row.name != "tr" || !row.attributes.is_empty() {
        return Ok(None);
    }
    let mut cells = Vec::new();
    for node in &row.children {
        if is_inter_element_whitespace_node(node) {
            continue;
        }
        let SafeHtmlNode::Element(cell) = node else {
            return Ok(None);
        };
        if !matches!(cell.name.as_str(), "th" | "td")
            || !cell.attributes.is_empty()
            || !cell.children.iter().all(is_safe_html_inline_node)
        {
            return Ok(None);
        }
        cells.push(cell);
    }
    if cells.is_empty() {
        return Ok(None);
    }
    let is_header = section_header || cells.iter().all(|cell| cell.name == "th");
    let mut renderer = SafeHtmlInlineRenderer::new(BlockSpec {
        kind: BLOCK_KIND_TABLE_ROW,
        flags: if is_header {
            BLOCK_FLAG_TABLE_HEADER
        } else {
            0
        },
        heading_level: 0,
        quote_depth: context.quote_depth,
        list_depth: 0,
        list_number: 0,
        metadata: std::iter::repeat_n(TABLE_ALIGNMENT_NONE, cells.len()).collect(),
    });
    for (cell_index, cell) in cells.iter().enumerate() {
        if cell_index != 0 {
            renderer.append_cell_separator()?;
        }
        if !renderer.render_nodes(&cell.children, 0, None)? {
            return Ok(None);
        }
        renderer.end_cell();
    }
    Ok(Some(renderer.finish()))
}

#[derive(Debug)]
struct SafeHtmlInlineRenderer {
    block: RenderBlock,
    pending_space: bool,
}

impl SafeHtmlInlineRenderer {
    /// Creates one inline renderer for an already validated block shape.
    fn new(spec: BlockSpec) -> Self {
        Self {
            block: RenderBlock::new(spec),
            pending_space: false,
        }
    }

    /// Renders a safe inline node sequence with inherited style and destination.
    fn render_nodes(
        &mut self,
        nodes: &[SafeHtmlNode],
        styles: u32,
        destination: Option<&str>,
    ) -> Result<bool, RenderError> {
        for node in nodes {
            match node {
                SafeHtmlNode::Text(text) => self.append_text(text, styles, destination)?,
                SafeHtmlNode::Element(element) => {
                    if !self.render_element(element, styles, destination)? {
                        return Ok(false);
                    }
                }
            }
        }
        Ok(true)
    }

    /// Renders one safe inline element without executing or fetching content.
    fn render_element(
        &mut self,
        element: &SafeHtmlElement,
        styles: u32,
        destination: Option<&str>,
    ) -> Result<bool, RenderError> {
        let Some(tag) = SafeHtmlInlineTag::from_name(&element.name) else {
            return match element.name.as_str() {
                "br" if element.attributes.is_empty() && element.children.is_empty() => {
                    self.append_break(styles, destination)?;
                    Ok(true)
                }
                "img" if element.children.is_empty() => {
                    let Some(label) = image_label(&element.attributes) else {
                        return Ok(false);
                    };
                    self.append_literal(&label, styles, destination)?;
                    Ok(true)
                }
                _ => Ok(false),
            };
        };
        if tag == SafeHtmlInlineTag::Anchor {
            if destination.is_some() {
                return Ok(false);
            }
            let Some(anchor) = anchor_destination(&element.attributes) else {
                return Ok(false);
            };
            return self.render_nodes(&element.children, styles, Some(&anchor));
        }
        if !element.attributes.is_empty() {
            return Ok(false);
        }
        self.render_nodes(&element.children, styles | tag.styles(), destination)
    }

    /// Appends text using HTML's ordinary collapsible-whitespace behavior.
    fn append_text(
        &mut self,
        text: &str,
        styles: u32,
        destination: Option<&str>,
    ) -> Result<(), RenderError> {
        let mut normalized = String::with_capacity(text.len());
        for character in text.chars() {
            if is_collapsible_html_whitespace(character) {
                if !normalized.is_empty()
                    || self
                        .block
                        .text
                        .as_bytes()
                        .last()
                        .is_some_and(|byte| !matches!(byte, b'\n' | b'\t'))
                {
                    self.pending_space = true;
                }
            } else {
                if self.pending_space {
                    normalized.push(' ');
                    self.pending_space = false;
                }
                normalized.push(character);
            }
        }
        self.append_normalized(&normalized, styles, destination)
    }

    /// Appends one already normalized visible inline replacement.
    fn append_literal(
        &mut self,
        text: &str,
        styles: u32,
        destination: Option<&str>,
    ) -> Result<(), RenderError> {
        let mut visible = String::with_capacity(text.len().saturating_add(1));
        if self.pending_space {
            visible.push(' ');
            self.pending_space = false;
        }
        visible.push_str(text);
        self.append_normalized(&visible, styles, destination)
    }

    /// Appends one normalized run with an optional safe link destination.
    fn append_normalized(
        &mut self,
        text: &str,
        styles: u32,
        destination: Option<&str>,
    ) -> Result<(), RenderError> {
        self.block.append(
            text,
            styles,
            destination.map(|destination| (InlineDestinationKind::Link, destination)),
        )
    }

    /// Appends one explicit safe HTML line break.
    fn append_break(&mut self, styles: u32, destination: Option<&str>) -> Result<(), RenderError> {
        self.pending_space = false;
        self.append_normalized("\n", styles, destination)
    }

    /// Separates two flattened table cells without carrying whitespace state.
    fn append_cell_separator(&mut self) -> Result<(), RenderError> {
        self.pending_space = false;
        self.block.append(TABLE_CELL_SEPARATOR, 0, None)
    }

    /// Clears trailing collapsible whitespace at one table-cell boundary.
    fn end_cell(&mut self) {
        self.pending_space = false;
    }

    /// Returns the completed presentation block.
    fn finish(self) -> RenderBlock {
        self.block
    }
}

/// Returns one validated and bounded anchor destination.
fn anchor_destination(attributes: &[SafeHtmlAttribute]) -> Option<String> {
    if !attributes_are_allowlisted(attributes, &["href", "title"]) {
        return None;
    }
    let destination = attribute_value(attributes, "href")?;
    (destination.len() <= MAX_LINK_DESTINATION_BYTES).then(|| destination.to_owned())
}

/// Returns a visible image replacement while deliberately ignoring its source.
fn image_label(attributes: &[SafeHtmlAttribute]) -> Option<String> {
    if !attributes_are_allowlisted(attributes, &["src", "alt", "title"]) {
        return None;
    }
    let alt = attribute_value(attributes, "alt").unwrap_or("");
    if alt.is_empty() {
        Some("Image".to_owned())
    } else {
        let mut label = String::with_capacity(IMAGE_PREFIX.len().saturating_add(alt.len()));
        label.push_str(IMAGE_PREFIX);
        label.push_str(alt);
        Some(label)
    }
}

/// Returns one exact attribute value when present.
fn attribute_value<'a>(attributes: &'a [SafeHtmlAttribute], name: &str) -> Option<&'a str> {
    attributes
        .iter()
        .find(|attribute| attribute.name == name)
        .map(|attribute| attribute.value.as_str())
}

/// Returns whether every attribute name belongs to one narrow allowlist.
fn attributes_are_allowlisted(attributes: &[SafeHtmlAttribute], allowed: &[&str]) -> bool {
    attributes
        .iter()
        .all(|attribute| allowed.contains(&attribute.name.as_str()))
}

/// Returns whether one node can be rendered inside an inline content run.
fn is_safe_html_inline_node(node: &SafeHtmlNode) -> bool {
    match node {
        SafeHtmlNode::Text(_) => true,
        SafeHtmlNode::Element(element) => {
            SafeHtmlInlineTag::from_name(&element.name).is_some()
                || matches!(element.name.as_str(), "br" | "img")
        }
    }
}

/// Returns whether one node is only collapsible inter-element whitespace.
fn is_inter_element_whitespace_node(node: &SafeHtmlNode) -> bool {
    matches!(node, SafeHtmlNode::Text(text) if text.chars().all(is_collapsible_html_whitespace))
}

/// Returns whether one tokenizer event is only collapsible inter-element whitespace.
fn is_inter_element_whitespace_token(token: &SafeHtmlToken) -> bool {
    matches!(token, SafeHtmlToken::Text(text) if text.chars().all(is_collapsible_html_whitespace))
}

/// Returns whether HTML collapses one source character into an ordinary space.
fn is_collapsible_html_whitespace(character: char) -> bool {
    matches!(
        character,
        '\u{0009}' | '\u{000a}' | '\u{000c}' | '\u{000d}' | '\u{0020}'
    )
}
