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
const val TOO_COMPLEX_NOTE = "This one is too complex for me. Try a shorter or simpler form."

const val CANT_REACH_NOTE = "I can't reach the solver. Check the connection, then try again. Your photo is kept."
const val SOLVER_TROUBLE_NOTE = "The solver had trouble reading this one. Try again. Your photo is kept."
const val PHOTO_TOO_LARGE_NOTE = "That photo is too large to send. Retake it a little closer."

/** What Review says when recognition failed; never the exception text or a response body. */
fun recognizeFailureNote(e: Throwable): String = when {
    e is java.io.IOException -> CANT_REACH_NOTE
    e is retrofit2.HttpException && e.code() == 413 -> PHOTO_TOO_LARGE_NOTE
    e is retrofit2.HttpException && e.code() == 503 -> READER_DOWN_NOTE
    e is retrofit2.HttpException && e.code() == 422 -> NOTHING_TO_READ_NOTE
    else -> SOLVER_TROUBLE_NOTE
}

const val NOTHING_TO_READ_NOTE = "I couldn't find any writing in that photo. Retake it with the problem inside the box."

const val READER_DOWN_NOTE = "I can't read handwriting right now. Try again in a minute. Your photo is kept."

/**
 * Every other failure (solve, check, full solution, the handwriting wizard) as one plain sentence
 * with what to do next. The exception's own message and the response body are for the log only.
 */
fun failureNote(e: Throwable): String = when {
    e is java.io.IOException -> "I can't reach the solver. Check the connection, then try again."
    e is retrofit2.HttpException -> when (e.code()) {
        413 -> PHOTO_TOO_LARGE_NOTE
        422 -> PARSE_ERROR_NOTE
        401, 403 -> "The solver didn't accept this app. Check the solver address in Settings."
        503 -> "I can't read handwriting right now. Try again in a minute."
        else -> "The solver had a problem. Try again."
    }
    else -> "Something went wrong. Try again."
}

/**
 * The yellow note Review shows for a problem the backend could not use. [serverText] is the
 * backend's `parse_error` or a 422 body; it is only inspected, never shown.
 */
fun parseNote(serverText: String?): String =
    if (serverText?.contains("too complex", ignoreCase = true) == true) TOO_COMPLEX_NOTE else PARSE_ERROR_NOTE
