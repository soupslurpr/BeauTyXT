//! Finds bounded GitHub-style extended autolinks in ordinary Markdown text.

use std::borrow::Cow;

const HTTP_PREFIX: &[u8] = b"http://";
const HTTPS_PREFIX: &[u8] = b"https://";
const WWW_PREFIX: &[u8] = b"www.";
const MAILTO_PREFIX: &[u8] = b"mailto:";
const XMPP_PREFIX: &[u8] = b"xmpp:";
const HTTP_INSERTION: &str = "http://";
const MAILTO_INSERTION: &str = "mailto:";

/// Describes one extended autolink and its explicit destination.
#[derive(Debug, Eq, PartialEq)]
pub(super) struct ExtendedAutolink<'a> {
    pub(super) start: usize,
    pub(super) end: usize,
    pub(super) destination: Cow<'a, str>,
}

/// Returns the first extended autolink at or after one UTF-8 byte offset.
pub(super) fn find_extended_autolink(
    text: &str,
    search_start: usize,
    max_destination_bytes: usize,
) -> Option<ExtendedAutolink<'_>> {
    if search_start >= text.len() {
        return None;
    }
    let bytes = text.as_bytes();
    // Skip rejected URL interiors without suppressing independent email detection.
    let mut url_retry_from = search_start;
    for start in search_start..bytes.len() {
        if !bytes[start].is_ascii() {
            continue;
        }
        if is_url_boundary(bytes, start) {
            if start >= url_retry_from {
                match parse_url(text, start, max_destination_bytes) {
                    Ok(link) => return Some(link),
                    Err(retry_from) => url_retry_from = retry_from,
                }
            }
            if let Some(link) = parse_protocol_email(text, start, max_destination_bytes) {
                return Some(link);
            }
        }
        if is_email_local(bytes[start])
            && (start == 0 || !is_email_local(bytes[start - 1]))
            && let Some(link) = parse_email(text, start, max_destination_bytes)
        {
            return Some(link);
        }
    }
    None
}

/// Parses a URL or returns the next offset that can begin a complete URL.
fn parse_url(
    text: &str,
    start: usize,
    max_destination_bytes: usize,
) -> Result<ExtendedAutolink<'_>, usize> {
    let bytes = text.as_bytes();
    let (domain_start, inserts_http) = if starts_ascii_case_insensitive(bytes, start, HTTPS_PREFIX)
    {
        (start + HTTPS_PREFIX.len(), false)
    } else if starts_ascii_case_insensitive(bytes, start, HTTP_PREFIX) {
        (start + HTTP_PREFIX.len(), false)
    } else if starts_ascii_case_insensitive(bytes, start, WWW_PREFIX) {
        (start + WWW_PREFIX.len(), true)
    } else {
        return Err(start + 1);
    };
    let domain_end = valid_url_domain_end(bytes, domain_start)?;
    let end = extended_url_end(text, start, domain_end);
    let inserted_bytes = usize::from(inserts_http) * HTTP_INSERTION.len();
    let max_visible_bytes = max_destination_bytes
        .checked_sub(inserted_bytes)
        .ok_or(end)?;
    if end - start > max_visible_bytes {
        return Err(end);
    }
    let visible = &text[start..end];
    let destination = if inserts_http {
        let mut destination = String::with_capacity(HTTP_INSERTION.len() + visible.len());
        destination.push_str(HTTP_INSERTION);
        destination.push_str(visible);
        Cow::Owned(destination)
    } else {
        Cow::Borrowed(visible)
    };
    Ok(ExtendedAutolink {
        start,
        end,
        destination,
    })
}

/// Parses a bare email address at one local-part boundary.
fn parse_email(
    text: &str,
    start: usize,
    max_destination_bytes: usize,
) -> Option<ExtendedAutolink<'_>> {
    let bytes = text.as_bytes();
    let mut at = start;
    while bytes.get(at).is_some_and(|byte| is_email_local(*byte)) {
        at += 1;
    }
    if bytes.get(at) != Some(&b'@') {
        return None;
    }
    let domain_start = at.checked_add(1)?;
    let end = valid_email_domain_end(bytes, domain_start)?;
    let visible = &text[start..end];
    if MAILTO_INSERTION.len().checked_add(visible.len())? > max_destination_bytes {
        return None;
    }
    let mut destination = String::with_capacity(MAILTO_INSERTION.len() + visible.len());
    destination.push_str(MAILTO_INSERTION);
    destination.push_str(visible);
    Some(ExtendedAutolink {
        start,
        end,
        destination: Cow::Owned(destination),
    })
}

/// Parses explicit mailto and XMPP extended email forms.
fn parse_protocol_email(
    text: &str,
    start: usize,
    max_destination_bytes: usize,
) -> Option<ExtendedAutolink<'_>> {
    let bytes = text.as_bytes();
    let (address_start, is_xmpp) = if starts_ascii_case_insensitive(bytes, start, MAILTO_PREFIX) {
        (start.checked_add(MAILTO_PREFIX.len())?, false)
    } else if starts_ascii_case_insensitive(bytes, start, XMPP_PREFIX) {
        (start.checked_add(XMPP_PREFIX.len())?, true)
    } else {
        return None;
    };
    let local_end = bytes[address_start..]
        .iter()
        .position(|byte| !is_email_local(*byte))
        .map_or(bytes.len(), |offset| address_start + offset);
    if bytes.get(local_end) != Some(&b'@') {
        return None;
    }
    let domain_start = local_end.checked_add(1)?;
    let mut end = valid_email_domain_end(bytes, domain_start)?;
    if is_xmpp && bytes.get(end) == Some(&b'/') {
        let resource_start = end.checked_add(1)?;
        let resource_length = bytes[resource_start..]
            .iter()
            .take_while(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'@' | b'.'))
            .count();
        if resource_length != 0 {
            end = resource_start.checked_add(resource_length)?;
        }
    }
    if end.checked_sub(start)? > max_destination_bytes {
        return None;
    }
    Some(ExtendedAutolink {
        start,
        end,
        destination: Cow::Borrowed(&text[start..end]),
    })
}

/// Returns a domain end or the first offset beyond its invalid domain segments.
fn valid_url_domain_end(bytes: &[u8], start: usize) -> Result<usize, usize> {
    let mut index = start;
    let mut segment_length = 0_usize;
    let mut dot_count = 0_usize;
    let mut previous_segment_has_underscore = false;
    let mut current_segment_has_underscore = false;
    let mut last_valid = None;
    while let Some(byte) = bytes.get(index).copied() {
        match byte {
            b'.' if segment_length != 0 => {
                dot_count += 1;
                previous_segment_has_underscore = current_segment_has_underscore;
                current_segment_has_underscore = false;
                segment_length = 0;
            }
            byte if is_url_domain_character(byte) => {
                segment_length += 1;
                current_segment_has_underscore |= byte == b'_';
                if dot_count != 0
                    && !previous_segment_has_underscore
                    && !current_segment_has_underscore
                {
                    last_valid = Some(index + 1);
                }
            }
            _ => break,
        }
        index += 1;
    }
    let end = last_valid.ok_or(index)?;
    if bytes[end..index].iter().any(|byte| *byte != b'.') {
        return Err(index);
    }
    Ok(end)
}

/// Returns the end of one valid GFM extended email domain.
fn valid_email_domain_end(bytes: &[u8], start: usize) -> Option<usize> {
    let mut index = start;
    while bytes.get(index).is_some_and(|byte| is_email_domain(*byte)) {
        index += 1;
    }
    while index > start && bytes[index - 1] == b'.' {
        index -= 1;
    }
    let domain = bytes.get(start..index)?;
    if !domain.contains(&b'.')
        || domain.split(|byte| *byte == b'.').any(<[u8]>::is_empty)
        || matches!(bytes.get(index.wrapping_sub(1)), Some(b'-' | b'_'))
    {
        return None;
    }
    Some(index)
}

/// Applies GFM's trailing-path rules without truncating the destination.
fn extended_url_end(text: &str, start: usize, domain_end: usize) -> usize {
    let mut end = domain_end;
    for (offset, character) in text[domain_end..].char_indices() {
        if character == '<' || character.is_whitespace() {
            break;
        }
        end = domain_end + offset + character.len_utf8();
    }
    let bytes = text.as_bytes();
    while end > domain_end
        && matches!(
            bytes[end - 1],
            b'?' | b'!' | b'.' | b',' | b':' | b'*' | b'_' | b'~'
        )
    {
        end -= 1;
    }
    if bytes.get(end.wrapping_sub(1)) == Some(&b')') {
        let link = &bytes[start..end];
        let opening_count = count_byte(link, b'(');
        let mut closing_count = count_byte(link, b')');
        while closing_count > opening_count && bytes.get(end.wrapping_sub(1)) == Some(&b')') {
            end -= 1;
            closing_count -= 1;
        }
    }
    if bytes.get(end.wrapping_sub(1)) == Some(&b';')
        && let Some(ampersand) = bytes[start..end].iter().rposition(|byte| *byte == b'&')
    {
        let entity_start = start + ampersand + 1;
        if entity_start < end - 1
            && bytes[entity_start..end - 1]
                .iter()
                .all(u8::is_ascii_alphanumeric)
        {
            end = start + ampersand;
        }
    }
    end
}

/// Returns the number of exact byte occurrences in one bounded slice.
fn count_byte(bytes: &[u8], expected: u8) -> usize {
    bytes.iter().fold(0_usize, |count, byte| {
        count + usize::from(*byte == expected)
    })
}

/// Returns whether a URL-like extended autolink may begin at one offset.
fn is_url_boundary(bytes: &[u8], start: usize) -> bool {
    start == 0
        || bytes[start - 1].is_ascii_whitespace()
        || matches!(bytes[start - 1], b'*' | b'_' | b'~' | b'(')
}

/// Returns whether one byte is allowed in an extended email local part.
const fn is_email_local(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_' | b'+')
}

/// Returns whether one byte is allowed in an extended email domain.
const fn is_email_domain(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_')
}

/// Returns whether one byte is allowed in a URL domain.
const fn is_url_domain_character(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_')
}

/// Returns whether one ASCII prefix matches case-insensitively.
fn starts_ascii_case_insensitive(bytes: &[u8], start: usize, prefix: &[u8]) -> bool {
    bytes
        .get(start..start.saturating_add(prefix.len()))
        .is_some_and(|candidate| candidate.eq_ignore_ascii_case(prefix))
}

#[cfg(test)]
mod tests {
    //! Verifies bounded GFM extended-autolink recognition.

    use super::{ExtendedAutolink, find_extended_autolink};
    use std::borrow::Cow;

    const MAX_DESTINATION_BYTES: usize = 4 * 1024;

    #[test]
    fn recognizes_urls_and_trims_gfm_trailing_text() {
        assert_link(
            "Visit www.commonmark.org/help for more.",
            "www.commonmark.org/help",
            "http://www.commonmark.org/help",
        );
        assert_link(
            "(https://example.com/search?q=Markup+(business))",
            "https://example.com/search?q=Markup+(business)",
            "https://example.com/search?q=Markup+(business)",
        );
        assert_link(
            "www.example.com/search?x=1&copy;",
            "www.example.com/search?x=1",
            "http://www.example.com/search?x=1",
        );
    }

    /// Recognizes complete addresses without requiring a path after their domain.
    #[test]
    fn recognizes_bare_domains_and_surrounding_punctuation() {
        for address in [
            "http://example.org",
            "https://example.org",
            "www.example.org",
        ] {
            let destination = if address.starts_with("www.") {
                format!("http://{address}")
            } else {
                address.to_owned()
            };
            for text in [
                address.to_owned(),
                format!("Visit {address}."),
                format!("({address})"),
                format!("{address}<next"),
            ] {
                assert_link(&text, address, &destination);
            }
        }
    }

    /// Leaves overlong addresses inert instead of linking a shortened destination.
    #[test]
    fn never_truncates_a_url_to_fit_the_destination_limit() {
        for prefix in ["https://example.org/", "www.example.org/"] {
            let inserted_bytes = if prefix.starts_with("www.") {
                "http://".len()
            } else {
                0
            };
            let path_length = MAX_DESTINATION_BYTES - inserted_bytes - prefix.len();
            let address = format!("{prefix}{}", "a".repeat(path_length));
            let destination = if inserted_bytes == 0 {
                address.clone()
            } else {
                format!("http://{address}")
            };
            assert_link(&address, &address, &destination);
            assert_link(&format!("({address})."), &address, &destination);
            for suffix in ["b", "é", "🌻"] {
                assert!(
                    find_extended_autolink(&format!("{address}{suffix}"), 0, MAX_DESTINATION_BYTES)
                        .is_none()
                );
            }
        }
    }

    /// Applies the byte limit to complete domains as well as URL paths.
    #[test]
    fn rejects_domains_above_the_destination_limit() {
        let address = format!("https://{}.example/", "a".repeat(MAX_DESTINATION_BYTES));
        assert!(find_extended_autolink(&address, 0, MAX_DESTINATION_BYTES).is_none());
    }

    /// Skips repeated URL prefixes inside one rejected address without rescanning it.
    #[test]
    fn skips_rejected_domain_and_path_runs() {
        for rejected in [
            format!("www.{}invalid_domain", "_www.".repeat(32 * 1024)),
            format!(
                "https://example.org/{}tail",
                "_www.example.org/".repeat(32 * 1024)
            ),
        ] {
            assert_eq!(
                super::parse_url(&rejected, 0, MAX_DESTINATION_BYTES).unwrap_err(),
                rejected.len(),
            );
            assert!(find_extended_autolink(&rejected, 0, MAX_DESTINATION_BYTES).is_none());
            assert_link(
                &format!("{rejected} https://example.org"),
                "https://example.org",
                "https://example.org",
            );
        }
    }

    /// Retains independent links after invalid domains and email local parts within them.
    #[test]
    fn recognizes_links_around_rejected_domains() {
        assert_link(
            "http://invalid_(https://example.org)",
            "https://example.org",
            "https://example.org",
        );
        assert_link(
            "http://reader_name@example.org",
            "reader_name@example.org",
            "mailto:reader_name@example.org",
        );
    }

    #[test]
    fn recognizes_email_and_protocol_forms() {
        assert_link("Contact a.b-c_d@a.b.", "a.b-c_d@a.b", "mailto:a.b-c_d@a.b");
        assert_link(
            "mailto:hello+tag@mail.example.",
            "mailto:hello+tag@mail.example",
            "mailto:hello+tag@mail.example",
        );
        assert_link(
            "xmpp:hello@mail.example/device@home",
            "xmpp:hello@mail.example/device@home",
            "xmpp:hello@mail.example/device@home",
        );
    }

    #[test]
    fn rejects_invalid_domains_and_oversized_destinations() {
        assert!(find_extended_autolink("www.local", 0, MAX_DESTINATION_BYTES).is_none());
        assert!(find_extended_autolink("www.foo_bar.example", 0, MAX_DESTINATION_BYTES).is_none());
        assert!(find_extended_autolink("a@b", 0, MAX_DESTINATION_BYTES).is_none());
        assert!(find_extended_autolink("https://example.com", 0, 5).is_none());
    }

    fn assert_link(text: &str, visible: &str, destination: &str) {
        let ExtendedAutolink {
            start,
            end,
            destination: actual_destination,
        } = find_extended_autolink(text, 0, MAX_DESTINATION_BYTES).expect("expected autolink");
        assert_eq!(&text[start..end], visible);
        assert_eq!(actual_destination, Cow::Borrowed(destination));
    }
}
