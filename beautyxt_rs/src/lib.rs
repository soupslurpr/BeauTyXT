use docx_rs::{Docx, Run, Style, StyleType};
use markdown::{mdast::Node, CompileOptions, Constructs, Options, ParseOptions};
use scraper::ElementRef;
use std::io::Cursor;

uniffi::setup_scaffolding!();

const PARSE_OPTIONS: ParseOptions = ParseOptions {
    constructs: Constructs {
        attention: true,
        autolink: true,
        block_quote: true,
        character_escape: true,
        character_reference: true,
        code_indented: false,
        code_fenced: true,
        code_text: false,
        definition: true,
        frontmatter: true,
        gfm_autolink_literal: true,
        gfm_footnote_definition: true,
        gfm_label_start_footnote: true,
        gfm_strikethrough: true,
        gfm_table: true,
        gfm_task_list_item: true,
        hard_break_escape: true,
        hard_break_trailing: true,
        heading_atx: true,
        heading_setext: true,
        html_flow: true,
        html_text: true,
        label_start_image: true,
        label_start_link: true,
        label_end: true,
        list_item: true,
        math_flow: true,
        math_text: true,
        mdx_esm: false,
        mdx_expression_flow: false,
        mdx_expression_text: false,
        mdx_jsx_flow: false,
        mdx_jsx_text: false,
        thematic_break: true,
    },
    gfm_strikethrough_single_tilde: true,
    math_text_single_dollar: false,
    mdx_expression_parse: None,
    mdx_esm_parse: None,
};

#[uniffi::export]
pub fn markdown_to_html(markdown: String) -> String {
    markdown::to_html_with_options(
        &markdown,
        &Options {
            parse: PARSE_OPTIONS,
            compile: CompileOptions {
                allow_dangerous_html: true,
                allow_dangerous_protocol: true,
                gfm_tagfilter: false,
                default_line_ending: Default::default(),
                gfm_footnote_label: Default::default(),
                gfm_footnote_label_tag_name: Default::default(),
                gfm_footnote_label_attributes: Default::default(),
                gfm_footnote_back_label: Default::default(),
                gfm_footnote_clobber_prefix: Some("".to_owned()),
                gfm_task_list_item_checkable: false,
            },
        },
    )
    .unwrap_or_else(|err| err.to_string())
}

#[uniffi::export]
pub fn markdown_to_docx(markdown: String) -> Vec<u8> {
    let tree = markdown::to_mdast(&markdown, &PARSE_OPTIONS).unwrap();

    let mut docx = Docx::new()
        // Add styles for headings.
        // The actual font size is this divided by 2 for some reason
        .add_style(
            Style::new("Heading1", StyleType::Paragraph)
                .name("Heading 1")
                .size(48),
        )
        .add_style(
            Style::new("Heading2", StyleType::Paragraph)
                .name("Heading 2")
                .size(36),
        )
        .add_style(
            Style::new("Heading3", StyleType::Paragraph)
                .name("Heading 3")
                .size(28),
        )
        .add_style(
            Style::new("Heading4", StyleType::Paragraph)
                .name("Heading 4")
                .size(24),
        )
        .add_style(
            Style::new("Heading5", StyleType::Paragraph)
                .name("Heading 5")
                .size(20),
        )
        .add_style(
            Style::new("Heading6", StyleType::Paragraph)
                .name("Heading 6")
                .size(16),
        );

    fn process_node(
        node: &Node,
        run: &mut Option<docx_rs::Run>,
        paragraph: &mut Option<docx_rs::Paragraph>,
        paragraphs: &mut Vec<docx_rs::Paragraph>,
    ) {
        match node {
            Node::Root(_) => {
                if let Some(children) = node.children() {
                    for child in children {
                        process_node(child, run, paragraph, paragraphs)
                    }
                }
            }
            Node::BlockQuote(_) => todo!(),
            Node::FootnoteDefinition(_) => todo!(),
            Node::MdxJsxFlowElement(_) => todo!(),
            Node::List(_) => todo!(),
            Node::MdxjsEsm(_) => todo!(),
            Node::Toml(_) => todo!(),
            Node::Yaml(_) => todo!(),
            Node::Break(_) => {
                if let Some(run_ref) = run.as_mut() {
                    *run = Some(run_ref.clone().add_break(docx_rs::BreakType::Unsupported))
                }
                if let Some(children) = node.children() {
                    for child in children {
                        process_node(child, run, paragraph, paragraphs)
                    }
                }
            }
            Node::InlineCode(_) => todo!(),
            Node::InlineMath(_) => todo!(),
            Node::Delete(_) => todo!(),
            // if let Some(run_ref) = run.as_mut() {
            //     *run = Some(run_ref.clone().strike())
            // }
            // if let Some(children) = node.children() {
            //     for child in children {
            //         process_node(child, run, paragraph, paragraphs)
            //     }
            // }
            Node::Emphasis(_) => {
                if let Some(run_ref) = run.as_mut() {
                    *run = Some(run_ref.clone().italic())
                }
                if let Some(children) = node.children() {
                    for child in children {
                        process_node(child, run, paragraph, paragraphs)
                    }
                }
            }
            Node::MdxTextExpression(_) => todo!(),
            Node::FootnoteReference(_) => todo!(),
            Node::Html(_) => {
                let document = scraper::Html::parse_fragment(&node.to_string());
                let root_element = document.root_element();

                fn process_html(
                    node: ego_tree::NodeRef<'_, scraper::Node>,
                    run: &mut Option<docx_rs::Run>,
                    paragraph: &mut Option<docx_rs::Paragraph>,
                    paragraphs: &mut Vec<docx_rs::Paragraph>,
                ) {
                    match node.value() {
                        scraper::Node::Document => todo!(),
                        scraper::Node::Fragment => todo!(),
                        scraper::Node::Doctype(_) => todo!(),
                        scraper::Node::Comment(_) => todo!(),
                        scraper::Node::Text(_) => {
                            process_node(
                                &markdown::to_mdast(
                                    node.value().as_text().unwrap(),
                                    &PARSE_OPTIONS,
                                )
                                .unwrap(),
                                run,
                                paragraph,
                                paragraphs,
                            );
                        }
                        scraper::Node::Element(_) => {
                            let element = ElementRef::wrap(node).unwrap();
                            let style = element.value().attr("style").unwrap_or("");

                            let alignment_type = match style {
                                style if style.contains("text-align: left") => docx_rs::AlignmentType::Left,
                                style if style.contains("text-align: center") => docx_rs::AlignmentType::Center,
                                style if style.contains("text-align: right") => docx_rs::AlignmentType::Right,
                                _ => docx_rs::AlignmentType::Start,
                            };

                            match element.value().name.local.as_ref() {
                                "div" => {
                                    for child in node.children() {
                                        if let Some(paragraph_ref) = paragraph.as_mut() {
                                            *paragraph = Some(
                                                paragraph_ref.clone().align(alignment_type),
                                            )
                                        }

                                        process_html(child, run, paragraph, paragraphs)
                                    }
                                }
                                _ => {}
                            }
                        }
                        scraper::Node::ProcessingInstruction(_) => todo!(),
                    }
                }

                for child in root_element.children() {
                    // TODO: Change this to maybe not make a new paragraph on each child and
                    // instead only a paragraph on divs and children get new runs only?
                    // Only would be needed if we decide to implement more HTML than just <div>.
                    if paragraph.is_none() {
                        *paragraph = Some(docx_rs::Paragraph::new());
                    }
                    process_html(child, run, paragraph, paragraphs);
                    *paragraph = None;
                }
            }
            Node::Image(_) => todo!(),
            Node::ImageReference(_) => todo!(),
            Node::MdxJsxTextElement(_) => todo!(),
            Node::Link(_) => todo!(),
            Node::LinkReference(_) => todo!(),
            Node::Strong(_) => {
                if let Some(run_ref) = run.as_mut() {
                    *run = Some(run_ref.clone().bold())
                }
                if let Some(children) = node.children() {
                    for child in children {
                        process_node(child, run, paragraph, paragraphs)
                    }
                }
            }
            Node::Text(_) => {
                if let Some(run_ref) = run.as_mut() {
                    *run = Some(
                        run_ref
                            .clone()
                            .add_text(node.to_string().replace("\n", " ")),
                    )
                }
            }
            Node::Code(_) => todo!(),
            Node::Math(_) => todo!(),
            Node::MdxFlowExpression(_) => todo!(),
            Node::Heading(heading) => {
                let style = match heading.depth {
                    1 => "Heading1",
                    2 => "Heading2",
                    3 => "Heading3",
                    4 => "Heading4",
                    5 => "Heading5",
                    6 => "Heading6",
                    _ => todo!(),
                };

                if let Some(children) = node.children() {
                    if paragraph.is_none() {
                        *paragraph = Some(docx_rs::Paragraph::new());
                    }

                    let mut runs: Vec<Option<Run>> = Vec::new();
                    for child in children {
                        let mut run = Some(docx_rs::Run::new().style(style));
                        process_node(child, &mut run, paragraph, paragraphs);
                        runs.push(run);
                    }
                    for run in runs {
                        if run.is_some() {
                            if let Some(paragraph_ref) = paragraph.as_mut() {
                                *paragraph = Some(paragraph_ref.clone().add_run(run.unwrap()))
                            }
                        }
                    }
                    paragraphs.push(paragraph.clone().unwrap());
                    *paragraph = Some(docx_rs::Paragraph::new());
                }
            }
            Node::Table(_) => todo!(),
            Node::ThematicBreak(_) => todo!(),
            Node::TableRow(_) => todo!(),
            Node::TableCell(_) => todo!(),
            Node::ListItem(_) => todo!(),
            Node::Definition(_) => todo!(),
            Node::Paragraph(_) => {
                if let Some(children) = node.children() {
                    if paragraph.is_none() {
                        *paragraph = Some(docx_rs::Paragraph::new());
                    }

                    let mut runs: Vec<Option<Run>> = Vec::new();
                    for child in children {
                        let mut run = Some(docx_rs::Run::new());
                        process_node(child, &mut run, paragraph, paragraphs);
                        runs.push(run);
                    }
                    for run in runs {
                        if run.is_some() {
                            if let Some(paragraph_ref) = paragraph.as_mut() {
                                *paragraph = Some(paragraph_ref.clone().add_run(run.unwrap()))
                            }
                        }
                    }
                    paragraphs.push(paragraph.clone().unwrap());
                    *paragraph = Some(docx_rs::Paragraph::new());
                }
            }
        }
    }

    let mut paragraphs = Vec::new();

    process_node(&tree, &mut None, &mut None, &mut paragraphs);

    for paragraph in paragraphs {
        docx = docx.add_paragraph(paragraph)
    }

    let mut buffer = Cursor::new(Vec::new());

    docx.build().pack(&mut buffer).unwrap();

    buffer.into_inner()
}
