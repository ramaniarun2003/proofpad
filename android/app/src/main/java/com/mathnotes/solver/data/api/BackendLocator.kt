package com.mathnotes.solver.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import com.mathnotes.solver.data.WelcomePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Finds and tracks the backend automatically so the user rarely types an address. Resolution
 * order (see [BackendResolver]): the URL saved in Settings → the last URL that worked →
 * `127.0.0.1:8000` (USB `adb reverse`) → mDNS `_proofpad._tcp` → the Wi-Fi gateway on :8000.
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
            BackendResolver.Strategy("last-worked") { listOfNotNull(WelcomePrefs.lastBackendUrl(app)) },
            BackendResolver.Strategy("usb-loopback") { listOf("http://127.0.0.1:8000") },
            BackendResolver.Strategy("mdns") { mdnsCandidates() },
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
                currentBaseUrl = (res.url.trimEnd('/') + "/").toHttpUrlOrNull()
                _state.value = BackendState.Connected(res.url, res.latencyMs)
                WelcomePrefs.setLastBackendUrl(app, res.url)
                Timber.tag("BackendLocator").i("connected via %s: %s (%dms)", res.strategy, res.url, res.latencyMs)
            } else {
                _state.value = BackendState.NotFound
                Timber.tag("BackendLocator").w("no backend found after all strategies")
            }
        }
    }

    /** GET `<base>/health`; latency in ms if it answers as *this* backend within 1.5s, else null. */
    private suspend fun probe(base: String): Long? = withContext(Dispatchers.IO) {
        val url = base.trimEnd('/') + "/health"
        val start = SystemClock.elapsedRealtime()
        runCatching {
            probeClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                // Require this backend's health shape so the unrelated :8000 squatter is skipped.
                if (resp.isSuccessful && resp.body?.string()?.contains("stub_recognition") == true) {
                    SystemClock.elapsedRealtime() - start
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    // --- mDNS (NsdManager) ---

    private suspend fun mdnsCandidates(): List<String> {
        val nsd = runCatching { app.getSystemService(Context.NSD_SERVICE) as NsdManager }.getOrNull() ?: return emptyList()
        val found = ConcurrentHashMap.newKeySet<String>()
        val resolveLock = Mutex()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo?) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                scope.launch { resolveLock.withLock { resolveToUrl(nsd, service)?.let(found::add) } }
            }
        }
        try {
            runCatching { nsd.discoverServices("_proofpad._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
            delay(3000)                    // the discovery/resolve window
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return found.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveToUrl(nsd: NsdManager, service: NsdServiceInfo): String? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (cont.isActive) cont.resume(host?.let { "http://$it:${serviceInfo.port}" })
                }
            }
            runCatching { nsd.resolveService(service, listener) }.onFailure { if (cont.isActive) cont.resume(null) }
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
