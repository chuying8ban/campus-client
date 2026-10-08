package top.ccbase.campus.school

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import top.ccbase.campus.net.EamsClient
import top.ccbase.campus.net.SchoolImportPolicy
import top.ccbase.campus.ui.theme.C
import androidx.compose.material3.MaterialTheme

/**
 * 导入用的登录状态清理。
 *
 * 教务的会话 Cookie **不是**挂在根路径上的：`SESSION` 与 `__pstsid__` 都带 `Path=/student`。
 * 只查 `getCookie("https://host")` 再写 `Path=/` 的过期，等于什么都没删 —— 下一个用这台
 * 手机的人还能拿着上一段会话继续用。所以这里按**已知路径**逐个删，并把 WebStorage /
 * 页面缓存 / 历史一并清掉；Cookie 的写入统一回主线程，避免在非主线程用 CookieManager。
 */
object SchoolCookie {
    /** 教务会话靠这两个名；先按名删，再并上各路径实测到的名，防止漏网。 */
    private val KNOWN_NAMES = listOf("SESSION", "__pstsid__")

    /** Cookie 实际所在的路径：根 + 学校把会话挂的 `/student`。 */
    private val KNOWN_PATHS = listOf("/", EamsClient.STUDENT, EamsClient.STUDENT + "/")

    fun host(): String = EamsClient.DEFAULT_BASE.substringAfter("https://").trimEnd('/')

    /** 只清 Cookie（课程表读完、但页面还在时用）。 */
    fun clear(host: String = host()) {
        onMain {
            val cm = CookieManager.getInstance() ?: return@onMain
            val h = host.trimEnd('/')
            for (path in KNOWN_PATHS) {
                val probe = "https://$h$path"
                val names = (KNOWN_NAMES + namesIn(cm.getCookie(probe)))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                for (name in names) {
                    // 两个变体都写：带 Domain 与不带 Domain，字面量要覆盖教务设置时用的那一个。
                    for (suffix in listOf("Path=$path", "Path=$path; Domain=$h")) {
                        cm.setCookie(probe, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; $suffix")
                    }
                }
            }
            cm.flush()
        }
    }

    /** 清掉当前页面源（务必是教务 origin）的 localStorage/sessionStorage。 */
    fun clearWebStorage(wv: WebView) {
        onMain {
            val url = wv.url
            if (url == null || !SchoolImportPolicy.isAllowedSchoolUrl(url)) return@onMain
            wv.evaluateJavascript(
                "(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}})()",
                null,
            )
        }
    }

    /**
     * 一次导入彻底收尾：Cookie + WebStorage + 页面缓存 + 历史。
     * 不做 `WebStorage.getInstance().deleteAllData()` —— 那是**全局**清空，会误伤
     * 同进程里别的 WebView（比如反馈页的内嵌页）。
     */
    fun clearAll(wv: WebView) {
        clear()
        clearWebStorage(wv)
        onMain {
            runCatching { wv.clearCache(true) }
            runCatching { wv.clearHistory() }
        }
    }

    private fun namesIn(header: String?): List<String> =
        header.orEmpty().split(";").mapNotNull { it.substringBefore("=").trim().takeIf { n -> n.isNotEmpty() } }

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}

/** 记录 `onPageFinished` 次数，供“导航到课表页并等它加载完”使用。 */
class PageGate {
    @Volatile var finishedTick: Int = 0
    @Volatile var finishedUrl: String? = null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SchoolWebLogin(
    onTimetableJson: (String) -> Unit,
    onWebError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var desktop by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var diagnostic by remember { mutableStateOf("等待页面") }
    var firstScriptError by remember { mutableStateOf("") }
    var softwareRender by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pageGate = remember { PageGate() }

    androidx.activity.compose.BackHandler {
        if (!busy) {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else onCancel()
        }
    }

    fun fail(text: String) {
        webView?.let { SchoolCookie.clearAll(it) }
        onWebError(text)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                // 离开这一页 = 导入结束（无论成功、取消还是截断）：登录状态与页面缓存一并清掉，
                // 再把 WebView 销毁，不留复用窗口。destroy 只在主线程做，回主线程执行。
                SchoolCookie.clearAll(wv)
                runCatching { wv.destroy() }
            }
            webView = null
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(
            Modifier.fillMaxWidth().background(C.card).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← 取消导入", fontSize = 13.sp, color = C.txt2,
                modifier = Modifier.clickable {
                    webView?.let { SchoolCookie.clearAll(it) }
                    onCancel()
                }.padding(vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Column {
                Text("官方教务登录", fontSize = 15.sp, color = C.txt)
                Text("eams.cupk.edu.cn · HTTPS", fontSize = 10.sp, color = C.txt3)
            }
            if (top.ccbase.campus.BuildConfig.DEBUG) Text("诊断", color = C.txt3, fontSize = 12.sp,
                modifier = Modifier.clickable { showDiagnostics = !showDiagnostics }.padding(10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text("后退", color = C.txt2, modifier = Modifier.clickable { webView?.let { if (it.canGoBack()) it.goBack() } }.padding(6.dp))
            Text("前进", color = C.txt2, modifier = Modifier.clickable { webView?.let { if (it.canGoForward()) it.goForward() } }.padding(6.dp))
            Text("刷新", color = C.txt2, modifier = Modifier.clickable { webView?.reload() }.padding(6.dp))
            Text(if (desktop) "桌面模式 · 切换" else "手机模式 · 切换", color = C.txt2, modifier = Modifier.clickable {
                desktop = !desktop
                webView?.let { wv ->
                    applySchoolBrowserMode(wv, desktop)
                    wv.reload()
                }
            }.padding(6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text("缩小 −", color = C.txt2, modifier = Modifier.clickable { webView?.zoomOut() }.padding(6.dp))
            Text("放大 ＋", color = C.txt2, modifier = Modifier.clickable { webView?.zoomIn() }.padding(6.dp))
            Text("可双指缩放、横向拖动网页", color = C.txt3, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
        }
        if (showDiagnostics && top.ccbase.campus.BuildConfig.DEBUG) {
        Text(if (softwareRender) "诊断：兼容绘制已开启（点此恢复）" else "诊断：切换兼容绘制", fontSize = 12.sp, color = C.txt2, modifier = Modifier.clickable {
            softwareRender = !softwareRender
            webView?.let {
                it.setLayerType(if (softwareRender) android.view.View.LAYER_TYPE_SOFTWARE else android.view.View.LAYER_TYPE_NONE, null)
                it.invalidate()
            }
        }.padding(horizontal = 16.dp, vertical = 8.dp))
        Text("页面进度 $progress% · $diagnostic", fontSize = 11.sp, color = C.txt2, modifier = Modifier.padding(horizontal = 16.dp))
        if (firstScriptError.isNotEmpty()) Text(firstScriptError, fontSize = 11.sp, color = C.red, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (progress < 100) androidx.compose.material3.LinearProgressIndicator(
            progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(),
            color = C.violet, trackColor = C.line)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        configureSecureWebView(this, pageGate, onDiagnostic = {
                            diagnostic = it
                            if (it.startsWith("资源") && firstScriptError.isEmpty()) firstScriptError = it
                        }) { fail(it) }
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        applySchoolBrowserMode(this, desktop)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {                                 progress = newProgress
                            }
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                    val path = runCatching { java.net.URI(message.sourceId()).path }.getOrNull().orEmpty()
                                    val text = "脚本错误 ${path.substringAfterLast('/').take(48)}:${message.lineNumber()}"
                                    diagnostic = text
                                    if (firstScriptError.isEmpty()) firstScriptError = text
                                }
                                // 不收集或回显 console 原文：其中可能含学校页面输出的秘密。
                                return true
                            }
                        }
                        loadUrl(EamsClient.DEFAULT_BASE + EamsClient.STUDENT + "/login")
                    }
                },
                update = {},
            )
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            readError?.let { Text(it, color = C.red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)) }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(step.ifBlank { "正在从教务读取课表…" }, fontSize = 12.sp, color = C.txt2)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) {
                        val wv = webView
                        if (wv == null) {
                            readError = "网页还没加载好，请稍等后重试"
                            return@clickable
                        }
                        busy = true
                        readError = null
                        step = "正在从教务读取课表…"
                        scope.launch {
                            try {
                                val result = wv.extractTimetableJson(pageGate)
                                SchoolCookie.clearAll(wv)
                                onTimetableJson(result)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                readError = "暂未读到课表，当前页面和登录状态已保留。可刷新后重试。"
                                if (top.ccbase.campus.BuildConfig.DEBUG) diagnostic = "读取阶段：" + when {
                                    e.message.orEmpty().contains("学期") -> "学期标识未就绪"
                                    e.message.orEmpty().contains("加载超时") -> "目标课表页未完成加载"
                                    e.message.orEmpty().contains("HTTP") -> "课表请求返回错误状态"
                                    else -> "课表数据未就绪"
                                }
                            } finally {
                                busy = false
                                step = ""
                            }
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("登录完成，读取课表", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "只打开 https://eams.cupk.edu.cn。密码在学校的登录页里输入，App 不读取、不保存。",
                fontSize = 11.sp, lineHeight = 17.sp, color = C.txt2,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureSecureWebView(
    wv: WebView,
    gate: PageGate,
    onDiagnostic: (String) -> Unit = {},
    onError: (String) -> Unit,
) {
    WebView.setWebContentsDebuggingEnabled(top.ccbase.campus.BuildConfig.DEBUG)
    wv.settings.javaScriptEnabled = true
    wv.settings.domStorageEnabled = true
    wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    wv.settings.allowFileAccess = false
    wv.settings.allowContentAccess = false
    wv.settings.allowFileAccessFromFileURLs = false
    wv.settings.allowUniversalAccessFromFileURLs = false
    wv.settings.setSupportMultipleWindows(false)
    wv.settings.javaScriptCanOpenWindowsAutomatically = false
    wv.settings.savePassword = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) wv.settings.safeBrowsingEnabled = true
    listOf("searchBoxJavaBridge_", "accessibility", "accessibilityTraversal").forEach {
        runCatching { wv.removeJavascriptInterface(it) }
    }
    CookieManager.getInstance()?.apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(wv, false)
    }
    SchoolCookie.clear()

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!SchoolImportPolicy.isAllowedSchoolUrl(request.url.toString())) {
                onError("阻止打开非官方教务地址")
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!SchoolImportPolicy.isAllowedSchoolUrl(request.url.toString())) {
                if (request.isForMainFrame) onError("阻止加载非官方教务地址")
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!SchoolImportPolicy.isAllowedSchoolUrl(url)) {
                view.stopLoading()
                onError("阻止加载非官方教务地址")
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            // 让“导航到课表页并等它加载完”有据可依（见 extractTimetableJson）。
            gate.finishedTick += 1
            gate.finishedUrl = url
            if (SchoolImportPolicy.isAllowedSchoolUrl(url)) {
                view.evaluateJavascript("""(function(){var m=document.querySelector('meta[name=viewport]');if(m){m.content=m.content.replace(/user-scalable\s*=\s*no/ig,'user-scalable=yes').replace(/maximum-scale\s*=\s*[0-9.]+/ig,'maximum-scale=5');}})()""", null)
            }
            // 已观察到登录面板高度为 0；为官方登录页明确设定视口高度。
            // 不读取输入内容、不改登录逻辑，仅修复布局。
            if (SchoolImportPolicy.isAllowedSchoolUrl(url) && android.net.Uri.parse(url).path == "/student/login") {
                val fix = "(function(){function apply(){if(location.protocol!=='https:'||location.hostname!=='eams.cupk.edu.cn'||location.pathname!=='/student/login')return;var h=window.innerHeight+'px';var s=document.getElementById('campus-login-layout')||document.createElement('style');s.id='campus-login-layout';s.textContent='html,body,#vue_main,.sw-login,.sw-login-wrapper{height:'+h+'!important;min-height:'+h+'!important}.sw-login-main{height:'+h+'!important;min-height:'+h+'!important;position:absolute!important;top:0!important;bottom:0!important}.sw-login-main-content{height:'+h+'!important;min-height:'+h+'!important}';if(!s.parentNode)document.head.appendChild(s);document.documentElement.style.height=h;document.body.style.height=h;var e=document.querySelector('.sw-login-main');if(e){e.style.setProperty('height',h,'important');e.style.setProperty('min-height',h,'important');e.style.setProperty('position','absolute','important');e.style.setProperty('top','0','important');e.style.setProperty('bottom','0','important');}}apply();if(!window.__campusLayoutResize){window.__campusLayoutResize=true;window.addEventListener('resize',apply);if(window.visualViewport)window.visualViewport.addEventListener('resize',apply);}})()"
                view.evaluateJavascript(fix, null)
                view.postDelayed({ if (SchoolImportPolicy.isAllowedSchoolUrl(view.url.orEmpty()) && android.net.Uri.parse(view.url).path == "/student/login") view.evaluateJavascript(fix, null) }, 1000)
                view.postDelayed({ if (SchoolImportPolicy.isAllowedSchoolUrl(view.url.orEmpty()) && android.net.Uri.parse(view.url).path == "/student/login") view.evaluateJavascript(fix, null) }, 4000)
            }
            if (SchoolImportPolicy.isAllowedSchoolUrl(url) && android.net.Uri.parse(url).path == "/student/login") {
                // 只计数，不读取输入框值、页面 HTML 或 Cookie。
                view.postDelayed({
                    if (!SchoolImportPolicy.isAllowedSchoolUrl(view.url.orEmpty()) || android.net.Uri.parse(view.url).path != "/student/login") return@postDelayed
                    view.evaluateJavascript("JSON.stringify({inputs:document.querySelectorAll('input').length,vue:typeof Vue,main:typeof requirejs!=='undefined'&&requirejs.defined('main'),viewport:[innerWidth,innerHeight],form:(function(){var e=document.querySelector('.sw-login-main');if(!e)return null;var r=e.getBoundingClientRect();return [Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height),getComputedStyle(e).visibility]})()})") { result ->
                        onDiagnostic("登录模块状态 ${result.take(240)}")
                    }
                }, 5000)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val file = request.url.lastPathSegment.orEmpty().take(48)
            view.post { onDiagnostic("资源 HTTP ${response.statusCode} · $file") }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onError("证书校验失败，已停止加载（不会在非官方链路上继续）")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (request.isForMainFrame) onError("教务页面加载失败（${error.errorCode}）")
            else view.post { onDiagnostic("资源加载失败 ${error.errorCode} · ${request.url.lastPathSegment.orEmpty().take(48)}") }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            SchoolCookie.clear()
            onError("教务页面进程已退出，已清除本次登录 Cookie")
            return true
        }
    }
}

internal fun applySchoolBrowserMode(view: WebView, desktop: Boolean) {
    view.settings.userAgentString = if (desktop)
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    else WebSettings.getDefaultUserAgent(view.context)
    view.settings.useWideViewPort = desktop
    view.settings.loadWithOverviewMode = desktop
    view.setInitialScale(0)
}

private val json = Json { ignoreUnknownKeys = true }

private const val EAMS_ERROR = "__EAMS_ERROR__"

/**
 * 读取课表的完整流程（全部在**主线程**上操作 WebView）：
 *
 *   ① 先把 WebView 导航到官方**课表页**（`EamsClient.COURSE_TABLE_PATH`）并等它加载完 ——
 *      `var semesters` / `var currentSemester` 只写在那一页；以前在登录根页上读这两个全局，
 *      必然读不到，用户就卡在"找不到学期列表"。
 *   ② 从这一页里取学期 id（window 全局优先，回退到内联脚本文本）。
 *   ③ 用**共享的** `EamsClient.printDataPath()` 拼 print-data 路径，same-origin fetch。
 *      URL 与 [EamsClient.fetchTimetableJson] 同源同一行代码，杜绝"猜一个看起来对的 URL"。
 *   ④ 等结果：HTTP 错 / 返回非 JSON（会话失效被重定向到登录页）都要如实报错，
 *      绝不把错误串当成课表 JSON 交出去。
 */
private suspend fun WebView.extractTimetableJson(gate: PageGate): String = withContext(Dispatchers.Main) {
    navigateAndAwait(gate, EamsClient.DEFAULT_BASE + EamsClient.COURSE_TABLE_PATH)

    val semToken = decodeValue(evaluate(SEMESTER_JS))
    if (semToken.startsWith(EAMS_ERROR)) {
        throw IllegalStateException(semToken.removePrefix(EAMS_ERROR).trim().ifBlank { "学期信息没找到" })
    }
    val sid = semToken.toIntOrNull() ?: throw IllegalStateException("学期信息没找到")

    val path = EamsClient.printDataPath(sid).replace("'", "%27")
    evaluate(fetchScript(path))

    var waited = 0L
    while (waited < 60_000) {
        delay(500)
        waited += 500
        val err = decodeValue(evaluate("(window.__eamsError||'')"))
        if (err.isNotEmpty()) throw IllegalStateException("读取课表失败：$err")
        val text = decodeValue(evaluate("(window.__eamsPayload||'')"))
        if (text.isNotEmpty()) return@withContext text
    }
    throw IllegalStateException("读取课表超时；请确认已在教务页面完成登录，再点一次")
}

private fun fetchScript(path: String): String = """
    (function(){
      window.__eamsPayload=null; window.__eamsError=null;
      try{
        fetch('$path',{credentials:'same-origin',headers:{'Accept':'application/json'}})
          .then(function(r){
            return r.text().then(function(t){
              if(!r.ok){ window.__eamsError='HTTP '+r.status; return; }
              var ct=(r.headers&&r.headers.get)?(r.headers.get('content-type')||''):'';
              if(ct.indexOf('json')<0 && t.charAt(0)!=='{'){ window.__eamsError='会话失效：返回的不是课表 JSON'; return; }
              window.__eamsPayload=t;
            });
          })
          .catch(function(e){ window.__eamsError=String(e); });
      }catch(e){ window.__eamsError=String(e); }
    })()
""".trimIndent()

/** 学期 id 提取：对齐 [EamsClient.currentSemesterId] 的口径（用 currentSemester 的 abbrEn 匹配 semesters）。 */
private val SEMESTER_JS = """
    (function(){
      try{
        function pickId(list, abbr){
          if(typeof list==='string'){ try{ list=JSON.parse(list); }catch(e){ return null; } }
          if(!Array.isArray(list)||list.length===0) return null;
          var p=null;
          if(abbr){ for(var i=0;i<list.length;i++){ var x=list[i]; if(x&&(x.abbrEn===abbr||x.abbr===abbr)){ p=x; break; } } }
          if(!p) p=list[0];
          return p&&(p.id!==undefined?p.id:p.semesterId);
        }
        var sem=window.semesters, cur=window.currentSemester;
        if(!sem||!cur){
          var html=document.documentElement?document.documentElement.innerHTML:'';
          var m=html.match(/var\s+semesters\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)/);
          if(!sem&&m){ try{ sem=JSON.parse(m[1]); }catch(e){} }
          if(!cur){ var m2=html.match(/var\s+currentSemester\s*=\s*([\s\S]*?);/); if(m2){ try{ cur=JSON.parse(m2[1]); }catch(e){} } }
        }
        var abbr=(cur&&(cur.abbrEn||cur.abbr||cur.name))||null;
        var id=pickId(sem,abbr);
        if(id===null||id===undefined||isNaN(Number(id))) return '$EAMS_ERROR 找不到学期（先在官方页面登录）';
        return String(id);
      }catch(e){ return '$EAMS_ERROR '+e; }
    })()
""".trimIndent()

/** 导航到同一个官方 origin 的页面，并等 `onPageFinished` 触发。 */
private suspend fun WebView.navigateAndAwait(gate: PageGate, url: String, timeoutMs: Long = 30_000) {
    if (!SchoolImportPolicy.isAllowedSchoolUrl(url)) throw IllegalStateException("拒绝导航到非官方教务地址")
    val targetPath = android.net.Uri.parse(url).path.orEmpty()
    if (SchoolImportPolicy.isAllowedSchoolUrl(this.url.orEmpty()) &&
        android.net.Uri.parse(this.url).path == targetPath && gate.finishedUrl == this.url) return
    val start = gate.finishedTick
    loadUrl(url)
    var waited = 0L
    while (waited < timeoutMs) {
        val finishedPath = android.net.Uri.parse(gate.finishedUrl).path.orEmpty()
        val wantedPath = android.net.Uri.parse(url).path.orEmpty()
        if (gate.finishedTick > start && (finishedPath == wantedPath || finishedPath == "$wantedPath/")) return
        delay(150)
        waited += 150
    }
    throw IllegalStateException("课表页加载超时；请确认已在教务页面完成登录")
}

private suspend fun WebView.evaluate(expression: String): String =
    suspendCoroutine { cont ->
        evaluateJavascript(expression) { value ->
            cont.resume(value ?: "")
        }
    }

private fun decodeValue(v: String): String {
    val text = v.trim()
    if (text.isEmpty() || text == "null") return ""
    return runCatching { json.parseToJsonElement(text).jsonPrimitive.content }.getOrNull() ?: text
}
