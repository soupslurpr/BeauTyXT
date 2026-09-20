# Product direction

BeauTyXT is a beautiful, private, secure, and efficient Android tool for quickly
reading, editing, and sharing plain text and Markdown. This document describes
its current purpose, experience, and boundaries.

## A document beyond its original app

Text can remain useful after it leaves the application that produced it. A
person might receive instructions, a reference document, or an assistant's
answer as plain text or Markdown. They should be able to read that shared
document independently, make changes if needed, and pass it on without needing
the original conversation or using the same assistant.

This is BeauTyXT's role alongside AI assistants. It does not depend on the
prediction that people will stop writing text. Ordinary files, notes, and
handwritten Markdown remain equally important. Assistant-generated content is
another source of documents, not a separate format or a reason to trust their
contents more.

Reading is a complete use case, not merely a step before editing. Editing
remains a first-class capability rather than becoming a prerequisite for using
the app.

## What this means for the experience

- Make opened or received content useful quickly. Recognized incoming Markdown
  begins in rendered reading; received plain text does not summon the keyboard.
  New documents still start ready for writing.
- Make long documents comfortable to read and navigate. Contents, links,
  footnotes, source search, and code copying serve the document rather than
  surrounding it with another conversation interface.
- Keep rendered reading and honest source editing distinct, connected by
  document positions. Preserve the user's place when moving between them.
- Support deliberate sharing and export through Android, PDF, QR, and NFC,
  with clear capability and size limits. Receiving content does not require
  creating a saved file before reading it.
- Keep existing files in user-selected locations. Source autosave is available
  when the storage provider supports verified writes. New and received text
  stays transient until explicitly saved.

## Boundaries

This direction does not call for an embedded assistant, model service, account,
cloud synchronization, or an app-private document library. The app itself has
no Internet permission. It complements the tools that produce text without
depending on a particular assistant or provider.

Received content remains untrusted input regardless of who or what produced
it. Rendering does not establish factual accuracy or authenticity, and code
copying never executes the copied text. The bounded native renderer, isolated
workers, and storage rules in the [architecture](architecture.md) remain
authoritative.

## Languages

English is currently the only supported application language. User-facing
strings should use Android string and plural resources so that maintainers can
add other languages later without restructuring the interface.

Translation contributions are not currently accepted or solicited. Any future
translations will be maintainer-managed and must be verifiable for meaning,
terminology, accessibility, and behavior in the actual interface. Preparing
resources does not imply a commitment to new languages or a translation service.
