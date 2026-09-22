//! Native export, conditional save, and source verification AIDL host.
use crate::worker::{
    ExactSourceVersion, ExportOutcome, JobControl, RESULT_INPUT_IO, RESULT_INTERNAL,
    RESULT_OUTPUT_IO, decode_exact_source_version, decode_source_expectation,
    run_conditional_source_save, run_export, run_source_inspection, run_source_verification,
};
use beautyxt_native_service::{
    Binder, Cancellation, Jobs, Reader, ReliableFd, Service, UNKNOWN_TRANSACTION, Writer,
};
use std::ffi::CStr;
use std::os::fd::AsRawFd;
use std::sync::Arc;

struct ExportService {
    jobs: Arc<Jobs>,
}
pub(super) fn service() -> Arc<dyn Service> {
    Arc::new(ExportService {
        jobs: Arc::default(),
    })
}
impl Cancellation for JobControl {
    fn cancel(&self) {
        let _ = Self::cancel(self);
    }
}
struct Request {
    operation: u32,
    id: i64,
    input: ReliableFd,
    backing: Option<ReliableFd>,
    output: Option<ReliableFd>,
    expected: i64,
    version: Option<ExactSourceVersion>,
    timeout: i64,
}
impl Service for ExportService {
    fn descriptor(&self) -> &'static CStr {
        c"dev.soupslurpr.beautyxt.exporting.IExportService"
    }
    fn unbind(&self) {
        self.jobs.cancel(None, false);
    }
    fn destroy(&self) {
        self.jobs.cancel(None, true);
    }
    fn transact(&self, code: u32, input: &Reader, reply: &Writer) -> Result<(), i32> {
        if code == 5 {
            let id = input.i64()?;
            input.finished()?;
            self.jobs.cancel(Some(id), false);
            return Ok(());
        }
        if !(1..=4).contains(&code) {
            return Err(UNKNOWN_TRANSACTION);
        }
        let id = input.i64()?;
        let source = input.descriptor()?;
        let backing = if code == 2 { input.descriptor()? } else { None };
        let output = if code <= 2 { input.descriptor()? } else { None };
        let expected = input.i64()?;
        let version = match code {
            2 => {
                let source_bytes = input.i64()?;
                let digest = input.bytes(32)?;
                digest.ok_or(()).and_then(|digest| {
                    decode_source_expectation(source_bytes, &digest).map_err(|_| ())
                })
            }
            3 => {
                let digest = input.bytes(32)?;
                digest.ok_or(()).and_then(|digest| {
                    decode_exact_source_version(expected, &digest)
                        .map(Some)
                        .map_err(|_| ())
                })
            }
            _ => Ok(None),
        };
        let timeout = input.i64()?;
        let descriptor = if code == 1 {
            c"dev.soupslurpr.beautyxt.exporting.IExportCallback"
        } else {
            c"dev.soupslurpr.beautyxt.exporting.ISourceSaveCallback"
        };
        let callback = input.binder(descriptor)?;
        input.finished()?;
        let accepted = (|| {
            let (Some(source), Ok(version)) = (source, version) else {
                return 2;
            };
            if id <= 0
                || !(0..=268_435_456).contains(&expected)
                || !(1..=900_000).contains(&timeout)
                || (code <= 2 && output.is_none())
            {
                return 2;
            }
            let Some(callback) = callback else {
                return 3;
            };
            let Ok(control) = JobControl::new().map(Arc::new) else {
                return 3;
            };
            let request = Request {
                operation: code,
                id,
                input: source,
                backing,
                output,
                expected,
                version,
                timeout,
            };
            self.jobs.start(
                id,
                control.clone(),
                callback,
                move |callback, completion| {
                    run(request, &control, callback, completion);
                },
            )
        })();
        reply.ok()?;
        reply.i32(accepted)
    }
}
fn run(
    mut request: Request,
    control: &Arc<JobControl>,
    callback: &Binder,
    completion: &beautyxt_native_service::Completion,
) {
    let source_operation = request.operation != 1;
    if status(
        callback,
        request.id,
        source_operation,
        0,
        &ExportOutcome::failure(0),
    )
    .is_err()
    {
        let _ = control.cancel();
    }
    let source_fd = request.input.as_raw_fd();
    let output_fd = request.output.as_ref().map_or(-1, AsRawFd::as_raw_fd);
    let mut outcome = if control.start().is_err() {
        ExportOutcome::failure(RESULT_INTERNAL)
    } else {
        match request.operation {
            1 => run_export(
                control,
                source_fd,
                output_fd,
                request.expected,
                request.timeout,
            ),
            2 => run_conditional_source_save(
                control,
                source_fd,
                request.backing.as_ref().map_or(-1, AsRawFd::as_raw_fd),
                output_fd,
                request.expected,
                request.version.as_ref(),
                request.timeout,
            ),
            3 => match &request.version {
                Some(version) => {
                    run_source_verification(control, source_fd, version, request.timeout)
                }
                None => ExportOutcome::failure(RESULT_INTERNAL),
            },
            4 => run_source_inspection(control, source_fd, request.expected, request.timeout),
            _ => ExportOutcome::failure(RESULT_INTERNAL),
        }
    };
    if outcome.result_code == 0 {
        let error = if request.input.check_error()
            || request
                .backing
                .as_mut()
                .is_some_and(ReliableFd::check_error)
        {
            Some(RESULT_INPUT_IO)
        } else if request.output.as_mut().is_some_and(ReliableFd::check_error) {
            Some(RESULT_OUTPUT_IO)
        } else {
            None
        };
        if let Some(error) = error {
            outcome = if request.operation == 2 && outcome.output_started {
                ExportOutcome::source_uncertain()
            } else {
                ExportOutcome::failure(error)
            };
        }
    }
    let success = outcome.result_code == 0;
    request.input.close(success);
    if let Some(backing) = request.backing {
        backing.close(success);
    }
    if let Some(output) = request.output {
        output.close(success);
    }
    completion.finish();
    let _ = status(
        callback,
        request.id,
        source_operation,
        if success {
            1
        } else if outcome.result_code == 1 {
            3
        } else {
            2
        },
        &outcome,
    );
}
fn status(
    callback: &Binder,
    id: i64,
    source: bool,
    state: i32,
    outcome: &ExportOutcome,
) -> Result<(), i32> {
    callback.notify(1, |reply| {
        reply.i64(id)?;
        reply.i32(state)?;
        reply.i32(outcome.result_code)?;
        reply.i64(i64::try_from(outcome.input_bytes).map_err(|_| -libc::EINVAL)?)?;
        reply.i64(i64::try_from(outcome.output_bytes).map_err(|_| -libc::EINVAL)?)?;
        if source {
            reply.boolean(outcome.output_started)?;
            reply.bytes(Some(
                outcome
                    .source_sha256
                    .as_ref()
                    .map_or(&[], <[u8; 32]>::as_slice),
            ))?;
        }
        Ok(())
    })
}
