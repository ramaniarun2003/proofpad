package com.mathnotes.solver.ui

/**
 * The line under the problem on Review that says where the capture was split into problem and
 * steps. [splitAt] is the backend's `split_at`: "=", a symbol ("⇒", "∴", "→"), a word ("so",
 * "is", "converges") or "line". Null when there was no split, so nothing is shown.
 */
fun splitCaption(splitAt: String): String? {
    val where = when {
        splitAt.isBlank() -> return null
        splitAt == "=" -> "at the = sign"
        splitAt == "line" -> "at the line break"
        splitAt.any { it.isLetter() } -> "at \"$splitAt\""
        else -> "at the $splitAt"
    }
    return "I split this $where; fix it below if I got it wrong"
}

const val PARSE_ERROR_NOTE = "I could not parse this, fix the LaTeX and try again"
