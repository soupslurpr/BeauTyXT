package dev.soupslurpr.beautyxt.markdown;

/** Receives bounded status and render metrics for one Markdown job. */
oneway interface IMarkdownCallback {
    void onMarkdownStatus(
        long jobId,
        int state,
        int resultCode,
        long inputBytes,
        long packetBytes,
        long blockCount,
        long spanCount,
        long documentFlags
    );
}
