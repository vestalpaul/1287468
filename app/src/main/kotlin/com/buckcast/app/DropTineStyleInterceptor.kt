package com.buckcast.app

import com.buckcast.feature.map.MapTileConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Intercepts virtual HTTPS style requests targeting `https://styles.droptine.app/{styleKey}.json`
 * so both MapLibre live rendering and `OfflineManager` tile downloads receive HTTP 200 OK
 * MapLibre v8 style JSON without hitting the public network or stalling on `file://` URIs.
 */
class DropTineStyleInterceptor(
    private val mapTilerApiKeyProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme.equals("https", ignoreCase = true) &&
            url.host.equals(MapTileConfig.VIRTUAL_STYLE_HOST, ignoreCase = true)
        ) {
            val styleJson = MapTileConfig.resolveStyleJsonForPath(
                path = url.encodedPath,
                mapTilerApiKey = mapTilerApiKeyProvider(),
            )
            return if (styleJson != null) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .header("Cache-Control", CACHE_CONTROL_HEADER)
                    .body(styleJson.toResponseBody(JSON_MEDIA_TYPE.toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Unknown DropTine style")
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .body("""{"error":"Unknown style ${url.encodedPath}"}""".toResponseBody(JSON_MEDIA_TYPE.toMediaType()))
                    .build()
            }
        }
        return chain.proceed(request)
    }

    companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val CACHE_CONTROL_HEADER = "public, max-age=31536000"
    }
}
