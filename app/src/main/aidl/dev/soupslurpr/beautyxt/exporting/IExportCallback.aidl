package dev.soupslurpr.beautyxt.exporting;

/** Receives bounded numeric status for one export job. */
oneway interface IExportCallback {
    void onExportStatus(
        long jobId,
        int state,
        int resultCode,
        long inputBytes,
        long outputBytes
    );
}
