/* Validates transient print options and bounded decimal physical measurements. */
package dev.soupslurpr.beautyxt.printing

import java.math.BigDecimal
import java.math.RoundingMode

private const val MILS_PER_INCH = 1_000
private const val MICROMETRES_PER_INCH = 25_400
private const val TENTHS_OF_MICROMETRES_PER_MILLIMETRE = 10_000
private const val TENTHS_OF_MICROMETRES_PER_MIL = 254
private const val DEFAULT_MARGIN_MILS = 500
private const val DEFAULT_SOURCE_FONT_SIZE_POINTS = 10
private const val DEFAULT_MARKDOWN_FONT_SIZE_POINTS = 12
private const val MINIMUM_FONT_SIZE_POINTS = 6
private const val MAXIMUM_FONT_SIZE_POINTS = 72
private const val MAXIMUM_MARGIN_MILS = 20_000
private const val MARGIN_DECIMAL_SCALE = 3
private const val MAXIMUM_PRINT_NUMBER_CHARACTERS = 32

/** Selects the document representation sent to the bounded PDF renderer. */
internal enum class PrintContentMode {
    /** Prints the exact source text, including Markdown syntax. */
    Source,

    /** Prints the isolated renderer's semantic Markdown model. */
    FormattedMarkdown
}

/** Selects one platform typeface family for printed body text. */
internal enum class PrintFontFamily {
    SansSerif,
    Serif,
    Monospace
}

/** Selects the physical unit used only while editing margin fields. */
internal enum class PrintMarginUnit {
    Inches,
    Millimetres
}

/** Stores four independent physical margins in exact thousandths of an inch. */
internal data class PrintMargins(
    val topMils: Int,
    val bottomMils: Int,
    val leftMils: Int,
    val rightMils: Int
) {
    init {
        require(topMils in 0..MAXIMUM_MARGIN_MILS) { "top print margin is outside its bounds" }
        require(bottomMils in 0..MAXIMUM_MARGIN_MILS) {
            "bottom print margin is outside its bounds"
        }
        require(leftMils in 0..MAXIMUM_MARGIN_MILS) {
            "left print margin is outside its bounds"
        }
        require(rightMils in 0..MAXIMUM_MARGIN_MILS) {
            "right print margin is outside its bounds"
        }
    }
}

/** Contains one immutable, transient print configuration. */
internal data class PrintSettings(
    val contentMode: PrintContentMode,
    val margins: PrintMargins,
    val fontFamily: PrintFontFamily,
    val fontSizePoints: Int,
    val wrapLongLines: Boolean,
    val showFileName: Boolean,
    val showPageNumbers: Boolean
) {
    init {
        require(fontSizePoints in MINIMUM_FONT_SIZE_POINTS..MAXIMUM_FONT_SIZE_POINTS) {
            "print font size is outside its bounds"
        }
    }
}

/** Retains editable print fields in memory while the setup sheet is visible. */
internal data class PrintSetupDraft(
    val contentMode: PrintContentMode,
    val marginUnit: PrintMarginUnit,
    val topMargin: String,
    val bottomMargin: String,
    val leftMargin: String,
    val rightMargin: String,
    val fontFamily: PrintFontFamily,
    val fontSize: String,
    val wrapLongLines: Boolean,
    val showFileName: Boolean,
    val showPageNumbers: Boolean
) {
    /** Returns whether numeric fields are small enough to retain and lay out interactively. */
    val hasBoundedInput: Boolean
        get() = topMargin.length <= MAXIMUM_PRINT_NUMBER_CHARACTERS &&
            bottomMargin.length <= MAXIMUM_PRINT_NUMBER_CHARACTERS &&
            leftMargin.length <= MAXIMUM_PRINT_NUMBER_CHARACTERS &&
            rightMargin.length <= MAXIMUM_PRINT_NUMBER_CHARACTERS &&
            fontSize.length <= MAXIMUM_PRINT_NUMBER_CHARACTERS
}

/** Reports field-level validation and the complete configuration when valid. */
internal data class PrintSetupValidation(
    val settings: PrintSettings?,
    val invalidMarginFields: Set<PrintMarginField>,
    val isFontSizeInvalid: Boolean
) {
    /** Returns whether every transient field can enter the renderer. */
    val isValid: Boolean
        get() = settings != null
}

/** Identifies one independently editable physical margin. */
internal enum class PrintMarginField {
    Top,
    Bottom,
    Left,
    Right
}

/** Creates the transient defaults for one source or Markdown-oriented document. */
internal fun defaultPrintSetupDraft(formattedMarkdown: Boolean): PrintSetupDraft {
    val contentMode =
        if (formattedMarkdown) PrintContentMode.FormattedMarkdown else PrintContentMode.Source
    val fontFamily =
        if (formattedMarkdown) PrintFontFamily.SansSerif else PrintFontFamily.Monospace
    val fontSize =
        if (formattedMarkdown) {
            DEFAULT_MARKDOWN_FONT_SIZE_POINTS
        } else {
            DEFAULT_SOURCE_FONT_SIZE_POINTS
        }
    val marginText = formatPrintMargin(DEFAULT_MARGIN_MILS, PrintMarginUnit.Inches)
    return PrintSetupDraft(
        contentMode = contentMode,
        marginUnit = PrintMarginUnit.Inches,
        topMargin = marginText,
        bottomMargin = marginText,
        leftMargin = marginText,
        rightMargin = marginText,
        fontFamily = fontFamily,
        fontSize = fontSize.toString(),
        wrapLongLines = true,
        showFileName = true,
        showPageNumbers = true
    )
}

/** Returns one validated immutable default without exposing editable fields. */
internal fun defaultPrintSettings(formattedMarkdown: Boolean): PrintSettings =
    checkNotNull(validatePrintSetup(defaultPrintSetupDraft(formattedMarkdown)).settings) {
        "default print settings must be valid"
    }

/** Validates all fields without accepting partial or locale-ambiguous numbers. */
internal fun validatePrintSetup(draft: PrintSetupDraft): PrintSetupValidation {
    val topMils = parsePrintMargin(draft.topMargin, draft.marginUnit)
    val bottomMils = parsePrintMargin(draft.bottomMargin, draft.marginUnit)
    val leftMils = parsePrintMargin(draft.leftMargin, draft.marginUnit)
    val rightMils = parsePrintMargin(draft.rightMargin, draft.marginUnit)
    val invalidMarginFields = buildSet {
        if (topMils == null) add(PrintMarginField.Top)
        if (bottomMils == null) add(PrintMarginField.Bottom)
        if (leftMils == null) add(PrintMarginField.Left)
        if (rightMils == null) add(PrintMarginField.Right)
    }
    val fontSize = draft.fontSize.takeIf {
        it.length <= MAXIMUM_PRINT_NUMBER_CHARACTERS
    }?.toIntOrNull()
    val isFontSizeInvalid =
        fontSize !in MINIMUM_FONT_SIZE_POINTS..MAXIMUM_FONT_SIZE_POINTS
    if (invalidMarginFields.isNotEmpty() || isFontSizeInvalid) {
        return PrintSetupValidation(
            settings = null,
            invalidMarginFields = invalidMarginFields,
            isFontSizeInvalid = isFontSizeInvalid
        )
    }
    return PrintSetupValidation(
        settings =
            PrintSettings(
                contentMode = draft.contentMode,
                margins =
                    PrintMargins(
                        topMils = checkNotNull(topMils),
                        bottomMils = checkNotNull(bottomMils),
                        leftMils = checkNotNull(leftMils),
                        rightMils = checkNotNull(rightMils)
                    ),
                fontFamily = draft.fontFamily,
                fontSizePoints = checkNotNull(fontSize),
                wrapLongLines = draft.wrapLongLines,
                showFileName = draft.showFileName,
                showPageNumbers = draft.showPageNumbers
            ),
        invalidMarginFields = emptySet(),
        isFontSizeInvalid = false
    )
}

/** Converts every valid margin field when the visible physical unit changes. */
internal fun convertPrintMarginUnit(
    draft: PrintSetupDraft,
    unit: PrintMarginUnit
): PrintSetupDraft {
    if (unit == draft.marginUnit) {
        return draft
    }
    fun convert(value: String): String =
        parsePrintMargin(value, draft.marginUnit)?.let { mils -> formatPrintMargin(mils, unit) }
            ?: value
    return draft.copy(
        marginUnit = unit,
        topMargin = convert(draft.topMargin),
        bottomMargin = convert(draft.bottomMargin),
        leftMargin = convert(draft.leftMargin),
        rightMargin = convert(draft.rightMargin)
    )
}

/** Parses one nonnegative physical margin into exact thousandths of an inch. */
private fun parsePrintMargin(value: String, unit: PrintMarginUnit): Int? {
    if (value.length > MAXIMUM_PRINT_NUMBER_CHARACTERS) return null
    val normalized = value.trim()
    if (normalized.any { character -> character !in '0'..'9' && character != '.' }) {
        return null
    }
    val decimal = normalized.toBigDecimalOrNull() ?: return null
    val mils =
        when (unit) {
            PrintMarginUnit.Inches ->
                decimal
                    .multiply(BigDecimal.valueOf(MILS_PER_INCH.toLong()))
                    .setScale(0, RoundingMode.HALF_UP)

            PrintMarginUnit.Millimetres ->
                decimal
                    .multiply(
                        BigDecimal.valueOf(TENTHS_OF_MICROMETRES_PER_MILLIMETRE.toLong())
                    )
                    .divide(
                        BigDecimal.valueOf(TENTHS_OF_MICROMETRES_PER_MIL.toLong()),
                        0,
                        RoundingMode.HALF_UP
                    )
        }
    val exact = try {
        mils.intValueExact()
    } catch (_: ArithmeticException) {
        return null
    }
    return exact.takeIf { margin -> margin in 0..MAXIMUM_MARGIN_MILS }
}

/** Gives validation feedback the same upper bound and unit conversion as the parser. */
internal fun maximumPrintMarginText(unit: PrintMarginUnit): String =
    formatPrintMargin(MAXIMUM_MARGIN_MILS, unit)

/** Formats one exact physical margin for the requested transient unit. */
private fun formatPrintMargin(mils: Int, unit: PrintMarginUnit): String {
    require(mils in 0..MAXIMUM_MARGIN_MILS) { "print margin is outside its bounds" }
    val value =
        when (unit) {
            PrintMarginUnit.Inches ->
                BigDecimal.valueOf(mils.toLong())
                    .divide(
                        BigDecimal.valueOf(MILS_PER_INCH.toLong()),
                        MARGIN_DECIMAL_SCALE,
                        RoundingMode.UNNECESSARY
                    )

            PrintMarginUnit.Millimetres ->
                BigDecimal.valueOf(mils.toLong())
                    .multiply(BigDecimal.valueOf(MICROMETRES_PER_INCH.toLong()))
                    .divide(
                        BigDecimal.valueOf(MILS_PER_INCH.toLong() * 1_000L),
                        MARGIN_DECIMAL_SCALE,
                        RoundingMode.HALF_UP
                    )
        }
    return value.stripTrailingZeros().toPlainString()
}
