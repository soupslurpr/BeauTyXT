/* Verifies compiled Android resources, not a second hard-coded production string catalog. */
package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.importing.client.DocumentImportFailure
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.editor.DocumentTransferCapacity
import dev.soupslurpr.beautyxt.ui.editor.FindStatus
import dev.soupslurpr.beautyxt.ui.editor.FindWrap
import dev.soupslurpr.beautyxt.ui.editor.findStatusMessage
import dev.soupslurpr.beautyxt.ui.editor.transferCapacityDescription
import dev.soupslurpr.beautyxt.ui.incomingShareCharacterCount
import dev.soupslurpr.beautyxt.ui.resolve
import dev.soupslurpr.beautyxt.ui.userMessage

/** Checks launch themes, plurals, template arguments, escaping, and literal boundaries. */
internal fun verifyUiResources(context: Context) {
    verifyLaunchThemes(context)
    val resources = context.resources
    for ((text, label) in listOf(
        "" to "0 Unicode characters",
        "a" to "1 Unicode character",
        "😀" to "1 Unicode character",
        "a😀" to "2 Unicode characters",
        "e\u0301" to "2 Unicode characters"
    )) {
        check(incomingShareCharacterCount(text).resolve(resources) == label)
    }
    check(resources.getString(R.string.print_page_number, 42) == "Page 42")
    check(
        UiText.Resource(R.string.operation_line_range, listOf(1L, Long.MAX_VALUE))
            .resolve(resources) == "Enter a line from 1 to 9223372036854775807"
    )
    check(resources.getString(R.string.cleanup_failed) == "Couldn't prepare BeauTyXT")
    for (failure in DocumentImportFailure.entries) {
        check(failure.userMessage.resolve(resources).isNotBlank())
    }
    for (failure in DocumentExportFailure.entries) {
        check(failure.userMessage.resolve(resources).isNotBlank())
    }
    val match = FindMatch(Utf16Range(12, 16), ViewportCursor(3, 4, 12))
    check(
        findStatusMessage(FindStatus.Match(match, FindWrap.Beginning)).resolve(resources) ==
            "Wrapped to beginning · Match on line 5"
    )
    check(
        transferCapacityDescription(DocumentTransferCapacity(765_915, 1_536))
            .resolve(resources) == "Too large · 765,915 bytes; limit 1,536"
    )
    val literal = "100% literal %1\$s & ' \" 😀"
    check(UiText.Literal(literal).resolve(resources) == literal)
    check(
        resources.getString(R.string.incoming_close_for_file, literal)
            .contains("“$literal”")
    )
}

/** The system splash uses XML resources before Compose can apply its dynamic theme. */
private fun verifyLaunchThemes(context: Context) {
    for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
        val light = nightMode == Configuration.UI_MODE_NIGHT_NO
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val themedContext = ContextThemeWrapper(
            context.createConfigurationContext(configuration),
            context.applicationInfo.theme
        )
        themedContext.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.windowSplashScreenBackground,
                android.R.attr.windowBackground,
                android.R.attr.isLightTheme,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar
            )
        ).use { attributes ->
            val splash = attributes.getColor(0, Color.TRANSPARENT)
            val background = if (Color.alpha(splash) == 0) {
                attributes.getColor(1, Color.TRANSPARENT)
            } else {
                splash
            }
            check(Color.alpha(background) == 255) { "Launch background is not opaque" }
            check((Color.luminance(background) > 0.5f) == light) {
                "Splash background does not follow night mode $nightMode"
            }
            check(attributes.getBoolean(2, !light) == light) {
                "Launch theme does not follow night mode $nightMode"
            }
            check(attributes.getBoolean(3, !light) == light) {
                "Launch status-bar icons do not match night mode $nightMode"
            }
            check(attributes.getBoolean(4, !light) == light) {
                "Launch navigation-bar icons do not match night mode $nightMode"
            }
        }
    }
}
