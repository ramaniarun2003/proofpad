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

    private val resolver = BackendResolver(
        strategies = listOf(
            // The URL the user typed in Settings / scanned via QR, else the build's configured URL.
            BackendResolver.Strategy("manual") {
                val saved = prefs.getString("backend_url", null)?.ifBlank { null }
                listOfNotNull(saved ?: BuildConfig.BACKEND_URL.ifBlank { null })
            },
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

    /** Probe (or re-probe) the configured backend URL. Coalesces concurrent calls. */
    fun locate() {
        if (locating?.isActive == true) return
        locating = scope.launch {
            _state.value = BackendState.Searching
            val res = resolver.resolve()
            if (res != null) {
                onConnected(res.url, res.strategy, res.latencyMs)
            } else {
                _state.value = BackendState.NotFound
                Timber.tag("BackendLocator").w("backend URL not reachable — set it in Settings")
            }
        }
    }

    private fun onConnected(url: String, strategy: String, latencyMs: Long) {
        currentBaseUrl = (url.trimEnd('/') + "/").toHttpUrlOrNull()
        _state.value = BackendState.Connected(url, latencyMs)
        Timber.tag("BackendLocator").i("connected via %s: %s (%dms)", strategy, url, latencyMs)
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
