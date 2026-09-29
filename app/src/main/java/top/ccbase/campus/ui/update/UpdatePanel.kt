package top.ccbase.campus.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.update.DownloadProgress
import top.ccbase.campus.update.UpdateLogic
import top.ccbase.campus.update.UpdateManifest
import java.io.File

/**
 * 更新框的四种状态。**刻意用普通 Box 浮层，不用 `Dialog`** ——
 * Dialog 是独立窗口，Robolectric 看不见，那这个框就永远测不到
 * （这个坑在抢课页的确认层上踩过一次，见技能里的记录）。
 */
sealed class UpdateUi {
    data class Offer(val m: UpdateManifest, val forced: Boolean, val why: String) : UpdateUi()

    /**
     * **下载之前**就要系统许可的那一步。
     *
     * 和下面 `Ready.needPermission` 的区别：那个是"下完了才发现装不了"的兜底，
     * 这个是"先问、再下"的正路 —— 用户点「更新」后先来这里，一个字节都不下。
     */
    data class AskPermission(val m: UpdateManifest, val forced: Boolean) : UpdateUi()
    /**
     * @param detail 更细的下载状态（已下/总字节、第几次尝试、退避剩余毫秒）。
     *   null = 老调用方只给百分比，界面照旧显示百分比。
     */
    data class Progress(
        val m: UpdateManifest,
        val pct: Float,
        val detail: DownloadProgress? = null,
    ) : UpdateUi()
    data class Ready(val m: UpdateManifest, val file: File, val needPermission: Boolean) : UpdateUi()
    data class Failed(val m: UpdateManifest?, val msg: String) : UpdateUi()
}

/**
 * 版本更新浮层。
 *
 * 三条原则，都是"别骗用户"：
 *  1. 说清**从哪个版本到哪个版本**，更新说明逐条列出（不写"修复若干问题"）；
 *  2. 强制更新（服务端 force / 版本过低）时**不给「以后再说」**，并说明为什么必须更新；
 *  3. 下载完成时把**校验和**摆出来 —— 旁加载的包，用户有权知道它被验证过。
 */
@Composable
fun UpdateOverlay(
    state: UpdateUi,
    currentVersion: String,
    onStart: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenPermission: () -> Unit = {},
) {
    Box(
        Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.72f))
            .clickable(enabled = state !is UpdateUi.Progress && (state as? UpdateUi.Offer)?.forced != true) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.88f)
                .background(C.bgSoft, RoundedCornerShape(16.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}          // 吃掉点击，别穿透到背后的关闭
                .padding(20.dp),
        ) {
            when (state) {
                is UpdateUi.Offer -> {
                    val m = state.m
                    Text(
                        if (state.forced) "需要更新到 ${m.versionName}" else "发现新版本 ${m.versionName}",
                        color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("当前 $currentVersion · 新版本 ${m.versionName}" +
                            (m.size.takeIf { it > 0 }?.let { " · ${UpdateLogic.humanSize(it)}" } ?: ""),
                        color = C.txt3, fontSize = 12.sp)
                    if (state.forced && state.why.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(state.why, color = C.amber, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (m.notes.isEmpty()) {
                        Text("这次更新没有写说明。", color = C.txt2, fontSize = 13.sp)
                    } else {
                        Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                            m.notes.forEach { n ->
                                Text("· $n", color = C.txt2, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!state.forced) {
                            Btn("以后再说", primary = false, onClick = onSkip)
                        }
                        Btn(if (state.forced) "立即更新" else "更新", primary = true, onClick = onStart)
                    }
                }

                is UpdateUi.AskPermission -> {
                    Text("更新前需要系统许可", color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    UpdateLogic.installPermissionSteps().forEach { s ->
                        Text("· $s", color = C.txt2, fontSize = 12.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("在允许之前不会开始下载。", color = C.txt3, fontSize = 11.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!state.forced) Btn("以后再说", primary = false, onClick = onSkip)
                        Btn("去允许", primary = true, onClick = onOpenPermission)
                    }
                }

                is UpdateUi.Progress -> {
                    Text("正在下载更新", color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val d = state.detail
                    val retrying = (d?.retryInMs ?: 0L) > 0L
                    Text(
                        when {
                            // 断了正在等退避：必须说出来，否则用户看到的就是一个不动的条
                            retrying -> "${state.m.versionName} · ${d?.note ?: "正在重试"}" +
                                "（第 ${d?.attempt ?: 2} 次 · ${(d!!.retryInMs / 1000)} 秒）"
                            d != null && d.sizeText().isNotBlank() -> "${state.m.versionName} · ${d.sizeText()}"
                            state.pct < 0f -> "${state.m.versionName} · 正在下载…"
                            else -> "${state.m.versionName} · ${(state.pct * 100).toInt()}%"
                        },
                        color = if (retrying) C.amber else C.txt2, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    val p = if (state.pct < 0f) 0.02f else state.pct.coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp).background(C.line, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(p).height(4.dp).background(C.violet, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (retrying) {
                            // 说清"断的是网络，不是你下的东西"：已经下好的字节还在，重试是接着下
                            "网络断了，App 会自己接着上次下到的地方继续 —— 不用重新下一遍，" +
                                "下完还会校验，校验不过不会安装。"
                        } else {
                            "下载完会自动校验文件，校验不过不会安装。中途断网会自动接着下。"
                        },
                        color = C.txt3, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }

                is UpdateUi.Ready -> {
                    Text("更新包已就绪", color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("${state.m.versionName} · ${UpdateLogic.humanSize(state.file.length())} · " +
                            "校验和 ${state.m.sha256.take(12)}… 已核对",
                        color = C.txt2, fontSize = 12.sp, lineHeight = 18.sp)
                    if (state.needPermission) {
                        Spacer(Modifier.height(10.dp))
                        Text(UpdateLogic.installPermissionHint(false)!!, color = C.amber, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("稍后", primary = false, onClick = onDismiss)
                        if (state.needPermission) Btn("去开启权限", primary = true, onClick = onOpenPermission)
                        else Btn("安装", primary = true, onClick = onInstall)
                    }
                }

                is UpdateUi.Failed -> {
                    Text("更新没成功", color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(state.msg, color = C.red, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("关闭", primary = false, onClick = onDismiss)
                        if (state.m != null) Btn("重试", primary = true, onClick = onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun Btn(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (primary) C.violet.copy(alpha = 0.55f) else C.lineHi, RoundedCornerShape(10.dp))
            .background(if (primary) C.violet.copy(alpha = 0.16f) else C.card, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) C.violet else C.txt2, fontSize = 13.sp)
    }
}
