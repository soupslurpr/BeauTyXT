//! Maps native-service and Binder lifetimes to reference-counted Rust instances.
use crate::{BAD_VALUE, Binder, Reader, Writer};
use crate::{binder, ffi};
use std::collections::BTreeMap;
use std::ffi::{CStr, c_char, c_void};
use std::ptr::{self, NonNull};
use std::sync::{Arc, LazyLock, Mutex};

/// One isolated service's bounded protocol and lifecycle.
pub trait Service: Send + Sync + 'static {
    /// The exact descriptor from the corresponding AIDL interface.
    fn descriptor(&self) -> &'static CStr;
    /// Maximum payload size, excluding the NDK-validated interface token.
    fn maximum_transaction_bytes(&self) -> i32 {
        512
    }
    /// Dispatches one decoded Binder transaction, returning an NDK status on error.
    ///
    /// # Errors
    /// Returns a Binder error for malformed or unsupported requests.
    fn transact(&self, code: u32, input: &Reader, output: &Writer) -> Result<(), i32>;
    /// Cancels work after the final client unbinds.
    fn unbind(&self);
    /// Cancels work and rejects new jobs before instance destruction.
    fn destroy(&self);
}

struct Instance {
    binder: Binder,
    service: Arc<dyn Service>,
}
static INSTANCES: LazyLock<Mutex<BTreeMap<usize, Instance>>> =
    LazyLock::new(|| Mutex::new(BTreeMap::new()));

/// Registers an ART-free service and transfers its callbacks to Android.
///
/// # Safety
/// `native` must be the live `ANativeService` pointer Android passed to its creation
/// entry point, called exactly once on the main thread for this instance.
///
/// # Panics
/// Aborts on allocation failure or poisoned internal ownership state.
pub unsafe fn create(native: *mut c_void, service: Arc<dyn Service>) {
    let data = Box::into_raw(Box::new(Arc::clone(&service))).cast();
    // SAFETY: class storage is permanent; the binder receives sole ownership of the box.
    let raw = unsafe { ffi::AIBinder_new(binder::class(service.descriptor()).0.as_ptr(), data) };
    let binder = Binder(NonNull::new(raw).expect("native binder allocation failed"));
    INSTANCES
        .lock()
        .expect("service registry poisoned")
        .insert(native as usize, Instance { binder, service });
    // SAFETY: Android retains this live service and invokes the ABI-matching callbacks.
    unsafe {
        ffi::ANativeService_setOnBindCallback(native.cast(), on_bind);
        ffi::ANativeService_setOnUnbindCallback(native.cast(), on_unbind);
        ffi::ANativeService_setOnDestroyCallback(native.cast(), on_destroy);
    }
}

unsafe extern "C" fn on_bind(
    native: *mut ffi::Service,
    _: u64,
    _: *const c_char,
    _: *const c_char,
) -> *mut ffi::Binder {
    INSTANCES
        .lock()
        .expect("service registry poisoned")
        .get(&(native as usize))
        .map_or(ptr::null_mut(), |instance| {
            instance.binder.clone().into_raw()
        })
}
unsafe extern "C" fn on_unbind(native: *mut ffi::Service, _: u64) -> bool {
    let service = INSTANCES
        .lock()
        .expect("service registry poisoned")
        .get(&(native as usize))
        .map(|instance| Arc::clone(&instance.service));
    if let Some(service) = service {
        service.unbind();
    }
    false
}
unsafe extern "C" fn on_destroy(native: *mut ffi::Service) {
    let instance = INSTANCES
        .lock()
        .expect("service registry poisoned")
        .remove(&(native as usize));
    if let Some(instance) = instance {
        instance.service.destroy();
    }
}
pub(crate) unsafe extern "C" fn binder_create(data: *mut c_void) -> *mut c_void {
    data
}
pub(crate) unsafe extern "C" fn binder_destroy(data: *mut c_void) {
    // SAFETY: AIBinder destroys this unique box once its final strong reference is gone.
    drop(unsafe { Box::from_raw(data.cast::<Arc<dyn Service>>()) });
}
pub(crate) unsafe extern "C" fn transact(
    binder: *mut ffi::Binder,
    code: u32,
    input: *const ffi::Parcel,
    output: *mut ffi::Parcel,
) -> i32 {
    // SAFETY: the local binder owns this initialized box for the entire callback.
    let service = unsafe { &*ffi::AIBinder_getUserData(binder).cast::<Arc<dyn Service>>() };
    let reader = Reader(input);
    if !(0..=service.maximum_transaction_bytes()).contains(&reader.remaining()) {
        return BAD_VALUE;
    }
    // Debug-only fault injection: am crash requests a VM crash, unavailable without ART.
    // Gradle's staging and release variants use Cargo's release profile, which omits this.
    #[cfg(debug_assertions)]
    if code == 0x00ff_fffe {
        if let Err(error) = reader.finished() {
            return error;
        }
        // SAFETY: only this private isolated worker exits, closing all its capabilities.
        unsafe { libc::_exit(0) };
    }
    service
        .transact(code, &reader, &Writer(output))
        .err()
        .unwrap_or(0)
}
