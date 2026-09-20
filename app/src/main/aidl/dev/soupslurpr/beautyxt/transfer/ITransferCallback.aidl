package dev.soupslurpr.beautyxt.transfer;

/** Receives bounded status and metrics for one local transfer job. */
oneway interface ITransferCallback {
    void onTransferStatus(
        long jobId,
        int state,
        int resultCode,
        long inputBytes,
        long outputBytes,
        long detailZero,
        long detailOne
    );
}
