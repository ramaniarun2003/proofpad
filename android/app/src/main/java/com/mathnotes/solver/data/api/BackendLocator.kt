package com.mathnotes.solver.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.mathnotes.solver.BuildConfig
import com.mathnotes.solver.data.WelcomePrefs
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
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Checks the URL saved in Settings, the build's configured URL, the last URL that worked,
 * `127.0.0.1:8000` for USB `adb reverse`, then the Wi-Fi gateway on port 8000.
 * Re-runs whenever connectivity changes. ApiClient's interceptor reads [currentBaseUrl].
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
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(1500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val resolver = BackendResolver(
        strategies = listOf(
            BackendResolver.Strategy("saved") { listOfNotNull(prefs.getString("backend_url", null)?.ifBlank { null }) },
            BackendResolver.Strategy("configured") { listOf(BuildConfig.BACKEND_URL) },
            BackendResolver.Strategy("last-worked") { listOfNotNull(WelcomePrefs.lastBackendUrl(app)) },
            BackendResolver.Strategy("usb-loopback") { listOf("http://127.0.0.1:8000") },
            BackendResolver.Strategy("gateway") { gatewayCandidates() },
        ),
        probe = ::probe,
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

    /** Resolve (or re-resolve) the backend. Coalesces concurrent calls. */
    fun locate() {
        if (locating?.isActive == true) return
        locating = scope.launch {
            _state.value = BackendState.Searching
            val res = resolver.resolve()
            if (res != null) {
                onConnected(res.url, res.strategy, res.latencyMs)
                return@launch
            }
            // Last resort: the public Render URL. It can cold-start for up to ~60s, so poll /health
            // every 5s for up to 75s (showing "waking up…") rather than giving up after one probe.
            val publicUrl = BuildConfig.PUBLIC_BACKEND_URL.trim().trimEnd('/')
            if (publicUrl.isNotBlank()) {
                _state.value = BackendState.WakingUp
                val deadline = SystemClock.elapsedRealtime() + 75_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val latency = probe(publicUrl, publicProbeClient)
                    Timber.tag("BackendLocator").d("try public: %s -> %s", publicUrl, latency?.let { "${it}ms ✓" } ?: "waking…")
                    if (latency != null) { onConnected(publicUrl, "public", latency); return@launch }
                    delay(5_000)
                }
            }
            _state.value = BackendState.NotFound
            Timber.tag("BackendLocator").w("no backend found after all strategies")
        }
    }

    private suspend fun onConnected(url: String, strategy: String, latencyMs: Long) {
        currentBaseUrl = (url.trimEnd('/') + "/").toHttpUrlOrNull()
        _state.value = BackendState.Connected(url, latencyMs)
        WelcomePrefs.setLastBackendUrl(app, url)
        Timber.tag("BackendLocator").i("connected via %s: %s (%dms)", strategy, url, latencyMs)
    }

    /** A longer-timeout client for the public URL, whose first (cold-start) response can be slow. */
    private val publicProbeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** GET `<base>/health`; latency in ms if it answers as *this* backend in time, else null. */
    private suspend fun probe(base: String, client: OkHttpClient = probeClient): Long? = withContext(Dispatchers.IO) {
        val url = base.trimEnd('/') + "/health"
        val start = SystemClock.elapsedRealtime()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                // Require this backend's health shape so the unrelated :8000 squatter is skipped.
                if (resp.isSuccessful && resp.body?.string()?.contains("stub_recognition") == true) {
                    SystemClock.elapsedRealtime() - start
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    // --- Wi-Fi gateway (laptop-as-hotspot) ---

    private fun gatewayCandidates(): List<String> {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = cm.activeNetwork ?: return emptyList()
        val lp = cm.getLinkProperties(net) ?: return emptyList()
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        return gateway?.let { listOf("http://$it:8000") } ?: emptyList()
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
        @Volatile private var instance: BackendLocator? = null

        fun get(context: Context): BackendLocator = instance ?: synchronized(this) {
            instance ?: BackendLocator(context.applicationContext).also { instance = it }
        }

        /** The live instance for ApiClient's interceptor; null before [get] is first called. */
        fun peek(): BackendLocator? = instance
    }
}
