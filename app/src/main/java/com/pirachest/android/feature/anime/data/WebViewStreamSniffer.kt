package com.pirachest.android.feature.anime.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object WebViewStreamSniffer {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffStreamUrl(
        context: Context,
        embedUrl: String,
        referer: String = embedUrl,
        timeoutMs: Long = 15_000L
    ): VideoStreamSource? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context.applicationContext)
                var resolved = false

                fun cleanup() {
                    runCatching {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.clearHistory()
                        webView.removeAllViews()
                        webView.destroy()
                    }
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                }

                val userAgent = webView.settings.userAgentString

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        val isVideo = url.contains(".m3u8", ignoreCase = true) ||
                            (url.contains(".mp4", ignoreCase = true) && !url.contains("preview"))

                        if (isVideo && !resolved && continuation.isActive) {
                            resolved = true
                            val headers = mutableMapOf(
                                "User-Agent" to userAgent,
                                "Referer" to referer
                            )
                            request?.requestHeaders?.get("Origin")?.let {
                                headers["Origin"] = it
                            }

                            Handler(Looper.getMainLooper()).post { cleanup() }
                            continuation.resume(
                                VideoStreamSource(
                                    providerName = "WebEmbed",
                                    url = url,
                                    quality = "Auto",
                                    isHls = url.contains(".m3u8", ignoreCase = true),
                                    headers = headers
                                )
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { cleanup() }
                }

                webView.loadUrl(embedUrl, mapOf("Referer" to referer))
            }
        }
    }
}