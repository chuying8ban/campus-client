package top.ccbase.campus.ui.me

import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.FeedbackItem
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.pageSurface

/** 用例里的抓手（和项目其它页面一致，用 testTag 而不是按文案点） */
const val FB_INPUT = "fb-input"
const val FB_SUBMIT = "fb-submit"

/** 「我提过的」里那条作者回复（有回复才画） */
const val FB_REPLY = "fb-reply"

/** 长度上限由**服务端**管（超了回 413 + 一句人话）；这里只做输入时的手感提示 */
private const val FB_MAX = 1000

/**
 * 「给 App 提建议」。
 *
 * 用户 2026-09-21：「加一个提建议功能，每个用户都可以在 app 内给这个 app 提供建议」。
 *
 * 设计上刻意只有三块：写、发、看我提过的。没有分类、没有评分、没有附件 ——
 * 提建议的人不该为了提一句话先做选择题（用户反感把简单的入口做成表单）。
 *
 * 底部那行"会带上你的学号姓名"的隐私说明，2026-09-21 由用户拍板**删掉**（他圈出来说「这段话删了」）：
 * 写一句建议不需要先读一段说明，这页只留"写 + 提交"。
 */
@Composable
fun FeedbackScreen(
    ctx: Context,
    version: String,
    api: CampusApi,
    onClose: () -> Unit,
) {
    val token = TokenStore.token(ctx)
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<FeedbackItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    BackHandler { onClose() }

    // 进来先把我提过的拉出来。拉不到**不打断**提建议这件事本身 —— 没历史不影响写。
    LaunchedEffect(token) {
        if (!token.isNullOrBlank()) {
            when (val r = api.myFeedback(token)) {
                is ApiResult.Ok -> mine = r.value.items
                is ApiResult.Err -> Unit
            }
        }
    }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty() || busy || token.isNullOrBlank()) return
        busy = true
        msg = null
        scope.launch {
            when (val r = api.submitFeedback(token, t, version)) {
                is ApiResult.Ok -> {
                    text = ""
                    bad = false
                    msg = r.value.note.ifBlank { "收到了，谢谢 —— 我会看" }
                    (api.myFeedback(token) as? ApiResult.Ok)?.let { mine = it.value.items }
                }
                is ApiResult.Err -> {
                    bad = true
                    msg = r.message        // 服务端说的是人话（"太长了，1000 字以内就够说清了"）
                }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().pageSurface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ 返回", color = C.cyan, fontSize = 14.sp,
                modifier = Modifier.clickable { onClose() })
            Spacer(Modifier.width(16.dp))
            Text("提建议", color = C.txt, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "想加什么功能、哪里不好用、哪里看不懂 —— 都行。",
                color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                if (text.isEmpty()) {
                    Text("写在这里…", color = C.txt3, fontSize = 13.sp)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= FB_MAX) text = it },
                    textStyle = TextStyle(color = C.txt, fontSize = 13.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(C.cyan),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp).testTag(FB_INPUT),
                )
            }

            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 只在快到上限时才冒出来：平时不打扰，快写满时提醒一句
                if (text.length > FB_MAX - 200) {
                    Text("${text.length}/$FB_MAX", color = C.txt3, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                val can = text.isNotBlank() && !busy && !token.isNullOrBlank()
                Box(
                    Modifier.background(
                        if (can) C.cyan else C.card, RoundedCornerShape(10.dp),
                    ).clickable(enabled = can) { submit() }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                        .testTag(FB_SUBMIT),
                ) {
                    Text(
                        if (busy) "发送中…" else "提交",
                        color = if (can) C.bg else C.txt3,
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            msg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = if (bad) C.red else C.cyan, fontSize = 12.sp, lineHeight = 18.sp)
            }

            if (mine.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("我提过的", color = C.txt3, fontSize = 11.5.sp)
                Spacer(Modifier.height(4.dp))
                mine.forEach { it ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Text(it.text, color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOf(it.statusCn, it.createdAt.take(10)).filter { s -> s.isNotBlank() }
                                .joinToString(" · "),
                            color = C.txt3, fontSize = 11.sp,
                        )
                        // 作者的定向回复（用户 2026-09-21：「我也可以定向回复」）。
                        // 文案就用「回复」—— 不提"作者/后台"，那些词不该出现在普通用户的屏幕上。
                        if (it.replied) {
                            Spacer(Modifier.height(7.dp))
                            Text(
                                "回复：${it.reply}",
                                color = C.cyan, fontSize = 12.5.sp, lineHeight = 19.sp,
                                modifier = Modifier.testTag(FB_REPLY),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
