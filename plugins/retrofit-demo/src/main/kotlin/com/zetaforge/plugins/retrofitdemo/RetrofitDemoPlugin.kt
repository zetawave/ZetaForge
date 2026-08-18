package com.zetaforge.plugins.retrofitdemo

import android.content.Context
import android.os.Bundle
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.ZetaLog
import com.zetaforge.sdk.ZetaPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Demo plugin: plain Kotlin, plain Android `Context`, plain Retrofit/OkHttp.
 *
 * It is compiled in its own Gradle module, packaged as `retrofit-demo.zeta` and
 * loaded by the Host at runtime. Retrofit, OkHttp and Okio live inside the
 * plugin's own DEX - the Host APK does not contain them.
 *
 * Supported [Bundle] inputs:
 * | key            | type    | meaning                                                  |
 * |----------------|---------|----------------------------------------------------------|
 * | `baseUrl`      | String  | Retrofit base URL (default `https://postman-echo.com/`)   |
 * | `expectStatus` | Int     | HTTP status treated as success (default 200)              |
 * | `statusPath`   | Int     | if set, calls `/status/<n>` instead of `/get`             |
 * | `throwOnPurpose` | Boolean | throws a RuntimeException to exercise Host error handling |
 */
class RetrofitDemoPlugin : ZetaPlugin {

    override val id: String = "com.zetaforge.plugins.retrofitdemo"
    override val name: String = "Retrofit Demo"
    override val version: String = "0.1.0"

    override suspend fun onLoad(context: Context) {
        ZetaLog.info(id, TAG, "onLoad in process ${context.packageName}")
    }

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val startedAt = System.currentTimeMillis()
        ZetaLog.info(id, TAG, "START")

        // --- proof that the plugin holds a real Host Context -----------------
        val hostPackage = context.packageName
        val filesDir = context.filesDir.absolutePath
        val contentResolverClass = context.contentResolver.javaClass.name
        ZetaLog.info(id, TAG, "Context: packageName=$hostPackage")
        ZetaLog.debug(id, TAG, "Context: filesDir=$filesDir")
        ZetaLog.debug(id, TAG, "Context: contentResolver=$contentResolverClass")

        if (input.getBoolean(KEY_THROW, false)) {
            ZetaLog.warn(id, TAG, "throwOnPurpose requested - throwing")
            throw RuntimeException("Intentional plugin crash requested via input bundle")
        }

        val baseUrl = input.getString(KEY_BASE_URL) ?: DEFAULT_BASE_URL
        val expectedStatus = input.getInt(KEY_EXPECT_STATUS, 200)
        val statusPath = input.getInt(KEY_STATUS_PATH, -1)

        return try {
            withContext(Dispatchers.IO) {
                runRequest(baseUrl, expectedStatus, statusPath, startedAt, hostPackage, filesDir, contentResolverClass)
            }
        } catch (e: IOException) {
            val duration = System.currentTimeMillis() - startedAt
            ZetaLog.error(id, TAG, "Network failure: ${e.javaClass.simpleName}: ${e.message}", e)
            PluginResult.Failure(
                message = "HTTP request failed: ${e.message}",
                durationMs = duration,
                errorCode = "NETWORK_ERROR",
                cause = e,
                data = mapOf("baseUrl" to baseUrl, "exception" to e.javaClass.name),
            )
        }
    }

    private fun runRequest(
        baseUrl: String,
        expectedStatus: Int,
        statusPath: Int,
        startedAt: Long,
        hostPackage: String,
        filesDir: String,
        contentResolverClass: String,
    ): PluginResult {
        val client = OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "ZetaForge-RetrofitDemo/$version")
                    .build()
                ZetaLog.debug(id, TAG, "OkHttp -> ${request.method} ${request.url}")
                chain.proceed(request)
            }
            .build()
        ZetaLog.info(id, TAG, "OkHttp ${okhttp3.OkHttp.VERSION} client built")

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .build()
        ZetaLog.info(id, TAG, "Retrofit initialized (baseUrl=$baseUrl)")

        val api = retrofit.create(DemoApi::class.java)
        val call = if (statusPath > 0) api.status(statusPath) else api.get()

        // One retry on a 5xx: public echo endpoints occasionally answer 502/504,
        // and a transient gateway error says nothing about the plugin runtime.
        var attempt = 0
        var code: Int
        var body: String
        var requestDuration: Long
        var currentCall = call
        while (true) {
            attempt++
            ZetaLog.info(id, TAG, "HTTP request started (attempt $attempt)")
            val requestStarted = System.currentTimeMillis()
            val response = currentCall.execute()
            requestDuration = System.currentTimeMillis() - requestStarted
            body = response.body()?.string() ?: response.errorBody()?.string().orEmpty()
            code = response.code()
            ZetaLog.info(id, TAG, "HTTP $code (${requestDuration} ms, ${body.length} chars)")
            if (code < 500 || attempt >= MAX_ATTEMPTS) break
            ZetaLog.warn(id, TAG, "Server error $code, retrying once")
            currentCall = currentCall.clone()
        }

        val totalDuration = System.currentTimeMillis() - startedAt
        val data = mapOf(
            "httpStatus" to code.toString(),
            "requestDurationMs" to requestDuration.toString(),
            "responseBytes" to body.toByteArray().size.toString(),
            "bodyPreview" to body.take(BODY_PREVIEW_CHARS).replace('\n', ' '),
            "retrofit" to "bundled in plugin DEX (" + Retrofit::class.java.name + ")",
            "attempts" to attempt.toString(),
            "okhttpVersion" to okhttp3.OkHttp.VERSION,
            "hostPackage" to hostPackage,
            "hostFilesDir" to filesDir,
            "contentResolver" to contentResolverClass,
            "pluginClassLoader" to javaClass.classLoader?.javaClass?.name.orEmpty(),
        )

        return if (code == expectedStatus) {
            ZetaLog.info(id, TAG, "SUCCESS")
            PluginResult.Success(
                message = "HTTP $code in ${requestDuration} ms (${body.length} chars)",
                durationMs = totalDuration,
                data = data,
            )
        } else {
            ZetaLog.warn(id, TAG, "Unexpected status $code, expected $expectedStatus")
            PluginResult.Failure(
                message = "Unexpected HTTP status $code (expected $expectedStatus)",
                durationMs = totalDuration,
                errorCode = "HTTP_ERROR",
                data = data,
            )
        }
    }

    override suspend fun onUnload() {
        ZetaLog.info(id, TAG, "onUnload")
    }

    private companion object {
        const val TAG = "RetrofitDemo"
        const val DEFAULT_BASE_URL = "https://postman-echo.com/"
        const val MAX_ATTEMPTS = 2
        const val REQUEST_TIMEOUT_SECONDS = 15L
        const val BODY_PREVIEW_CHARS = 240

        const val KEY_BASE_URL = "baseUrl"
        const val KEY_EXPECT_STATUS = "expectStatus"
        const val KEY_STATUS_PATH = "statusPath"
        const val KEY_THROW = "throwOnPurpose"
    }
}
