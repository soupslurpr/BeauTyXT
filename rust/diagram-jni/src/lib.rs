//! Fixed-size JNI admission for the independent diagram process.

use beautyxt_illustration_core::DrawingError;
use jni::{
    Env, EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JByteArray, JByteBuffer, JClass, JObjectArray},
};

/// Borrows read-only platform fonts synchronously and returns a status-prefixed drawing packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_illustration_NativeDiagramRenderer_render<
    'caller,
>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    source: JByteArray<'caller>,
    fonts: JObjectArray<'caller, JByteBuffer<'caller>>,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> jni::errors::Result<JByteArray<'caller>> {
            let rendered = if source.len(env)? > beautyxt_diagram_core::MAX_SOURCE_BYTES {
                Err(DrawingError::Limit)
            } else {
                let source = env.convert_byte_array(&source)?;
                match std::str::from_utf8(&source) {
                    Ok(source) => render_mapped_fonts(env, source, &fonts)?,
                    Err(_) => Err(DrawingError::Invalid),
                }
            };
            let (status, packet) = match rendered {
                Ok(packet) => (0_u32, packet),
                Err(DrawingError::Unsupported) => (1, Vec::new()),
                Err(DrawingError::Limit) => (2, Vec::new()),
                Err(DrawingError::Invalid) => (3, Vec::new()),
                Err(DrawingError::TimedOut) => (5, Vec::new()),
            };
            let mut response = status.to_le_bytes().to_vec();
            response.extend_from_slice(&packet);
            env.byte_array_from_slice(&response)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn render_mapped_fonts(
    env: &mut Env<'_>,
    source: &str,
    fonts: &JObjectArray<'_, JByteBuffer<'_>>,
) -> jni::errors::Result<Result<Vec<u8>, DrawingError>> {
    let count = fonts.len(env)?;
    if count == 0 || count > beautyxt_diagram_core::MAX_FONTS {
        return Ok(Err(DrawingError::Limit));
    }
    let mut references = Vec::with_capacity(count);
    let mut lengths = Vec::with_capacity(count);
    for index in 0..count {
        let buffer = fonts.get_element(env, index)?;
        lengths.push(env.get_direct_buffer_capacity(&buffer)?);
        references.push(buffer);
    }
    if let Err(error) = beautyxt_diagram_core::validate_font_lengths(&lengths) {
        return Ok(Err(error));
    }
    let mut data = Vec::with_capacity(count);
    for (buffer, length) in references.iter().zip(lengths) {
        let address = env.get_direct_buffer_address(buffer)?;
        // SAFETY: the private Kotlin call accepts only read-only Android Font.buffer mappings.
        // Each direct capacity and the aggregate size were checked above. JNI references and
        // Kotlin's Font owners remain alive throughout this synchronous call. No borrowed bytes
        // are mutated or retained; the native font database makes its own bounded owned copy.
        data.push(unsafe { std::slice::from_raw_parts(address.cast_const(), length) });
    }
    Ok(beautyxt_diagram_core::render_diagram_with_fonts(
        source, &data,
    ))
}
