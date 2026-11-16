package indent.rainbow

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.StdFileTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import indent.rainbow.highlightingPass.LineIndents
import indent.rainbow.highlightingPass.LineIndentsCalculator

class IrTest : BasePlatformTestCase() {

    fun testBasic() {
        // TODO
        //  We should use LightJavaCodeInsightFixtureTestCase,
        //  Because Java-related functionality is not available in BasePlatformTestCase
        //  But I can't find how to add dependency on com.intellij.modules.java only for tests
        if (Language.findLanguageByID("JAVA") == null) return


        val content = """
            public class Foo {
            
                void foo(int x,
                         int y) {}  // line 3
            
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText(StdFileTypes.JAVA, content)

        val lineStart = 3

        val indentHelper = IrFormatterIndentHelper.getInstance(psiFile)!!
        val (indent, alignment) = indentHelper.getIndentAndAlignment(lineStart)!!

        val useTabs = indentHelper.indentOptions.USE_TAB_CHARACTER
        if (useTabs) {
            assert(indent == 1)
            assert(alignment == 9)
        } else {
            assert(indent == 4)
            assert(alignment == 9)
        }
    }

    fun testContinuationLineAlignedWithNotClosedLeftBracket() {
        val indents = computeIndentLevels(
            """
                public class Foo {
                    void foo(int x,
                             int y) {}
                }
            """.trimIndent()
        ) ?: return

        // indent of `int y` is aligned with the character after `(` in `void foo(`, so it is not an error
        val bracketColumn = 4 + "void foo".length
        assertEquals((bracketColumn + 1) / indents.indentSize, indents.levels[2])
    }

    fun testContinuationLineNotAlignedWithNotClosedLeftBracket() {
        val indents = computeIndentLevels(
            """
                public class Foo {
                    void foo(int x,
                               int y) {}
                }
            """.trimIndent()
        ) ?: return

        // indent of `int y` is neither a multiple of indentSize nor aligned with `(`, so it is an error
        assertEquals(-1, indents.levels[2])
    }

    fun testContinuationLinesAlignedWithNotClosedLeftBracketOnEarlierLine() {
        val indents = computeIndentLevels(
            """
                public class Foo {
                    void foo(int a,
                             int b,
                             int c) {}
                }
            """.trimIndent()
        ) ?: return

        // for `int c` the previous line has no brackets, so the lookup goes two lines up
        val expectedLevel = (4 + "void foo".length + 1) / indents.indentSize
        assertEquals(expectedLevel, indents.levels[2])
        assertEquals(expectedLevel, indents.levels[3])
    }

    fun testIndentInsideClosedBracketsIsError() {
        val indents = computeIndentLevels(
            """
                public class Foo {
                    void foo() {
                        bar(1);
                           baz();
                    }
                }
            """.trimIndent()
        ) ?: return

        // brackets of `bar(1)` are closed, so there is no left bracket to align with
        assertEquals(-1, indents.levels[3])
    }

    /** Returns null if the project uses tabs for indentation. */
    private fun computeIndentLevels(content: String): LineIndents? {
        val psiFile = myFixture.configureByText(StdFileTypes.PLAIN_TEXT, content)
        if (CodeStyle.getIndentOptions(psiFile).USE_TAB_CHARACTER) return null
        val document = psiFile.document ?: return null
        return LineIndentsCalculator(psiFile, document).compute()
    }
}
