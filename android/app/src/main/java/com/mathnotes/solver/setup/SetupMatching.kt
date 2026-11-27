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

    /** 0..1: how alike two lines are, by shared tokens (order-insensitive, multiset). */
    fun similarity(expected: String, recognized: String): Double {
        val e = tokens(expected).map(::fold)
        val r = tokens(recognized).map(::fold)
        if (e.isEmpty() || r.isEmpty()) return 0.0
        val pool = e.toMutableList()
        var shared = 0
        for (t in r) if (pool.remove(t)) shared++
        return 2.0 * shared / (e.size + r.size)
    }

    /**
     * Which recognized line belongs to which expected line. Both lists are top to bottom, so the
     * pairing keeps that order; it uses as many pairs as possible and, among those, the pairing
     * with the highest total [similarity]. Returns, per expected line, the index of its recognized
     * line or null when the page read has no line for it (the one to retake). Extra recognized
     * lines (a second set of lines on the page, a stray mark) are left out.
     */
    fun align(expected: List<String>, recognized: List<String>): List<Int?> {
        val n = expected.size
        val m = recognized.size
        val pairs = minOf(n, m)
        // From (i, j) onward: the most pairs possible, and the best total similarity among those.
        val count = Array(n + 1) { IntArray(m + 1) }
        val total = Array(n + 1) { DoubleArray(m + 1) }
        val take = Array(n + 1) { BooleanArray(m + 1) }
        fun better(c1: Int, t1: Double, c2: Int, t2: Double) = c1 > c2 || (c1 == c2 && t1 > t2 + 1e-9)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            // Skip an expected line, skip a recognized line, or pair them.
            var c = count[i + 1][j]; var t = total[i + 1][j]; var tk = false
            if (better(count[i][j + 1], total[i][j + 1], c, t)) { c = count[i][j + 1]; t = total[i][j + 1] }
            val pc = count[i + 1][j + 1] + 1
            val pt = total[i + 1][j + 1] + similarity(expected[i], recognized[j])
            if (better(pc, pt, c, t) || (pc == c && kotlin.math.abs(pt - t) <= 1e-9)) { c = pc; t = pt; tk = true }
            count[i][j] = c; total[i][j] = t; take[i][j] = tk
        }
        val out = MutableList<Int?>(n) { null }
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (take[i][j]) { out[i] = j; i++; j++ }
            else if (count[i + 1][j] == count[i][j] && kotlin.math.abs(total[i + 1][j] - total[i][j]) <= 1e-9) i++
            else j++
        }
        check(out.count { it != null } <= pairs)
        return out
    }

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
