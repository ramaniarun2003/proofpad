package com.mathnotes.solver.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** The interceptor's core: swap scheme/host/port onto the located backend, keep the rest. */
class HostRewriteTest {

    @Test fun rewrites_host_and_port_keeping_path_and_query() {
        val request = "http://127.0.0.1:8000/recognize?device_id=abc".toHttpUrl()
        val base = "http://192.168.1.42:8000/".toHttpUrl()
        val out = rewriteUrl(request, base)
        assertEquals("http://192.168.1.42:8000/recognize?device_id=abc", out.toString())
    }

    @Test fun follows_a_different_port() {
        val out = rewriteUrl("http://127.0.0.1:8000/health".toHttpUrl(), "http://10.0.0.5:8123/".toHttpUrl())
        assertEquals("http://10.0.0.5:8123/health", out.toString())
        assertEquals(8123, out.port)
        assertEquals("10.0.0.5", out.host)
    }

    @Test fun follows_https_scheme() {
        val out = rewriteUrl("http://placeholder/solve".toHttpUrl(), "https://proofpad.example/".toHttpUrl())
        assertEquals("https", out.scheme)
        assertEquals("https://proofpad.example/solve", out.toString())
    }
}
