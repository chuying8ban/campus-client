package top.ccbase.campus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.alarm.DiagLog
import top.ccbase.campus.alarm.SilenceDiag
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits

/**
 * 自动静音的自检面板（默认收起，符合"提醒类 UI 压到最淡"的一贯做法）。
 *
 * 为什么要有它：静音失败在真机上就是"什么都没发生" —— 用户看不到失败原因，
 * 没法描述、也没法自己排查。这里把三件事摊开：
 *   ① 体检：缺哪一项权限，以及缺了会怎样
 *   ② 系统眼里的下一个闹钟（和我们排的对不上就说明没排上）
 *   ③ **实际触发记录**：排上了没有、响了没有、为什么没静音
 *
 * 第 ③ 项是关键 —— 前两项只能回答"条件够不够"，只有记录能回答"到底发生了没有"。
 */
@Composable
fun SilenceDiagPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // 展开时重算一次：用户多半是刚授权完回来看的
    var tick by remember { mutableStateOf(0) }
    val snap = remember(tick) { DiagLog.snapshot(ctx) }
    val items = remember(snap) { SilenceDiag.items(snap) }
    val verdict = remember(items) { SilenceDiag.verdict(items) }
    val log = remember(tick) { DiagLog.read(ctx, limit = 6) }
    val nextAlarm = remember(tick) { DiagLog.systemNextAlarm(ctx) }

    val allGood = items.all { it.ok }

    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { open = !open; if (open) tick++ },
        ) {
            Box(
                Modifier.size(6.dp)
                    .background(if (allGood) C.cyan else C.amber, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(9.dp))
            Text(
                if (open) "自检" else verdict,
                color = if (allGood) C.txt3 else C.amber,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text("${if (open) "收起" else "自检"} ›", color = C.txt3, fontSize = 11.sp)
        }

        if (!open) return@Column

        Spacer(Modifier.height(10.dp))

        // ① 体检：缺哪项就摊开"照着做"的步骤（用户要的是"到底开哪个"，不是"没授权"）
        items.forEach { item -> key(item.name) {
            // 这一项自己管自己的展开状态（缺项才需要）
            var guide by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "· " + (if (item.ok) "✓ " else "✗ ") + item.name,
                    color = if (item.ok) C.txt3 else C.amber,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    item.detail,
                    color = C.txt3,
                    fontSize = 10.5.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!item.ok) {
                    Text(
                        item.action + " ›",
                        color = C.amber, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { DiagLog.openItem(ctx, item) },
                    )
                }
            }
            if (!item.ok && item.steps.isNotEmpty()) {
                Text(
                    if (guide) "收起怎么开" else "怎么开 ›",
                    color = C.txt3, fontSize = 10.5.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                        .clickable { guide = !guide },
                )
                if (guide) {
                    Column(
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        item.steps.forEachIndexed { i, step ->
                            Row {
                                Text(
                                    "${i + 1}.",
                                    color = C.amber, fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(step, color = C.txt2, fontSize = 10.5.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        } }

        Spacer(Modifier.height(4.dp))

        // ② 系统眼里的下一个闹钟 —— 和我们排的对不上 = 没排上
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("系统下一个闹钟（含其他 App）", color = C.txt3, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                nextAlarm ?: "无 —— 说明一条都没排上",
                color = if (nextAlarm == null) C.amber else C.cyan,
                fontSize = 11.sp, style = Digits,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ③ 实际触发记录
        Text("最近触发记录", color = C.txt3, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        if (log.isEmpty()) {
            Text(
                "还没有记录。到点没响、或者静音没生效，这里会留下原因。",
                color = C.txt3, fontSize = 10.5.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                log.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.at, color = C.txt3, fontSize = 10.5.sp, style = Digits)
                        Spacer(Modifier.width(8.dp))
                        Text(a.what, color = C.txt3, fontSize = 10.5.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (a.ok) "成功" else "失败",
                            color = if (a.ok) C.cyan else C.amber,
                            fontSize = 10.5.sp,
                        )
                        if (!a.ok && a.reason.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                SilenceDiag.explain(a.reason),
                                color = C.amber, fontSize = 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
