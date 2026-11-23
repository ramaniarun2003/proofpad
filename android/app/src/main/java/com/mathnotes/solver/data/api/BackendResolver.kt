package com.mathnotes.solver.data.api

/** Live connection state for the backend. */
sealed interface BackendState {
    data object Searching : BackendState
    data class Connected(val url: String, val latencyMs: Long) : BackendState
    data object NotFound : BackendState
}

/**
 * Pure strategy-order resolution: try each strategy's candidate URLs in order and stop at the
 * first whose probe succeeds. Android-free so it can be unit-tested with a fake prober; the
 * Android wiring (saved URL, mDNS, gateway…) lives in BackendLocator.
 */
class BackendResolver(
    private val strategies: List<Strategy>,
    /** Returns round-trip latency in ms if `<url>/health` answered 200 in time, else null. */
    private val probe: suspend (String) -> Long?,
    private val log: (String) -> Unit = {},
) {
    /** A named source of candidate base URLs (e.g. "saved", "mdns"). Candidates are tried in order. */
    data class Strategy(val name: String, val candidates: suspend () -> List<String>)

    data class Resolution(val strategy: String, val url: String, val latencyMs: Long)

    suspend fun resolve(): Resolution? {
        for (strategy in strategies) {
            val candidates = runCatching { strategy.candidates() }.getOrElse { emptyList() }
            for (candidate in candidates) {
                val url = normalize(candidate)
                val latency = probe(url)
                log("${strategy.name}: $url -> ${latency?.let { "${it}ms ✓" } ?: "no"}")
                if (latency != null) return Resolution(strategy.name, url, latency)
            }
        }
        return null
    }

    companion object {
        /** Drop a trailing slash so a base URL is a clean `scheme://host:port`. */
        fun normalize(url: String): String = url.trim().trimEnd('/')
    }
}
