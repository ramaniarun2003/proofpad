package com.mathnotes.solver.data.handwriting

import com.mathnotes.solver.data.db.HandwritingSample

/** Whether a derived rule is safe to apply automatically or should only be suggested. */
enum class RuleMode { APPLY, SUGGEST }

/**
 * One learned substitution: the recognizer reads [fromToken] but the user actually wrote
 * [toToken]. [context] is the preceding token when it is `^` or `_` (so "z→2 after a power"
 * is distinct from "z→2 anywhere"); null means anywhere. [support] counts how many samples
 * back the rule. [mode] is SUGGEST when the same token was read correctly elsewhere in the
 * same context (a contradiction), otherwise APPLY.
 */
data class CorrectionRule(
    val fromToken: String,
    val toToken: String,
    val context: String?,
    val support: Int,
    val mode: RuleMode,
)

/** A rewrite the map actually performed, for logging. */
data class Rewrite(val from: String, val to: String, val context: String?)

/** A suggested (not auto-applied) swap surfaced on the Review screen. */
data class CorrectionSuggestion(val from: String, val to: String, val context: String?) {
    val message: String get() =
        "I read ${CorrectionMap.display(from)} here; you usually write ${CorrectionMap.display(to)}. Tap to swap."
}

/** Result of applying the map to a recognizer string. */
data class ApplyResult(val latex: String, val rewrites: List<Rewrite>)

/**
 * A per-user LaTeX correction map derived from the handwriting setup samples, applied to
 * every recognizer result before it reaches /solve. Pure and Android-free (besides the
 * plain [HandwritingSample] data class) so it can be unit-tested; see CorrectionMapTest.
 */
class CorrectionMap(val rules: List<CorrectionRule>) {

    private val apply = rules.filter { it.mode == RuleMode.APPLY }
    private val suggest = rules.filter { it.mode == RuleMode.SUGGEST }

    /** Rewrite [latex] using the APPLY rules, returning the new string and every rewrite made. */
    fun applyTo(latex: String): ApplyResult {
        if (apply.isEmpty()) return ApplyResult(latex, emptyList())
        val toks = tokenizeSpans(latex)
        val rewrites = mutableListOf<Rewrite>()
        val edits = mutableListOf<Triple<Int, Int, String>>()   // start, end, replacement
        toks.forEachIndexed { i, tok ->
            val ctx = contextOf(toks.getOrNull(i - 1)?.text)
            val rule = bestRule(apply, tok.text, ctx)
            if (rule != null) {
                edits += Triple(tok.start, tok.end, rule.toToken)
                rewrites += Rewrite(rule.fromToken, rule.toToken, rule.context)
            }
        }
        // Apply right-to-left so earlier spans keep their offsets.
        val sb = StringBuilder(latex)
        for ((start, end, repl) in edits.sortedByDescending { it.first }) sb.replace(start, end, repl)
        return ApplyResult(sb.toString(), rewrites)
    }

    /** SUGGEST rules whose from-token actually appears (in the right context) in [latex]. */
    fun suggestionsFor(latex: String): List<CorrectionSuggestion> {
        if (suggest.isEmpty()) return emptyList()
        val toks = tokenizeSpans(latex)
        val found = LinkedHashSet<CorrectionSuggestion>()
        toks.forEachIndexed { i, tok ->
            val ctx = contextOf(toks.getOrNull(i - 1)?.text)
            bestRule(suggest, tok.text, ctx)?.let { found += CorrectionSuggestion(it.fromToken, it.toToken, it.context) }
        }
        return found.toList()
    }

    /** Up to [max] plain-word descriptions of the derived rules, strongest support first. */
    fun phrasings(max: Int = 3): List<String> =
        rules.sortedByDescending { it.support }.take(max).map { r ->
            val where = when (r.context) {
                "^" -> "${display(r.toToken)} after a power"
                "_" -> "${display(r.toToken)} in a subscript"
                else -> "${display(r.toToken)} is sometimes"
            }
            "$where read as ${display(r.fromToken)}, I'll fix that"
        }

    private fun bestRule(pool: List<CorrectionRule>, from: String, ctx: String?): CorrectionRule? {
        // Prefer a context-specific rule when the context matches; fall back to an anywhere rule.
        pool.firstOrNull { it.fromToken == from && it.context != null && it.context == ctx }?.let { return it }
        return pool.firstOrNull { it.fromToken == from && it.context == null }
    }

    companion object {
        private val TOKEN = Regex("""\\[a-zA-Z]+|\\.|[A-Za-z]|[0-9]|[{}]|[^\s]""")

        data class Tok(val text: String, val start: Int, val end: Int)

        fun tokenizeSpans(latex: String): List<Tok> =
            TOKEN.findAll(latex).map { Tok(it.value, it.range.first, it.range.last + 1) }.toList()

        fun tokenize(latex: String): List<String> = tokenizeSpans(latex).map { it.text }

        private fun contextOf(prev: String?): String? = if (prev == "^" || prev == "_") prev else null

        // --- Alignment ---

        enum class EditType { EQUAL, SUB, DEL, INS }
        data class Edit(val type: EditType, val a: String?, val b: String?)

        /** LCS alignment of recognized tokens [a] against truth tokens [b], with DEL+INS folded to SUB. */
        fun align(a: List<String>, b: List<String>): List<Edit> {
            val m = a.size
            val n = b.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in m - 1 downTo 0) for (j in n - 1 downTo 0)
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            val raw = mutableListOf<Edit>()
            var i = 0
            var j = 0
            while (i < m && j < n) {
                when {
                    a[i] == b[j] -> { raw += Edit(EditType.EQUAL, a[i], b[j]); i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> { raw += Edit(EditType.DEL, a[i], null); i++ }
                    else -> { raw += Edit(EditType.INS, null, b[j]); j++ }
                }
            }
            while (i < m) { raw += Edit(EditType.DEL, a[i], null); i++ }
            while (j < n) { raw += Edit(EditType.INS, null, b[j]); j++ }
            val out = mutableListOf<Edit>()
            var k = 0
            while (k < raw.size) {
                val cur = raw[k]
                val nxt = raw.getOrNull(k + 1)
                when {
                    cur.type == EditType.DEL && nxt?.type == EditType.INS -> { out += Edit(EditType.SUB, cur.a, nxt.b); k += 2 }
                    cur.type == EditType.INS && nxt?.type == EditType.DEL -> { out += Edit(EditType.SUB, nxt.a, cur.b); k += 2 }
                    else -> { out += cur; k++ }
                }
            }
            return out
        }

        /** Build a map from handwriting samples; truth = correction if present, else the expected line. */
        fun fromSamples(samples: List<HandwritingSample>): CorrectionMap =
            build(samples.map { it.recognizedLatex to (it.correctedLatex ?: it.expectedLatex) })

        /** Build from (recognized, truth) pairs. The core algorithm, exercised directly by tests. */
        fun build(pairs: List<Pair<String, String>>): CorrectionMap {
            // All observations of (recognizedToken, context) → truthToken, across every sample.
            val observed = HashMap<Pair<String, String?>, MutableList<String>>()
            // Support for concrete substitutions (from != to).
            val support = HashMap<Triple<String, String, String?>, Int>()

            for ((recognized, truth) in pairs) {
                val edits = align(tokenize(recognized), tokenize(truth))
                var prev: String? = null
                for (e in edits) {
                    when (e.type) {
                        EditType.EQUAL -> {
                            val ctx = contextOf(prev)
                            observed.getOrPut(e.a!! to ctx) { mutableListOf() } += e.a
                            prev = e.a
                        }
                        EditType.SUB -> {
                            val ctx = contextOf(prev)
                            observed.getOrPut(e.a!! to ctx) { mutableListOf() } += e.b!!
                            support.merge(Triple(e.a, e.b, ctx), 1, Int::plus)
                            prev = e.a
                        }
                        EditType.DEL -> prev = e.a
                        EditType.INS -> { /* truth-only token; recognized context unchanged */ }
                    }
                }
            }

            val rules = support.entries.map { (key, count) ->
                val (from, to, ctx) = key
                val tos = observed[from to ctx].orEmpty()
                val contradicted = tos.any { it != to }   // read correctly, or mapped elsewhere
                CorrectionRule(from, to, ctx, count, if (contradicted) RuleMode.SUGGEST else RuleMode.APPLY)
            }.sortedWith(compareByDescending<CorrectionRule> { it.support }.thenBy { it.fromToken })
            return CorrectionMap(rules)
        }

        /** A readable glyph for a token, for suggestions and the done-screen phrasings. */
        fun display(token: String): String = when (token) {
            "\\times" -> "×"
            "\\cdot" -> "·"
            "\\int" -> "∫"
            "\\sum" -> "Σ"
            "\\lim" -> "lim"
            "\\ln" -> "ln"
            "\\sin" -> "sin"
            "\\cos" -> "cos"
            "\\to" -> "→"
            else -> token.removePrefix("\\")
        }
    }
}
