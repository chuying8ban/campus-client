package top.ccbase.campus.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.data.local.PeriodTableStore
import top.ccbase.campus.domain.PeriodTable
import top.ccbase.campus.domain.StudyWindow
import top.ccbase.campus.ui.theme.C

/**
 * 作息时间表（2026-10-21 用户要求「支持更改课表时间表」）。
 *
 * 能改的东西就是他那句话里列的：每节课多长时间、课间、大课间（哪几节之后是大课间 + 多久）、
 * 上午第一节、下午第一节、晚上第一节、以及早自习/晚自习时间（可以设成「没有早自习」）。
 *
 * 为什么不是「每节课一个输入框」：12 个节点 ×2 个时间 = 24 个输入框，改起来比手抄还累，
 * 而且很容易出现「2 节 10:20 下课、3 节 10:15 上课」这种时间倒挂。这里只让用户填
 * **规则**（每节多长、课间多少、大课间在哪），钟点由规则算出来，并且在下面**实时预览**。
 * 自动算出来的表永远不会自相矛盾。
 *
 * 默认值 = 学校现行作息（09:30 第一节、45 分钟一节、5 分钟课间、第 2/7 节后 20 分钟大课间），
 * 算出来和教务官方课表逐分钟一致 —— 不点保存就一点变化都没有。
 */
@Composable
fun PeriodTableEditor(saving: Boolean, onSave: (PeriodTable, Boolean) -> Unit) {
    var t by remember { mutableStateOf(PeriodTableStore.table) }
    var applyToStudy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val units = t.units()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        Text(
            "作息时间表",
            color = C.txt, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "只填规则，钟点自动算。周视图左列、今日页时间、课前提醒都跟着这张表走。",
            color = C.txt2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 5.dp, bottom = 6.dp),
        )

        // —— 三段：上午 / 下午 / 晚上
        SegmentCard("上午") {
            TimeRow("第一节时间", t.morningStart) { t = t.copy(morningStart = it) }
            Stepper("上午节数", t.morningCount, " 节", 0, 12, 1) { t = t.copy(morningCount = it) }
        }
        SegmentCard("下午") {
            TimeRow("第一节时间", t.afternoonStart) { t = t.copy(afternoonStart = it) }
            Stepper("下午节数", t.afternoonCount, " 节", 0, 12, 1) { t = t.copy(afternoonCount = it) }
        }
        SegmentCard("晚上") {
            TimeRow("第一节时间", t.eveningStart) { t = t.copy(eveningStart = it) }
            Stepper("晚上节数", t.eveningCount, " 节", 0, 12, 1) { t = t.copy(eveningCount = it) }
        }

        // —— 节长与课间
        SegmentCard("每节课 / 课间") {
            Stepper("每节课", t.classMinutes, " 分钟", 10, 180, 5) { t = t.copy(classMinutes = it) }
            Stepper("普通课间", t.breakMinutes, " 分钟", 0, 90, 5) { t = t.copy(breakMinutes = it) }
            Stepper("大课间时长", t.bigBreakMinutes, " 分钟", 0, 180, 5) { t = t.copy(bigBreakMinutes = it) }
            Text(
                "大课间放在第几节之后（可多选）",
                color = C.txt2, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1 until t.totalUnits.coerceAtLeast(1)).forEach { u ->
                    val on = u in t.bigBreakAfter
                    FilterChip(
                        selected = on,
                        onClick = {
                            t = t.copy(bigBreakAfter = if (on) t.bigBreakAfter - u else (t.bigBreakAfter + u).sorted())
                        },
                        label = { Text("第 $u 节后") },
                    )
                }
            }
        }

        // —— 早晚自习
        SegmentCard("早晚自习") {
            StudyEditor(
                title = "早自习",
                window = t.morningStudy,
                onChange = { t = t.copy(morningStudy = it) },
            )
            Spacer(Modifier.height(6.dp))
            StudyEditor(
                title = "晚自习",
                window = t.eveningStudy,
                onChange = { t = t.copy(eveningStudy = it) },
            )
        }

        // —— 实时预览
        Text("算出来的时间（这一节到那一节下课）", color = C.txt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp, bottom = 7.dp))
        if (units.isEmpty()) {
            Text("上面全是 0 节，先给个节数", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } else {
            Column(Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(12.dp)).border(0.8.dp, C.line, RoundedCornerShape(12.dp)).padding(10.dp)) {
                units.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        pair.forEach { u ->
                            Text(
                                "第 ${u.index} 节  ${u.start}-${u.end}",
                                color = C.txt2, fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (t.morningStudy.enabled || t.eveningStudy.enabled) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同时改已排的自习时间", color = C.txt2, fontSize = 12.5.sp)
                    Text("起止时间应用到已排的自习；关掉的那一档会一并清掉", color = C.txt3, fontSize = 10.5.sp)
                }
                Switch(checked = applyToStudy, onCheckedChange = { applyToStudy = it })
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }

        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val err = t.validate()
                    if (err != null) error = err else {
                        error = null
                        onSave(t.normalized(), applyToStudy)
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "保存中…" else "保存作息表") }
            OutlinedButton(onClick = { t = PeriodTable(); error = null }) { Text("恢复默认") }
        }
        Text(
            "提示：改作息表不会挪动已经排好的课。原来按第 3 节排的课，还是第 3 节，只是钟点跟着变。",
            color = C.txt3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SegmentCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .background(C.card, RoundedCornerShape(14.dp))
            .border(0.8.dp, C.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Text(title, color = C.cyan, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun TimeRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.txt2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TimeChoice("", value, onChange)
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    suffix: String,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.txt2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StepBtn("−") { onChange((value - step).coerceAtLeast(min)) }
        Text(
            "$value$suffix",
            color = C.txt, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 68.dp).padding(horizontal = 4.dp),
        )
        StepBtn("＋") { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepBtn(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp)
            .background(C.card, RoundedCornerShape(9.dp))
            .border(0.8.dp, C.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = C.txt2, fontSize = 15.sp) }
}

@Composable
private fun StudyEditor(title: String, window: StudyWindow, onChange: (StudyWindow) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.txt2, fontSize = 13.sp)
            Text(
                if (window.enabled) "${window.start}-${window.end}" else "没有$title",
                color = if (window.enabled) C.txt3 else C.txt3, fontSize = 10.5.sp,
            )
        }
        Switch(checked = window.enabled, onCheckedChange = { onChange(window.copy(enabled = it)) })
    }
    if (window.enabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TimeChoice("开始", window.start, { onChange(window.copy(start = it)) }, Modifier.weight(1f))
            TimeChoice("结束", window.end, { onChange(window.copy(end = it)) }, Modifier.weight(1f))
        }
    }
}
