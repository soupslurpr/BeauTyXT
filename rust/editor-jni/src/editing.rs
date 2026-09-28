//! Bounded insertion transfer and persistent native history operations.

use super::{
    BridgeError, DocumentError, EnvUnowned, JByteArray, JClass, JLongArray,
    ThrowRuntimeExAndDefault, Utf16Range, encode_document_metrics, jboolean, jlong, lock_registry,
    nonnegative_u64, nonnegative_usize,
};
use beautyxt_editor_core::MAX_INSERTION_CHUNK_BYTES;

/// Starts an unpublished insertion owned by one document.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_beginInsertion(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: jlong,
    revision: jlong,
    start: jlong,
    end: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        Ok(lock_registry()?.get_mut(handle)?.begin_insertion(
            nonnegative_u64(revision, "revision")?,
            Utf16Range::new(
                nonnegative_usize(start, "start")?,
                nonnegative_usize(end, "end")?,
            ),
        )?)
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

/// Transfers a strictly validated bounded UTF-8 chunk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_appendInsertion(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: jlong,
    revision: jlong,
    chunk: JByteArray<'_>,
) {
    env.with_env(|env| -> Result<(), BridgeError> {
        if chunk.len(env)? > MAX_INSERTION_CHUNK_BYTES {
            return Err(BridgeError::InvalidArgument("insertion chunk limit"));
        }
        let bytes = env.convert_byte_array(&chunk)?;
        let text = std::str::from_utf8(&bytes)
            .map_err(|_| BridgeError::InvalidArgument("insertion UTF-8"))?;
        Ok(lock_registry()?
            .get_mut(handle)?
            .append_insertion(nonnegative_u64(revision, "revision")?, text)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

/// Discards private chunks without affecting document or history.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_cancelInsertion(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        lock_registry()?.get_mut(handle)?.cancel_insertion();
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

/// Commits one complete insertion together with its Undo tree.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_finishInsertion<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
) -> JByteArray<'caller> {
    env.with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
        let outcome = lock_registry()?
            .get_mut(handle)?
            .finish_insertion(nonnegative_u64(revision, "revision")?);
        let metrics = match outcome {
            Err(DocumentError::DocumentTooLargeForSaving { .. }) => {
                env.throw_new(
                    jni::jni_str!("dev/soupslurpr/beautyxt/document/DocumentSizeLimitException"),
                    jni::jni_str!("edit exceeds the serialized document size limit"),
                )?;
                return Ok(JByteArray::null());
            }
            result => result?,
        };
        Ok(env.byte_array_from_slice(&encode_document_metrics(&metrics)?)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Restores an exact native history token as a new revision.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_restoreHistory<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    token: jlong,
    undo: jboolean,
) -> JByteArray<'caller> {
    env.with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
        let metrics = lock_registry()?.get_mut(handle)?.restore_history(
            nonnegative_u64(revision, "revision")?,
            nonnegative_u64(token, "history token")?,
            undo,
        )?;
        Ok(env.byte_array_from_slice(&encode_document_metrics(&metrics)?)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns the oldest available Undo token for pruning platform metadata.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_historyState<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) -> JLongArray<'caller> {
    env.with_env(|env| -> Result<JLongArray<'caller>, BridgeError> {
        let state = lock_registry()?.get(handle)?.history_state();
        let values = [
            state.undo,
            state.redo,
            state.oldest_undo,
            u64::try_from(state.retained_bytes)
                .map_err(|_| BridgeError::InvalidArgument("history bytes"))?,
            u64::try_from(state.entries)
                .map_err(|_| BridgeError::InvalidArgument("history entries"))?,
        ];
        let values = values
            .map(jlong::try_from)
            .into_iter()
            .collect::<Result<Vec<_>, _>>()
            .map_err(|_| BridgeError::InvalidArgument("history state"))?;
        let result = env.new_long_array(5)?;
        result.set_region(env, 0, &values)?;
        Ok(result)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Releases native history roots without affecting saved snapshots.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_clearHistory(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        lock_registry()?.get_mut(handle)?.clear_history();
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}
