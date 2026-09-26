//! Bounded search handles and atomic replacement packets.

use std::sync::Arc;

use beautyxt_editor_core::{
    DocumentEdit, MAX_BATCH_EDITS,
    search::{SearchCompletion, SearchCursor, SearchOptions, SearchPage, SearchPattern},
};

use crate::{
    BTreeMap, BridgeError, DocumentError, EnvUnowned, JByteArray, JClass, JLongArray, JString,
    LazyLock, Mutex, ThrowRuntimeExAndDefault, Utf16Range, encode_document_metrics, jboolean,
    jlong, lock_registry, nonnegative_u64, nonnegative_usize,
};

const MAX_PACKET_BYTES: usize = 2 * 1024 * 1024;
type SearchRegistry = (i64, BTreeMap<i64, Arc<SearchPattern>>);
static SEARCHES: LazyLock<Mutex<SearchRegistry>> =
    LazyLock::new(|| Mutex::new((1, BTreeMap::new())));

fn search(handle: i64) -> Result<Arc<SearchPattern>, BridgeError> {
    SEARCHES
        .lock()
        .map_err(|_| BridgeError::InvalidArgument("search registry"))?
        .1
        .get(&handle)
        .cloned()
        .ok_or(BridgeError::InvalidArgument("closed search"))
}

/// Compiles one cancellable pattern shared by source and displayed-text searches.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_compileSearch(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    query: JString<'_>,
    regex: jboolean,
    match_case: jboolean,
    whole_word: jboolean,
) -> jlong {
    env.with_env(|_| -> Result<jlong, BridgeError> {
        let pattern = SearchPattern::new(
            &query.to_string(),
            SearchOptions {
                regex,
                match_case,
                whole_word,
            },
        )?;
        let mut registry = SEARCHES
            .lock()
            .map_err(|_| BridgeError::InvalidArgument("search registry"))?;
        if registry.1.len() >= 32 {
            return Err(BridgeError::InvalidArgument("too many searches"));
        }
        let handle = registry.0;
        registry.0 = handle
            .checked_add(1)
            .ok_or(BridgeError::InvalidArgument("search handle exhausted"))?;
        registry.1.insert(handle, Arc::new(pattern));
        Ok(handle)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Cancels ongoing work and releases a pattern without waiting for document locks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_closeSearch(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), BridgeError> {
        if let Some(pattern) = SEARCHES
            .lock()
            .map_err(|_| BridgeError::InvalidArgument("search registry"))?
            .1
            .remove(&handle)
        {
            pattern.cancel();
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

/// Searches a captured source revision and returns a bounded review packet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_searchSource<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    pattern: jlong,
    start: jlong,
    end: jlong,
    cursor: jlong,
    skip_empty: jboolean,
    replace: jboolean,
    replacement: JString<'caller>,
) -> JByteArray<'caller> {
    env.with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
        let revision = nonnegative_u64(revision, "search revision")?;
        let snapshot = lock_registry()?.capture_snapshot(handle, revision)?;
        let pattern = search(pattern)?;
        let replacement = replacement.to_string();
        let page = snapshot.search(
            &pattern,
            range(start, end)?,
            SearchCursor {
                start: nonnegative_usize(cursor, "search cursor")?,
                skip_empty,
            },
            replace.then_some(replacement.as_str()),
        )?;
        Ok(env.byte_array_from_slice(&encode_page(&page, revision, replace)?)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Searches one rendered semantic text unit using exactly the source matcher.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_searchText<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    pattern: jlong,
    text: JString<'caller>,
    start: jlong,
    end: jlong,
    cursor: jlong,
    skip_empty: jboolean,
) -> JByteArray<'caller> {
    env.with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
        let pattern = search(pattern)?;
        let text = text.to_string();
        let page = pattern.search(
            &text,
            range(start, end)?,
            SearchCursor {
                start: nonnegative_usize(cursor, "search cursor")?,
                skip_empty,
            },
            None,
        )?;
        Ok(env.byte_array_from_slice(&encode_page(&page, 0, false)?)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Resolves a logical offset without creating an editable selection.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_positionAt<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    offset: jlong,
) -> JLongArray<'caller> {
    env.with_env(|env| -> Result<JLongArray<'caller>, BridgeError> {
        let snapshot =
            lock_registry()?.capture_snapshot(handle, nonnegative_u64(revision, "revision")?)?;
        let position = snapshot.position_at(nonnegative_usize(offset, "position")?)?;
        let values = [
            jlong::try_from(position.line).map_err(|_| BridgeError::InvalidArgument("line"))?,
            jlong::try_from(position.utf16_offset)
                .map_err(|_| BridgeError::InvalidArgument("offset"))?,
        ];
        let result = env.new_long_array(2)?;
        result.set_region(env, 0, &values)?;
        Ok(result)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Returns a bounded exact range for selection output and history validation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_readRange<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    start: jlong,
    end: jlong,
) -> JString<'caller> {
    env.with_env(|env| -> Result<JString<'caller>, BridgeError> {
        let snapshot =
            lock_registry()?.capture_snapshot(handle, nonnegative_u64(revision, "revision")?)?;
        let text = snapshot.read_range(range(start, end)?)?;
        Ok(env.new_string(text)?)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Validates a complete batch packet and publishes its edits exactly once.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_soupslurpr_beautyxt_document_NativeDocument_replaceBatch<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    revision: jlong,
    packet: JByteArray<'caller>,
) -> JByteArray<'caller> {
    env.with_env(|env| -> Result<JByteArray<'caller>, BridgeError> {
        if packet.len(env)? > MAX_PACKET_BYTES {
            return Err(BridgeError::InvalidArgument("batch packet limit"));
        }
        let packet = env.convert_byte_array(&packet)?;
        let edits = decode_batch(&packet)?;
        let metrics = lock_registry()?
            .get_mut(handle)?
            .replace_batch(nonnegative_u64(revision, "batch revision")?, &edits);
        let metrics = match metrics {
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

fn range(start: jlong, end: jlong) -> Result<Utf16Range, BridgeError> {
    Ok(Utf16Range::new(
        nonnegative_usize(start, "range start")?,
        nonnegative_usize(end, "range end")?,
    ))
}

fn encode_page(
    page: &SearchPage,
    revision: u64,
    replacements: bool,
) -> Result<Vec<u8>, BridgeError> {
    let mut packet = b"BESR\x01\x00".to_vec();
    packet.push(match page.completion {
        SearchCompletion::Complete => 0,
        SearchCompletion::PageLimit => 1,
        SearchCompletion::WorkLimit => 2,
        SearchCompletion::ContextLimit => 3,
        SearchCompletion::Cancelled => 4,
    });
    packet.push(u8::from(page.next.skip_empty) | (u8::from(replacements) << 1));
    packet.extend_from_slice(&revision.to_le_bytes());
    packet.extend_from_slice(
        &u64::try_from(page.next.start)
            .map_err(|_| BridgeError::InvalidArgument("search cursor"))?
            .to_le_bytes(),
    );
    packet.extend_from_slice(
        &u32::try_from(page.hits.len())
            .map_err(|_| BridgeError::InvalidArgument("search hit count"))?
            .to_le_bytes(),
    );
    for hit in &page.hits {
        for offset in [hit.range.start, hit.range.end] {
            packet.extend_from_slice(
                &u64::try_from(offset)
                    .map_err(|_| BridgeError::InvalidArgument("search range"))?
                    .to_le_bytes(),
            );
        }
        for value in [
            &hit.text,
            hit.replacement.as_deref().unwrap_or(""),
            &hit.before,
            &hit.after,
        ] {
            packet.extend_from_slice(
                &u32::try_from(value.len())
                    .map_err(|_| BridgeError::InvalidArgument("search text"))?
                    .to_le_bytes(),
            );
            packet.extend_from_slice(value.as_bytes());
        }
    }
    if packet.len() > MAX_PACKET_BYTES {
        return Err(BridgeError::InvalidArgument("search packet limit"));
    }
    Ok(packet)
}

fn decode_batch(mut packet: &[u8]) -> Result<Vec<DocumentEdit>, BridgeError> {
    if packet.len() > MAX_PACKET_BYTES || take::<8>(&mut packet)? != *b"BEBT\x01\x00\x00\x00" {
        return Err(BridgeError::InvalidArgument("batch header"));
    }
    let count = u32::from_le_bytes(take(&mut packet)?) as usize;
    if count > MAX_BATCH_EDITS {
        return Err(BridgeError::InvalidArgument("batch edit count"));
    }
    let mut edits = Vec::with_capacity(count);
    for _ in 0..count {
        let start = usize::try_from(u64::from_le_bytes(take(&mut packet)?))
            .map_err(|_| BridgeError::InvalidArgument("batch start"))?;
        let end = usize::try_from(u64::from_le_bytes(take(&mut packet)?))
            .map_err(|_| BridgeError::InvalidArgument("batch end"))?;
        edits.push(DocumentEdit {
            range: Utf16Range::new(start, end),
            expected: take_text(&mut packet)?,
            replacement: take_text(&mut packet)?,
        });
    }
    if !packet.is_empty() {
        return Err(BridgeError::InvalidArgument("batch trailing bytes"));
    }
    Ok(edits)
}

fn take<const N: usize>(packet: &mut &[u8]) -> Result<[u8; N], BridgeError> {
    let bytes = packet
        .get(..N)
        .ok_or(BridgeError::InvalidArgument("truncated batch"))?;
    let value = bytes
        .try_into()
        .map_err(|_| BridgeError::InvalidArgument("batch field"))?;
    *packet = &packet[N..];
    Ok(value)
}

fn take_text(packet: &mut &[u8]) -> Result<String, BridgeError> {
    let length = u32::from_le_bytes(take(packet)?) as usize;
    let bytes = packet
        .get(..length)
        .ok_or(BridgeError::InvalidArgument("batch text length"))?;
    let text = std::str::from_utf8(bytes)
        .map_err(|_| BridgeError::InvalidArgument("batch UTF-8"))?
        .to_owned();
    *packet = &packet[length..];
    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::decode_batch;

    fn packet() -> Vec<u8> {
        let mut bytes = b"BEBT\x01\x00\x00\x00".to_vec();
        bytes.extend_from_slice(&1_u32.to_le_bytes());
        bytes.extend_from_slice(&2_u64.to_le_bytes());
        bytes.extend_from_slice(&4_u64.to_le_bytes());
        bytes.extend_from_slice(&4_u32.to_le_bytes());
        bytes.extend_from_slice("😀".as_bytes());
        bytes.extend_from_slice(&0_u32.to_le_bytes());
        bytes
    }

    #[test]
    fn preserves_unicode_expected_text_and_empty_deletion() {
        let edits = decode_batch(&packet()).unwrap();
        assert_eq!(edits.len(), 1);
        assert_eq!(edits[0].range.start, 2);
        assert_eq!(edits[0].range.end, 4);
        assert_eq!(edits[0].expected, "😀");
        assert!(edits[0].replacement.is_empty());
    }

    #[test]
    fn rejects_every_truncation_invalid_header_count_and_utf8() {
        let valid = packet();
        for length in 0..valid.len() {
            assert!(decode_batch(&valid[..length]).is_err());
        }
        let mut trailing = valid.clone();
        trailing.push(0);
        assert!(decode_batch(&trailing).is_err());
        for (offset, value) in [(0, 0), (4, 2), (7, 1), (14, 255), (32, 255)] {
            let mut malformed = valid.clone();
            malformed[offset] = value;
            if offset == 14 {
                // Coordinate validity is checked against the source snapshot by the engine.
                assert!(decode_batch(&malformed).is_ok());
            } else {
                assert!(decode_batch(&malformed).is_err());
            }
        }
        let mut excessive = valid;
        excessive[8..12].copy_from_slice(&4097_u32.to_le_bytes());
        assert!(decode_batch(&excessive).is_err());
    }
}
