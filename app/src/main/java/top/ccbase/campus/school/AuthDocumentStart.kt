package top.ccbase.campus.school

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.*
import java.net.URI
import java.util.WeakHashMap

/** Registration is before loadUrl / popup transport, never a page-finished substitute. */
internal class AuthDocumentStart(private val hosts: Set<String>) {
    private val states = WeakHashMap<WebView, String>()
    private val handlers = WeakHashMap<WebView, androidx.webkit.ScriptHandler>()
    var enabled = false
    fun stop() {
        enabled=false
        handlers.values.toList().forEach { runCatching { it.remove() } };handlers.clear()
        states.keys.toList().forEach { view -> runCatching { view.evaluateJavascript("(function(){if(window.__campusAuthStop)window.__campusAuthStop();})()",null) } }
        states.clear()
    }
    fun install(view: WebView) {
        if(!enabled)return
        // Remove listeners in the current document before replacing future-document registration.
        if (runCatching { URI(view.url.orEmpty()).let { it.scheme == "https" && it.host in hosts && it.rawUserInfo == null && (it.port == -1 || it.port == 443) } }.getOrDefault(false))
            runCatching { view.evaluateJavascript("(function(){if(window.__campusAuthStop)window.__campusAuthStop();})()", null) }
        handlers.remove(view)?.remove()
        states[view] = register(
            { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) },
            { script, origins -> handlers[view] = WebViewCompat.addDocumentStartJavaScript(view, script, origins) }
        )
    }
    fun state(view: WebView): String = states[view] ?: "not_registered"
    fun register(supported: () -> Boolean, add: (String, Set<String>) -> Unit): String = try {
        val origins = origins(hosts)
        if (origins.isEmpty()) "invalid_origins"
        else if (!supported()) "unsupported_no_observer"
        else { add(script(origins), origins); "registered_document_start" }
    } catch (_: Exception) { "registration_failed_no_observer" }

    companion object {
        fun origins(hosts: Set<String>): Set<String> = hosts.mapNotNull { host ->
            val u = runCatching { URI("https://$host") }.getOrNull()
            if (u != null && u.host == host && u.rawAuthority == host &&
                u.rawUserInfo == null && u.port == -1 && u.rawPath.isNullOrEmpty() &&
                u.rawQuery == null && u.rawFragment == null && !host.contains('*')) "https://$host" else null
        }.toSet()
        fun script(origins: Set<String>): String {
            val json = JsonArray(origins.sorted().map { JsonPrimitive(it) })
            return "(function(){if($json.indexOf(location.origin)<0||window!==window.top)return;" +
                AuthResourceDiagnostics.errorObserver + ";window.__campusAuthObserverStarted=true;})()"
        }
        fun observed(result: String): Boolean = runCatching {
            Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content)
                .jsonObject["observerStarted"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
    }
}
