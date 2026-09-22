/*
 * Copyright (C) 2018 The Android Open Source Project
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
/*
 * Copyright (C) 2025 The Android Open Source Project
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
// Modified for BeauTyXT: minimal Rust declarations for the public NDK ABI.
// Exact sources and complete license: repository-root CREDITS.

//! Public NDK declarations; ownership is enforced by the Android adapter.
#![allow(non_snake_case)]

use std::ffi::{c_char, c_void};

pub enum Service {}
pub enum Binder {}
pub enum Class {}
pub enum Parcel {}
pub enum Status {}
pub enum DeathRecipient {}

pub type OnTransact = unsafe extern "C" fn(*mut Binder, u32, *const Parcel, *mut Parcel) -> i32;

#[link(name = "android")]
unsafe extern "C" {
    pub fn ANativeService_setOnBindCallback(
        service: *mut Service,
        callback: unsafe extern "C" fn(
            *mut Service,
            u64,
            *const c_char,
            *const c_char,
        ) -> *mut Binder,
    );
    pub fn ANativeService_setOnUnbindCallback(
        service: *mut Service,
        callback: unsafe extern "C" fn(*mut Service, u64) -> bool,
    );
    pub fn ANativeService_setOnDestroyCallback(
        service: *mut Service,
        callback: unsafe extern "C" fn(*mut Service),
    );
}

#[link(name = "binder_ndk")]
unsafe extern "C" {
    pub fn AIBinder_Class_define(
        descriptor: *const c_char,
        create: unsafe extern "C" fn(*mut c_void) -> *mut c_void,
        destroy: unsafe extern "C" fn(*mut c_void),
        transact: OnTransact,
    ) -> *mut Class;
    pub fn AIBinder_new(class: *const Class, data: *mut c_void) -> *mut Binder;
    pub fn AIBinder_incStrong(binder: *mut Binder);
    pub fn AIBinder_decStrong(binder: *mut Binder);
    pub fn AIBinder_associateClass(binder: *mut Binder, class: *const Class) -> bool;
    pub fn AIBinder_getUserData(binder: *mut Binder) -> *mut c_void;
    pub fn AIBinder_prepareTransaction(binder: *mut Binder, input: *mut *mut Parcel) -> i32;
    pub fn AIBinder_transact(
        binder: *mut Binder,
        code: u32,
        input: *mut *mut Parcel,
        output: *mut *mut Parcel,
        flags: u32,
    ) -> i32;
    pub fn AIBinder_DeathRecipient_new(
        died: unsafe extern "C" fn(*mut c_void),
    ) -> *mut DeathRecipient;
    pub fn AIBinder_DeathRecipient_setOnUnlinked(
        recipient: *mut DeathRecipient,
        unlinked: unsafe extern "C" fn(*mut c_void),
    );
    pub fn AIBinder_DeathRecipient_delete(recipient: *mut DeathRecipient);
    pub fn AIBinder_linkToDeath(
        binder: *mut Binder,
        recipient: *mut DeathRecipient,
        cookie: *mut c_void,
    ) -> i32;
    pub fn AParcel_delete(parcel: *mut Parcel);
    pub fn AParcel_getDataPosition(parcel: *const Parcel) -> i32;
    pub fn AParcel_setDataPosition(parcel: *const Parcel, position: i32) -> i32;
    pub fn AParcel_getDataSize(parcel: *const Parcel) -> i32;
    pub fn AParcel_readInt32(parcel: *const Parcel, value: *mut i32) -> i32;
    pub fn AParcel_readInt64(parcel: *const Parcel, value: *mut i64) -> i32;
    pub fn AParcel_readBool(parcel: *const Parcel, value: *mut bool) -> i32;
    pub fn AParcel_readParcelFileDescriptor(parcel: *const Parcel, fd: *mut i32) -> i32;
    pub fn AParcel_readStrongBinder(parcel: *const Parcel, binder: *mut *mut Binder) -> i32;
    pub fn AParcel_writeStatusHeader(parcel: *mut Parcel, status: *const Status) -> i32;
    pub fn AParcel_writeInt32(parcel: *mut Parcel, value: i32) -> i32;
    pub fn AParcel_writeInt64(parcel: *mut Parcel, value: i64) -> i32;
    pub fn AParcel_writeBool(parcel: *mut Parcel, value: bool) -> i32;
    pub fn AParcel_readByteArray(
        parcel: *const Parcel,
        data: *mut c_void,
        allocate: unsafe extern "C" fn(*mut c_void, i32, *mut *mut i8) -> bool,
    ) -> i32;
    pub fn AParcel_writeByteArray(parcel: *mut Parcel, bytes: *const i8, length: i32) -> i32;
    pub fn AStatus_newOk() -> *mut Status;
    pub fn AStatus_delete(status: *mut Status);
}
