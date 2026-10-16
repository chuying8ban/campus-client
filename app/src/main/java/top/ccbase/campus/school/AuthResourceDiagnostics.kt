package top.ccbase.campus.school

import java.net.URI

/** Local-only bounded diagnostics. No URL, response body, console or exception message retained. */
internal class AuthResourceDiagnostics {
    private val entries = linkedSetOf<String>()
    @Synchronized fun http(url: String, status: Int, main: Boolean) {
        if (status in 400..599) add("http:${if (main) "document" else category(url)}:$status")
    }
    @Synchronized fun network(url: String, code: Int, main: Boolean) {
        if (code in -16..-1) add("network:${if (main) "document" else category(url)}:$code")
    }
    @Synchronized private fun add(value: String) { if (entries.size < 16) entries.add(value) }
    @Synchronized fun snapshot(): List<String> = entries.toList()
    companion object {
        // Only fixed error names; never message, stack, source URL, rejection value or console.
        val errorObserver = """(function(){if(window.__campusAuthErrors)return;window.__campusAuthErrors=[];function record(n){var a=['TypeError','ReferenceError','SyntaxError','RangeError','SecurityError','NotSupportedError','UnhandledRejection'];n=a.indexOf(n)>=0?n:'OtherError';if(window.__campusAuthErrors.indexOf(n)<0)window.__campusAuthErrors.push(n);}function err(e){if(e.error)record(e.error.name);}function rejection(){record('UnhandledRejection');}window.addEventListener('error',err);window.addEventListener('unhandledrejection',rejection);window.__campusAuthStop=function(){window.removeEventListener('error',err);window.removeEventListener('unhandledrejection',rejection);delete window.__campusAuthErrors;delete window.__campusAuthObserverStarted;delete window.__campusAuthStop;};})()"""
        val diagnosticScript = SchoolDiagnostics.script.replace("ready:document.readyState", "probe:"+MfaProbe.script+",observerStarted:window.__campusAuthObserverStarted===true,capabilities:{Promise:typeof Promise!=='undefined',fetch:typeof fetch!=='undefined',WebAssembly:typeof WebAssembly!=='undefined',credentials:typeof navigator.credentials!=='undefined'},jsTypes:(window.__campusAuthErrors||[]).slice(0,8),ready:document.readyState")
        fun jsTypes(result: String): String = runCatching {
            val outer = kotlinx.serialization.json.Json.parseToJsonElement(result) as kotlinx.serialization.json.JsonPrimitive
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(outer.content) as kotlinx.serialization.json.JsonObject
            val values = obj["jsTypes"] as? kotlinx.serialization.json.JsonArray
            val allowed = setOf("TypeError", "ReferenceError", "SyntaxError", "RangeError", "SecurityError", "NotSupportedError", "OtherError", "UnhandledRejection")
            values.orEmpty().take(8).mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { n -> n in allowed } }.distinct().joinToString(",").ifEmpty { "未捕获（不代表没有错误）" }
        }.getOrDefault("不可用")
        fun category(url: String): String = runCatching {
            val path = URI(url).path.orEmpty().lowercase()
            when (path.substringAfterLast('.', "")) {
                "js", "mjs" -> "script"
                "css" -> "style"
                "woff", "woff2", "ttf", "otf" -> "font"
                "png", "jpg", "jpeg", "svg", "gif", "ico", "webp" -> "image"
                "html", "htm" -> "document"
                else -> "other"
            }
        }.getOrDefault("other")
    }
}
