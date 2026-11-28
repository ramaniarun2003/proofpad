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
            strategies = listOf(strat("addresses", "http://a:8000", "http://b:8000")),
            probe = prober("http://b:8000"),
        )
        val res = resolver.resolve()
        assertEquals("addresses", res?.strategy)
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
                BackendResolver.Strategy("saved") { error("Couldn’t read the saved URL") },
                strat("gateway", "http://10.0.0.1:8000"),
            ),
            probe = prober("http://10.0.0.1:8000"),
        )
        assertEquals("gateway", resolver.resolve()?.strategy)
    }

    // --- which addresses are tried ---

    private val hosted = "https://proofpad.example.com/"
    private val laptop = "http://192.168.1.42:8000/"

    @Test fun a_fresh_install_tries_the_hosted_solver_first_then_the_builds_address() {
        assertEquals(
            listOf("hosted" to "https://proofpad.example.com", "build" to "http://192.168.1.42:8000"),
            BackendResolver.candidates(saved = null, hosted = hosted, build = laptop),
        )
        assertEquals(BackendResolver.candidates(null, hosted, laptop), BackendResolver.candidates("  ", hosted, laptop))
    }

    @Test fun an_address_the_user_set_is_the_only_one_tried() {
        assertEquals(
            listOf("saved" to "http://10.0.0.5:8123"),
            BackendResolver.candidates(saved = " http://10.0.0.5:8123/ ", hosted = hosted, build = laptop),
        )
    }

    @Test fun missing_or_duplicate_defaults_are_dropped() {
        assertEquals(listOf("build" to "http://192.168.1.42:8000"), BackendResolver.candidates(null, "", laptop))
        assertEquals(listOf("hosted" to "https://proofpad.example.com"), BackendResolver.candidates(null, hosted, null))
        assertEquals(listOf("hosted" to "http://192.168.1.42:8000"), BackendResolver.candidates(null, laptop, "http://192.168.1.42:8000"))
        assertEquals(emptyList<Pair<String, String>>(), BackendResolver.candidates(null, null, " "))
    }

    @Test fun a_fresh_install_connects_to_the_hosted_solver_when_it_answers() = runBlocking {
        val resolver = BackendResolver(
            strategies = listOf("saved", "hosted", "build").map { name ->
                BackendResolver.Strategy(name) { BackendResolver.candidates(null, hosted, laptop).filter { it.first == name }.map { it.second } }
            },
            probe = prober("https://proofpad.example.com", "http://192.168.1.42:8000"),
        )
        assertEquals(BackendResolver.Resolution("hosted", "https://proofpad.example.com", 7L), resolver.resolve())
    }

    @Test fun it_falls_back_to_the_builds_address_when_the_hosted_solver_does_not_answer() = runBlocking {
        val resolver = BackendResolver(
            strategies = listOf("saved", "hosted", "build").map { name ->
                BackendResolver.Strategy(name) { BackendResolver.candidates(null, hosted, laptop).filter { it.first == name }.map { it.second } }
            },
            probe = prober("http://192.168.1.42:8000"),
        )
        assertEquals("build", resolver.resolve()!!.strategy)
    }
}
