//! Independently terminable native math/diagram service, with bounded Binder inputs.
#![cfg(target_os = "android")]

use beautyxt_illustration_core::{DrawingError, HEADER_BYTES, MAX_PACKET_BYTES};
use beautyxt_native_service::{
    Binder, Cancellation, Jobs, Reader, ReliableFd, Service, UNKNOWN_TRANSACTION, Writer,
};
use std::ffi::CStr;
use std::io;
use std::mem::MaybeUninit;
use std::os::fd::AsRawFd;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

/// A renderer that owns no Android or Binder state.
pub type Renderer = fn(&str, bool) -> Result<Vec<u8>, DrawingError>;

struct IllustrationService {
    maximum: usize,
    timeout: Duration,
    render: Renderer,
    jobs: Arc<Jobs>,
}

/// Creates a serial renderer with its own process-wide hard deadline.
#[must_use]
pub fn service(maximum: usize, timeout: Duration, render: Renderer) -> Arc<dyn Service> {
    Arc::new(IllustrationService {
        maximum,
        timeout,
        render,
        jobs: Arc::default(),
    })
}

impl Service for IllustrationService {
    fn descriptor(&self) -> &'static CStr {
        c"dev.soupslurpr.beautyxt.illustration.IIllustrationService"
    }
    fn maximum_transaction_bytes(&self) -> i32 {
        i32::try_from(self.maximum + 256).expect("bounded illustration source")
    }
    fn unbind(&self) {
        // Instances are private to one connection, including idle renderers.
        terminate();
    }
    fn destroy(&self) {
        terminate();
    }
    fn transact(&self, code: u32, input: &Reader, _: &Writer) -> Result<(), i32> {
        if code == 2 {
            let id = input.i64()?;
            input.finished()?;
            self.jobs.cancel(Some(id), false);
            return Ok(());
        }
        if code != 1 {
            return Err(UNKNOWN_TRANSACTION);
        }
        let id = input.i64()?;
        let source = input.bytes(self.maximum)?;
        let display = input.boolean()?;
        let output = input.descriptor()?;
        let callback =
            input.binder(c"dev.soupslurpr.beautyxt.illustration.IIllustrationCallback")?;
        input.finished()?;
        let Some(callback) = callback else {
            return Ok(());
        };
        let (Some(mut source), Some(output)) = (source, output) else {
            return result(&callback, id, 4, 0);
        };
        if id <= 0 || source.is_empty() || !valid_output(&output) {
            source.fill(0);
            return result(&callback, id, 4, 0);
        }
        let control = Arc::new(KillControl::default());
        let timeout = self.timeout;
        let render = self.render;
        let accepted = self.jobs.start(
            id,
            control.clone(),
            callback.clone(),
            move |callback, completion| {
                let timer_control = control.clone();
                let timer_callback = callback.clone();
                let watchdog = std::thread::Builder::new()
                    .name("Illustration deadline".into())
                    .spawn(move || {
                        let active = timer_control.active.lock().expect("deadline poisoned");
                        let (active, _) = timer_control
                            .wake
                            .wait_timeout_while(active, timeout, |active| *active)
                            .expect("deadline poisoned");
                        if *active {
                            // Best effort; the client also handles Binder death as a fallback.
                            let _ = result(&timer_callback, id, 5, 0);
                            terminate();
                        }
                    });
                let (status, bytes) = if watchdog.is_err() {
                    (4, 0)
                } else {
                    let rendered = std::str::from_utf8(&source)
                        .map_err(|_| DrawingError::Invalid)
                        .and_then(|text| render(text, display));
                    match rendered {
                        Ok(packet) if (HEADER_BYTES..=MAX_PACKET_BYTES).contains(&packet.len()) => {
                            if write_packet(&output, &packet).is_ok() {
                                (0, packet.len())
                            } else {
                                (4, 0)
                            }
                        }
                        Ok(_) => (4, 0),
                        Err(DrawingError::Unsupported) => (1, 0),
                        Err(DrawingError::Limit) => (2, 0),
                        Err(DrawingError::Invalid) => (3, 0),
                        Err(DrawingError::TimedOut) => (5, 0),
                    }
                };
                source.fill(0);
                output.close(status == 0);
                control.finish();
                if let Ok(watchdog) = watchdog {
                    let _ = watchdog.join();
                }
                completion.finish();
                let _ = result(callback, id, status, bytes);
            },
        );
        if accepted != 0 {
            result(&callback, id, 4, 0)?;
        }
        Ok(())
    }
}

struct KillControl {
    active: Mutex<bool>,
    wake: Condvar,
}
impl Default for KillControl {
    fn default() -> Self {
        Self {
            active: Mutex::new(true),
            wake: Condvar::new(),
        }
    }
}
impl KillControl {
    fn finish(&self) {
        *self.active.lock().expect("deadline poisoned") = false;
        self.wake.notify_all();
    }
}
impl Cancellation for KillControl {
    fn cancel(&self) {
        if *self.active.lock().expect("deadline poisoned") {
            terminate();
        }
    }
}
fn terminate() -> ! {
    // SAFETY: this is a private isolated process, never the editor or another client's worker.
    unsafe { libc::_exit(0) }
}
fn result(callback: &Binder, id: i64, status: i32, bytes: usize) -> Result<(), i32> {
    callback.notify(1, |reply| {
        reply.i64(id)?;
        reply.i32(status)?;
        reply.i32(i32::try_from(bytes).map_err(|_| -libc::EINVAL)?)
    })
}
fn valid_output(output: &ReliableFd) -> bool {
    let mut stat = MaybeUninit::<libc::stat>::uninit();
    // SAFETY: output owns a live descriptor and stat has space for fstat's output.
    if unsafe { libc::fstat(output.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
        return false;
    }
    // SAFETY: successful fstat initialized the complete value.
    let stat = unsafe { stat.assume_init() };
    stat.st_mode & libc::S_IFMT == libc::S_IFREG
        && stat.st_nlink == 0
        && stat.st_size == i64::try_from(MAX_PACKET_BYTES).expect("fixed packet size")
}
fn write_packet(output: &ReliableFd, packet: &[u8]) -> io::Result<()> {
    let mut offset = 0;
    while offset < packet.len() {
        // SAFETY: output is owned and validated; slice and file offset stay within the packet cap.
        let count = unsafe {
            libc::pwrite(
                output.as_raw_fd(),
                packet[offset..].as_ptr().cast(),
                packet.len() - offset,
                i64::try_from(offset).expect("bounded packet"),
            )
        };
        if count < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if count == 0 {
            return Err(io::ErrorKind::WriteZero.into());
        }
        offset += usize::try_from(count).expect("positive write");
    }
    Ok(())
}
