//! Fixed-size JNI input admission for a separately terminable math process.

use beautyxt_illustration_core::DrawingError;
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass};
use jni::sys::jboolean;

/// Renders one formula into a bounded status-prefixed packet, retaining no JNI inputs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_illustration_NativeMathRenderer_render<
    'caller,
>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    source: JByteArray<'caller>,
    display: jboolean,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> jni::errors::Result<JByteArray<'caller>> {
            let size = source.len(env)?;
            let rendered = if size == 0 || size > beautyxt_math_core::MAX_SOURCE_BYTES {
                Err(DrawingError::Limit)
            } else {
                let input = env.convert_byte_array(&source)?;
                std::str::from_utf8(&input)
                    .map_err(|_| DrawingError::Invalid)
                    .and_then(|text| beautyxt_math_core::render_math(text, display))
            };
            let (status, packet) = match rendered {
                Ok(packet) => (0_u32, packet),
                Err(DrawingError::Unsupported) => (1, Vec::new()),
                Err(DrawingError::Limit) => (2, Vec::new()),
                Err(DrawingError::Invalid) => (3, Vec::new()),
                Err(DrawingError::TimedOut) => (5, Vec::new()),
            };
            let mut response = Vec::with_capacity(4 + packet.len());
            response.extend_from_slice(&status.to_le_bytes());
            response.extend_from_slice(&packet);
            env.byte_array_from_slice(&response)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
