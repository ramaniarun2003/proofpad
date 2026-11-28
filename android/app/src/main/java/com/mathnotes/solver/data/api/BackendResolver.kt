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
 * Android wiring (saved URL, USB loopback, gateway) lives in BackendLocator.
 */
class BackendResolver(
    private val strategies: List<Strategy>,
    /** Returns round-trip latency in ms if `<url>/health` answered 200 in time, else null. */
    private val probe: suspend (String) -> Long?,
    private val log: (String) -> Unit = {},
) {
    /** A named source of candidate base URLs. Candidates are tried in order. */
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

        const val SAVED = "saved"
        const val HOSTED = "hosted"
        const val BUILD = "build"

        /**
         * Which base URLs to try, in order.
         *
         * - Someone who typed or scanned an address ([saved]) gets exactly that one. It is never
         *   silently swapped for another server: if it is down, the app says so.
         * - Everyone else gets the hosted service first, so a fresh install works with no setup,
         *   and then the address this build was configured with (a developer's laptop).
         */
        fun candidates(saved: String?, hosted: String?, build: String?): List<Pair<String, String>> {
            val mine = saved?.trim().orEmpty()
            if (mine.isNotEmpty()) return listOf(SAVED to normalize(mine))
            return listOf(HOSTED to hosted, BUILD to build)
                .mapNotNull { (name, url) -> url?.trim()?.takeIf { it.isNotEmpty() }?.let { name to normalize(it) } }
                .distinctBy { it.second }
        }
    }
}
