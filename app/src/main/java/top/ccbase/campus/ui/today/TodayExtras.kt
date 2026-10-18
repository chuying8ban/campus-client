package top.ccbase.campus.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.school.SchoolRecordsCache
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import top.ccbase.campus.widget.WidgetPin

/**
 * 今日页底部的两块"顺手"：考试倒计时、把小组件放上桌面。
 *
 * 为什么放在**今天**这一页的最后：这两个都是"今天会用到一次"的信息 ——
 * 考试还有几天、桌面能不能一眼看到课。它们都不该占顶部的位置，
 * 也不该像弹窗那样打断人；翻到底看见就够了。
 */
@Composable
fun ExamStrip(db: CampusDb, onOpen: () -> Unit) {
    var next by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val records = withContext(Dispatchers.IO) { SchoolRecordsCache.load(db) }
        val today = LocalDate.now()
        val e = records?.nextExam(today)
        val days = e?.daysFrom(today)
        next = e?.let { exam ->
            val head = when (days) {
                null -> "有一场考试"
                0L -> "今天就考试"
                1L -> "明天考试"
                else -> "还有 $days 天考试"
            }
            head to listOfNotNull(exam.name, exam.time, exam.room?.let { "考场 $it" }).joinToString(" · ")
        }
        loaded = true
    }

    if (!loaded) return
    val n = next ?: return
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp)
            .background(C.card, RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.first, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.amber, style = Digits)
            Spacer(Modifier.weight(1f))
            Text("考试安排 ›", fontSize = 11.sp, color = C.txt3)
        }
        n.second?.let {
            Spacer(Modifier.height(5.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = C.txt2)
        }
    }
}

/**
 * 「把今天的课放到桌面」—— 邀请卡。
 *
 * 只在**桌面上还没有这个小组件**时出现（[WidgetPin.placedCount]），
 * 点了直接弹系统的"添加到主屏幕"，而不是教用户长按桌面去找。
 * 系统不支持时改成文字说明（国产 ROM 常关掉这个能力），按钮不出现 —— 不给点了没反应的按钮。
 */
@Composable
fun WidgetInvite() {
    val ctx = LocalContext.current
    var placed by remember { mutableIntStateOf(WidgetPin.placedCount(ctx)) }
    var pinned by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val supported = remember { WidgetPin.supported(ctx) }

    // 从桌面回来 / 系统确认框回来后重算：用户可能已经加上了
    LifecycleResumeEffect(Unit) {
        placed = WidgetPin.placedCount(ctx)
        onPauseOrDispose { }
    }
    if (placed > 0) return

    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp)
            .border(1.dp, C.line, RoundedCornerShape(14.dp))
            .background(C.bgSoft, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("把「今天的课」放到桌面", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.txt)
        Spacer(Modifier.height(5.dp))
        Text(
            "不用打开 App：桌面上那张小卡片直接显示正在上 / 下一节课和教室，每半小时自己刷新。",
            fontSize = 11.sp, lineHeight = 18.sp, color = C.txt3,
        )
        Spacer(Modifier.height(12.dp))
        if (supported) {
            Box(
                Modifier.fillMaxWidth().height(38.dp)
                    .background(C.amber.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                    .clickable {
                        val ok = WidgetPin.requestPin(ctx)
                        pinned = ok
                        hint = if (ok) "系统会让你点一下「添加」——加完就一直在桌面了。" else null
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (pinned) "已发送添加请求 · 再点一次可再加一个" else "添加到主屏幕",
                    fontSize = 13.sp, color = C.amber,
                )
            }
        } else {
            Text(
                "长按桌面空白处 → 小部件 → 找「校园」→ 今日课表",
                fontSize = 11.sp, lineHeight = 18.sp, color = C.txt2,
            )
        }
        hint?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 10.sp, lineHeight = 16.sp, color = C.txt3)
        }
    }
}
