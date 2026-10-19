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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    fun clear(host: String = host(), expire: ((String, String) -> Unit)? = null) {
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
                    for (suffix in listOf("Path=$path", "Path=$path; Secure", "Path=$path; Domain=$h", "Path=$path; Domain=$h; Secure")) {
                        val removal = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; $suffix"
                        if (expire == null) cm.setCookie(probe, removal) else expire(probe, removal)
                    }
                }
            }
            cm.flush()
        }
    }

    /**
     * 这一刻是否已登录：教务把会话挂在 `SESSION` / `__pstsid__` 上，读到非空值就算登录了。
     *
     * 只读 Cookie 头，不碰页面内容、不读输入框、不读密码 —— 界面用它决定「读取课表」按钮
     * 出不出现：没登录时那个按钮一定导不出东西，摆着只会让人点了以为坏了。
     */
    fun hasSession(profile: SchoolProfile = SchoolRegistry.cupk): Boolean {
        val cm = CookieManager.getInstance() ?: return false
        val h = profile.host
        return KNOWN_PATHS.any { path ->
            SchoolCookieRules.hasSession(cm.getCookie("https://$h$path"))
        }
    }

    /**
     * 把当前会话 cookie 改写成持久 cookie（默认 30 天）。
     *
     * 这是「登录过一次，以后进内置浏览器都自动登录」的全部机制：WebView 默认把会话 Cookie
     * 当临时物件，App 进程一死就没了，用户又回到登录页。补上过期时间后，只要学校那边的会话
     * 还没过期，进来就已经是登录态 —— 不保存密码，也不上传 Cookie。
     *
     * @return 至少改写成功一条才算 true
     */
    fun persistSession(profile: SchoolProfile = SchoolRegistry.cupk, days: Long = 30): Boolean {
        var done = false
        onMain {
            val cm = CookieManager.getInstance() ?: return@onMain
            val h = profile.host
            val maxAge = (days * 86_400L).toInt()
            val expires = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusDays(days),
            )
            for (path in KNOWN_PATHS) {
                val probe = "https://$h$path"
                val header = cm.getCookie(probe).orEmpty()
                for (name in SchoolCookieRules.SESSION_NAMES) {
                    val value = SchoolCookieRules.valueOf(header, name) ?: continue
                    cm.setCookie(
                        probe,
                        SchoolCookieRules.persistent("$name=$value", path, maxAge, expires),
                    )
                    done = true
                }
            }
            if (done) cm.flush()
        }
        return done
    }

    /** 清掉当前页面源（务必是教务 origin）的 localStorage/sessionStorage。 */
    fun clearWebStorage(wv: WebView, profile: SchoolProfile = SchoolRegistry.cupk) {
        onMain {
            val url = wv.url
            if (url == null || !profile.allows(url)) return@onMain
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
    fun clearAll(wv: WebView, profile: SchoolProfile = SchoolRegistry.cupk) {
        clear(profile.host)
        clearWebStorage(wv,profile)
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
/** 用例抓手：主/备登录入口切换 */
const val SWITCH_ENTRY = "school-switch-entry"

@Composable
fun SchoolWebLogin(
    onTimetableJson: (String) -> Unit,
    onWebError: (String) -> Unit,
    onCancel: () -> Unit,
    profile: SchoolProfile = SchoolRegistry.cupk,
) {
    val ctx = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    /**
     * 「正在进入教务系统」：从点进来到第一个页面真正上屏之间有一段空白 ——
     * 冷启动要初始化内核、做一次完整 TLS 握手、再等首屏渲染，这段能到好几秒。
     * 不提示的话，用户看到的就是"卡住了"（用户原话：进入登录页面前会卡一会）。
     */
    var entering by remember { mutableStateOf(true) }
    /** 登录态：没登录时「读取课表」一定读不到，那个按钮就不该出现在下面。 */
    var loggedIn by remember { mutableStateOf(SchoolCookie.hasSession(profile)) }
    var step by remember { mutableStateOf("") }
    var desktop by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var diagnostic by remember { mutableStateOf("等待页面") }
    var firstScriptError by remember { mutableStateOf("") }
    var softwareRender by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    // 主入口 = 融合门户统一身份认证；备用 = 教务直登（2026-10-19 用户拍板）
    var useBackup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pageGate = remember { PageGate() }

    // 兜底：即使首屏迟迟不上屏（弱网），15 秒后也把"正在进入"收掉，免得盖成一块死板。
    LaunchedEffect(Unit) {
        delay(15_000)
        entering = false
    }
    // 登录态轮询：教务把会话挂在 Cookie 上，读到它就是已登录。
    // 一检测到登录，并且用户同意在本机记住登录，就把会话 cookie 改写成持久 cookie ——
    // 下一次进内置浏览器直接就是登录态（不保存密码，只保留会话）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            val has = SchoolCookie.hasSession(profile)
            if (has && !loggedIn) {
                if (SchoolSession.remembered(ctx, profile)) SchoolCookie.persistSession(profile)
                loggedIn = true
            } else if (!has && loggedIn) {
                loggedIn = false
            }
        }
    }

    androidx.activity.compose.BackHandler {
        if (!busy) {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else onCancel()
        }
    }

    fun fail(text: String) {
        webView?.let { SchoolCookie.clearAll(it,profile) }
        onWebError(text)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                // 离开这一页 = 导入结束（无论成功、取消还是截断）：登录状态与页面缓存一并清掉，
                // 再把 WebView 销毁，不留复用窗口。destroy 只在主线程做，回主线程执行。
                if (SchoolSession.remembered(wv.context,profile)) { SchoolSession.flush(); wv.clearCache(true); wv.clearHistory() } else SchoolCookie.clearAll(wv,profile)
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
                    webView?.let { if (!SchoolSession.remembered(it.context,profile)) SchoolCookie.clearAll(it,profile) else SchoolSession.flush() }
                    onCancel()
                }.padding(vertical = 8.dp))
            Spacer(Modifier.weight(1f))
            Column {
                Text("官方教务登录", fontSize = 15.sp, color = C.txt)
                Text("${profile.host} · HTTPS", fontSize = 12.sp, color = C.txt2)
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
            Text("可双指缩放、横向拖动网页", color = C.txt2, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
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
                        configureSecureWebView(
                            this, pageGate, profile = profile,
                            onFirstPaint = { entering = false },
                            onDiagnostic = {
                                diagnostic = it
                                if (it.startsWith("资源") && firstScriptError.isEmpty()) firstScriptError = it
                            },
                        ) { fail(it) }
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
                        loadUrl(
                            if (SchoolSession.remembered(ctx, profile)) profile.origin + profile.timetablePath
                            else if (useBackup) profile.backupLoginUrl
                            else profile.loginUrl
                        )
                    }
                },
                update = {},
            )
            // 首屏没上屏之前盖一层"正在进入"，别让人对着空白页等
            if (entering) {
                Box(
                    Modifier.fillMaxSize().background(C.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp, color = C.violet)
                        Spacer(Modifier.height(14.dp))
                        Text("正在进入教务系统…", fontSize = 14.sp, color = C.txt)
                        Spacer(Modifier.height(6.dp))
                        Text("正在连接 ${profile.host}", fontSize = 12.sp, color = C.txt2)
                    }
                }
            }
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
            if (loggedIn) {
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
                                    val result = wv.extractTimetableJson(pageGate,profile)
                                    // 学籍卡片：趁 WebView 还停在教务、会话还有效，顺手读一次。
                                    // 失败一律不影响导课表 —— 导课表是主任务，学籍只是"顺便更好"。
                                    runCatching {
                                        val app = wv.context.applicationContext as? top.ccbase.campus.CampusApplication
                                        val db = app?.db
                                        if (db != null) {
                                            wv.extractEamsProfile()?.let { SchoolProfileCache.save(db, it) }
                                            wv.extractEamsRecords()?.let { SchoolRecordsCache.save(db, it) }
                                        }
                                    }
                                    if (!SchoolSession.remembered(wv.context,profile)) SchoolCookie.clearAll(wv,profile) else SchoolSession.flush()
                                    onTimetableJson(result)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    readError = "暂未读到课表。若学校会话已过期，请在官方页面重新登录后重试；原课表未改变。"
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
            } else {
                // 没登录就一定导不出课表 —— 按钮先不出现，只说明"登录后才会出现"，
                // 免得用户点了之后以为功能坏了（用户原话：没登录时是一定导不了的）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp, color = C.txt3)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已进入学校登录页 —— 登录成功后，这里才会出现「登录完成，读取课表」",
                        fontSize = 12.sp, lineHeight = 18.sp, color = C.txt2,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 主/备切换（2026-10-19）：默认走融合门户的统一身份认证；
            // 门户抽风或 CAS 改版时，一键切回教务直登，不用等发版。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (useBackup) "现在走的是教务直登（备用入口）" else "现在走的是融合门户统一身份认证",
                    fontSize = 11.5.sp, color = C.txt3, modifier = Modifier.weight(1f),
                )
                Text(
                    if (useBackup) "改用融合门户" else "改用教务直登",
                    fontSize = 11.5.sp, color = C.cyan,
                    modifier = Modifier
                        .testTag(SWITCH_ENTRY)
                        .clickable {
                            useBackup = !useBackup
                            webView?.loadUrl(if (useBackup) profile.backupLoginUrl else profile.loginUrl)
                            step = ""
                        }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只打开 ${profile.origin} 与统一身份认证（cas.cupk.edu.cn）。密码在学校的登录页里输入，App 不读取、不保存。",
                fontSize = 13.sp, lineHeight = 19.sp, color = C.txt2,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureSecureWebView(
    wv: WebView,
    gate: PageGate,
    profile: SchoolProfile = SchoolRegistry.cupk,
    onFirstPaint: () -> Unit = {},
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
    if (!SchoolSession.remembered(wv.context,profile)) SchoolCookie.clear(profile.host)

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!profile.allows(request.url.toString())) {
                onError("阻止打开非官方教务地址")
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!profile.allows(request.url.toString())) {
                if (request.isForMainFrame) onError("阻止加载非官方教务地址")
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!profile.allows(url)) {
                view.stopLoading()
                onError("阻止加载非官方教务地址")
            }
        }

        /** 首个页面真正上屏（API 23+）：这时才把"正在进入"的覆盖层收掉。 */
        override fun onPageCommitVisible(view: WebView, url: String) {
            onFirstPaint()
        }

        override fun onPageFinished(view: WebView, url: String) {
            onFirstPaint()
            // 让“导航到课表页并等它加载完”有据可依（见 extractTimetableJson）。
            gate.finishedTick += 1
            gate.finishedUrl = url
            if (profile.allows(url)) {
                view.evaluateJavascript("""(function(){var m=document.querySelector('meta[name=viewport]');if(m){m.content=m.content.replace(/user-scalable\s*=\s*no/ig,'user-scalable=yes').replace(/maximum-scale\s*=\s*[0-9.]+/ig,'maximum-scale=5');}})()""", null)
            }
            // 已观察到登录面板高度为 0；为官方登录页明确设定视口高度。
            // 不读取输入内容、不改登录逻辑，仅修复布局。
            if (profile.id==SchoolRegistry.cupk.id && profile.allows(url) && android.net.Uri.parse(url).path == "/student/login") {
                val fix = "(function(){function apply(){if(location.protocol!=='https:'||location.hostname!=='eams.cupk.edu.cn'||location.pathname!=='/student/login')return;var h=window.innerHeight+'px';var s=document.getElementById('campus-login-layout')||document.createElement('style');s.id='campus-login-layout';s.textContent='html,body,#vue_main,.sw-login,.sw-login-wrapper{height:'+h+'!important;min-height:'+h+'!important}.sw-login-main{height:'+h+'!important;min-height:'+h+'!important;position:absolute!important;top:0!important;bottom:0!important}.sw-login-main-content{height:'+h+'!important;min-height:'+h+'!important}';if(!s.parentNode)document.head.appendChild(s);document.documentElement.style.height=h;document.body.style.height=h;var e=document.querySelector('.sw-login-main');if(e){e.style.setProperty('height',h,'important');e.style.setProperty('min-height',h,'important');e.style.setProperty('position','absolute','important');e.style.setProperty('top','0','important');e.style.setProperty('bottom','0','important');}}apply();if(!window.__campusLayoutResize){window.__campusLayoutResize=true;window.addEventListener('resize',apply);if(window.visualViewport)window.visualViewport.addEventListener('resize',apply);}})()"
                view.evaluateJavascript(fix, null)
                view.postDelayed({ if (profile.allows(view.url.orEmpty()) && android.net.Uri.parse(view.url).path == "/student/login") view.evaluateJavascript(fix, null) }, 1000)
                view.postDelayed({ if (profile.allows(view.url.orEmpty()) && android.net.Uri.parse(view.url).path == "/student/login") view.evaluateJavascript(fix, null) }, 4000)
            }
            // 统一身份认证（CAS）登录页：填账号密码，**不自动提交**（有验证码）。
            // 口令仍然只从本机 Keystore 密文里取，跨到 cas 这个 origin 填也是用户勾了"记住密码"才发生。
            if (profile.allows(url) && runCatching { java.net.URI(url).host }.getOrNull()
                    ?.equals("cas.cupk.edu.cn", ignoreCase = true) == true
            ) {
                val cred = CredentialVault.load(view.context)
                if (cred != null) {
                    val js = CredentialVault.ssoAutofillJs(cred.first, cred.second)
                    listOf(600L, 1800L, 4200L).forEach { at ->
                        view.postDelayed({
                            if (runCatching { java.net.URI(view.url.orEmpty()).host }.getOrNull()
                                    ?.equals("cas.cupk.edu.cn", ignoreCase = true) != true
                            ) return@postDelayed
                            view.evaluateJavascript(js) { r ->
                                when {
                                    r?.contains("filled-need-captcha") == true ->
                                        onDiagnostic("已填好账号密码，请输入验证码")
                                    r?.contains("clicked") == true ->
                                        onDiagnostic("已用记住的账号自动登录")
                                }
                            }
                        }, at)
                    }
                }
            }
            // 记住账号密码的用户：会话过期落到登录页时自动填表并提交。
            // 默认没有存凭据，这里就什么都不做（行为和以前一样）。
            if (profile.allows(url) && android.net.Uri.parse(url).path == "/student/login") {
                val cred = CredentialVault.load(view.context)
                if (cred != null) {
                    val js = CredentialVault.autofillJs(cred.first, cred.second)
                    // 表单是 Vue 异步渲染的，一次填不进去：错开几次重试；翻走/登录成功就自然不再命中
                    listOf(1200L, 3000L, 6500L).forEach { at ->
                        view.postDelayed({
                            val now = view.url.orEmpty()
                            if (!profile.allows(now) || android.net.Uri.parse(now).path != "/student/login") return@postDelayed
                            view.evaluateJavascript(js) { r ->
                                if (r?.contains("clicked") == true) onDiagnostic("已用记住的账号自动登录")
                            }
                        }, at)
                    }
                }
            }
            if (profile.allows(url) && android.net.Uri.parse(url).path == "/student/login") {
                // 只计数，不读取输入框值、页面 HTML 或 Cookie。
                view.postDelayed({
                    if (!profile.allows(view.url.orEmpty()) || android.net.Uri.parse(view.url).path != "/student/login") return@postDelayed
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
            SchoolCookie.clear(profile.host)
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
private suspend fun WebView.extractTimetableJson(gate: PageGate, profile: SchoolProfile = SchoolRegistry.cupk): String = withContext(Dispatchers.Main) {
    navigateAndAwait(gate, profile.origin + profile.timetablePath, profile=profile)

    val semToken = decodeValue(evaluate(SchoolRegistry.adapter(profile).semesterScript))
    if (semToken.startsWith(EAMS_ERROR)) {
        throw IllegalStateException(semToken.removePrefix(EAMS_ERROR).trim().ifBlank { "学期信息没找到" })
    }
    val sid = semToken.toIntOrNull() ?: throw IllegalStateException("学期信息没找到")

    val path = SchoolRegistry.adapter(profile).printDataPath(sid).replace("'", "%27")
    evaluate(SchoolRegistry.adapter(profile).fetchScript(path))

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

/**
 * 学籍卡片：在**已登录的教务页**上发一次同源 GET，解析逻辑全在
 * [ShuweiExtraction.profileScript] 里（页面是 HTML，没有 JSON 接口）。
 *
 * 超时 20 秒、失败返回 null —— 绝不能因为读不到学籍而让导课表报错。
 */
private suspend fun WebView.extractEamsProfile(): String? = withContext(Dispatchers.Main) {
    evaluate(ShuweiExtraction.profileScript)
    waitForGlobal("__eamsProfile", "__eamsProfileError", 20_000)
}

/** 考试安排 + 成绩单：同一个 WebView、同一次会话里顺手读，失败不影响导课表。 */
private suspend fun WebView.extractEamsRecords(): String? = withContext(Dispatchers.Main) {
    evaluate(ShuweiExtraction.recordsScript)
    waitForGlobal("__eamsRecords", "__eamsRecordsError", 30_000)
}

/** 等注入脚本把结果写进 `window.<name>`；出错或被 _error 标记就返回 null。 */
private suspend fun WebView.waitForGlobal(name: String, errorName: String, timeoutMs: Long): String? {
    var waited = 0L
    while (waited < timeoutMs) {
        delay(400)
        waited += 400
        val payload = decodeValue(evaluate("(window.$name||'')"))
        if (payload.isNotEmpty()) return payload
        if (decodeValue(evaluate("(window.$errorName||'')")).isNotEmpty()) return null
    }
    return null
}

/** 导航到同一个官方 origin 的页面，并等 `onPageFinished` 触发。 */
private suspend fun WebView.navigateAndAwait(gate: PageGate, url: String, timeoutMs: Long = 30_000, profile: SchoolProfile = SchoolRegistry.cupk) {
    if (!profile.allows(url)) throw IllegalStateException("拒绝导航到非官方教务地址")
    val targetPath = android.net.Uri.parse(url).path.orEmpty()
    if (profile.allows(this.url.orEmpty()) &&
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
