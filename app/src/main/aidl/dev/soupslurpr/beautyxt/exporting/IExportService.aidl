package dev.soupslurpr.beautyxt.exporting;

import dev.soupslurpr.beautyxt.exporting.IExportCallback;
import dev.soupslurpr.beautyxt.exporting.ISourceSaveCallback;
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor;

/** Controls one descriptor-based export job at a time. */
interface IExportService {
    /** Streams one exact snapshot into a user-authorized destination. */
    int startExport(
        long jobId,
        in TransferredFileDescriptor input,
        in TransferredFileDescriptor output,
        long expectedBytes,
        long timeoutMillis,
        IExportCallback callback
    );

    /** Replaces one source only when its exact expected version still matches. */
    int startConditionalSourceSave(
        long jobId,
        in TransferredFileDescriptor packageInput,
        in TransferredFileDescriptor sourceBacking,
        in TransferredFileDescriptor source,
        long expectedBytes,
        long expectedSourceBytes,
        in byte[] expectedSourceSha256,
        long timeoutMillis,
        ISourceSaveCallback callback
    );

    /** Verifies one source against an exact expected version without mutation. */
    int startSourceVerification(
        long jobId,
        in TransferredFileDescriptor source,
        long expectedSourceBytes,
        in byte[] expectedSourceSha256,
        long timeoutMillis,
        ISourceSaveCallback callback
    );

    /** Returns one source's exact bounded version without mutation. */
    int startSourceInspection(
        long jobId,
        in TransferredFileDescriptor source,
        long maximumSourceBytes,
        long timeoutMillis,
        ISourceSaveCallback callback
    );

    oneway void cancelExport(long jobId);
}
