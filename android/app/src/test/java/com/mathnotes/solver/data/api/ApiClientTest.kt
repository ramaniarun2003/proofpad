package com.mathnotes.solver.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for backend URL validation/normalization (OkHttp runs on the JVM). */
class ApiClientTest {

    @Test fun adds_a_trailing_slash() {
        assertEquals("http://192.168.1.42:8000/", ApiClient.parseBaseUrl("http://192.168.1.42:8000"))
    }

    @Test fun keeps_an_existing_trailing_slash() {
        assertEquals("http://10.0.2.2:8000/", ApiClient.parseBaseUrl("http://10.0.2.2:8000/"))
    }

    @Test fun trims_surrounding_whitespace() {
        assertEquals("http://host:8123/", ApiClient.parseBaseUrl("  http://host:8123  "))
    }

    @Test fun accepts_https() {
        assertEquals("https://proofpad.example/", ApiClient.parseBaseUrl("https://proofpad.example"))
    }

    @Test fun rejects_blank_and_non_http() {
        assertNull(ApiClient.parseBaseUrl(""))
        assertNull(ApiClient.parseBaseUrl("   "))
        assertNull(ApiClient.parseBaseUrl("not a url"))
        assertNull(ApiClient.parseBaseUrl("ftp://host/"))
    }
}
