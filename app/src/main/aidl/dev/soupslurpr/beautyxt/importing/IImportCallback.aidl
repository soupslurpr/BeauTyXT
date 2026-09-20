package dev.soupslurpr.beautyxt.importing;

/** Receives bounded status and an exact source digest for one import job. */
oneway interface IImportCallback {
    void onImportStatus(
        long jobId,
        int state,
        int resultCode,
        long inputBytes,
        long outputBytes,
        int sourceFlags,
        in byte[] sourceSha256
    );
}
