package app.fitness.android.ui.poster

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.fitness.android.ui.components.QuietButton
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle
import app.fitness.core.Units
import app.fitness.data.AnalyticsRepository
import java.io.ByteArrayInputStream

private const val HOST = "appassets.androidplatform.net"

/**
 * The running-life poster. The page is the PC app's own `poster.html` (bundled in assets), which draws SVG from one JSON
 * payload; this screen answers its one data request from the database and saves what it downloads to the phone's pictures.
 * Nothing leaves the phone: every other request is refused.
 */
@Composable
fun PosterScreen(analytics: AnalyticsRepository, units: Units, onBack: () -> Unit) {
    val t = Fitness.theme
    val payload by produceState<String?>(null, analytics) { value = analytics.posterJson() }
    Column(Modifier.fillMaxSize().background(t.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton(if (t.layout == LayoutStyle.TERMINAL) "<- back" else "‹ Training", onBack)
        }
        val json = payload
        if (json == null) Text("Preparing the poster…", color = t.muted, modifier = Modifier.padding(16.dp))
        else PosterWeb(json, units)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PosterWeb(payload: String, units: Units) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.blockNetworkLoads = false
                addJavascriptInterface(Bridge(ctx.applicationContext), "AndroidPoster")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        answer(ctx, request, payload, units)
                }
                loadUrl("https://$HOST/poster/poster.html")
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun answer(ctx: Context, request: WebResourceRequest, payload: String, units: Units): WebResourceResponse {
    val url = request.url
    if (url.host != HOST) return refuse()
    val path = url.path ?: return refuse()
    if (path == "/analytics/poster") return text(payload, "application/json")
    val name = path.removePrefix("/poster/").takeIf { path.startsWith("/poster/") && '/' !in it && ".." !in it } ?: return refuse()
    val bytes = try { ctx.assets.open("poster/$name").use { it.readBytes() } } catch (_: Exception) { return refuse(404) }
    return when {
        // The page keeps the unit choice in localStorage; seed it from the app's setting.
        name == "common.js" -> text("localStorage.setItem(\"units\", \"${if (units == Units.IMPERIAL) "imperial" else "metric"}\");\n" + bytes.decodeToString(), "text/javascript")
        name.endsWith(".js") -> WebResourceResponse("text/javascript", "utf-8", ByteArrayInputStream(bytes))
        name.endsWith(".css") -> WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(bytes))
        else -> WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
    }
}

private fun text(body: String, mime: String) = WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body.toByteArray()))
private fun refuse(status: Int = 403) = WebResourceResponse("text/plain", "utf-8", status, if (status == 404) "Not Found" else "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** Called from the page's Download buttons. Saves into Pictures (images) or Downloads (SVG), which needs no permission. */
private class Bridge(private val context: Context) {
    @JavascriptInterface
    fun save(name: String, mime: String, base64: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val image = mime.startsWith("image/") && mime != "image/svg+xml"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/Fitness Tracker" else "Download/Fitness Tracker")
        }
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val message = try {
            val uri = context.contentResolver.insert(collection, values) ?: error("could not create the file")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
            "Saved ${if (image) "to Pictures" else "to Downloads"}/Fitness Tracker"
        } catch (e: Exception) { "Could not save: ${e.message}" }
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}
