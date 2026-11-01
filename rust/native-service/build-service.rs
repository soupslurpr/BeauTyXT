//! Retains a native service's executable mapping until its isolated process exits.

fn main() {
    println!("cargo::rerun-if-changed=../native-service/build-service.rs");
    // Build scripts run on the host; the emitted linker flag belongs to the target.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo::rustc-link-arg-cdylib=-Wl,-z,nodelete");
    }
}
