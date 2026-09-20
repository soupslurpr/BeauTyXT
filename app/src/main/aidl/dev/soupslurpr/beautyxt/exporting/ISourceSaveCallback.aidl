package dev.soupslurpr.beautyxt.exporting;

/** Receives bounded status for one conditional source operation. */
oneway interface ISourceSaveCallback {
    void onSourceSaveStatus(
        long jobId,
        int state,
        int resultCode,
        long inputBytes,
        long outputBytes,
        boolean outputStarted,
        in byte[] sourceSha256
    );
}
