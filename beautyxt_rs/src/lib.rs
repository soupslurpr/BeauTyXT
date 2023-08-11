use markdown::{Options, ParseOptions, Constructs, CompileOptions};

uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn markdown_to_html(markdown: String) -> String {
    markdown::to_html_with_options(
        &markdown,
        &Options {
            parse: ParseOptions {
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
            },
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
            },
        },
    )
    .unwrap_or_else(|err| err.to_string())
}
