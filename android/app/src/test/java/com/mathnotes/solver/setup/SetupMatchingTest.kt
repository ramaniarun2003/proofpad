package com.mathnotes.solver.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the wizard's line splitter and comparison normalization. */
class SetupMatchingTest {

    // --- splitLines ---

    @Test fun splits_on_latex_row_breaks() {
        val latex = """\int x^2 \, dx \\ \frac{d}{dx}\sin(3x) \\ \frac{e^x}{x+1}"""
        assertEquals(
            listOf("""\int x^2 \, dx""", """\frac{d}{dx}\sin(3x)""", """\frac{e^x}{x+1}"""),
            SetupMatching.splitLines(latex),
        )
    }

    @Test fun splits_on_newlines_and_drops_blank_lines() {
        val latex = "\\int x\n\n\\sum \\frac{1}{n^2}\n"
        assertEquals(listOf("""\int x""", """\sum \frac{1}{n^2}"""), SetupMatching.splitLines(latex))
    }

    @Test fun single_line_returns_one_entry() {
        assertEquals(listOf("""\lim_{x\to0}\ln(1+x)"""), SetupMatching.splitLines("""\lim_{x\to0}\ln(1+x)"""))
    }

    // --- normalize / matches ---

    @Test fun whitespace_thinspace_and_braces_are_ignored() {
        assertTrue(SetupMatching.matches("""\int x^2 \, dx""", """\int  x^{2}\,dx"""))
    }

    @Test fun left_right_delimiters_are_ignored() {
        assertTrue(SetupMatching.matches("""\ln(1+x)""", """\ln\left(1+x\right)"""))
    }

    @Test fun frac_ddx_and_slash_form_are_equal() {
        assertTrue(SetupMatching.matches("""\frac{d}{dx}\sin(3x)""", """d/dx \sin(3x)"""))
    }

    @Test fun cdot_and_times_are_equal_to_each_other() {
        assertTrue(SetupMatching.matches("""2\cdot 3""", """2\times 3"""))
    }

    @Test fun times_is_not_equal_to_the_variable_x() {
        assertFalse(SetupMatching.matches("""2x""", """2\times"""))
        assertFalse(SetupMatching.matches("""a x b""", """a \cdot b"""))
    }

    @Test fun genuinely_different_expressions_do_not_match() {
        assertFalse(SetupMatching.matches("""\int x^2 \, dx""", """\int x^3 \, dx"""))
    }

    // --- firstDiffToken / correctionLines ---

    @Test fun first_diff_token_reports_the_misread_symbol() {
        // expected a variable x, recognizer saw a times sign.
        assertEquals("×", SetupMatching.firstDiffToken("""a x""", """a \times"""))
    }

    @Test fun no_diff_token_when_they_match() {
        assertNull(SetupMatching.firstDiffToken("""\int x^2 \, dx""", """\int x^{2}\,dx"""))
    }

    @Test fun correction_lines_are_distinct_and_capped() {
        val pairs = listOf(
            """a x""" to """a \times""",
            """b x""" to """b \times""",            // same confusion → deduped
            """\ln x""" to """\int x""",            // ln read as integral
        )
        val lines = SetupMatching.correctionLines(pairs, max = 3)
        assertEquals(listOf("x is sometimes read as ×, I'll fix that", "ln is sometimes read as ∫, I'll fix that"), lines)
    }
}
