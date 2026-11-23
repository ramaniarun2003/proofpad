package com.mathnotes.solver.data.handwriting

import com.mathnotes.solver.data.db.HandwritingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the per-user correction map and the default `\times` heuristic. */
class CorrectionMapTest {

    private fun rule(map: CorrectionMap, from: String, ctx: String?) =
        map.rules.firstOrNull { it.fromToken == from && it.context == ctx }

    // --- Alignment on the five sample lines, one injected recognizer error each ---

    @Test fun derives_one_rule_per_injected_error() {
        val map = CorrectionMap.build(
            listOf(
                // (recognized, truth)
                """\int x^z \, dx""" to """\int x^2 \, dx""",            // z -> 2 after a power
                """\sum \frac{1}{n^z}""" to """\sum \frac{1}{n^2}""",    // z -> 2 after a power (again)
                """\frac{e^x}{x+l}""" to """\frac{e^x}{x+1}""",          // l -> 1 anywhere
                """\frac{d}{dx} \sin(3\times)""" to """\frac{d}{dx} \sin(3x)""", // \times -> x anywhere
                """\lim_{x \to 0} \ln(1+x)""" to """\lim_{x \to 0} \ln(1+x)""",  // read correctly
            ),
        )

        val z = rule(map, "z", "^")!!
        assertEquals("2", z.toToken)
        assertEquals(2, z.support)
        assertEquals(RuleMode.APPLY, z.mode)

        val l = rule(map, "l", null)!!
        assertEquals("1", l.toToken)
        assertEquals(RuleMode.APPLY, l.mode)

        val times = rule(map, "\\times", null)!!
        assertEquals("x", times.toToken)
        assertEquals(RuleMode.APPLY, times.mode)

        // The correctly-read line adds no rule.
        assertEquals(3, map.rules.size)
    }

    @Test fun applies_rules_preserving_surrounding_latex() {
        val map = CorrectionMap.build(listOf("""\frac{e^x}{\times+1}""" to """\frac{e^x}{x+1}"""))
        val out = map.applyTo("""\frac{a^b}{\times+1}""")
        assertEquals("""\frac{a^b}{x+1}""", out.latex)
        assertEquals(1, out.rewrites.size)
    }

    // --- Contradiction handling ---

    @Test fun a_token_read_correctly_elsewhere_makes_the_rule_a_suggestion() {
        val map = CorrectionMap.build(
            listOf(
                """a^z""" to """a^2""",   // z -> 2 after a power
                """b^z""" to """b^z""",   // but z after a power was read correctly here
            ),
        )
        val z = rule(map, "z", "^")!!
        assertEquals(RuleMode.SUGGEST, z.mode)
        // SUGGEST rules are not auto-applied.
        assertEquals("""c^z""", map.applyTo("""c^z""").latex)
    }

    // --- Context: a rule learned after ^ only fires after ^ ---

    @Test fun context_rule_only_fires_after_a_power() {
        val map = CorrectionMap.build(listOf("""x^z""" to """x^2"""))
        val z = rule(map, "z", "^")!!
        assertEquals("^", z.context)
        // Only the z that follows ^ is rewritten; the leading z is left alone.
        assertEquals("""z^2""", map.applyTo("""z^z""").latex)
        assertEquals("z", map.applyTo("z").latex)
    }

    @Test fun builds_truth_from_correction_then_expected() {
        val map = CorrectionMap.fromSamples(
            listOf(
                HandwritingSample(expectedLatex = """x+1""", recognizedLatex = """x+l""", correctedLatex = """x+1"""),
                HandwritingSample(expectedLatex = """n^2""", recognizedLatex = """n^z""", correctedLatex = null),
            ),
        )
        assertEquals("1", rule(map, "l", null)?.toToken)
        assertEquals("2", rule(map, "z", "^")?.toToken)
    }

    @Test fun phrasings_read_in_plain_words() {
        val map = CorrectionMap.build(
            listOf(
                """a^z""" to """a^2""",
                """\times y""" to """x y""",
            ),
        )
        val lines = map.phrasings(3)
        assertTrue(lines.contains("2 after a power read as z, I'll fix that"))
        assertTrue(lines.contains("x is sometimes read as ×, I'll fix that"))
    }

    // --- Default `\times` heuristic (part 3) ---

    @Test fun default_times_rewrites_only_when_adjacent_low_confidence_and_unparsed() {
        // Adjacent alphanumerics, unsure, didn't parse → rewrite.
        assertEquals("ax3", DefaultRules.correctTimes("""a\times3""", 0.8, parses = false))
        // A spaced product is real → leave it.
        assertEquals("""2\times x""", DefaultRules.correctTimes("""2\times x""", 0.8, parses = false))
        // High confidence → leave it.
        assertEquals("""a\times3""", DefaultRules.correctTimes("""a\times3""", 0.95, parses = false))
        // It parsed → leave it.
        assertEquals("""a\times3""", DefaultRules.correctTimes("""a\times3""", 0.8, parses = true))
    }

    @Test fun looks_parseable_proxy_flags_wedged_times() {
        assertTrue(DefaultRules.looksParseable("""2\times x"""))
        assertTrue(DefaultRules.looksParseable("""x+1"""))
        assertEquals(false, DefaultRules.looksParseable("""a\times3"""))
    }

    @Test fun no_rules_when_everything_read_correctly() {
        val map = CorrectionMap.build(listOf("""\int x^2 \, dx""" to """\int x^2 \, dx"""))
        assertTrue(map.rules.isEmpty())
        assertTrue(map.phrasings().isEmpty())
        assertNull(rule(map, "x", null))
    }
}
