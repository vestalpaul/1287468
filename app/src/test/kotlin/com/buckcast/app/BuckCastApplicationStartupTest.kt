package com.buckcast.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.buckcast.data.sync.WeatherRefreshWorker
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
