/* Verifies actual annotated Markdown pixels under independent fonts and Bold text. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_CODE
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_EMPHASIS
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.ui.editor.AppearanceGroup
import dev.soupslurpr.beautyxt.ui.editor.AppearanceOption
import dev.soupslurpr.beautyxt.ui.editor.TextAppearance
import dev.soupslurpr.beautyxt.ui.editor.appearanceFont
import dev.soupslurpr.beautyxt.ui.editor.markdownAnnotatedStringForPresentation
import dev.soupslurpr.beautyxt.ui.editor.markdownEmphasis

internal fun Instrumentation.verifyMarkdownEmphasis() {
    val text = "Ordinary words"
    for (adjustment in listOf(0, 300)) {
        val configuration = Configuration(targetContext.resources.configuration).apply {
            fontWeightAdjustment = adjustment
        }
        val locales = LocaleList(configuration.locales.toLanguageTags())
        val resolver = createFontFamilyResolver(targetContext.createConfigurationContext(configuration))
        for (family in 0..2) {
            for (requestedWeight in listOf(100, 400, 500, 700, 1000)) {
                for (code in listOf(false, true)) {
                    // 1000 also exercises the effective weight of an already-bold heading/header.
                    val appearance = TextAppearance()
                        .change(AppearanceOption.Weight, requestedWeight.coerceAtMost(700).toFloat())
                        .change(AppearanceOption.Font, if (code) 0f else family.toFloat())
                        .change(AppearanceOption.CodeFont, family.toFloat())
                    val bodyEmphasis = markdownEmphasis(if (code) 0f else family.toFloat(), requestedWeight, adjustment)
                    val codeEmphasis = markdownEmphasis(family.toFloat(), appearance.value(AppearanceOption.Weight, AppearanceGroup.Markdown).toInt(), adjustment)
                    for (italic in listOf(false, true)) {
                        fun sample(strong: Boolean): Bitmap {
                            val styles = (if (code) MARKDOWN_SPAN_STYLE_CODE else 0) or
                                (if (italic) MARKDOWN_SPAN_STYLE_EMPHASIS else 0) or
                                (if (strong) MARKDOWN_SPAN_STYLE_STRONG else 0)
                            val annotated = markdownAnnotatedStringForPresentation(text,
                                listOf(MarkdownInlineSpan(0, text.length, styles, null)),
                                linkColor = Color.Blue, codeBackground = Color.Transparent,
                                linkInteractionListener = LinkInteractionListener {}, appearance = appearance,
                                bodyEmphasis = bodyEmphasis, codeEmphasis = codeEmphasis)
                            check(annotated.text == text)
                            val paragraph = Paragraph(annotated.text, spanStyles = annotated.spanStyles,
                                style = TextStyle(fontFamily = appearanceFont(if (code) 0f else family.toFloat()),
                                    fontWeight = FontWeight(requestedWeight), fontSize = 24.sp),
                                constraints = Constraints(maxWidth = 500), density = Density(1f), fontFamilyResolver = resolver,
                                defaultLocaleList = locales)
                            return Bitmap.createBitmap(500, 100, Bitmap.Config.ARGB_8888).also {
                                paragraph.paint(Canvas(android.graphics.Canvas(it)))
                            }
                        }
                        val ordinary = sample(false)
                        val strong = sample(true)
                        try {
                            check(!ordinary.sameAs(strong)) {
                                "Markdown emphasis disappeared: adjustment=$adjustment family=$family weight=$requestedWeight code=$code italic=$italic"
                            }
                        } finally {
                            ordinary.recycle()
                            strong.recycle()
                        }
                    }
                }
            }
        }
    }
}
