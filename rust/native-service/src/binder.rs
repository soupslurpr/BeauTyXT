/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified for BeauTyXT: preserve the reliable-PFD wire protocol across NDK Binder.
// Exact AOSP source and complete license: repository-root CREDITS.

//! Public NDK Binder handles and bounded AIDL parcel access.
use crate::ReliableFd;
use crate::ffi;
use std::collections::BTreeMap;
use std::ffi::CStr;
use std::ffi::c_void;
use std::os::fd::{FromRawFd, OwnedFd};
use std::ptr::{self, NonNull};
use std::sync::{LazyLock, Mutex};

/// Malformed or unsupported parcel value.
pub const BAD_VALUE: i32 = -libc::EINVAL;
/// A transaction not defined by the AIDL interface.
pub const UNKNOWN_TRANSACTION: i32 = -libc::EBADMSG;

static CLASSES: LazyLock<Mutex<BTreeMap<&'static CStr, &'static BinderClass>>> =
    LazyLock::new(|| Mutex::new(BTreeMap::new()));
pub(crate) fn class(descriptor: &'static CStr) -> &'static BinderClass {
    let mut classes = CLASSES.lock().expect("binder class registry poisoned");
    classes.entry(descriptor).or_insert_with(|| {
        Box::leak(Box::new(BinderClass::new(
            descriptor,
            crate::lifecycle::transact,
        )))
    })
}

pub(crate) struct BinderClass(pub(crate) NonNull<ffi::Class>);
// SAFETY: NDK classes are immutable after definition and live for the process lifetime.
unsafe impl Send for BinderClass {}
// SAFETY: all calls share the same immutable class descriptor and callbacks.
unsafe impl Sync for BinderClass {}

impl BinderClass {
    pub(crate) fn new(name: &'static CStr, transact: ffi::OnTransact) -> Self {
        // SAFETY: descriptor storage and function pointers live for the process lifetime.
        let class = unsafe {
            ffi::AIBinder_Class_define(
                name.as_ptr(),
                crate::lifecycle::binder_create,
                crate::lifecycle::binder_destroy,
                transact,
            )
        };
        Self(NonNull::new(class).expect("native binder class allocation failed"))
    }
}

/// An owned strong reference to a class-checked Binder endpoint.
pub struct Binder(pub(crate) NonNull<ffi::Binder>);
// SAFETY: AIBinder uses thread-safe strong references and permits concurrent transactions.
unsafe impl Send for Binder {}
// SAFETY: shared access never mutates Rust state behind this opaque handle.
unsafe impl Sync for Binder {}

impl Clone for Binder {
    fn clone(&self) -> Self {
        // SAFETY: self retains a strong reference while acquiring another.
        unsafe { ffi::AIBinder_incStrong(self.0.as_ptr()) };
        Self(self.0)
    }
}

impl Drop for Binder {
    fn drop(&mut self) {
        // SAFETY: this wrapper owns exactly one NDK strong reference.
        unsafe { ffi::AIBinder_decStrong(self.0.as_ptr()) };
    }
}

impl Binder {
    pub(crate) fn into_raw(self) -> *mut ffi::Binder {
        let pointer = self.0.as_ptr();
        std::mem::forget(self);
        pointer
    }

    /// Sends one AIDL one-way callback, releasing both parcels on every path.
    ///
    /// # Errors
    /// Returns the NDK write or transaction status.
    pub fn notify(
        &self,
        code: u32,
        write: impl FnOnce(&Writer) -> Result<(), i32>,
    ) -> Result<(), i32> {
        let mut input = Parcel(ptr::null_mut());
        // SAFETY: this live, class-associated binder creates a caller-owned parcel.
        check(unsafe { ffi::AIBinder_prepareTransaction(self.0.as_ptr(), &raw mut input.0) })?;
        write(&Writer(input.0))?;
        let mut output = Parcel(ptr::null_mut());
        // SAFETY: NDK consumes/clears input and returns an owned output (possibly
        // null for one-way calls). Both remaining pointers are guarded by Drop.
        check(unsafe {
            ffi::AIBinder_transact(
                self.0.as_ptr(),
                code,
                &raw mut input.0,
                &raw mut output.0,
                1,
            )
        })?;
        Ok(())
    }
}

struct Parcel(*mut ffi::Parcel);
impl Drop for Parcel {
    fn drop(&mut self) {
        // SAFETY: the pointer is either null or the uniquely owned parcel returned by NDK.
        unsafe { ffi::AParcel_delete(self.0) };
    }
}

struct Status(NonNull<ffi::Status>);
impl Drop for Status {
    fn drop(&mut self) {
        // SAFETY: this guard owns the AStatus returned by NDK.
        unsafe { ffi::AStatus_delete(self.0.as_ptr()) };
    }
}

// Borrowed parcel adapters stay within a transaction or the owner's lexical scope.
/// A readable parcel borrowed for one synchronous dispatch; never retained by a worker.
pub struct Reader(pub(crate) *const ffi::Parcel);
impl Reader {
    /// Reads one AIDL int.
    /// # Errors
    /// Returns an NDK decode error on truncated or malformed data.
    pub fn i32(&self) -> Result<i32, i32> {
        let mut value = 0;
        // SAFETY: the transaction owns the readable parcel; value is a writable out parameter.
        check(unsafe { ffi::AParcel_readInt32(self.0, &raw mut value) })?;
        Ok(value)
    }

    /// Reads one AIDL long.
    /// # Errors
    /// Returns an NDK decode error on truncated or malformed data.
    pub fn i64(&self) -> Result<i64, i32> {
        let mut value = 0;
        // SAFETY: the transaction owns the readable parcel; value is a writable out parameter.
        check(unsafe { ffi::AParcel_readInt64(self.0, &raw mut value) })?;
        Ok(value)
    }

    /// Returns the bytes remaining after the current read position.
    #[must_use]
    pub fn remaining(&self) -> i32 {
        // SAFETY: both calls inspect the same live parcel owned by the transaction.
        unsafe { ffi::AParcel_getDataSize(self.0) - ffi::AParcel_getDataPosition(self.0) }
    }

    /// Requires exact consumption before any request can have side effects.
    /// # Errors
    /// Rejects trailing bytes.
    pub fn finished(&self) -> Result<(), i32> {
        if self.remaining() == 0 {
            Ok(())
        } else {
            Err(BAD_VALUE)
        }
    }

    fn fd(&self) -> Result<Option<OwnedFd>, i32> {
        // Reject reliable PFDs before the NDK reader can detach their error channel.
        // TransferredFileDescriptor parcels its data/status as separate plain PFDs.
        // SAFETY: position is from this same live parcel and restored before NDK decoding.
        let position = unsafe { ffi::AParcel_getDataPosition(self.0) };
        match self.i32()? {
            0 => return Ok(None),
            1 if self.i32()? == 0 => {}
            _ => return Err(BAD_VALUE),
        }
        // SAFETY: position was obtained above, before successful reads from this parcel.
        check(unsafe { ffi::AParcel_setDataPosition(self.0, position) })?;
        let mut fd = -1;
        // SAFETY: NDK validates the object and gives the caller a newly owned descriptor.
        check(unsafe { ffi::AParcel_readParcelFileDescriptor(self.0, &raw mut fd) })?;
        if fd < 0 {
            return Err(BAD_VALUE);
        }
        // SAFETY: the successful NDK call transferred exactly one descriptor to us.
        Ok(Some(unsafe { OwnedFd::from_raw_fd(fd) }))
    }

    /// Reads and checks one optional callback Binder against its AIDL descriptor.
    /// # Errors
    /// Rejects malformed handles or a different interface descriptor.
    pub fn binder(&self, descriptor: &'static CStr) -> Result<Option<Binder>, i32> {
        let mut binder = ptr::null_mut();
        // SAFETY: NDK transfers one strong reference, or a null pointer, to the caller.
        check(unsafe { ffi::AParcel_readStrongBinder(self.0, &raw mut binder) })?;
        NonNull::new(binder)
            .map(Binder)
            .map(|binder| {
                // SAFETY: both the live binder and immutable process-lifetime class are valid.
                if unsafe {
                    ffi::AIBinder_associateClass(binder.0.as_ptr(), class(descriptor).0.as_ptr())
                } {
                    Ok(binder)
                } else {
                    Err(BAD_VALUE)
                }
            })
            .transpose()
    }

    /// Reads the owned data/status pair written by `TransferredFileDescriptor`.
    /// # Errors
    /// Rejects absent data, malformed parcelables, or invalid status channels.
    pub fn descriptor(&self) -> Result<Option<ReliableFd>, i32> {
        match self.i32()? {
            0 => Ok(None),
            1 => {
                let data = self.fd()?.ok_or(BAD_VALUE)?;
                let status = self.fd()?;
                ReliableFd::new(data, status)
                    .map(Some)
                    .map_err(|_| BAD_VALUE)
            }
            _ => Err(BAD_VALUE),
        }
    }

    /// Reads an AIDL boolean.
    /// # Errors
    /// Returns an NDK decode error on truncated data.
    pub fn boolean(&self) -> Result<bool, i32> {
        let mut value = false;
        // SAFETY: the live parcel owns the bytes; value is a writable output.
        check(unsafe { ffi::AParcel_readBool(self.0, &raw mut value) })?;
        Ok(value)
    }

    /// Reads an optional byte array, enforcing its size before allocation.
    /// # Errors
    /// Rejects malformed arrays or lengths above the caller's bound.
    pub fn bytes(&self, maximum: usize) -> Result<Option<Vec<u8>>, i32> {
        let mut array = ByteArray {
            maximum,
            bytes: None,
        };
        // SAFETY: NDK synchronously invokes the allocator with this stack context.
        check(unsafe {
            ffi::AParcel_readByteArray(self.0, (&raw mut array).cast(), allocate_bytes)
        })?;
        Ok(array.bytes)
    }
}

struct ByteArray {
    maximum: usize,
    bytes: Option<Vec<u8>>,
}
unsafe extern "C" fn allocate_bytes(data: *mut c_void, length: i32, buffer: *mut *mut i8) -> bool {
    // SAFETY: readByteArray provides the live ByteArray context and a writable buffer pointer.
    let array = unsafe { &mut *data.cast::<ByteArray>() };
    if length == -1 {
        // SAFETY: NDK supplied the writable output pointer; null represents an absent array.
        unsafe {
            *buffer = ptr::null_mut();
        }
        return true;
    }
    let Ok(length) = usize::try_from(length) else {
        return false;
    };
    if length > array.maximum {
        return false;
    }
    let mut bytes = Vec::new();
    if bytes.try_reserve_exact(length).is_err() {
        return false;
    }
    bytes.resize(length, 0);
    // SAFETY: the allocated buffer has the requested length and the context owns it until read returns.
    unsafe {
        *buffer = bytes.as_mut_ptr().cast();
    }
    array.bytes = Some(bytes);
    true
}

/// A writable parcel borrowed for one reply or callback.
pub struct Writer(pub(crate) *mut ffi::Parcel);
impl Writer {
    /// Writes one AIDL int.
    /// # Errors
    /// Returns an NDK parcel write error.
    pub fn i32(&self, value: i32) -> Result<(), i32> {
        // SAFETY: the callback or prepared transaction owns this writable parcel.
        check(unsafe { ffi::AParcel_writeInt32(self.0, value) })
    }

    /// Writes one AIDL long.
    /// # Errors
    /// Returns an NDK parcel write error.
    pub fn i64(&self, value: i64) -> Result<(), i32> {
        // SAFETY: the callback or prepared transaction owns this writable parcel.
        check(unsafe { ffi::AParcel_writeInt64(self.0, value) })
    }

    /// Writes a successful AIDL exception header before a synchronous result.
    /// # Errors
    /// Returns an NDK allocation or parcel write error.
    pub fn ok(&self) -> Result<(), i32> {
        // SAFETY: the allocator returns a caller-owned initialized success status.
        let status = Status(NonNull::new(unsafe { ffi::AStatus_newOk() }).ok_or(BAD_VALUE)?);
        // SAFETY: both the status and writable parcel remain live during the copy.
        check(unsafe { ffi::AParcel_writeStatusHeader(self.0, status.0.as_ptr()) })
    }

    /// Writes one AIDL boolean.
    /// # Errors
    /// Returns an NDK parcel write error.
    pub fn boolean(&self, value: bool) -> Result<(), i32> {
        // SAFETY: the prepared transaction owns this writable parcel.
        check(unsafe { ffi::AParcel_writeBool(self.0, value) })
    }

    /// Writes optional bounded bytes without retaining the source buffer.
    /// # Errors
    /// Returns an NDK parcel write error or rejects a length above `i32::MAX`.
    pub fn bytes(&self, bytes: Option<&[u8]>) -> Result<(), i32> {
        let (pointer, length) = match bytes {
            None => (ptr::null(), -1),
            Some(bytes) => (
                bytes.as_ptr().cast(),
                i32::try_from(bytes.len()).map_err(|_| BAD_VALUE)?,
            ),
        };
        // SAFETY: the slice remains live and has the given length; null/-1 means absent.
        check(unsafe { ffi::AParcel_writeByteArray(self.0, pointer, length) })
    }
}

pub(crate) const fn check(status: i32) -> Result<(), i32> {
    if status == 0 { Ok(()) } else { Err(status) }
}
