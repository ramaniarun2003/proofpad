package com.mathnotes.solver.setup

/**
 * Pure (Android-free) helpers for the handwriting setup wizard: splitting a
 * multi-line recognizer result into lines, normalizing LaTeX for a lenient
 * "did I read this right?" comparison, and diffing a mismatch down to the
 * token that changed. Unit-tested in SetupMatchingTest.
 */
object SetupMatching {

    /** Split a multi-line recognizer result on LaTeX row breaks (`\\`) or newlines. */
    fun splitLines(latex: String): List<String> =
        latex.split(Regex("""\\\\|\r?\n""")).map { it.trim() }.filter { it.isNotEmpty() }

    private val FRAC_DDX = Regex("""\\frac\s*\{\s*d\s*\}\s*\{\s*d\s*([a-zA-Z])\s*\}""")

    /**
     * Canonicalize LaTeX for comparison: drop whitespace, `\,`, `\left`/`\right` and
     * braces; fold `\frac{d}{dx}` and `d/dx` to one form. `\cdot`/`\times` map to `*`,
     * which is deliberately *not* equal to the variable `x`.
     */
    fun normalize(latex: String): String {
        var s = latex
        s = FRAC_DDX.replace(s) { "d/d${it.groupValues[1]}" }
        s = s.replace("""\cdot""", "*").replace("""\times""", "*")
        s = s.replace("""\left""", "").replace("""\right""", "").replace("""\,""", "")
        s = s.replace("{", "").replace("}", "")
        s = s.replace(Regex("""\s+"""), "")
        return s
    }

    /** True when the recognized LaTeX reads the same as the expected, under [normalize]. */
    fun matches(expected: String, recognized: String): Boolean =
        normalize(expected) == normalize(recognized)

    // --- Token-level diff (for the mismatch highlight and the "sometimes read as" lines) ---

    private val TOKEN = Regex("""\\[a-zA-Z]+|d/d[a-zA-Z]|[^\s]""")

    /** Canonical form that keeps `\cdot`/`\times` as tokens (so we can name them) but folds the rest. */
    private fun canonical(latex: String): String {
        var s = latex
        s = FRAC_DDX.replace(s) { "d/d${it.groupValues[1]}" }
        s = s.replace("""\left""", "").replace("""\right""", "").replace("""\,""", "")
        s = s.replace("{", "").replace("}", "")
        return s
    }

    private fun tokens(latex: String): List<String> = TOKEN.findAll(canonical(latex)).map { it.value }.toList()

    /** Two tokens are "the same" if they match after folding multiplication dots. */
    private fun sameToken(a: String, b: String): Boolean = fold(a) == fold(b)
    private fun fold(t: String): String = if (t == "\\cdot" || t == "\\times") "*" else t

    /** The first recognized token that differs from the expected chain, or null if they align. */
    fun firstDiffToken(expected: String, recognized: String): String? {
        val e = tokens(expected)
        val r = tokens(recognized)
        for (i in r.indices) {
            if (i >= e.size || !sameToken(e[i], r[i])) return display(r[i])
        }
        return if (r.size < e.size) "" else null
    }

    /**
     * Up to [max] distinct "x is sometimes read as ×, I'll fix that" lines, built from the
     * first differing (expected → recognized) token of each mismatched sample.
     */
    fun correctionLines(pairs: List<Pair<String, String>>, max: Int = 3): List<String> {
        val lines = LinkedHashSet<String>()
        for ((expected, recognized) in pairs) {
            val e = tokens(expected)
            val r = tokens(recognized)
            val i = (0 until maxOf(e.size, r.size)).firstOrNull { idx ->
                idx >= e.size || idx >= r.size || !sameToken(e[idx], r[idx])
            } ?: continue
            val exp = e.getOrNull(i)?.let(::display) ?: continue
            val rec = r.getOrNull(i)?.let(::display) ?: continue
            if (exp.isNotBlank() && rec.isNotBlank() && exp != rec) {
                lines += "$exp is sometimes read as $rec, I'll fix that"
            }
            if (lines.size >= max) break
        }
        return lines.toList()
    }

    /** A readable glyph for a LaTeX token, for the correction lines and the highlight chip. */
    fun display(token: String): String = when (token) {
        "\\times" -> "×"
        "\\cdot" -> "·"
        "\\int" -> "∫"
        "\\sum" -> "Σ"
        "\\lim" -> "lim"
        "\\ln" -> "ln"
        "\\sin" -> "sin"
        "\\cos" -> "cos"
        "\\tan" -> "tan"
        "\\to" -> "→"
        "\\frac" -> "fraction"
        else -> token.removePrefix("\\")
    }
}
