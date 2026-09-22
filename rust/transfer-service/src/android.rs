//! Native QR/NFC AIDL host.
use crate::worker::{
    JobControl, RESULT_INPUT_IO, RESULT_INTERNAL, TransferOutcome, TransferRequest, run_transfer,
    unpack_tag_label,
};
use beautyxt_native_service::{
    Binder, Cancellation, Jobs, Reader, Service, UNKNOWN_TRANSACTION, Writer,
};
use std::ffi::CStr;
use std::os::fd::AsRawFd;
use std::sync::Arc;
struct TransferService {
    jobs: Arc<Jobs>,
}
pub(super) fn service() -> Arc<dyn Service> {
    Arc::new(TransferService {
        jobs: Arc::default(),
    })
}
impl Cancellation for JobControl {
    fn cancel(&self) {
        let _ = Self::cancel(self);
    }
}
impl Service for TransferService {
    fn descriptor(&self) -> &'static CStr {
        c"dev.soupslurpr.beautyxt.transfer.ITransferService"
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
                let operation = input.i32()?;
                let source = input.descriptor()?;
                let output = input.descriptor()?;
                let expected = input.i64()?;
                let argument_zero = input.i64()?;
                let argument_one = input.i64()?;
                let timeout = input.i64()?;
                let callback =
                    input.binder(c"dev.soupslurpr.beautyxt.transfer.ITransferCallback")?;
                input.finished()?;
                let accepted = (|| {
                    let (Some(mut source), Some(output)) = (source, output) else {
                        return 2;
                    };
                    if id <= 0
                        || !valid_request(operation, expected, argument_zero, argument_one)
                        || !(1..=30_000).contains(&timeout)
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
                            if status(callback, id, 0, &TransferOutcome::failure(0)).is_err() {
                                let _ = control.cancel();
                            }
                            let mut outcome = if control.start().is_ok() {
                                run_transfer(
                                    &control,
                                    TransferRequest {
                                        operation,
                                        input_raw_fd: source.as_raw_fd(),
                                        output_raw_fd: output.as_raw_fd(),
                                        expected_input_bytes: expected,
                                        argument_zero,
                                        argument_one,
                                        timeout_millis: timeout,
                                    },
                                )
                            } else {
                                TransferOutcome::failure(RESULT_INTERNAL)
                            };
                            if outcome.result_code == 0 && source.check_error() {
                                output.reset();
                                outcome = TransferOutcome::failure(RESULT_INPUT_IO);
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
fn status(callback: &Binder, id: i64, state: i32, outcome: &TransferOutcome) -> Result<(), i32> {
    callback.notify(1, |reply| {
        reply.i64(id)?;
        reply.i32(state)?;
        reply.i32(outcome.result_code)?;
        reply.i64(i64::try_from(outcome.input_bytes).map_err(|_| -libc::EINVAL)?)?;
        reply.i64(i64::try_from(outcome.output_bytes).map_err(|_| -libc::EINVAL)?)?;
        reply.i64(i64::from(outcome.detail_zero))?;
        reply.i64(i64::from(outcome.detail_one))
    })
}

fn valid_request(operation: i32, expected: i64, argument_zero: i64, argument_one: i64) -> bool {
    match operation {
        1 => {
            (0..=1536).contains(&expected) && (0..=1).contains(&argument_zero) && argument_one == 0
        }
        2 => {
            argument_zero >= 48
                && argument_one >= 48
                && argument_zero.checked_mul(argument_one) == Some(expected)
                && expected <= 1280 * 960
        }
        3 => {
            (0..=256 * 1024 - 54 - 44 - 3).contains(&expected)
                && (0..=1).contains(&argument_zero)
                && unpack_tag_label(argument_one).is_ok()
        }
        4 => (3..=256 * 1024).contains(&expected) && argument_zero == 0 && argument_one == 0,
        _ => false,
    }
}
