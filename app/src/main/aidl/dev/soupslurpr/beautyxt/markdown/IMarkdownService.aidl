package dev.soupslurpr.beautyxt.markdown;

import dev.soupslurpr.beautyxt.markdown.IMarkdownCallback;
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor;

/** Controls one descriptor-based Markdown render job at a time. */
interface IMarkdownService {
    /** Parses one exact UTF-8 snapshot into an anonymous render packet. */
    int startRender(
        long jobId,
        in TransferredFileDescriptor input,
        in TransferredFileDescriptor output,
        long expectedInputBytes,
        long maxPacketBytes,
        long timeoutMillis,
        IMarkdownCallback callback
    );

    oneway void cancelRender(long jobId);
}
