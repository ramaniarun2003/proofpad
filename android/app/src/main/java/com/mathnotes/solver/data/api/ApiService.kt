package com.mathnotes.solver.data.api

import com.mathnotes.solver.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

    @POST("solve")
    suspend fun solve(@Body body: SolveRequest): SolveResponse

    @POST("verify")
    suspend fun verify(@Body body: VerifyRequest): VerifyResponse

    @GET("health")
    suspend fun health(): HealthResponse
}

object ApiClient {
    /** Changeable at runtime from the settings sheet, so the demo laptop's IP can be typed in. */
    @Volatile var baseUrl: String = BuildConfig.BACKEND_URL
        set(value) { field = if (value.endsWith("/")) value else "$value/"; cached = null }

    @Volatile private var cached: ApiService? = null

    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    val service: ApiService
        get() = cached ?: build().also { cached = it }

    private fun build(): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)      // Mathpix + SymPy can take a few seconds
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }
}
