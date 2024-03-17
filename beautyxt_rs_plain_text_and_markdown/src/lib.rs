mod markdown;
mod plain_text;

uniffi::setup_scaffolding!();

#[cfg(target_family = "unix")]
use seccompiler::{apply_filter_all_threads, BpfProgram, SeccompAction, SeccompFilter};

// Required for UPX to work
#[no_mangle]
pub fn _init() {}

#[uniffi::export]
pub fn apply_seccomp_bpf() {
    #[cfg(not(target_family = "unix"))]
    panic!("seccomp is only available on the unix family! Also, only unix is currently supported.");

    #[cfg(target_family = "unix")]
    {
        let SYS_mmap: i64 = {
            if std::env::consts::ARCH == "x86_64" {
                9
            } else if std::env::consts::ARCH == "aarch64" {
                222
            } else {
                panic!()
            }
        };

        let filter: BpfProgram = SeccompFilter::new(
            vec![
                (libc::SYS_ioctl, vec![]),
                (libc::SYS_prctl, vec![]),
                (libc::SYS_mprotect, vec![]),
                (libc::SYS_getrandom, vec![]),
                (libc::SYS_clock_gettime, vec![]),
                (SYS_mmap, vec![]),
            ]
            .into_iter()
            .collect(),
            // mismatch_action
            // SeccompAction::Log, // Useful for testing which syscalls are needed
            SeccompAction::KillProcess,
            // match_action
            SeccompAction::Allow,
            // target architecture of filter
            std::env::consts::ARCH.try_into().unwrap(),
        )
        .unwrap()
        .try_into()
        .unwrap();

        apply_filter_all_threads(&filter).unwrap();
    }
}
