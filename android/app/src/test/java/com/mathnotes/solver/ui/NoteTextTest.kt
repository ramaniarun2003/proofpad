package com.mathnotes.solver.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Notes arrive as sentences with `$…$` math spans; only those spans are typeset. */
class NoteTextTest {

    @Test fun plain_note_is_one_text_part() {
        assertEquals(listOf(NotePart("multiply by the conjugate", false)), splitNote("multiply by the conjugate"))
    }

    @Test fun math_spans_are_separated_from_the_words() {
        assertEquals(
            listOf(
                NotePart("memorized limit: ", false),
                NotePart("""\lim_{x \to 0} \frac{\ln(1+x)}{x} = 1""", true),
            ),
            splitNote("""memorized limit: $\lim_{x \to 0} \frac{\ln(1+x)}{x} = 1$"""),
        )
    }

    @Test fun several_spans_in_one_sentence() {
        val d = "$"   // keep Kotlin from reading $x as a template
        val parts = splitNote("substitute ${d}x = 2$d; the expression is defined and equals ${d}5$d")
        assertEquals(listOf(false, true, false, true), parts.map { it.isMath })
        assertEquals(listOf("x = 2", "5"), parts.filter { it.isMath }.map { it.text })
    }

    @Test fun an_unmatched_dollar_stays_text() {
        assertEquals(listOf(NotePart("costs $5 today", false)), splitNote("costs $5 today"))
    }

    @Test fun slash_derivative_is_shown_as_a_fraction() {
        assertEquals("""\frac{d}{dx} x^{2} \sin x""", displayProblem("""d / d x x^{2} \sin x"""))
        assertEquals("""\frac{d}{dx} (x^2)""", displayProblem("d/dx(x^2)"))
        assertEquals("""\frac{\partial}{\partial x} \left(x^{2} y\right)""", displayProblem("""\partial / \partial x\left(x^{2} y\right)"""))
    }

    @Test fun other_problems_are_shown_as_written() {
        assertEquals("""\frac{d}{dx} x^2""", displayProblem("""\frac{d}{dx} x^2"""))
        assertEquals("""\int 2x \, dx""", displayProblem("""\int 2x \, dx"""))
        assertEquals("""\sum_{n=1}^{\infty} \frac{1}{n^{2}}""", displayProblem("""\sum_{n=1}^{\infty} \frac{1}{n^{2}}"""))
        assertEquals("a d / b", displayProblem("a d / b"))
    }

    @Test fun data_summary_counts_in_plain_words() {
        assertEquals("3 notes, 1 folder and 5 handwriting samples.", dataSummary(3, 1, 5))
        assertEquals("1 note, 0 folders and 1 handwriting sample.", dataSummary(1, 0, 1))
        assertEquals("0 notes, 0 folders and 0 handwriting samples.", dataSummary(0, 0, 0))
    }
}
