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
    #[cfg(target_family = "unix")]
    let filter: BpfProgram = SeccompFilter::new(
        vec![
            (libc::SYS_ioctl, vec![]),
            // Not sure why prctl is required
            (libc::SYS_prctl, vec![]),
            (libc::SYS_mprotect, vec![]),
            (libc::SYS_getrandom, vec![]),
            (libc::SYS_clock_gettime, vec![]),
            (222, vec![]), // mmap
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

    #[cfg(target_family = "unix")]
    apply_filter_all_threads(&filter).unwrap();

    #[cfg(not(target_family = "unix"))]
    panic!("seccomp is only available on the unix family!")
}
