package dev.soupslurpr.beautyxt.importing;

import android.os.ParcelFileDescriptor;
import dev.soupslurpr.beautyxt.importing.IImportCallback;

/** Controls one descriptor-based import job at a time. */
interface IImportService {
    /** Validates and copies input bytes into one anonymous, seekable output descriptor. */
    int startImport(
        long jobId,
        in ParcelFileDescriptor input,
        in ParcelFileDescriptor output,
        long maxInputBytes,
        long maxOutputBytes,
        long timeoutMillis,
        IImportCallback callback
    );

    oneway void cancelImport(long jobId);
}
