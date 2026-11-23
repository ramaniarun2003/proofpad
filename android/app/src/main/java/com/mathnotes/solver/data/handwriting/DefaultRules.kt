package com.mathnotes.solver.data.handwriting

/**
 * The tiny built-in correction that applies for everyone until they have their own samples.
 * Kept as one pure function so it is easy to tune and unit-test (CorrectionMapTest).
 */
object DefaultRules {

    /**
     * Rewrite `\times` → `x` only when it sits directly between two single-letter/digit tokens
     * with no spaces (e.g. `a\times3`), AND the recognizer was unsure ([confidence] < 0.9) AND
     * the result did not parse ([parses] == false). Anything else is left untouched — a spaced
     * `2\times x` is a real product and stays.
     *
     * The real "does it parse" check is SymPy on the backend; on-device we pass [looksParseable]
     * as a cheap proxy so the backend can stay stateless.
     */
    fun correctTimes(latex: String, confidence: Double, parses: Boolean): String {
        if (confidence >= 0.9 || parses) return latex
        return AMBIGUOUS_TIMES.replace(latex, "x")
    }

    private val AMBIGUOUS_TIMES = Regex("""(?<=[A-Za-z0-9])\\times(?=[A-Za-z0-9])""")

    /**
     * On-device stand-in for "fails to parse in SymPy": treat an expression as un-parseable when a
     * `\times` is wedged directly between two alphanumerics, which is almost always a misread `x`.
     */
    fun looksParseable(latex: String): Boolean = !AMBIGUOUS_TIMES.containsMatchIn(latex)
}
