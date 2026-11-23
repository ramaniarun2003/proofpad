package com.mathnotes.solver.data.api

import com.mathnotes.solver.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Finds this project's backend so the user never has to type an IP. In order: the saved URL,
 * then common loopback/emulator addresses (reachable over `adb reverse`), then a parallel sweep
 * of the Wi-Fi /24 on the backend's ports. A candidate counts only if its `/health` is *this*
 * backend — identified by the `stub_recognition` field, which the unrelated :8000 squatter lacks.
 */
object BackendDiscovery {

    private val PORTS = listOf(8000, 8123)
    private const val SCAN_CONCURRENCY = 64

    /** @return a normalized base URL that answers as this backend, or null if none found. */
    suspend fun discover(saved: String?): String? {
        val fixed = buildList {
            saved?.takeIf { it.isNotBlank() }?.let { add(it) }
            BuildConfig.BACKEND_URL.takeIf { it.isNotBlank() }?.let { add(it) }
            add("http://10.0.2.2:8000/")   // Android emulator → host loopback
            add("http://127.0.0.1:8000/")  // physical device via `adb reverse`
            add("http://127.0.0.1:8123/")
        }.map(::normalize).distinct()

        for (url in fixed) if (reachable(url, timeoutMs = 800)) {
            Timber.d("backend discovery: fixed candidate hit %s", url)
            return url
        }

        val lan = subnetCandidates()
        if (lan.isEmpty()) return null
        Timber.d("backend discovery: sweeping %d LAN addresses", lan.size)
        return probeFirst(lan, timeoutMs = 500)?.also { Timber.d("backend discovery: LAN hit %s", it) }
    }

    /** True when `<base>health` responds as this backend (has the `stub_recognition` field). */
    suspend fun reachable(base: String, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        runCatching {
            client.newCall(Request.Builder().url(normalize(base) + "health").build()).execute().use { resp ->
                resp.isSuccessful && (resp.body?.string()?.contains("stub_recognition") == true)
            }
        }.getOrDefault(false)
    }

    /** Probe many URLs concurrently; return the first that answers, cancelling the rest. */
    private suspend fun probeFirst(urls: List<String>, timeoutMs: Long): String? = coroutineScope {
        val gate = Semaphore(SCAN_CONCURRENCY)
        val found = CompletableDeferred<String?>()
        val jobs = urls.map { url ->
            launch {
                gate.withPermit {
                    if (found.isActive && withTimeoutOrNull(timeoutMs * 2) { reachable(url, timeoutMs) } == true) {
                        found.complete(url)
                    }
                }
            }
        }
        launch { jobs.joinAll(); if (found.isActive) found.complete(null) }
        val result = found.await()
        jobs.forEach { it.cancel() }
        result
    }

    /** `http://<prefix>.<1..254>:<port>/` for every site-local IPv4 /24 the device is on. */
    private fun subnetCandidates(): List<String> {
        val prefixes = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
        return prefixes.flatMap { p -> (1..254).flatMap { host -> PORTS.map { "http://$p.$host:$it/" } } }
    }

    private fun normalize(url: String): String = if (url.endsWith("/")) url else "$url/"
}
