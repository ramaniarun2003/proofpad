package com.mathnotes.solver.data.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Strategy-order resolution with a fake prober — no network, no Android. */
class BackendResolverTest {

    private fun strat(name: String, vararg urls: String) =
        BackendResolver.Strategy(name) { urls.toList() }

    /** Fake prober: the given reachable URLs answer (latency fixed), everything else is null. */
    private fun prober(vararg reachable: String): suspend (String) -> Long? {
        val ok = reachable.map { it.trimEnd('/') }.toSet()
        return { url -> if (url.trimEnd('/') in ok) 7L else null }
    }

    @Test fun stops_at_the_first_strategy_that_answers() = runBlocking {
        val tried = mutableListOf<String>()
        val resolver = BackendResolver(
            strategies = listOf(
                strat("saved", "http://saved:8000"),
                strat("last", "http://last:8000"),
                strat("usb", "http://127.0.0.1:8000"),
            ),
            probe = prober("http://last:8000", "http://127.0.0.1:8000"),
            log = { tried += it },
        )
        val res = resolver.resolve()
        assertEquals("last", res?.strategy)
        assertEquals("http://last:8000", res?.url)
        // Probed saved (miss) and last (hit); never reached usb.
        assertEquals(2, tried.size)
    }

    @Test fun earlier_strategy_wins_even_if_a_later_one_also_answers() = runBlocking {
        val resolver = BackendResolver(
            strategies = listOf(strat("saved", "http://saved:8000"), strat("gateway", "http://10.0.0.1:8000")),
            probe = prober("http://saved:8000", "http://10.0.0.1:8000"),
        )
        assertEquals("saved", resolver.resolve()?.strategy)
    }

    @Test fun tries_candidates_within_a_strategy_in_order() = runBlocking {
        val resolver = BackendResolver(
            strategies = listOf(strat("mdns", "http://a:8000", "http://b:8000")),
            probe = prober("http://b:8000"),
        )
        val res = resolver.resolve()
        assertEquals("mdns", res?.strategy)
        assertEquals("http://b:8000", res?.url)
    }

    @Test fun returns_null_when_nothing_answers() = runBlocking {
        val resolver = BackendResolver(
            strategies = listOf(strat("saved", "http://saved:8000"), strat("usb", "http://127.0.0.1:8000")),
            probe = prober(/* nothing reachable */),
        )
        assertNull(resolver.resolve())
    }

    @Test fun a_strategy_that_throws_is_skipped() = runBlocking {
        val resolver = BackendResolver(
            strategies = listOf(
                BackendResolver.Strategy("mdns") { error("NsdManager blew up") },
                strat("gateway", "http://10.0.0.1:8000"),
            ),
            probe = prober("http://10.0.0.1:8000"),
        )
        assertEquals("gateway", resolver.resolve()?.strategy)
    }
}
