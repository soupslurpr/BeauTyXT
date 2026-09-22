//! One active job per service, with cancellation on unbind and callback death.
use crate::{Binder, binder::check, ffi};
use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::{Arc, Mutex};

/// A worker's idempotent cancellation signal, safe to invoke from Binder threads.
pub trait Cancellation: Send + Sync + 'static {
    /// Wakes cooperative work or terminates an independently isolated renderer.
    fn cancel(&self);
}
struct Active {
    id: i64,
    control: Arc<dyn Cancellation>,
}
#[derive(Default)]
struct State {
    destroyed: bool,
    active: Option<Arc<Active>>,
}
/// Serial job admission and cancellation shared by the isolated worker roles.
#[derive(Default)]
pub struct Jobs(Mutex<State>);
impl Jobs {
    /// Accepts one job (0), or rejects a busy (1) or unavailable (3) service.
    /// The closure owns its resources even when admission or thread creation fails.
    ///
    /// # Panics
    /// Aborts if internal ownership state is poisoned.
    pub fn start(
        self: &Arc<Self>,
        id: i64,
        control: Arc<dyn Cancellation>,
        callback: Binder,
        run: impl FnOnce(&Binder, &Completion) + Send + 'static,
    ) -> i32 {
        let job = Arc::new(Active { id, control });
        let death = {
            let mut state = self.0.lock().expect("job state poisoned");
            if state.destroyed {
                return 3;
            }
            if state.active.is_some() {
                return 1;
            }
            let Ok(death) = DeathLink::new(&callback, &job) else {
                return 3;
            };
            state.active = Some(Arc::clone(&job));
            death
        };
        let host = Arc::clone(self);
        let worker_job = Arc::clone(&job);
        if std::thread::Builder::new()
            .name("BeauTyXT worker".into())
            .spawn(move || {
                let completion = Completion {
                    host: host.clone(),
                    job: worker_job.clone(),
                };
                run(&callback, &completion);
                drop(death);
                host.clear(&worker_job);
            })
            .is_err()
        {
            self.clear(&job);
            return 3;
        }
        0
    }
    fn clear(&self, job: &Arc<Active>) {
        let mut state = self.0.lock().expect("job state poisoned");
        if state
            .active
            .as_ref()
            .is_some_and(|active| Arc::ptr_eq(active, job))
        {
            state.active = None;
        }
    }
    /// Cancels the matching job, or all work; destruction also closes admission.
    ///
    /// # Panics
    /// Aborts if internal ownership state is poisoned.
    pub fn cancel(&self, id: Option<i64>, destroy: bool) {
        let mut state = self.0.lock().expect("job state poisoned");
        state.destroyed |= destroy;
        if let Some(job) = &state.active
            && id.is_none_or(|id| id == job.id)
        {
            job.control.cancel();
        }
    }
}
/// Releases admission before sending a terminal receipt, preventing a next-job race.
pub struct Completion {
    host: Arc<Jobs>,
    job: Arc<Active>,
}
impl Completion {
    /// Makes this service available for the next request after resources are closed.
    ///
    /// # Panics
    /// Aborts if internal ownership state is poisoned.
    pub fn finish(&self) {
        self.host.clear(&self.job);
    }
}
struct DeathLink(NonNull<ffi::DeathRecipient>);
// SAFETY: NDK supports deleting a recipient from any thread; its cookie owns a separate Arc.
unsafe impl Send for DeathLink {}
impl DeathLink {
    fn new(binder: &Binder, job: &Arc<Active>) -> Result<Self, i32> {
        // SAFETY: this process-lifetime callback accepts the matching Arc<Active> cookie.
        let raw = unsafe { ffi::AIBinder_DeathRecipient_new(died) };
        let recipient = Self(NonNull::new(raw).ok_or(-libc::ENOMEM)?);
        let cookie = Arc::into_raw(Arc::clone(job)).cast_mut().cast();
        // SAFETY: onUnlinked owns the cookie, including failed link attempts, and
        // runs after any onBinderDied call finishes even if deletion returns first.
        unsafe {
            ffi::AIBinder_DeathRecipient_setOnUnlinked(recipient.0.as_ptr(), unlinked);
            check(ffi::AIBinder_linkToDeath(
                binder.0.as_ptr(),
                recipient.0.as_ptr(),
                cookie,
            ))?;
        }
        Ok(recipient)
    }
}
impl Drop for DeathLink {
    fn drop(&mut self) {
        // SAFETY: this guard owns the recipient; deletion unlinks it and schedules cookie cleanup.
        unsafe { ffi::AIBinder_DeathRecipient_delete(self.0.as_ptr()) };
    }
}
unsafe extern "C" fn died(cookie: *mut c_void) {
    // SAFETY: NDK retains the cookie until the later onUnlinked callback.
    unsafe { &*cookie.cast::<Active>() }.control.cancel();
}
unsafe extern "C" fn unlinked(cookie: *mut c_void) {
    // SAFETY: exactly one callback returns ownership of each Arc given to linkToDeath.
    drop(unsafe { Arc::from_raw(cookie.cast::<Active>()) });
}
