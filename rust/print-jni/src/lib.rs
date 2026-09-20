//! Synchronous, non-retaining bridge for Android-selected platform font outlines.

use beautyxt_print_core::{
    MAX_FONT_AXES, MAX_FONT_BYTES, OutlineError, glyph_outline, variation_tag,
};
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteBuffer, JClass, JFloatArray, JIntArray};
use jni::sys::jint;
use std::fmt;

#[derive(Debug)]
struct Error(std::io::Error);

impl Error {
    fn other(error: impl Into<Box<dyn std::error::Error + Send + Sync>>) -> Self {
        Self(std::io::Error::other(error))
    }
}

impl fmt::Display for Error {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.fmt(formatter)
    }
}

impl std::error::Error for Error {}

impl From<jni::errors::Error> for Error {
    fn from(value: jni::errors::Error) -> Self {
        Self::other(value)
    }
}

/// Returns a bounded outline, or null when native Android color-glyph drawing is needed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_printing_NativePrintFont_outline<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    font_buffer: JByteBuffer<'caller>,
    face_index: jint,
    glyph_id: jint,
    axis_tags: JIntArray<'caller>,
    axis_values: JFloatArray<'caller>,
) -> JFloatArray<'caller> {
    unowned_env
        .with_env(|env| -> Result<JFloatArray<'caller>, Error> {
            let length = env
                .get_direct_buffer_capacity(&font_buffer)
                .map_err(Error::other)?;
            let axis_count = axis_tags.len(env).map_err(Error::other)?;
            if length == 0 || length > MAX_FONT_BYTES || axis_count > MAX_FONT_AXES {
                return Err(Error::other(OutlineError::Limit));
            }
            if axis_count != axis_values.len(env).map_err(Error::other)? {
                return Err(Error::other(OutlineError::InvalidFont));
            }
            let face_index = u32::try_from(face_index).map_err(Error::other)?;
            let glyph_id = u32::try_from(glyph_id).map_err(Error::other)?;
            let mut tags = vec![0; axis_count];
            let mut values = vec![0.0; axis_count];
            axis_tags
                .get_region(env, 0, &mut tags)
                .map_err(Error::other)?;
            axis_values
                .get_region(env, 0, &mut values)
                .map_err(Error::other)?;
            let variations: Vec<_> = tags
                .iter()
                .zip(values)
                .map(|(tag, value)| (variation_tag(tag.to_be_bytes()), value))
                .collect();
            let address = env
                .get_direct_buffer_address(&font_buffer)
                .map_err(Error::other)?;
            // SAFETY: the private Kotlin boundary passes only Android Font.buffer,
            // a read-only mapping kept alive by this JNI reference and its Font owner.
            // JNI has checked direct-buffer capacity; no bytes are mutated or retained
            // beyond this synchronous call, and the font mapping cannot be resized.
            let data = unsafe { std::slice::from_raw_parts(address.cast_const(), length) };
            let Some(outline) =
                glyph_outline(data, face_index, glyph_id, &variations).map_err(Error::other)?
            else {
                return Ok(JFloatArray::null());
            };
            let result = JFloatArray::new(env, outline.len()).map_err(Error::other)?;
            result.set_region(env, 0, &outline).map_err(Error::other)?;
            Ok(result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
