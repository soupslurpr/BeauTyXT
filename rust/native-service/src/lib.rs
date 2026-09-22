//! Public Android native-service transport and descriptor ownership.
#![cfg(any(target_os = "android", test))]
#[cfg(target_os = "android")]
mod binder;
mod descriptors;
#[cfg(target_os = "android")]
mod ffi;
#[cfg(target_os = "android")]
mod jobs;
#[cfg(target_os = "android")]
mod lifecycle;
#[cfg(target_os = "android")]
pub use binder::{BAD_VALUE, Binder, Reader, UNKNOWN_TRANSACTION, Writer};
pub use descriptors::ReliableFd;
#[cfg(target_os = "android")]
pub use jobs::{Cancellation, Completion, Jobs};
#[cfg(target_os = "android")]
pub use lifecycle::{Service, create};
