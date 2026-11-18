package com.notebudget.app.feature.folders

/** The icon of a folder: up to three "characters" as a person sees them, so a whole emoji is never cut in half. */
object FolderIcon {
    const val MAX_CHARACTERS = 3

    /** Keeps at most [MAX_CHARACTERS] visible characters, including joined emoji as one character. */
    fun limit(text: String): String {
        if (text.isEmpty()) return text
        var end = 0
        var count = 0
        while (end < text.length && count < MAX_CHARACTERS) {
            end = nextCharacterEnd(text, end)
            count++
        }
        return if (end == text.length) text else text.substring(0, end)
    }

    /** A grapheme approximation for folder labels: combining marks and emoji modifiers attach to their base; ZWJ joins both sides. */
    private fun nextCharacterEnd(text: String, start: Int): Int {
        var cursor = start
        val first = text.codePointAt(cursor)
        cursor += Character.charCount(first)

        // A pair of regional indicators is a single flag (a third starts the next flag).
        if (isRegionalIndicator(first) && cursor < text.length) {
            val next = text.codePointAt(cursor)
            if (isRegionalIndicator(next)) cursor += Character.charCount(next)
        }

        while (cursor < text.length) {
            val codePoint = text.codePointAt(cursor)
            when {
                isExtender(codePoint) -> cursor += Character.charCount(codePoint)
                codePoint == ZERO_WIDTH_JOINER -> {
                    cursor += Character.charCount(codePoint)
                    if (cursor < text.length) {
                        val joined = text.codePointAt(cursor)
                        cursor += Character.charCount(joined)
                    }
                }
                first == CARRIAGE_RETURN && codePoint == LINE_FEED -> cursor += Character.charCount(codePoint)
                else -> return cursor
            }
        }
        return cursor
    }

    private fun isExtender(codePoint: Int): Boolean = when {
        codePoint in EMOJI_MODIFIER_START..EMOJI_MODIFIER_END -> true
        codePoint in TAG_START..TAG_END || codePoint == CANCEL_TAG -> true
        codePoint == VARIATION_SELECTOR_TEXT || codePoint == VARIATION_SELECTOR_EMOJI || codePoint == KEYCAP -> true
        else -> when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val VARIATION_SELECTOR_TEXT = 0xFE0E
    private const val VARIATION_SELECTOR_EMOJI = 0xFE0F
    private const val KEYCAP = 0x20E3
    private const val EMOJI_MODIFIER_START = 0x1F3FB
    private const val EMOJI_MODIFIER_END = 0x1F3FF
    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF
    private const val TAG_START = 0xE0020
    private const val TAG_END = 0xE007E
    private const val CANCEL_TAG = 0xE007F
    private const val CARRIAGE_RETURN = 0x000D
    private const val LINE_FEED = 0x000A
}
