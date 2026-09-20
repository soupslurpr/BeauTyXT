package dev.soupslurpr.beautyxt.illustration;

import dev.soupslurpr.beautyxt.illustration.IIllustrationCallback;
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor;

/** One serial worker connection. Cancellation terminates its private isolated process. */
interface IIllustrationService {
    oneway void render(long jobId, in byte[] source, boolean display,
        in TransferredFileDescriptor output, IIllustrationCallback callback);
    oneway void cancel(long jobId);
}
