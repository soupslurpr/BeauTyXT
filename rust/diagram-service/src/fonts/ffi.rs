/*
 * Copyright (C) 2019 The Android Open Source Project
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
//! Adapted minimal Rust declarations from Android NDK font headers; see CREDITS.
use std::ffi::c_char;
pub(super) enum Matcher {}
pub(super) enum Font {}
#[link(name = "android")]
unsafe extern "C" {
    pub(super) fn AFontMatcher_create() -> *mut Matcher;
    pub(super) fn AFontMatcher_destroy(matcher: *mut Matcher);
    pub(super) fn AFontMatcher_setStyle(matcher: *mut Matcher, weight: u16, italic: bool);
    pub(super) fn AFontMatcher_match(
        matcher: *const Matcher,
        family: *const c_char,
        text: *const u16,
        length: u32,
        run: *mut u32,
    ) -> *mut Font;
    pub(super) fn AFont_getFontFilePath(font: *const Font) -> *const c_char;
    pub(super) fn AFont_close(font: *mut Font);
}
