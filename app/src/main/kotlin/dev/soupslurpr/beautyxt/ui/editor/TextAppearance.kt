/* Defines content-free, independently resettable document appearance preferences. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.annotation.StringRes
import dev.soupslurpr.beautyxt.R
import java.io.Reader
import java.io.Writer
import java.util.Properties
import kotlin.math.roundToInt
import kotlin.math.round

internal enum class AppearanceGroup(@param:StringRes val title: Int) {
    Source(R.string.appearance_source), Markdown(R.string.appearance_markdown)
}

/** Keys are stable storage identifiers; missing values explicitly follow app defaults. */
internal enum class AppearanceOption(
    val key: String,
    @param:StringRes val title: Int,
    val minimum: Float,
    val maximum: Float,
    val step: Float,
    val sourceDefault: Float,
    val markdownDefault: Float = sourceDefault
) {
    Font("font", R.string.appearance_font, 0f, 2f, 1f, 2f, 0f),
    Size("size", R.string.appearance_size, 8f, 72f, .5f, 16f, 18f),
    Weight("weight", R.string.appearance_weight, 100f, 700f, 100f, 400f),
    LineSpacing("line_spacing", R.string.appearance_line_spacing, 1f, 3f, .05f, 1.5f, 28f / 18f),
    LetterSpacing("letter_spacing", R.string.appearance_letter_spacing, 0f, 30f, 1f, 0f),
    WordSpacing("word_spacing", R.string.appearance_word_spacing, 0f, 100f, 5f, 0f),
    ParagraphSpacing("paragraph_spacing", R.string.appearance_paragraph_spacing, 0f, 3f, .1f, 12f / 18f),
    ColumnWidth("column_width", R.string.appearance_column_width, 240f, 1600f, 20f, 960f, 680f),
    FullWidth("full_width", R.string.appearance_full_width, 0f, 1f, 1f, 0f),
    Margin("margin", R.string.appearance_margin, 0f, 96f, 4f, 24f),
    Wrap("wrap", R.string.appearance_wrap, 0f, 1f, 1f, 1f),
    TabWidth("tab_width", R.string.appearance_tab_width, 1f, 16f, 1f, 4f),
    HeadingFont("heading_font", R.string.appearance_heading_font, 0f, 2f, 1f, 0f),
    HeadingScale("heading_scale", R.string.appearance_heading_scale, 75f, 200f, 5f, 100f),
    CodeFont("code_font", R.string.appearance_code_font, 0f, 2f, 1f, 2f),
    CodeScale("code_scale", R.string.appearance_code_scale, 50f, 150f, 5f, 100f);

    fun default(group: AppearanceGroup): Float =
        if (group == AppearanceGroup.Source) sourceDefault else markdownDefault

    fun valid(value: Float): Boolean = value.isFinite() && value in minimum..maximum &&
        (this !in discreteOptions || value == round(value))

    val intervals: Int get() = ((maximum.toDouble() - minimum) / step).roundToInt()

    private fun gridValue(index: Int): Float =
        (minimum.toDouble() + index.coerceIn(0, intervals) *
            ((maximum.toDouble() - minimum) / intervals)).toFloat()

    private fun nearestIndex(value: Float): Int =
        ((value.toDouble() - minimum) * intervals / (maximum.toDouble() - minimum)).roundToInt()

    fun stepped(value: Float): Float = gridValue(nearestIndex(value))

    fun nextStep(value: Float, direction: Int): Float {
        require(direction == -1 || direction == 1)
        val nearest = nearestIndex(value)
        val candidate = gridValue(nearest)
        return if (direction > 0 && candidate > value || direction < 0 && candidate < value)
            candidate else gridValue(nearest + direction)
    }

    companion object {
        private val discreteOptions = setOf(Font, Weight, FullWidth, Wrap, TabWidth, HeadingFont, CodeFont)
    }
}

internal data class TextAppearance(val custom: Map<AppearanceOption, Float> = emptyMap()) {
    fun value(option: AppearanceOption, group: AppearanceGroup): Float = custom[option] ?: option.default(group)

    /** Explicit defaults and an inactive column limit do not reflow the document. */
    fun effective(group: AppearanceGroup): TextAppearance = TextAppearance(custom.filter { (option, number) ->
        number != option.default(group) &&
            (option != AppearanceOption.ColumnWidth || value(AppearanceOption.FullWidth, group) == 0f) &&
            when (option) {
                AppearanceOption.Wrap, AppearanceOption.TabWidth -> group == AppearanceGroup.Source
                AppearanceOption.ParagraphSpacing, AppearanceOption.HeadingScale, AppearanceOption.HeadingFont,
                AppearanceOption.CodeScale, AppearanceOption.CodeFont -> group == AppearanceGroup.Markdown
                else -> true
            }
    })

    fun change(option: AppearanceOption, value: Float?): TextAppearance {
        require(value == null || option.valid(value))
        return copy(custom = if (value == null) custom - option else custom + (option to value))
    }
}

internal data class DocumentAppearances(
    val source: TextAppearance = TextAppearance(),
    val markdown: TextAppearance = TextAppearance()
) {
    operator fun get(group: AppearanceGroup): TextAppearance =
        if (group == AppearanceGroup.Source) source else markdown

    fun change(group: AppearanceGroup, appearance: TextAppearance): DocumentAppearances =
        if (group == AppearanceGroup.Source) copy(source = appearance) else copy(markdown = appearance)
}

/** Stores only known numeric settings, never document names, locations, or content. */
internal fun writeDocumentAppearances(value: DocumentAppearances, writer: Writer) {
    Properties().apply {
        AppearanceGroup.entries.forEach { group ->
            value[group].custom.forEach { (option, number) ->
                setProperty("${group.name}.${option.key}", number.toString())
            }
        }
    }.store(writer, "BeauTyXT text appearance")
}

/** Invalid individual values fall back independently without discarding valid choices. */
internal fun readDocumentAppearances(reader: Reader): DocumentAppearances {
    val properties = Properties().apply { load(reader) }
    fun read(group: AppearanceGroup) = TextAppearance(buildMap {
        AppearanceOption.entries.forEach { option ->
            properties.getProperty("${group.name}.${option.key}")?.toFloatOrNull()
                ?.takeIf(option::valid)?.let { put(option, it) }
        }
    })
    return DocumentAppearances(read(AppearanceGroup.Source), read(AppearanceGroup.Markdown))
}
