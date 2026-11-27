package com.mathnotes.solver.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing the page read with the five expected wizard lines. The recognized lists are the real
 * backend results for the three notebook-page captures of 2026-11-27 (see the backend fixtures
 * `wizard_page_{1,2,3}`).
 */
class SetupAlignTest {

    private val expected = listOf(
        """\int x^2 \, dx""",
        """\frac{d}{dx} \sin(3x)""",
        """\frac{e^x}{x+1}""",
        """\sum \frac{1}{n^2}""",
        """\lim_{x \to 0} \ln(1+x)""",
    )

    @Test fun five_lines_map_one_to_one() {
        val read = listOf(
            """\int x^{2} d x""", """\frac{d}{d x} \sin (3 x)""", """e^{x} / x+1""",
            """\sum \frac{1}{n^{2}}""", """\lim _{x \rightarrow 0} \ln (1+x)""",
        )
        assertEquals(listOf(0, 1, 2, 3, 4), SetupMatching.align(expected, read))
    }

    /** Page 1: Mathpix fused e^x/(x+1) into the sum line. Four lines; the sum row is the missing one. */
    @Test fun four_lines_mark_the_line_that_is_really_missing() {
        val read = listOf(
            """\int x^{2} d x""", """\frac{d}{d x} \sin (3 x)""", """\sum^{\frac{e^{x}}{x+1}}""",
            """\lim _{x \rightarrow 0} \ln (1+x)""",
        )
        assertEquals(listOf(0, 1, 2, null, 3), SetupMatching.align(expected, read))
    }

    /** Page 2: the photo starts at the tail of an upper set; that stray first line is left out. */
    @Test fun a_stray_leading_line_is_skipped() {
        val read = listOf(
            """\lim _{x \rightarrow 0} \ln (1+x)""",
            """\int x^{2} d x""", """d / d x \sin (3 x)""", """e^{x} / x+1""", """\sum 1 / n^{2}""",
            """\lim _{x \rightarrow 0} \ln (1+x)""",
        )
        assertEquals(listOf(1, 2, 3, 4, 5), SetupMatching.align(expected, read))
    }

    /** Page 3: the five lines written twice. One whole set is used, in order. */
    @Test fun two_sets_on_the_page_use_five_lines_in_order() {
        val set = listOf(
            """\int x^{2} d x""", """\frac{d}{d x} \sin (3 x)""", """e^{x} / x+1""",
            """\sum \frac{1}{n^{2}}""", """\lim _{x \rightarrow 0} \ln (1+x)""",
        )
        val slots = SetupMatching.align(expected, set + set)
        assertEquals(5, slots.filterNotNull().size)
        assertEquals(slots.filterNotNull().sorted(), slots.filterNotNull())           // top to bottom
        slots.forEachIndexed { i, at -> assertEquals(i, at!! % 5) }                     // each slot gets its own line
    }

    @Test fun a_missing_first_line_does_not_shift_the_rest() {
        val read = listOf(
            """\frac{d}{d x} \sin (3 x)""", """e^{x} / x+1""", """\sum \frac{1}{n^{2}}""",
            """\lim _{x \rightarrow 0} \ln (1+x)""",
        )
        assertEquals(listOf(null, 0, 1, 2, 3), SetupMatching.align(expected, read))
    }

    @Test fun nothing_read_means_every_line_is_missing() {
        assertEquals(listOf<Int?>(null, null, null, null, null), SetupMatching.align(expected, emptyList()))
    }

    @Test fun similarity_is_high_for_the_same_line_and_low_for_another() {
        assertTrue(SetupMatching.similarity(expected[0], """\int x^{2} d x""") > 0.8)
        assertTrue(SetupMatching.similarity(expected[0], """\lim _{x \rightarrow 0} \ln (1+x)""") < 0.4)
    }
}
