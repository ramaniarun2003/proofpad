package com.mathnotes.solver.data.api

import com.mathnotes.solver.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

/** The five endpoints in docs/api.md. All calls are suspend functions used from a ViewModel. */
interface ApiService {
    @Multipart
    @POST("recognize")
    suspend fun recognize(
        @Part image: MultipartBody.Part,
        @Part("device_id") deviceId: RequestBody,
    ): RecognizeResponse

    /** mode=page: a whole page of lines, each with a pixel bbox (handwriting setup wizard). */
    @Multipart
    @POST("recognize")
    suspend fun recognizePage(
        @Part image: MultipartBody.Part,
        @Part("device_id") deviceId: RequestBody,
        @Part("mode") mode: RequestBody,
    ): PageRecognizeResponse

    @POST("solve")
    suspend fun solve(@Body body: SolveRequest): SolveResponse

    @POST("verify")
    suspend fun verify(@Body body: VerifyRequest): VerifyResponse

    @GET("health")
    suspend fun health(): HealthResponse
}

object ApiClient {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    /** Validate and normalize a user-typed URL (trailing slash); null if it isn't a valid http(s) URL. */
    fun parseBaseUrl(value: String): String? {
        val t = value.trim()
        if (t.toHttpUrlOrNull() == null) return null
        return if (t.endsWith("/")) t else "$t/"
    }

    // Built once. The HostRewriteInterceptor redirects every call onto whatever base URL the
    // BackendLocator has currently settled on, so we never rebuild Retrofit on a network change.
    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)      // Mathpix + SymPy can take a few seconds
            .retryOnConnectionFailure(true)
            .addInterceptor(HostRewriteInterceptor { BackendLocator.peek()?.currentBaseUrl })
            .addInterceptor { chain ->
                // Shared secret for the public deployment; empty on LAN-only builds.
                val key = BuildConfig.PROOFPAD_API_KEY
                val request = if (key.isNotBlank()) {
                    chain.request().newBuilder().header("X-ProofPad-Key", key).build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:8000/")   // placeholder; HostRewriteInterceptor sets the real host
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }
}
