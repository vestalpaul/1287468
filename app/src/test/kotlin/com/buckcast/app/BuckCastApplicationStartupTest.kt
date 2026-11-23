package com.buckcast.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.buckcast.data.sync.WeatherRefreshWorker
import com.buckcast.feature.map.MapBaseLayer
import com.buckcast.feature.map.MapTileConfig
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = BuckCastApplication::class,
    shadows = [ShadowMapLibre::class, ShadowHttpRequestUtil::class],
)
class BuckCastApplicationStartupTest {

    @After
    fun tearDown() {
        ShadowMapLibre.reset()
        ShadowHttpRequestUtil.reset()
    }

    @Test
    fun `BuckCastApplication cold-start initializes MapLibre, HttpRequestUtil, Hilt BillingGateway, and WorkManager in strict order`() {
        val app = ApplicationProvider.getApplicationContext<BuckCastApplication>()

        // 1. MapLibre.getInstance(this) MUST precede HttpRequestUtil.setOkHttpClient(...)
        assertEquals(
            listOf("MapLibre.getInstance", "HttpRequestUtil.setOkHttpClient"),
            ShadowMapLibre.initEvents,
        )

        // 2. MapLibre tile HTTP client must attach DropTine User-Agent header
        val capturedFactory = ShadowHttpRequestUtil.capturedClient
        assertTrue("Expected OkHttpClient to be configured", capturedFactory is OkHttpClient)
        val okClient = capturedFactory as OkHttpClient
        assertTrue("Expected User-Agent interceptor on tile OkHttpClient", okClient.interceptors.isNotEmpty())

        var observedUserAgent: String? = null
        val dummyRequest = Request.Builder().url("https://basemap.nationalmap.gov/test").build()
        val fakeChain = object : Interceptor.Chain {
            override fun request(): Request = dummyRequest
            override fun proceed(request: Request): Response {
                observedUserAgent = request.header("User-Agent")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            override fun connection(): Connection? = null
            override fun call(): Call = okClient.newCall(dummyRequest)
            override fun connectTimeoutMillis(): Int = 10_000
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun readTimeoutMillis(): Int = 10_000
            override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 10_000
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        }
        okClient.interceptors.first().intercept(fakeChain)
        assertTrue(
            "Expected DropTine/ User-Agent header, got: $observedUserAgent",
            observedUserAgent?.startsWith("DropTine/") == true,
        )

        // 3. Hilt singletons injected and WorkManager initialized with HiltWorkerFactory
        assertNotNull(app.billingGateway)
        assertNotNull(app.entitlementRepository)
        assertSame(app.workerFactory, app.workManagerConfiguration.workerFactory)

        val workInfos = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WeatherRefreshWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue("Expected periodic weather sync work to be enqueued", workInfos.isNotEmpty())
    }

    @Test
    fun `DropTineStyleInterceptor returns 200 OK MapLibre style JSON for all layers and hillshade variants`() {
        val styleInterceptor = DropTineStyleInterceptor { "" }

        for (layer in MapBaseLayer.entries) {
            for (hillshade in listOf(false, true)) {
                for (offlineSafe in listOf(false, true)) {
                    val url = MapTileConfig.styleUrl(
                        baseLayer = layer,
                        showHillshade = hillshade,
                        offlineSafe = offlineSafe,
                    )
                    val request = Request.Builder().url(url).build()
                    var networkProceeded = false
                    val chain = object : Interceptor.Chain {
                        override fun request(): Request = request
                        override fun proceed(request: Request): Response {
                            networkProceeded = true
                            error("Virtual style URL must not hit network: $url")
                        }
                        override fun connection(): Connection? = null
                        override fun call(): Call = OkHttpClient().newCall(request)
                        override fun connectTimeoutMillis(): Int = 10_000
                        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
                        override fun readTimeoutMillis(): Int = 10_000
                        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
                        override fun writeTimeoutMillis(): Int = 10_000
                        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
                    }
                    val response = styleInterceptor.intercept(chain)
                    assertEquals(200, response.code)
                    assertEquals(DropTineStyleInterceptor.CACHE_CONTROL_HEADER, response.header("Cache-Control"))
                    assertTrue(!networkProceeded)
                    val body = response.body?.string().orEmpty()
                    assertTrue("Missing version 8 in $url", body.contains("\"version\": 8"))
                    assertTrue("Missing droptine-base in $url", body.contains("\"droptine-base\""))
                    if (offlineSafe) {
                        assertTrue("Offline style must exclude {bbox-epsg-3857}: $url", !body.contains("{bbox-epsg-3857}"))
                        assertTrue("Offline style must omit glyphs: $url", !body.contains("\"glyphs\""))
                        assertTrue("Offline style must omit {fontstack}: $url", !body.contains("{fontstack}"))
                        assertTrue("Offline style must omit {range}: $url", !body.contains("{range}"))
                    } else {
                        assertTrue("Live style must include glyphs: $url", body.contains("\"glyphs\""))
                    }
                    // Parse body with org.json.JSONObject to guarantee well-formed JSON without trailing/missing commas
                    val parsed = org.json.JSONObject(body)
                    assertEquals(8, parsed.getInt("version"))
                    assertTrue(parsed.getJSONObject("sources").length() >= 1)
                    assertTrue(parsed.getJSONArray("layers").length() >= 1)
                }
            }
        }

        // Unknown virtual style path returns 404 locally without hitting network
        val unknownRequest = Request.Builder().url("https://styles.droptine.app/unknown_style.json").build()
        val unknownChain = object : Interceptor.Chain {
            override fun request(): Request = unknownRequest
            override fun proceed(request: Request): Response = error("Should not proceed for unknown host path")
            override fun connection(): Connection? = null
            override fun call(): Call = OkHttpClient().newCall(unknownRequest)
            override fun connectTimeoutMillis(): Int = 10_000
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun readTimeoutMillis(): Int = 10_000
            override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 10_000
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        }
        assertEquals(404, styleInterceptor.intercept(unknownChain).code)

        // App-configured OkHttpClient must include DropTineStyleInterceptor
        val okClient = ShadowHttpRequestUtil.capturedClient as OkHttpClient
        assertTrue(okClient.interceptors.any { it is DropTineStyleInterceptor })
    }
}

@Implements(MapLibre::class)
class ShadowMapLibre {
    companion object {
        val initEvents = mutableListOf<String>()
        var initialized = false

        @JvmStatic
        @Implementation
        fun getInstance(context: Context): MapLibre? {
            initialized = true
            initEvents.add("MapLibre.getInstance")
            return null
        }

        fun reset() {
            initialized = false
            initEvents.clear()
        }
    }
}

@Implements(HttpRequestUtil::class)
class ShadowHttpRequestUtil {
    companion object {
        var capturedClient: Call.Factory? = null

        @JvmStatic
        @Implementation
        fun setOkHttpClient(client: Call.Factory) {
            check(ShadowMapLibre.initialized) {
                "HttpRequestUtil.setOkHttpClient called before MapLibre.getInstance"
            }
            ShadowMapLibre.initEvents.add("HttpRequestUtil.setOkHttpClient")
            capturedClient = client
        }

        fun reset() {
            capturedClient = null
        }
    }
}
