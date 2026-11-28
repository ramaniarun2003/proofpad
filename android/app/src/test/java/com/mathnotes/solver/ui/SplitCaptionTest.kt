package com.mathnotes.solver.ui

import com.mathnotes.solver.data.api.Claim
import com.mathnotes.solver.data.api.RecognizeResponse
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.VerifyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review's caption names what the capture was actually split at, and says nothing without a split. */
class SplitCaptionTest {

    @Test fun no_split_says_nothing() {
        assertNull(splitCaption(""))
    }

    @Test fun equals_sign_and_arrow() {
        assertEquals("I split this at the = sign; fix it below if I got it wrong", splitCaption("="))
        assertEquals("I split this at the ⇒; fix it below if I got it wrong", splitCaption("⇒"))
        assertEquals("I split this at the ∴; fix it below if I got it wrong", splitCaption("∴"))
    }

    @Test fun words_are_quoted_and_a_line_break_is_named() {
        assertEquals("I split this at \"so\"; fix it below if I got it wrong", splitCaption("so"))
        assertEquals("I split this at the line break; fix it below if I got it wrong", splitCaption("line"))
    }

    @Test fun only_the_two_tokens_are_claims() {
        assertTrue(Claim.isClaim("CONVERGES"))
        assertTrue(Claim.isClaim(" DIVERGES "))
        assertFalse(Claim.isClaim("converges"))
        assertFalse(Claim.isClaim("2x"))
        assertFalse(Claim.isClaim(""))
    }

    @Test fun recognize_response_reads_the_claim_split_and_parse_error() {
        val adapter = ApiClient.moshi.adapter(RecognizeResponse::class.java)
        val split = adapter.fromJson(
            """{"session_id":"s","latex":"x","confidence":0.9,"topic_guess":"series","low_confidence":false,
               "problem_latex":"\\sum_{n=1}^{\\infty} \\frac{1}{n^{2}}","steps":["CONVERGES"],"split_at":"⇒","parse_error":null}"""
        )!!
        assertEquals(listOf("CONVERGES"), split.steps)
        assertEquals("⇒", split.splitAt)
        assertNull(split.parseError)

        val failed = adapter.fromJson(
            """{"session_id":"s","latex":"x","confidence":0.9,"topic_guess":"unknown","low_confidence":false,
               "problem_latex":"x","steps":[],"parse_error":"could not parse"}"""
        )!!
        assertEquals("could not parse", failed.parseError)
        assertEquals("", failed.splitAt)
    }

    @Test fun verify_response_reads_our_test_steps() {
        val resp = ApiClient.moshi.adapter(VerifyResponse::class.java).fromJson(
            """{"verified":false,"mismatch_at":0,"verdict":"mismatch",
               "steps":[{"latex":"\\text{converges}","status":"wrong","rule":"","hint":"our p-series test says it diverges"}],
               "solution_steps":[{"index":0,"rule":"p-series","before_latex":"a","after_latex":"diverges","note":"p = 1"}]}"""
        )!!
        assertEquals("p-series", resp.solutionSteps.single().rule)
        assertEquals("our p-series test says it diverges", resp.steps.single().hint)
    }

    @Test fun recognize_response_reads_every_problem_of_a_page() {
        val resp = ApiClient.moshi.adapter(RecognizeResponse::class.java).fromJson(
            """{"session_id":"s","latex":"x","confidence":0.9,"topic_guess":"series","low_confidence":false,
               "problem_latex":"b","steps":["CONVERGES"],"problem_index":1,
               "problems":[{"problem_latex":"a","steps":[],"topic_guess":"unknown","split_uncertain":true},
                           {"problem_latex":"b","steps":["CONVERGES"],"split_at":"⇒","topic_guess":"series","bbox":[1,2,3,4]}]}"""
        )!!
        assertEquals(listOf("a", "b"), resp.problems.map { it.problemLatex })
        assertEquals(1, resp.problemIndex)
        assertEquals("series", resp.problems[1].topicGuess)
        assertEquals(listOf(1, 2, 3, 4), resp.problems[1].bbox)
        // An older backend sends no list: the single problem is the top-level one.
        val old = ApiClient.moshi.adapter(RecognizeResponse::class.java).fromJson(
            """{"session_id":"s","latex":"x","confidence":0.9,"topic_guess":"series","low_confidence":false}"""
        )!!
        assertTrue(old.problems.isEmpty())
    }

    @Test fun too_complex_gets_its_own_note_and_server_text_is_never_shown() {
        assertEquals(TOO_COMPLEX_NOTE, parseNote("""{"detail":"this one is too complex for me"}"""))
        assertEquals(TOO_COMPLEX_NOTE, parseNote("this one is too complex for me"))
        assertEquals(PARSE_ERROR_NOTE, parseNote("""{"detail":"could not parse '\\frac{d}{dx}(': Invalid LaTeX"}"""))
        assertEquals(PARSE_ERROR_NOTE, parseNote(null))
    }

    @Test fun recognition_failures_become_plain_sentences() {
        assertEquals(CANT_REACH_NOTE, recognizeFailureNote(java.net.SocketTimeoutException("timeout")))
        assertEquals(CANT_REACH_NOTE, recognizeFailureNote(java.net.ConnectException("Failed to connect to /10.0.2.2:8000")))
        assertEquals(SOLVER_TROUBLE_NOTE, recognizeFailureNote(IllegalStateException("Expected BEGIN_OBJECT but was STRING")))
        listOf(CANT_REACH_NOTE, SOLVER_TROUBLE_NOTE, PHOTO_TOO_LARGE_NOTE).forEach {
            assertFalse(it.contains("{") || it.contains("Exception") || it.contains("http"))
        }
    }

    @Test fun no_failure_message_carries_exception_text_or_a_response_body() {
        fun http(code: Int, body: String) = retrofit2.HttpException(
            retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, body)),
        )
        val failures = listOf(
            java.net.UnknownHostException("Unable to resolve host \"192.168.40.9\""),
            http(422, """{"detail":"could not parse 'x(': Invalid LaTeX"}"""),
            http(500, "Internal Server Error"),
            http(503, """{"detail":"recognition unavailable: Mathpix returned 429: {\"error\":\"quota\"}"}"""),
            http(401, """{"detail":"invalid or missing X-ProofPad-Key"}"""),
            IllegalStateException("java.lang.NullPointerException at Preprocessor.decodeUpright"),
        )
        failures.forEach { e ->
            listOf(failureNote(e), recognizeFailureNote(e)).forEach { note ->
                listOf("{", "detail", "Exception", "Mathpix", "192.168", "HTTP", "500", "503", "422", "backend").forEach { leak ->
                    assertFalse("\"$note\" leaks \"$leak\"", note.contains(leak))
                }
                assertTrue(note.length in 20..120)
            }
        }
        assertEquals(PARSE_ERROR_NOTE, failureNote(http(422, "x")))
        // A blank photo (the recognizer found nothing) asks for a retake, not a wait.
        assertEquals(NOTHING_TO_READ_NOTE, recognizeFailureNote(http(422, """{"detail":"no writing found in the photo"}""")))
        assertEquals(READER_DOWN_NOTE, recognizeFailureNote(http(503, "x")))
    }
}
