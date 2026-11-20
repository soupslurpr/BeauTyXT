package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.AppearanceGroup
import dev.soupslurpr.beautyxt.ui.editor.AppearanceOption
import dev.soupslurpr.beautyxt.ui.editor.AtomicAppearanceStorage
import dev.soupslurpr.beautyxt.ui.editor.DocumentAppearances
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceDialogs
import dev.soupslurpr.beautyxt.ui.editor.TextAppearancePanel
import dev.soupslurpr.beautyxt.ui.editor.TextAppearancePanelState
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStore
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStoreState
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers

/** Exercise real AtomicFile recovery and both actions on a fixture-owned preference file. */
internal fun Instrumentation.verifyAppearanceStorageRecovery() {
    val directory = Files.createTempDirectory(targetContext.cacheDir.toPath(), "appearance-store").toFile()
    val file = directory.resolve("preferences")
    val backup = directory.resolve("preferences.bak")
    val corrupt = "Source.size=\\uXXXX"
    val saved = "Markdown.size=26"
    var activity: HomeActivity? = null
    var primaryFailure: Throwable? = null
    try {
        val host = startActivitySync(Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as HomeActivity
        activity = host
        fun showStore(): TextAppearanceStore {
            val store = TextAppearanceStore(AtomicAppearanceStorage(file), Dispatchers.IO)
            runOnMainSync {
                host.setContent {
                    val state by store.state.collectAsState()
                    val panel = remember(store) { TextAppearancePanelState() }
                    var group by remember { mutableStateOf(AppearanceGroup.Markdown) }
                    BeauTyXTTheme {
                        TextAppearancePanel(panel, group, { group = it }, store, state, {})
                        TextAppearanceDialogs(panel, store, state, onFind = { false })
                    }
                }
            }
            return store
        }
        file.writeText(corrupt)
        val retry = showStore()
        requireActionableText("Retry loading")
        check(uiAutomation.rootInActiveWindow?.findNode { it.contentDescription == "Edit Text size" } == null)
        requireActionableText("Reset saved appearance…").performRequiredClick()
        requireActionableText("Cancel").performRequiredClick()
        check(file.readText() == corrupt && retry.state.value == TextAppearanceStoreState.LoadFailed)
        // The next read must let AtomicFile restore a surviving backup first.
        backup.writeText(saved)
        requireActionableText("Retry loading").performRequiredClick()
        awaitReadingCondition("retry did not recover the original Markdown size") {
            (retry.state.value as? TextAppearanceStoreState.Ready)?.preferences?.markdown?.custom?.get(AppearanceOption.Size) == 26f
        }
        check(file.readText() == saved && !backup.exists())
        file.writeText(corrupt)
        val reset = showStore()
        requireActionableText("Reset saved appearance…").performRequiredClick()
        check(file.readText() == corrupt)
        requireActionableText("Reset both groups").performRequiredClick()
        awaitReadingCondition("confirmed reset did not restore both defaults") {
            reset.state.value == TextAppearanceStoreState.Ready(DocumentAppearances()) && !file.exists() && !backup.exists()
        }
        requireActionableContentDescription("Edit Text size")
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        runFixtureCleanup(primaryFailure, {
            activity?.let { host ->
                runOnMainSync(host::finishAndRemoveTask)
                awaitReadingCondition("appearance recovery fixture did not close") { host.isDestroyed }
            }
        }, { check(directory.deleteRecursively()) })
    }
}
