//! Native Markdown AIDL host.
use crate::worker::{JobControl, RESULT_INPUT_IO, RESULT_INTERNAL, RenderOutcome, run_render};
use beautyxt_native_service::{
    Binder, Cancellation, Jobs, Reader, Service, UNKNOWN_TRANSACTION, Writer,
};
use std::ffi::CStr;
use std::os::fd::AsRawFd;
use std::sync::Arc;
struct MarkdownService {
    jobs: Arc<Jobs>,
}
pub(super) fn service() -> Arc<dyn Service> {
    Arc::new(MarkdownService {
        jobs: Arc::default(),
    })
}
impl Cancellation for JobControl {
    fn cancel(&self) {
        let _ = Self::cancel(self);
    }
}
impl Service for MarkdownService {
    fn descriptor(&self) -> &'static CStr {
        c"dev.soupslurpr.beautyxt.markdown.IMarkdownService"
    }
    fn unbind(&self) {
        self.jobs.cancel(None, false);
    }
    fn destroy(&self) {
        self.jobs.cancel(None, true);
    }
    fn transact(&self, code: u32, input: &Reader, reply: &Writer) -> Result<(), i32> {
        match code {
            1 => {
                let id = input.i64()?;
                let source = input.descriptor()?;
                let output = input.descriptor()?;
                let expected = input.i64()?;
                let maximum = input.i64()?;
                let timeout = input.i64()?;
                let callback =
                    input.binder(c"dev.soupslurpr.beautyxt.markdown.IMarkdownCallback")?;
                input.finished()?;
                let accepted = (|| {
                    let (Some(mut source), Some(output)) = (source, output) else {
                        return 2;
                    };
                    if id <= 0
                        || !(0..=16 * 1024 * 1024).contains(&expected)
                        || !(1..=32 * 1024 * 1024).contains(&maximum)
                        || !(1..=300_000).contains(&timeout)
                    {
                        return 2;
                    }
                    let Some(callback) = callback else {
                        return 3;
                    };
                    let Ok(control) = JobControl::new().map(Arc::new) else {
                        return 3;
                    };
                    self.jobs.start(
                        id,
                        control.clone(),
                        callback,
                        move |callback, completion| {
                            if status(callback, id, 0, &RenderOutcome::failure(0)).is_err() {
                                let _ = control.cancel();
                            }
                            let mut outcome = if control.start().is_ok() {
                                run_render(
                                    &control,
                                    source.as_raw_fd(),
                                    output.as_raw_fd(),
                                    expected,
                                    maximum,
                                    timeout,
                                )
                            } else {
                                RenderOutcome::failure(RESULT_INTERNAL)
                            };
                            if outcome.result_code == 0 && source.check_error() {
                                output.reset();
                                outcome = RenderOutcome::failure(RESULT_INPUT_IO);
                            }
                            let success = outcome.result_code == 0;
                            source.close(success);
                            output.close(success);
                            completion.finish();
                            let _ = status(
                                callback,
                                id,
                                if success {
                                    1
                                } else if outcome.result_code == 1 {
                                    3
                                } else {
                                    2
                                },
                                &outcome,
                            );
                        },
                    )
                })();
                reply.ok()?;
                reply.i32(accepted)
            }
            2 => {
                let id = input.i64()?;
                input.finished()?;
                self.jobs.cancel(Some(id), false);
                Ok(())
            }
            _ => Err(UNKNOWN_TRANSACTION),
        }
    }
}
fn status(callback: &Binder, id: i64, state: i32, outcome: &RenderOutcome) -> Result<(), i32> {
    callback.notify(1, |reply| {
        reply.i64(id)?;
        reply.i32(state)?;
        reply.i32(outcome.result_code)?;
        for value in [
            outcome.input_bytes,
            outcome.packet_bytes,
            u64::from(outcome.block_count),
            u64::from(outcome.span_count),
            u64::from(outcome.flags),
        ] {
            reply.i64(i64::try_from(value).map_err(|_| -libc::EINVAL)?)?;
        }
        Ok(())
    })
}
