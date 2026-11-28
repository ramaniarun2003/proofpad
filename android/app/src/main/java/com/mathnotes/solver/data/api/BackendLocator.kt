package com.mathnotes.solver.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.mathnotes.solver.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Uses the single backend URL the user typed in Settings (or scanned via QR), falling back to the
 * build's configured URL when nothing is saved. Auto-discovery (USB loopback, Wi-Fi gateway, mDNS,
 * public cold-start) was removed: on campus Wi-Fi with client isolation it guessed the wrong host.
 * The URL is probed only to drive the status chip. ApiClient's interceptor reads [currentBaseUrl].
 */
class BackendLocator private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("solver_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<BackendState>(BackendState.Searching)
    val state: StateFlow<BackendState> = _state

    /** The base URL the interceptor rewrites requests onto; null until something answers. */
    @Volatile
    var currentBaseUrl: HttpUrl? = null
        private set

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Generous enough that a typed remote URL (not just a snappy LAN host) can answer.
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** The hosted service sleeps when idle and can take most of a minute to answer its first request. */
    private val slowProbeClient: OkHttpClient by lazy {
        probeClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(HOSTED_PROBE_SECONDS, TimeUnit.SECONDS)
            .callTimeout(HOSTED_PROBE_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val hostedUrl: String? = BuildConfig.PUBLIC_BACKEND_URL.ifBlank { null }?.let(BackendResolver::normalize)

    /** The address the user typed or scanned, if any. With none, the app uses its defaults. */
    val savedUrl: String? get() = prefs.getString("backend_url", null)?.ifBlank { null }

    private fun candidates() = BackendResolver.candidates(savedUrl, hostedUrl, BuildConfig.BACKEND_URL)

    private val resolver = BackendResolver(
        strategies = listOf(BackendResolver.SAVED, BackendResolver.HOSTED, BackendResolver.BUILD).map { name ->
            BackendResolver.Strategy(name) { candidates().filter { it.first == name }.map { it.second } }
        },
        probe = { url -> probe(url, if (url == hostedUrl) slowProbeClient else probeClient) },
        log = { Timber.tag("BackendLocator").d("try %s", it) },
    )

    private var locating: Job? = null
    @Volatile private var started = false

    /** Register for connectivity changes and run the first resolution (idempotent). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        registerNetworkCallback()
        locate()
    }

    /** Probe (or re-probe) the configured backend URL. Coalesces concurrent calls. */
    fun locate(restart: Boolean = false) {
        // A new address must be tried now, not after a slow probe of the old one finishes.
        if (locating?.isActive == true) { if (!restart) return else locating?.cancel() }
        locating = scope.launch {
            _state.value = BackendState.Searching
            val res = resolver.resolve()
            if (res != null) {
                onConnected(res.url, res.strategy, res.latencyMs)
            } else {
                _state.value = BackendState.NotFound
                Timber.tag("BackendLocator").w("no backend reachable; tried %s", candidates().joinToString { it.second })
            }
        }
    }

    private fun onConnected(url: String, strategy: String, latencyMs: Long) {
        currentBaseUrl = (url.trimEnd('/') + "/").toHttpUrlOrNull()
        _state.value = BackendState.Connected(url, latencyMs)
        Timber.tag("BackendLocator").i("connected via %s: %s (%dms)", strategy, url, latencyMs)
    }

    /**
     * Latency in ms if `base` is this backend and will take this app's requests, else null.
     * `/health` proves it is the right service (and wakes a sleeping one); `/discover` proves it
     * accepts the key this build carries. Without the second check a build with no key would show
     * "connected" to the hosted service and then have every request refused.
     */
    private suspend fun probe(base: String, client: OkHttpClient = probeClient): Long? = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        runCatching {
            val healthy = client.newCall(Request.Builder().url(base.trimEnd('/') + "/health").build()).execute().use { resp ->
                // Require this backend's health shape so some other server on the port is skipped.
                resp.isSuccessful && resp.body?.string()?.contains("stub_recognition") == true
            }
            if (!healthy) return@runCatching null
            val request = Request.Builder().url(base.trimEnd('/') + "/discover")
                .apply { BuildConfig.PROOFPAD_API_KEY.takeIf { it.isNotBlank() }?.let { header("X-ProofPad-Key", it) } }
                .build()
            val accepted = client.newCall(request).execute().use { it.code }
            if (accepted == 401 || accepted == 403) {
                Timber.tag("BackendLocator").w("%s is up but refused this build's key (HTTP %d)", base, accepted)
                null
            } else {
                SystemClock.elapsedRealtime() - start
            }
        }.getOrNull()
    }

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { locate() }
            override fun onLost(network: Network) { locate() }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    companion object {
        private const val HOSTED_PROBE_SECONDS = 60L
        @Volatile private var instance: BackendLocator? = null

        fun get(context: Context): BackendLocator = instance ?: synchronized(this) {
            instance ?: BackendLocator(context.applicationContext).also { instance = it }
        }

        /** The live instance for ApiClient's interceptor; null before [get] is first called. */
        fun peek(): BackendLocator? = instance
    }
}
