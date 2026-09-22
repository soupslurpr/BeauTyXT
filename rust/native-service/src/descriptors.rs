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

//! Owns data and reliable status capabilities without an Android runtime.
use std::io;
use std::os::fd::{AsRawFd, OwnedFd, RawFd};

/// A transferred data descriptor and its optional reliable provider error channel.
pub struct ReliableFd {
    data: OwnedFd,
    status: Option<OwnedFd>,
    checked: Option<bool>,
    finished: bool,
}
impl ReliableFd {
    /// Takes both handles, requiring the status channel to be a sequenced-packet socket.
    ///
    /// # Errors
    /// Rejects a status capability that cannot carry Android's reliable-PFD protocol.
    pub fn new(data: OwnedFd, status: Option<OwnedFd>) -> io::Result<Self> {
        if let Some(status) = &status {
            let mut kind: libc::c_int = 0;
            let mut size = 4_u32;
            // SAFETY: status owns the descriptor and both output buffers have the given size.
            let result = unsafe {
                libc::getsockopt(
                    status.as_raw_fd(),
                    libc::SOL_SOCKET,
                    libc::SO_TYPE,
                    (&raw mut kind).cast(),
                    &raw mut size,
                )
            };
            if result != 0 || kind != libc::SOCK_SEQPACKET {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "invalid provider status socket",
                ));
            }
        }
        Ok(Self {
            data,
            status,
            checked: None,
            finished: false,
        })
    }
    /// Checks the bounded big-endian status after EOF; absent/pending status is not an error.
    pub fn check_error(&mut self) -> bool {
        if let Some(error) = self.checked {
            return error;
        }
        let Some(status) = &self.status else {
            return false;
        };
        let mut bytes = [0_u8; 1024];
        // SAFETY: this live socket writes at most the capacity of the initialized buffer.
        let count = unsafe {
            libc::recv(
                status.as_raw_fd(),
                bytes.as_mut_ptr().cast(),
                bytes.len(),
                libc::MSG_DONTWAIT,
            )
        };
        if count < 0 && io::Error::last_os_error().kind() == io::ErrorKind::WouldBlock {
            return false;
        }
        let error = count < 4 || bytes[..4] != 0_i32.to_be_bytes();
        self.checked = Some(error);
        error
    }
    /// Best-effort clears an anonymous output after a failed operation.
    pub fn reset(&self) {
        // SAFETY: data remains owned during both operations; failures are discarded.
        unsafe {
            libc::ftruncate(self.data.as_raw_fd(), 0);
            libc::lseek(self.data.as_raw_fd(), 0, libc::SEEK_SET);
        }
    }
    /// Closes the capability, publishing success or a fixed, content-free failure.
    pub fn close(mut self, success: bool) {
        self.send_status(success);
        self.finished = true;
    }
    fn send_status(&self, success: bool) {
        let Some(status) = &self.status else {
            return;
        };
        let ok = 0_i32.to_be_bytes();
        let error = b"\x00\x00\x00\x01native worker failed";
        let bytes: &[u8] = if success { &ok } else { error };
        // SAFETY: status is a live sequenced-packet socket and bytes remain valid.
        // Nonblocking/no-signal flags keep provider failures from blocking or killing us.
        unsafe {
            libc::send(
                status.as_raw_fd(),
                bytes.as_ptr().cast(),
                bytes.len(),
                libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
            );
        }
    }
}
impl AsRawFd for ReliableFd {
    fn as_raw_fd(&self) -> RawFd {
        self.data.as_raw_fd()
    }
}
impl Drop for ReliableFd {
    fn drop(&mut self) {
        if !self.finished {
            self.send_status(false);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Read;
    use std::os::fd::FromRawFd;

    fn channel() -> (ReliableFd, OwnedFd, File) {
        let mut pair = [-1; 2];
        assert_eq!(
            // SAFETY: pair has space for the two new descriptors.
            unsafe {
                libc::socketpair(
                    libc::AF_UNIX,
                    libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC,
                    0,
                    pair.as_mut_ptr(),
                )
            },
            0
        );
        // SAFETY: socketpair transferred two distinct owned descriptors.
        let (status, peer) =
            unsafe { (OwnedFd::from_raw_fd(pair[0]), OwnedFd::from_raw_fd(pair[1])) };
        assert_eq!(
            // SAFETY: pair has space for the new pipe's descriptors.
            unsafe { libc::pipe2(pair.as_mut_ptr(), libc::O_CLOEXEC) },
            0
        );
        // SAFETY: pipe2 transferred two distinct owned descriptors.
        let (reader, writer) =
            unsafe { (File::from_raw_fd(pair[0]), OwnedFd::from_raw_fd(pair[1])) };
        (ReliableFd::new(writer, Some(status)).unwrap(), peer, reader)
    }

    fn send(fd: &OwnedFd, bytes: &[u8]) {
        // SAFETY: the socket and the complete message remain live for the call.
        let count = unsafe {
            libc::send(
                fd.as_raw_fd(),
                bytes.as_ptr().cast(),
                bytes.len(),
                libc::MSG_NOSIGNAL,
            )
        };
        assert_eq!(usize::try_from(count).unwrap(), bytes.len());
    }

    #[test]
    fn provider_status_is_nonblocking_big_endian_and_retained_after_read() {
        for (bytes, error) in [
            (0_i32.to_be_bytes().to_vec(), false),
            (1_i32.to_be_bytes().to_vec(), true),
            (2_i32.to_be_bytes().to_vec(), true),
            (3_i32.to_be_bytes().to_vec(), true),
            (vec![0, 0, 0], true),
        ] {
            let (mut descriptor, peer, _reader) = channel();
            assert!(!descriptor.check_error());
            send(&peer, &bytes);
            assert_eq!(descriptor.check_error(), error);
            assert_eq!(descriptor.check_error(), error);
        }
        let (mut descriptor, peer, _reader) = channel();
        drop(peer);
        assert!(
            descriptor.check_error(),
            "a dead provider cannot report success"
        );
    }

    #[test]
    fn every_terminal_path_closes_data_and_reports_success_or_failure() {
        for success in [Some(true), Some(false), None] {
            let (descriptor, peer, mut reader) = channel();
            if let Some(success) = success {
                descriptor.close(success);
            } else {
                drop(descriptor);
            }
            assert_eq!(reader.read(&mut [0]).unwrap(), 0);
            let mut message = [0; 128];
            // SAFETY: peer owns the socket, and message is writable for its full length.
            let count = unsafe {
                libc::recv(
                    peer.as_raw_fd(),
                    message.as_mut_ptr().cast(),
                    message.len(),
                    libc::MSG_DONTWAIT,
                )
            };
            assert!(count >= 4);
            assert_eq!(
                &message[..4],
                &i32::from(success != Some(true)).to_be_bytes()
            );
        }
    }

    #[test]
    fn non_socket_status_descriptor_is_rejected() {
        let data: OwnedFd = File::open("/dev/null").unwrap().into();
        let status: OwnedFd = File::open("/dev/null").unwrap().into();
        assert!(ReliableFd::new(data, Some(status)).is_err());
    }
}
