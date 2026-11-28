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
}
