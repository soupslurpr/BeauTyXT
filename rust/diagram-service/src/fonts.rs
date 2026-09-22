//! Selects bounded platform fonts through the NDK without starting ART.
use beautyxt_diagram_core::{
    MAX_FONT_BYTES, MAX_FONTS, render_diagram_with_fonts, validate_font_lengths,
};
use beautyxt_illustration_core::DrawingError;
use std::collections::BTreeSet;
use std::ffi::{CStr, OsStr};
use std::fs::File;
use std::io::Read;
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;
use std::ptr::NonNull;

mod ffi;

struct Matcher(NonNull<ffi::Matcher>);
impl Drop for Matcher {
    fn drop(&mut self) {
        // SAFETY: this guard owns the matcher and all matches have completed.
        unsafe {
            ffi::AFontMatcher_destroy(self.0.as_ptr());
        }
    }
}
struct Font(NonNull<ffi::Font>);
impl Drop for Font {
    fn drop(&mut self) {
        // SAFETY: this guard owns the font and no path pointer is retained.
        unsafe {
            ffi::AFont_close(self.0.as_ptr());
        }
    }
}

pub(super) fn render(source: &str, _: bool) -> Result<Vec<u8>, DrawingError> {
    // A Latin fallback plus all document text, including any supported script.
    let text: Vec<u16> = "A ".encode_utf16().chain(source.encode_utf16()).collect();
    // SAFETY: creation has no arguments and returns a caller-owned matcher.
    let matcher =
        Matcher(NonNull::new(unsafe { ffi::AFontMatcher_create() }).ok_or(DrawingError::Limit)?);
    let mut seen = BTreeSet::new();
    let mut fonts = Vec::new();
    let mut total = 0;
    for (weight, italic) in [(400, false), (700, false), (400, true)] {
        // SAFETY: the matcher is exclusively owned for this render call.
        unsafe {
            ffi::AFontMatcher_setStyle(matcher.0.as_ptr(), weight, italic);
        }
        let mut offset = 0;
        while offset < text.len() {
            let remaining = u32::try_from(text.len() - offset).map_err(|_| DrawingError::Limit)?;
            let mut run = 0;
            // SAFETY: all pointers remain live for the call, the UTF-16 length is checked,
            // and the selected family is fixed rather than derived from document content.
            let font = unsafe {
                ffi::AFontMatcher_match(
                    matcher.0.as_ptr(),
                    c"sans-serif".as_ptr(),
                    text[offset..].as_ptr(),
                    remaining,
                    &raw mut run,
                )
            };
            let font = Font(NonNull::new(font).ok_or(DrawingError::Invalid)?);
            if run == 0 || run > remaining {
                return Err(DrawingError::Invalid);
            }
            // SAFETY: the font owns the returned path until it is closed below.
            let path = unsafe { ffi::AFont_getFontFilePath(font.0.as_ptr()) };
            if path.is_null() {
                return Err(DrawingError::Invalid);
            }
            // SAFETY: NDK guarantees a NUL-terminated string, copied before font drops.
            let path = PathBuf::from(OsStr::from_bytes(
                unsafe { CStr::from_ptr(path) }.to_bytes(),
            ));
            if seen.insert(path.clone()) {
                if fonts.len() >= MAX_FONTS {
                    return Err(DrawingError::Limit);
                }
                // Only a path returned by Android's trusted matcher may be opened.
                let mut file = File::open(path).map_err(|_| DrawingError::Invalid)?;
                let size =
                    usize::try_from(file.metadata().map_err(|_| DrawingError::Invalid)?.len())
                        .map_err(|_| DrawingError::Limit)?;
                if size == 0 || size > MAX_FONT_BYTES - total {
                    return Err(DrawingError::Limit);
                }
                let mut bytes = vec![0; size];
                file.read_exact(&mut bytes)
                    .map_err(|_| DrawingError::Invalid)?;
                if file.read(&mut [0]).map_err(|_| DrawingError::Invalid)? != 0 {
                    return Err(DrawingError::Invalid);
                }
                total += size;
                fonts.push(bytes);
            }
            offset += usize::try_from(run).map_err(|_| DrawingError::Limit)?;
        }
    }
    validate_font_lengths(&fonts.iter().map(Vec::len).collect::<Vec<_>>())?;
    let references: Vec<&[u8]> = fonts.iter().map(Vec::as_slice).collect();
    render_diagram_with_fonts(source, &references)
}
