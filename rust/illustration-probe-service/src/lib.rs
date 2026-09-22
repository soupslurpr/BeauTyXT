//! Debug-only fixture exercising the production native worker's hard-stop machinery.
#![cfg(target_os = "android")]
use beautyxt_illustration_core::DrawingError;

/// Creates a synthetic renderer with a short deadline; never packaged in staging/release.
///
/// # Safety
/// Android must supply its live `ANativeService` instance on the main thread.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ANativeService_onCreate(service: *mut std::ffi::c_void) {
    let host =
        beautyxt_illustration_service::service(16, std::time::Duration::from_millis(250), render);
    // SAFETY: Android supplies the live native service under the entry point's contract.
    unsafe {
        beautyxt_native_service::create(service, host);
    }
}

fn render(source: &str, _: bool) -> Result<Vec<u8>, DrawingError> {
    match source {
        "hang" => loop {
            std::thread::park();
        },
        "crash" => std::process::exit(0),
        _ => Err(DrawingError::Unsupported),
    }
}
