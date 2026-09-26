package dev.soupslurpr.beautyxt.testing

/** Identifies the separate-UID caller used by document entry-point instrumentation. */
object DocumentCallerContract {
    const val PACKAGE = "dev.soupslurpr.beautyxt.debug.test.providers"
    const val ACTIVITY = "dev.soupslurpr.beautyxt.testing.DocumentCallerActivity"
    const val ACTION_EXTRA = "dev.soupslurpr.beautyxt.test.DOCUMENT_ACTION"
    const val SOURCE_TEXT = "This source belongs to a different app, with temporary URI grants."
    const val SHARED_TEXT = "Text sent by a separate Android application."
}
