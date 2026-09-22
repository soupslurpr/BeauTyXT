//! Import AIDL dispatch with native descriptor ownership and cancellation.
use crate::worker::{
    ImportOutcome, JobControl, RESULT_CANCELLED, RESULT_INTERNAL, RESULT_SUCCESS, run_import,
};
use beautyxt_native_service::{
    Binder, Cancellation, Jobs, Reader, Service, UNKNOWN_TRANSACTION, Writer,
};
use std::ffi::CStr;
use std::os::fd::AsRawFd;
use std::sync::Arc;

struct ImportService {
    jobs: Arc<Jobs>,
}
pub(super) fn service() -> Arc<dyn Service> {
    Arc::new(ImportService {
        jobs: Arc::default(),
    })
}
impl Cancellation for JobControl {
    fn cancel(&self) {
        let _ = Self::cancel(self);
    }
}
impl Service for ImportService {
    fn descriptor(&self) -> &'static CStr {
        c"dev.soupslurpr.beautyxt.importing.IImportService"
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
                let input_limit = input.i64()?;
                let output_limit = input.i64()?;
                let timeout = input.i64()?;
                let callback =
                    input.binder(c"dev.soupslurpr.beautyxt.importing.IImportCallback")?;
                input.finished()?;
                let accepted = (|| {
                    let (Some(mut source), Some(output)) = (source, output) else {
                        return 2;
                    };
                    if id < 0
                        || !(1..=268_435_456).contains(&input_limit)
                        || !(1..=268_435_456).contains(&output_limit)
                        || !(1..=900_000).contains(&timeout)
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
                            if status(callback, id, 0, &ImportOutcome::failure(0)).is_err() {
                                let _ = control.cancel();
                            }
                            let outcome = if control.start().is_ok() {
                                run_import(
                                    &control,
                                    source.as_raw_fd(),
                                    output.as_raw_fd(),
                                    input_limit,
                                    output_limit,
                                    timeout,
                                    || source.check_error(),
                                )
                            } else {
                                ImportOutcome::failure(RESULT_INTERNAL)
                            };
                            let success = outcome.result_code == RESULT_SUCCESS;
                            source.close(success);
                            output.close(success);
                            let terminal = if success {
                                1
                            } else if outcome.result_code == RESULT_CANCELLED {
                                3
                            } else {
                                2
                            };
                            completion.finish();
                            let _ = status(callback, id, terminal, &outcome);
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
fn status(callback: &Binder, id: i64, state: i32, outcome: &ImportOutcome) -> Result<(), i32> {
    callback.notify(1, |reply| {
        reply.i64(id)?;
        reply.i32(state)?;
        reply.i32(outcome.result_code)?;
        reply.i64(i64::try_from(outcome.input_bytes).map_err(|_| -libc::EINVAL)?)?;
        reply.i64(i64::try_from(outcome.output_bytes).map_err(|_| -libc::EINVAL)?)?;
        reply.i32(i32::try_from(outcome.source_flags).map_err(|_| -libc::EINVAL)?)?;
        reply.bytes(Some(&outcome.sha256))
    })
}
