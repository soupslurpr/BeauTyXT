package dev.soupslurpr.beautyxt.importing;

import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor;
import dev.soupslurpr.beautyxt.importing.IImportCallback;

/** Controls one descriptor-based import job at a time. */
interface IImportService {
    /**
     * Validates and copies into an anonymous, seekable output. Descriptor transfer
     * includes ownership of each data handle and any reliable error channel.
     */
    int startImport(
        long jobId,
        in TransferredFileDescriptor input,
        in TransferredFileDescriptor output,
        long maxInputBytes,
        long maxOutputBytes,
        long timeoutMillis,
        IImportCallback callback
    );

    oneway void cancelImport(long jobId);
}
