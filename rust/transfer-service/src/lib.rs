//! ART-free Android isolated transfer service.
#[cfg(target_os = "android")]
mod android;
// Platform entry points are used by Android dispatch; host tests exercise the worker helpers.
#[cfg(any(target_os = "android", test))]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod worker;

/// Registers this native worker with Android.
/// # Safety
/// Android must supply its live `ANativeService` instance on the main thread.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ANativeService_onCreate(service: *mut std::ffi::c_void) {
    // SAFETY: the platform supplies the live service required by the entry point.
    unsafe { beautyxt_native_service::create(service, android::service()) };
}
