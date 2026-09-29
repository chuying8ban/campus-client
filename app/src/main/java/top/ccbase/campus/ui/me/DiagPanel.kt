package top.ccbase.campus.ui.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.net.CheckStep
import top.ccbase.campus.net.SelfCheck
import top.ccbase.campus.net.Transport

/**
 * 网络自检面板。
 *
 * 装它的理由（真实踩到）：用户在户外用移动数据，App 报「网络异常：Connection reset」，
 * 而服务端日志里**一条请求都没有** —— 我这边只能确认"没到服务器"，断在哪一层完全瞎。
 * 这一页把手机自己知道的东西摆出来：解析到哪个 IP（系统 DNS vs 公共 DoH）、443 通不通、
 * 健康检查过不过、带令牌的接口过不过。一张截图就能定位。
 *
 * 用 Box 覆盖层而不是 Dialog：Robolectric 看不见 Dialog 窗口，Dialog 里的东西没法测。
 */
@Composable
fun DiagOverlay(
    ctx: Context,
    base: String,
    version: String,
    transport: Transport,
    token: String?,
    /** 链路模式（直连 / 不带 SNI）。传进来只是为了让用户和我都能看到现在走的是哪条路 */
    modeLabel: String? = null,
    onClose: () -> Unit,
    /** 握手探测的实现。默认真握手；UI 测试注入假的（不能真去连服务器） */
    handshake: (SelfCheck.Variant, String) -> String = SelfCheck.realHandshake,
) {
    var steps by remember { mutableStateOf<List<CheckStep>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runCheck() {
        running = true
        copied = false
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { SelfCheck.run(base, transport, token, handshake = handshake) }.getOrElse { e ->
                    listOf(CheckStep("自检本身崩了", false, e.javaClass.simpleName + "：" + e.message))
                }
            }
            steps = r
            running = false
            // 状态落盘：用户常在外面，事后想翻也能翻到
            runCatching {
                File(ctx.filesDir, "diag_last.txt")
                    .writeText(SelfCheck.format(base, version, r) + "\n")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000))) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(20.dp)
                .widthIn(max = 520.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("网络自检", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                "分四层各给一个结论：域名解析 → TCP 443 → 免登录接口 → 带令牌接口。" +
                    "连不上服务器时，一眼看出断在哪一层。",
                fontSize = 11.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (running) "正在自检…" else "跑一次自检", primary = true) {
                    if (!running) runCheck()
                }
                if (steps.isNotEmpty()) {
                    Pill(if (copied) "已复制" else "复制结果") {
                        val text = SelfCheck.format(base, version, steps)
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("selfcheck", text))
                        copied = true
                    }
                }
                Pill("关闭") { onClose() }
            }

            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                steps.forEach { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            (if (s.ok) "✅ " else "❌ ") + s.name,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (s.ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(s.detail, fontSize = 11.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("地址 $base · 版本 $version", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
            if (modeLabel != null) {
                Text("链路模式：$modeLabel", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (primary) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
                RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 12.5.sp,
            color = if (primary) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )
    }
}
