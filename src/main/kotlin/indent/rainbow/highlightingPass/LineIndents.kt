package indent.rainbow.highlightingPass

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import indent.rainbow.ifNotPositive
import indent.rainbow.mapToIntArray
import indent.rainbow.settings.IrConfig
import indent.rainbow.settings.cachedData
import java.util.regex.Pattern
import kotlin.math.min

@Suppress("ArrayInDataClass")
data class LineIndents(val levels: IntArray, val indentSize: Int)

class LineIndentsCalculator(private val file: PsiFile, private val document: Document) {

    private val config = IrConfig.INSTANCE
    private val ignoreLinesStartingWith: Pattern = config.cachedData.ignoreLinesStartingWith
    private val disableErrorHighlighting: Boolean = config.cachedData.disableErrorHighlightingLanguageFilter(file.language)

    fun compute(): LineIndents {
        val tabsAndSpaces = getTabsAndSpaces()
        val indents = getExplicitIndents(tabsAndSpaces)
        if (config.highlightEmptyLines) {
            fillIndentsOnEmptyLines(indents.levels)
        }
        return indents
    }

    private fun getExplicitIndents(tabsAndSpaces: Array<LineIndentInfo>): LineIndents {
        val indentOptions = CodeStyle.getIndentOptions(file)
        val useTabs = indentOptions.USE_TAB_CHARACTER
        val indentSize = indentOptions.INDENT_SIZE.ifNotPositive { 4 }
        return if (useTabs) {
            val indents = tabsAndSpaces.mapToIntArray {
                if (it.tabs + it.spaces == it.totalIndent) {
                    it.tabs
                } else {
                    -1  // incorrect mixed tabs and spaces
                }
            }
            LineIndents(indents, 1)
        } else {
            val fileText = document.charsSequence
            val tabSize = indentOptions.TAB_SIZE.ifNotPositive { 4 }
            val indents = IntArray(tabsAndSpaces.size) { line ->
                val info = tabsAndSpaces[line]
                val isCorrectLine = info.tabs == 0 && info.spaces == info.totalIndent
                        && (info.spaces % indentSize == 0
                        || shouldIgnoreLine(info, fileText)
                        || isAlignedWithNotClosedLeftBracket(line, info.spaces, fileText, tabSize))
                if (isCorrectLine || disableErrorHighlighting) {
                    info.spaces / indentSize
                } else {
                    -1
                }
            }
            LineIndents(indents, indentSize)
        }
    }

    private fun shouldIgnoreLine(info: LineIndentInfo, fileText: CharSequence): Boolean {
        val lineText = fileText.subSequence(info.startOffset + info.totalIndent, fileText.length)
        return ignoreLinesStartingWith.matcher(lineText).lookingAt()
    }

    /**
     * Consider code:
     * ```java
     * foo(argument1,
     *     argument2)
     * ```
     * Indentation of `argument2` is not a multiple of `indentSize`, but it is a valid continuation indent,
     * because it is aligned with the first character after the not closed `(`.
     */
    private fun isAlignedWithNotClosedLeftBracket(
        line: Int,
        spaces: Int,
        fileText: CharSequence,
        tabSize: Int,
    ): Boolean {
        var bracketsBalance = 0
        var previousLine = line
        var scannedLines = 0
        // This is a heuristic: brackets inside strings and comments are taken into account as well,
        // but an indentation that exactly matches such a bracket is unlikely
        while (previousLine > 0 && scannedLines < MAX_LOOKBACK_LINES) {
            --previousLine
            ++scannedLines
            val lineStart = document.getLineStartOffset(previousLine)
            val lineEnd = document.getLineEndOffset(previousLine)
            for (offset in lineEnd - 1 downTo lineStart) {
                when (fileText[offset]) {
                    ')' -> ++bracketsBalance
                    '(' -> if (bracketsBalance == 0) {
                        if (spaces == visualColumn(fileText, lineStart, offset, tabSize) + 1) return true
                    } else {
                        --bracketsBalance
                    }
                }
            }
        }
        return false
    }

    /** Returns column of [offset] with tabs expanded to [tabSize]. */
    private fun visualColumn(fileText: CharSequence, lineStart: Int, offset: Int, tabSize: Int): Int {
        var column = 0
        for (index in lineStart until offset) {
            column = if (fileText[index] == '\t') (column / tabSize + 1) * tabSize else column + 1
        }
        return column
    }

    private fun fillIndentsOnEmptyLines(indents: IntArray) {
        fun isEmptyLine(line: Int): Boolean =
            indents[line] == 0 && document.getLineStartOffset(line) == document.getLineEndOffset(line)

        for (lineStart in 1..indents.size - 2) {
            val prev = indents[lineStart - 1]
            if (!isEmptyLine(lineStart) || prev <= 0) continue
            var lineEnd = lineStart + 1
            while (lineEnd < indents.size && isEmptyLine(lineEnd)) {
                ++lineEnd
            }
            val next = indents.getOrElse(lineEnd) { 0 }
            if (next > 0) {
                for (line in lineStart until lineEnd) {
                    indents[line] = min(prev, next)
                }
            }
        }
    }

    private fun getTabsAndSpaces(): Array<LineIndentInfo> =
        Array(document.lineCount) { line -> document.getNumberTabsAndSpaces(line) }
}

/** How many previous lines can be scanned when looking for a not closed left bracket. */
private const val MAX_LOOKBACK_LINES = 100

/** [totalIndent] can be greater then [tabs] + [spaces] if line contains mixed tabs/spaces */
private data class LineIndentInfo(
    val startOffset: Int,
    val tabs: Int,
    val spaces: Int,
    val totalIndent: Int,
)

private fun Document.getNumberTabsAndSpaces(line: Int): LineIndentInfo {
    val lineStart = getLineStartOffset(line)
    val fileText = charsSequence

    val numberTabs = fileText.getNumberCharsFrom(lineStart, '\t')
    val numberSpaces = fileText.getNumberCharsFrom(lineStart + numberTabs, ' ')
    val totalIndent = CharArrayUtil.shiftForward(fileText, lineStart + numberTabs + numberSpaces, " \t") - lineStart
    return LineIndentInfo(lineStart, numberTabs, numberSpaces, totalIndent)
}

private fun CharSequence.getNumberCharsFrom(start: Int, char: Char): Int {
    var offset = start
    while (offset < length && this[offset] == char) {
        ++offset
    }
    return offset - start
}
