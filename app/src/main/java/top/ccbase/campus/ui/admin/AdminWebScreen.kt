package top.ccbase.campus.ui.admin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.ccbase.campus.BuildConfig
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.pageSurface

/** 后台管理页：挂在同一个站点的 `/admin/` 下（地址由构建参数 apiBase 决定）。App 内嵌与电脑浏览器用的是**同一个页面**。 */
val ADMIN_URL = BuildConfig.API_BASE + "/admin/"

/** 站点前缀：只有这个前缀内的跳转留在内嵌页里 */
private val ADMIN_PREFIX = BuildConfig.API_BASE

/**
 * 后台页地址；有令牌时把令牌塞进 URL 的 **fragment**。
 *
 * 为什么用 fragment 不用 query：fragment **不会发给服务器**（不进 nginx 访问日志、
 * 不出现在 Referer 里）。后台接口和 App 用的是同一个 Bearer 令牌（服务端
 * `author_only` 只认作者 uid），所以点进去就是已登录，不用再输一遍学号密码。
 */
internal fun adminUrlFor(token: String?): String =
    if (token.isNullOrBlank()) ADMIN_URL else ADMIN_URL + "#token=" + Uri.encode(token)

/** 退路：交给系统浏览器。只在内嵌页自己打不开时用（正常路径不再走它）。 */
internal fun openAdminInBrowser(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ADMIN_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        // 没有浏览器就算了：页面上还摆着「重试」，再弹个错误框只会更糊
    }
}

/** 组装后台 WebView（抽出来是为了让 `AdminWebScreen` 只管摆位，逻辑一眼看得完）
 *  internal 是为了让 `AdminWebViewSettingsTest` 能把它捏出来验设置 —— 那几行设置
 *  正是「后台卡顿/翻不动」的解药，不能被无声改掉。 */
@SuppressLint("SetJavaScriptEnabled")
internal fun buildAdminWebView(
    ctx: Context,
    token: String?,
    onLoading: (Boolean) -> Unit,
    onError: (String) -> Unit,
): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true     // 后台页是 JS 渲染的
    settings.domStorageEnabled = true     // 页面把令牌存在 localStorage
    // ── 下面这几行是治「后台卡顿、有时候翻不动」的（用户 2026-09-21 反馈）────────────
    // 不设 useWideViewPort 时，Android WebView 会按历史遗留的 980px 视口渲染，
    // 页面被整体缩小，并且它还得在「手指是在滚动还是在双击缩放」之间猜 ——
    // 表现出来就是划不动 / 划一下要等一下。配套把缩放关掉，页面是数据面板又不需要放大。
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.textZoom = 100               // 跟随系统字体时别把 15px 正文放大成巨无霸
    isVerticalScrollBarEnabled = false    // 滚动条在手机上是噪音
    overScrollMode = View.OVER_SCROLL_NEVER
    setBackgroundColor(0xFF0B0910.toInt()) // 与页面底色一致：加载瞬间不白闪
    // ────────────────────────────────────────────────────────────────────────────
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url?.toString() ?: return true
            // 自有域名内的跳转留在内嵌页；跳到别处（含 http）交给系统浏览器 ——
            // 不在 App 里开一个我们控制不了的页面
            if (u.startsWith(ADMIN_PREFIX)) return false
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) = onLoading(false)

        override fun onReceivedError(view: WebView, req: WebResourceRequest, e: WebResourceError) {
            // 只把**主框架**失败当成"打不开"：某个图标/接口挂了不该把整页判死
            if (req.isForMainFrame) {
                onLoading(false)
                onError("打不开后台：${e.description}")
            }
        }
    }
    loadUrl(adminUrlFor(token))
}

/**
 * 后台管理（App 内嵌打开）。
 *
 * 为什么内嵌而不是丢给系统浏览器（用户 2026-09-20 问「后台为什么不能直接在 app 里进去」）：
 * · 丢浏览器会弹系统框问「是否允许打开 XX 浏览器」；用户点「拒绝」时 Android **不抛异常**
 *   → App 一声不吭，就是"点了没反应"；
 * · 进了浏览器还得再登一次（学号+密码）；
 * · 内嵌用的就是同一个网页 → 网页与 App 天然同一套视觉，不用维护两份。
 */
@Composable
fun AdminWebScreen(ctx: Context, token: String?, onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }

    // 系统返回键：先退网页历史，退不动了才关这一页（不然进了二级页一按就整页退出）
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize().pageSurface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ 返回", color = C.cyan, fontSize = 14.sp,
                modifier = Modifier.clickable { onClose() })
            Spacer(Modifier.width(16.dp))
            Text("后台管理", color = C.txt, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("用浏览器打开", color = C.txt3, fontSize = 12.sp,
                modifier = Modifier.clickable { openAdminInBrowser(ctx) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 重试就整只重建：失败态的 WebView 硬 reload 有时不重取
            key(retry) {
                AndroidView(
                    factory = { c ->
                        buildAdminWebView(
                            c, token,
                            onLoading = { loading = it }, onError = { err = it },
                        ).also { web = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (loading && err == null) {
                Text("正在打开后台…", color = C.txt3, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center))
            }
            err?.let { m ->
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(m, color = C.txt2, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.background(C.cyan, RoundedCornerShape(10.dp))
                            .clickable { err = null; loading = true; retry++ }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) { Text("重试", color = C.bg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}
