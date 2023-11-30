mod markdown;
mod plain_text;
mod typst;

uniffi::setup_scaffolding!();

// Required for UPX to work
#[no_mangle]
pub fn _init() { }
