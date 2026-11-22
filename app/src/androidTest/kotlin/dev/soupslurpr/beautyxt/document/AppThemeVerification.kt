/* Exercises app-wide True black theming, dialog accessibility, rendering, and storage recovery. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import dev.soupslurpr.beautyxt.BeauTyXTApplication
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.MainActivity
import dev.soupslurpr.beautyxt.testing.DocumentCallerContract
import dev.soupslurpr.beautyxt.ui.designsystem.APP_THEME_FILE_NAME
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeDialog
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemePreferences
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeStore
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeStoreState
import dev.soupslurpr.beautyxt.ui.designsystem.AtomicAppThemeStorage
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers

/** Verifies real Home and external document activities across app-scoped dark and light modes. */
internal fun Instrumentation.verifyAppTheme() {
    val app = targetContext.applicationContext as BeauTyXTApplication
    val store = app.appTheme
    val uiModeManager = checkNotNull(targetContext.getSystemService(UiModeManager::class.java))
    awaitReadingCondition("app theme store did not load") {
        store.state.value is AppThemeStoreState.Ready
    }
    val original = (store.state.value as AppThemeStoreState.Ready).preferences
    val themeFile = File(targetContext.noBackupFilesDir, APP_THEME_FILE_NAME)
    val currentHome = AtomicReference<HomeActivity?>()
    val currentExternal = AtomicReference<MainActivity?>()
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) currentExternal.set(activity)
        }
        override fun onActivityResumed(activity: Activity) {
            if (activity is HomeActivity) currentHome.set(activity)
            if (activity is MainActivity) currentExternal.set(activity)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentHome.get() === activity) currentHome.set(null)
            if (currentExternal.get() === activity) currentExternal.set(null)
        }
    }
    var externalMonitor: Instrumentation.ActivityMonitor? = null
    var externalCallerPending = false
    var primaryFailure: Throwable? = null
    try {
        runOnMainSync {
            app.registerActivityLifecycleCallbacks(lifecycle)
            store.setTrueBlack(false)
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        }
        awaitReadingCondition("theme file did not clear before test") {
            store.state.value == AppThemeStoreState.Ready(AppThemePreferences(false)) &&
                !themeFile.exists()
        }

        val initialHome = startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as HomeActivity
        currentHome.set(initialHome)
        awaitReadingCondition("Home did not enter app-scoped dark mode") {
            val host = currentHome.get() ?: return@awaitReadingCondition false
            val expectedDarkSurface = dynamicDarkColorScheme(host).surface.toArgb()
            host.isNightMode() && host.windowSurfaceColor() == expectedDarkSurface
        }
        val darkHost = checkNotNull(currentHome.get())
        val expectedDarkSurface = dynamicDarkColorScheme(darkHost).surface.toArgb()
        val expectedDarkCard = dynamicDarkColorScheme(darkHost).surfaceContainer.toArgb()
        check(expectedDarkSurface != Color.BLACK) { "standard dark surface unexpectedly black" }
        verifyHomePixels(
            expectedCanvas = expectedDarkSurface,
            expectedReceiveCard = expectedDarkCard,
            label = "standard dark Home"
        )

        // Toggle True black on from Home and verify single accessible switch, canonical file,
        // Back dismissal, real HomeActivity exact black canvas + dynamic dark Receive card.
        requireActionableText("App theme").performRequiredClick()
        val homeSwitch = waitForAccessibilityNode("True black switch on Home") { node ->
            node.isCheckable && node.isClickable && node.hasDescendantOrSelfText("True black")
        }
        check(homeSwitch.checked == AccessibilityNodeInfo.CHECKED_STATE_FALSE) {
            "True black switch started checked"
        }
        homeSwitch.performRequiredClick()
        awaitReadingCondition("enabling True black did not write canonical file") {
            store.state.value == AppThemeStoreState.Ready(AppThemePreferences(true)) &&
                themeFile.isFile &&
                themeFile.readText() == "true_black=true\n"
        }
        val checkedSwitch = waitForAccessibilityNode("checked True black switch") { node ->
            node.isCheckable &&
                node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE &&
                node.hasDescendantOrSelfText("True black")
        }
        check(checkedSwitch.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE) {
            "True black dialog did not stay open with checked switch"
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitReadingCondition("Back did not dismiss App theme dialog on Home") {
            uiAutomation.rootInActiveWindow?.findNode { it.text?.toString() == "True black" } == null
        }
        awaitReadingCondition("Home window did not switch to pure black") {
            currentHome.get()?.windowSurfaceColor() == Color.BLACK
        }
        verifyHomePixels(
            expectedCanvas = Color.BLACK,
            expectedReceiveCard = expectedDarkCard,
            label = "True black Home"
        )

        // Launch a real external MainActivity document task, verify True black, and close both
        // MainActivity and its helper caller so the synthetic Downloads fixture is removed.
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        externalMonitor = monitor
        externalCallerPending = true
        targetContext.startActivity(
            Intent()
                .setClassName(DocumentCallerContract.PACKAGE, DocumentCallerContract.ACTIVITY)
                .putExtra(DocumentCallerContract.ACTION_EXTRA, Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val external = checkNotNull(waitForMonitorWithTimeout(monitor, 15_000L)) {
            "external VIEW did not reach MainActivity"
        } as MainActivity
        currentExternal.set(external)
        waitForAccessibilityNode("external document content") {
            it.text?.toString() == DocumentCallerContract.SOURCE_TEXT
        }
        awaitReadingCondition("external MainActivity did not apply True black") {
            external.isNightMode() && external.windowSurfaceColor() == Color.BLACK
        }
        verifyExternalDocumentPixels(
            expectedCanvas = Color.BLACK,
            expectedTopBar = Color.BLACK,
            label = "True black external document"
        )
        requireActionableContentDescription("Back").performRequiredClick()
        waitForAccessibilityNode("external caller after closing True black document") {
            it.packageName?.toString() == DocumentCallerContract.PACKAGE
        }
        awaitReadingCondition("external MainActivity did not destroy after Back") {
            external.isDestroyed
        }
        closeExternalCallerIfActive(waitForCallerWindow = true)
        externalCallerPending = false
        removeMonitor(monitor)
        externalMonitor = null

        // Switch BeauTyXT to light mode while True black remains saved; Home must stay light.
        runOnMainSync { uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO) }
        startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        awaitReadingCondition("Home did not apply dynamic light theme while True black is saved") {
            val host = currentHome.get() ?: return@awaitReadingCondition false
            val expectedLightSurface = dynamicLightColorScheme(host).surface.toArgb()
            !host.isNightMode() && host.windowSurfaceColor() == expectedLightSurface
        }
        val lightHost = checkNotNull(currentHome.get())
        val expectedLightSurface = dynamicLightColorScheme(lightHost).surface.toArgb()
        val expectedLightCard = dynamicLightColorScheme(lightHost).surfaceContainer.toArgb()
        check(Color.luminance(expectedLightSurface) > 0.5f) { "dynamic light surface was not light" }
        verifyHomePixels(
            expectedCanvas = expectedLightSurface,
            expectedReceiveCard = expectedLightCard,
            label = "light mode Home with True black enabled"
        )

        // Open a document from Home, enter a draft, toggle True black off from editor overflow,
        // and verify deletion + preserved draft.
        requireActionableText("New document").performRequiredClick()
        val field = waitForEditField()
        val draftText = "Draft survives theme switch"
        check(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        draftText
                    )
                }
            )
        )
        waitForAccessibilityNode("draft text in editor") {
            it.isEditable && it.text?.toString() == draftText
        }
        requireActionableContentDescription("More options").performRequiredClick()
        requireActionableText("App theme").performRequiredClick()
        val editorSwitch = waitForAccessibilityNode("True black switch in editor") { node ->
            node.isCheckable && node.isClickable && node.hasDescendantOrSelfText("True black")
        }
        check(editorSwitch.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE) {
            "editor App theme switch did not reflect saved True black in light mode"
        }
        editorSwitch.performRequiredClick()
        awaitReadingCondition("disabling True black did not delete theme file") {
            store.state.value == AppThemeStoreState.Ready(AppThemePreferences(false)) &&
                !themeFile.exists() &&
                !File(themeFile.path + ".bak").exists() &&
                !File(themeFile.path + ".new").exists()
        }
        requireActionableText("Close").performRequiredClick()
        waitForAccessibilityNode("draft preserved after closing App theme") {
            it.isEditable && it.text?.toString() == draftText
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        runFixtureCleanup(
            primaryFailure,
            {
                externalMonitor?.let(::removeMonitor)
                if (externalCallerPending) {
                    closeExternalFixture(currentExternal)
                }
            },
            {
                runOnMainSync {
                    app.unregisterActivityLifecycleCallbacks(lifecycle)
                    uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                    store.setTrueBlack(original.trueBlack)
                }
                awaitReadingCondition("original app theme was not restored") {
                    val state = store.state.value
                    state == AppThemeStoreState.Ready(original) &&
                        if (original.trueBlack) {
                            themeFile.isFile && themeFile.readText() == "true_black=true\n"
                        } else {
                            !themeFile.exists()
                        }
                }
            },
            {
                currentHome.get()?.let { host ->
                    runOnMainSync(host::finishAndRemoveTask)
                    awaitReadingCondition("app theme fixture activity did not close") {
                        host.isDestroyed
                    }
                }
            }
        )
    }
}

/** Verifies real AtomicFile recovery and reset UI on a fixture-owned app-theme file. */
internal fun Instrumentation.verifyAppThemeStorageRecovery() {
    val directory = Files.createTempDirectory(targetContext.cacheDir.toPath(), "theme-store").toFile()
    val file = directory.resolve(APP_THEME_FILE_NAME)
    val backup = directory.resolve("$APP_THEME_FILE_NAME.bak")
    val corrupt = "true_black=maybe\n"
    val saved = "true_black=true\n"
    var activity: HomeActivity? = null
    var primaryFailure: Throwable? = null
    try {
        val host = startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as HomeActivity
        activity = host
        fun showStore(): AppThemeStore {
            val store = AppThemeStore(AtomicAppThemeStorage { file }, Dispatchers.IO)
            runOnMainSync {
                host.setContent {
                    val state by store.state.collectAsState()
                    BeauTyXTTheme {
                        AppThemeDialog(store = store, state = state, onDismiss = {})
                    }
                }
            }
            return store
        }
        file.writeText(corrupt)
        val retry = showStore()
        requireActionableText("Retry loading")
        check(
            uiAutomation.rootInActiveWindow?.findNode { it.hasDescendantOrSelfText("True black") } ==
                null
        )
        requireActionableText("Reset saved theme…").performRequiredClick()
        requireActionableText("Cancel").performRequiredClick()
        check(file.readText() == corrupt && retry.state.value == AppThemeStoreState.LoadFailed)

        backup.writeText(saved)
        requireActionableText("Retry loading").performRequiredClick()
        awaitReadingCondition("retry did not recover True black from backup") {
            retry.state.value == AppThemeStoreState.Ready(AppThemePreferences(true))
        }
        check(file.readText() == saved && !backup.exists())

        file.writeText(corrupt)
        val reset = showStore()
        requireActionableText("Reset saved theme…").performRequiredClick()
        check(file.readText() == corrupt)
        requireActionableText("Reset").performRequiredClick()
        awaitReadingCondition("confirmed reset did not remove corrupt theme file") {
            reset.state.value == AppThemeStoreState.Ready(AppThemePreferences(false)) &&
                !file.exists() &&
                !backup.exists()
        }
        waitForAccessibilityNode("True black switch restored after reset") { node ->
            node.isCheckable &&
                node.checked == AccessibilityNodeInfo.CHECKED_STATE_FALSE &&
                node.hasDescendantOrSelfText("True black")
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        runFixtureCleanup(
            primaryFailure,
            {
                activity?.let { host ->
                    runOnMainSync(host::finishAndRemoveTask)
                    awaitReadingCondition("app theme recovery fixture did not close") {
                        host.isDestroyed
                    }
                }
            },
            { check(directory.deleteRecursively()) }
        )
    }
}

private fun Instrumentation.closeExternalFixture(currentExternal: AtomicReference<MainActivity?>) {
    val deadline = SystemClock.uptimeMillis() + 15_000L
    var sentCallerBack = false
    while (SystemClock.uptimeMillis() < deadline) {
        val doc = currentExternal.get()
        if (doc != null && !doc.isDestroyed) {
            runOnMainSync(doc::finishAndRemoveTask)
            awaitReadingCondition("external document fixture did not close") { doc.isDestroyed }
            sentCallerBack = false
            continue
        }
        val activePackage = uiAutomation.rootInActiveWindow?.packageName?.toString()
        if (activePackage == DocumentCallerContract.PACKAGE) {
            if (!sentCallerBack) {
                check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                sentCallerBack = true
            }
            SystemClock.sleep(20L)
            continue
        }
        if (sentCallerBack && activePackage != null) {
            return
        }
        SystemClock.sleep(20L)
    }
    val doc = currentExternal.get()
    check(doc == null || doc.isDestroyed) { "external document fixture remained open after cleanup" }
    check(uiAutomation.rootInActiveWindow?.packageName?.toString() != DocumentCallerContract.PACKAGE) {
        "the external caller did not finish before continuing"
    }
}

private fun Instrumentation.closeExternalCallerIfActive(waitForCallerWindow: Boolean) {
    if (waitForCallerWindow) {
        val appearDeadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < appearDeadline) {
            if (uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                DocumentCallerContract.PACKAGE
            ) {
                break
            }
            SystemClock.sleep(20L)
        }
    }
    if (uiAutomation.rootInActiveWindow?.packageName?.toString() != DocumentCallerContract.PACKAGE) {
        return
    }
    check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    val deadline = SystemClock.uptimeMillis() + 15_000L
    while (SystemClock.uptimeMillis() < deadline) {
        val activePackage = uiAutomation.rootInActiveWindow?.packageName?.toString()
        if (activePackage != null && activePackage != DocumentCallerContract.PACKAGE) return
        SystemClock.sleep(20L)
    }
    error("the external caller did not finish before continuing")
}

private fun Activity.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

private fun Activity.windowSurfaceColor(): Int =
    (window.decorView.background as? ColorDrawable)?.color ?: Color.TRANSPARENT

private fun Instrumentation.verifyHomePixels(
    expectedCanvas: Int,
    expectedReceiveCard: Int,
    label: String
) {
    val receiveHeading = waitForAccessibilityNode("Receive heading ($label)") {
        it.isVisibleToUser && it.text?.toString() == "Receive"
    }
    val headingBounds = Rect().also(receiveHeading::getBoundsInScreen)
    check(!headingBounds.isEmpty) { "Receive heading bounds were empty for $label" }

    var screenshot: Bitmap? = null
    var lastCanvas = Color.TRANSPARENT
    var lastCard = Color.TRANSPARENT
    try {
        awaitReadingCondition(
            "$label pixels did not match expected canvas=${expectedCanvas.hexRgb()} " +
                "(got ${lastCanvas.hexRgb()}) and card=${expectedReceiveCard.hexRgb()} " +
                "(got ${lastCard.hexRgb()})"
        ) {
            screenshot?.recycle()
            screenshot = uiAutomation.takeScreenshot()
            val bitmap = screenshot ?: return@awaitReadingCondition false
            val cardY = headingBounds.centerY().coerceIn(4, bitmap.height - 5)
            // Sample just right of the "Receive" label inside the card and near the screen edge outside it.
            val cardX = (headingBounds.right + 24).coerceIn(4, bitmap.width - 5)
            val canvasX = 4
            lastCanvas = bitmap.getPixel(canvasX, cardY)
            lastCard = bitmap.getPixel(cardX, cardY)
            matchesRenderedColor(lastCanvas, expectedCanvas) &&
                matchesRenderedColor(lastCard, expectedReceiveCard)
        }
    } finally {
        screenshot?.recycle()
    }
}

private fun Instrumentation.verifyExternalDocumentPixels(
    expectedCanvas: Int,
    expectedTopBar: Int,
    label: String
) {
    var screenshot: Bitmap? = null
    var lastCanvas = Color.TRANSPARENT
    var lastStatusBarTopBar = Color.TRANSPARENT
    var lastActionRowTopBar = Color.TRANSPARENT
    try {
        awaitReadingCondition(
            "$label pixels did not match canvas=${expectedCanvas.hexRgb()} " +
                "(got ${lastCanvas.hexRgb()}), status-bar top bar=${expectedTopBar.hexRgb()} " +
                "(got ${lastStatusBarTopBar.hexRgb()}), action-row top bar=${expectedTopBar.hexRgb()} " +
                "(got ${lastActionRowTopBar.hexRgb()})"
        ) {
            val backButton = uiAutomation.rootInActiveWindow?.findNode {
                it.contentDescription?.toString() == "Back"
            } ?: return@awaitReadingCondition false
            val backBounds = Rect().also(backButton::getBoundsInScreen)
            if (backBounds.isEmpty) return@awaitReadingCondition false
            screenshot?.recycle()
            screenshot = uiAutomation.takeScreenshot()
            val bitmap = screenshot ?: return@awaitReadingCondition false
            lastCanvas = bitmap.getPixel(4, bitmap.height / 2)
            // Sample behind the status bar away from clock, center cutout, and system icons.
            lastStatusBarTopBar = bitmap.getPixel(bitmap.width / 3, 4)
            // Sample inside the visible TopAppBar action row in the leading padding beside Back.
            val actionRowX = (backBounds.left / 2).coerceIn(0, bitmap.width - 1)
            val actionRowY = backBounds.centerY().coerceIn(0, bitmap.height - 1)
            lastActionRowTopBar = bitmap.getPixel(actionRowX, actionRowY)
            matchesRenderedColor(lastCanvas, expectedCanvas) &&
                matchesRenderedColor(lastStatusBarTopBar, expectedTopBar) &&
                matchesRenderedColor(lastActionRowTopBar, expectedTopBar)
        }
    } finally {
        screenshot?.recycle()
    }
}

/** Requires exact black for True black samples while allowing minor display rounding elsewhere. */
private fun matchesRenderedColor(actual: Int, expected: Int): Boolean =
    if (expected == Color.BLACK) {
        actual == Color.BLACK
    } else {
        Color.alpha(actual) == 255 &&
            kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 2 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 2 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 2
    }

private fun Int.hexRgb(): String = "#%06X".format(this and 0xFFFFFF)

private fun AccessibilityNodeInfo.hasDescendantOrSelfText(target: String): Boolean {
    if (text?.toString() == target || contentDescription?.toString() == target) return true
    for (index in 0 until childCount) {
        val child = getChild(index) ?: continue
        if (child.hasDescendantOrSelfText(target)) return true
    }
    return false
}
