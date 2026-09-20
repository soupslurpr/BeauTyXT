package dev.soupslurpr.beautyxt.illustration;

/** Small terminal receipts only. Drawing bytes use an app-owned anonymous descriptor. */
oneway interface IIllustrationCallback {
    void onResult(long jobId, int status, int packetBytes);
}
