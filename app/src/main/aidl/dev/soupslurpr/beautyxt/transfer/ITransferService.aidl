package dev.soupslurpr.beautyxt.transfer;

import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor;
import dev.soupslurpr.beautyxt.transfer.ITransferCallback;

/** Controls one descriptor-based local transfer job at a time. */
interface ITransferService {
    /** Runs one bounded QR or NFC transformation. */
    int startTransfer(
        long jobId,
        int operation,
        in TransferredFileDescriptor input,
        in TransferredFileDescriptor output,
        long expectedInputBytes,
        long argumentZero,
        long argumentOne,
        long timeoutMillis,
        ITransferCallback callback
    );

    oneway void cancelTransfer(long jobId);
}
