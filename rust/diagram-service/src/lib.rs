//! ART-free native diagram service entry point.
#![cfg(target_os = "android")]
mod fonts;
/// Creates the independently isolated renderer.
///
/// # Safety
/// Android must supply its live `ANativeService` instance on the main thread.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ANativeService_onCreate(service: *mut std::ffi::c_void) {
    let host = beautyxt_illustration_service::service(
        beautyxt_diagram_core::MAX_SOURCE_BYTES,
        std::time::Duration::from_secs(3),
        fonts::render,
    );
    // SAFETY: Android supplies the service pointer under the entry point's contract.
    unsafe {
        beautyxt_native_service::create(service, host);
    }
}
