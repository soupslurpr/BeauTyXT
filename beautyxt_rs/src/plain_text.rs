use docx_rs::{Docx, Run};
use std::io::Cursor;

#[uniffi::export]
pub fn plain_text_to_docx(plain_text: String) -> Vec<u8> {
    let mut docx = Docx::new();

    for paragraph in plain_text.split('\n') {
        docx = docx.add_paragraph(docx_rs::Paragraph::new().add_run(Run::new().add_text(paragraph)))
    }

    let mut buffer = Cursor::new(Vec::new());

    docx.build().pack(&mut buffer).unwrap();

    buffer.into_inner()
}
