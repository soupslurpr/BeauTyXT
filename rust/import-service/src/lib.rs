//! ART-free Android isolated import service with a bounded Rust worker.

#[cfg(target_os = "android")]
mod android;
#[cfg(any(target_os = "android", test))]
mod worker;

/// Initializes Android's native service callbacks without loading ART or JNI.
///
/// # Safety
/// Android must supply its live `ANativeService` instance on the main thread.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ANativeService_onCreate(service: *mut std::ffi::c_void) {
    // SAFETY: the platform supplies the live service required by this entry point.
    unsafe { beautyxt_native_service::create(service, android::service()) };
}
