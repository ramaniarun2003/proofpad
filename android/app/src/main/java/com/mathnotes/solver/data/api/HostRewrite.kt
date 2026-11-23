package com.mathnotes.solver.data.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Point a request at [base]'s scheme/host/port, keeping its path, query and the rest. */
fun rewriteUrl(requestUrl: HttpUrl, base: HttpUrl): HttpUrl =
    requestUrl.newBuilder()
        .scheme(base.scheme)
        .host(base.host)
        .port(base.port)
        .build()

/**
 * Rewrites every request onto the currently-located backend, so Retrofit is built once against a
 * placeholder and simply follows [currentBase] as the locator switches networks. No base returns
 * the request unchanged (it hits the placeholder and fails, surfacing as "not connected").
 */
class HostRewriteInterceptor(private val currentBase: () -> HttpUrl?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = currentBase() ?: return chain.proceed(chain.request())
        val request = chain.request()
        return chain.proceed(request.newBuilder().url(rewriteUrl(request.url, base)).build())
    }
}
