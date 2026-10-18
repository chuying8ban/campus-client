package top.ccbase.campus.ui.schedule

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import top.ccbase.campus.alarm.DailyTimetableCheck
import top.ccbase.campus.ui.theme.C
import java.time.LocalDateTime

/**
 * 顶部的「今天该不该核对课表」常驻提示条。
 *
 * 2026-10-17 用户口径：原来那个每天 08:00 弹的对话框不要了 —— 改成**最上方的无感常驻提醒**，
 * 「不影响用户使用的同时一直常驻在上面」。所以这里：
 *
 *  · 只占一条细带（约 32dp），不是弹窗、不挡任何操作，页面照常滚动；
 *  · 不提供关闭按钮（关掉就不叫常驻了），但点一下就直接进更新课表；
 *  · 状态自己会翻：今天还没导过 → 「还没核对课表」；导过一次 → 「今天 HH:mm 已核对」。
 *    判据只用**真的写过库的那次导入**（[DailyTimetableCheck.markUpdated]），不拿"点了按钮"当成功。
 *  · 每 30 秒 + 每次回到前台各重算一次，跨天/跨过 08:00 都不用重开 App。
 */
@Composable
fun DailyCheckBanner(ctx: Context, onUpdate: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val now = remember(tick) { LocalDateTime.now() }
    val updated = remember(tick, now) { DailyTimetableCheck.updatedToday(ctx, now) }
    // updatedAt 是 "2026-10-17T08:12:33"：只要时分，别在细带里塞秒
    val at = remember(tick, now) { DailyTimetableCheck.updatedAt(ctx)?.substringAfter('T')?.take(5) }
    val due = remember(tick, now) { DailyTimetableCheck.due(ctx, now) }

    val dot = when {
        updated -> C.green
        due -> C.amber
        else -> C.txt3
    }
    val text = when {
        updated -> "今天 $at 已核对课表 · 学校临时调课不会自动同步"
        due -> "今天还没核对课表 · 点右面更新"
        else -> "课表每天要自己对一次 · 08:00 后可更新"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(C.bgSoft.copy(alpha = 0.72f))
            .clickable { onUpdate() }
            .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 11.5.sp,
            color = if (updated) C.txt3 else C.txt2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        Spacer(Modifier.width(10.dp))
        Text("更新 ›", fontSize = 11.5.sp, color = C.violet)
    }
    // 一条极细分隔线收住，避免和下面页面标题糊在一起（用高度 1dp 的空 Box，比 Divider 轻）
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}
