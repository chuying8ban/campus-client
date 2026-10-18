package top.ccbase.campus.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.PlanEvent
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.ui.theme.Digits
import java.time.LocalDate
import java.time.LocalTime

/**
 * 日程规划（2026-10-18 新增，用户："用户可以输入日程的时间和内容，自建日程的显示同课程的
 * 显示，二者不要挤一块了，要有所区分"）。
 *
 * 三件事在这里定死：
 *  ① 日程是**用户自己的表**（plan_events），不并进课表 —— 下次导课表不会把它冲掉；
 *  ② 显示上跟课程同一套"左列时间 + 右边内容"的骨架，所以看起来是一家；
 *  ③ 区分靠**颜色 + 标签**：日程一律用强调色，并在时间后面标「日程」；课程是正文色。
 *     今天页和桌面小组件用的是同一套规则。
 */
private fun today(): String = LocalDate.now().toString()

private fun hhmmNow(): String {
    val t = LocalTime.now()
    return "%02d:%02d".format(t.hour, t.minute)
}

/** 今日页里那一块「今日日程」：空态给一句提示，有内容就按时间列出来。 */
@Composable
fun TodayPlanSection(db: CampusDb, day: String, onOpen: () -> Unit, onAdd: () -> Unit) {
    val plans by db.dao().plansOn(day).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("今日日程", color = C.txt, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            if (plans.isNotEmpty()) {
                Text(
                    "${plans.count { it.done == 0 }} 项待办",
                    color = C.txt3, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "全部 ›",
                color = C.violet, fontSize = 12.sp,
                modifier = Modifier.clickable { onOpen() }.padding(6.dp),
            )
        }
        if (plans.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(C.card, RoundedCornerShape(12.dp))
                    .border(1.dp, C.line, RoundedCornerShape(12.dp))
                    .clickable { onAdd() }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", color = C.violet, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text("加一条日程，例如「16:00 交实验报告」", color = C.txt2, fontSize = 12.5.sp)
            }
        } else {
            plans.forEach { p ->
                PlanRow(p) { db.dao() }
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier.clickable { onAdd() }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋ 再加一条", color = C.violet, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlanRow(p: PlanEvent, dao: () -> top.ccbase.campus.data.local.CampusDao) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.card, RoundedCornerShape(12.dp))
            .border(1.dp, C.line, RoundedCornerShape(12.dp))
            .clickable {
                scope.launch { dao().setPlanDone(p.id, if (p.done == 0) 1 else 0) }
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (p.end.isBlank()) p.start else "${p.start}–${p.end}",
            color = C.violet, fontSize = 12.sp, style = Digits,
            modifier = Modifier.width(88.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "日程", color = C.violet, fontSize = 10.sp,
                    modifier = Modifier
                        .background(C.violet.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    p.title,
                    color = if (p.done == 0) C.txt else C.txt3,
                    fontSize = 13.sp,
                    textDecoration = if (p.done == 0) null else androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    maxLines = 2,
                )
            }
            if (!p.note.isNullOrBlank()) {
                Text(p.note, color = C.txt3, fontSize = 11.sp, maxLines = 1)
            }
        }
        // 删除放在行尾、只占 34dp：删完这一行直接消失，不留空壳（用户："日程能够删除，
        // 不要在那里占位置"）。勾完成是点整行，删是点这个 ×，两个动作不会误触。
        Text(
            "✕",
            color = C.txt3, fontSize = 13.sp,
            modifier = Modifier
                .clickable { scope.launch { dao().deletePlan(p.id) } }
                .padding(horizontal = 9.dp, vertical = 8.dp),
        )
    }
}

/** 全部日程：按天分组，新建/删除都在这儿。 */
@Composable
fun DayAgendaScreen(db: CampusDb, onClose: () -> Unit, startWithEditor: Boolean = false) {
    // 系统返回键 = 回上一级（用户 2026-10-18："在图库等子页面按返回键要回退到上一级，而不是回桌面"）
    androidx.activity.compose.BackHandler { onClose() }
    var editing by remember { mutableStateOf<PlanEvent?>(null) }
    // 从「＋ 加一条」进来的直接开编辑层，少一次点击
    var showEditor by remember { mutableStateOf(startWithEditor) }
    val all by db.dao().allPlans().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // 整页要**自己铺底色**（之前直接叠在今日页上，底下的课表和顶部"已核对课表"那一条就透上来了，
    // 看着像日程页烂了）；再加状态栏留白，标题才不会钻到系统栏底下。
    Column(
        Modifier
            .fillMaxSize()
            // 整屏浮层：必须不透明 + 吃掉空白触摸，否则下面的今日页会透上来（overlaySurface 就是干这个的）
            .overlaySurface
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日程规划", color = C.txt, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("关闭", color = C.txt2, fontSize = 13.sp, modifier = Modifier.clickable { onClose() })
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.violet.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                .clickable { editing = null; showEditor = true }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("＋ 新建日程", color = C.violet, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))
        if (all.isEmpty()) {
            Spacer(Modifier.height(30.dp))
            Text(
                "还没有日程。\n日程和课表分开存：课表是教务导进来的，日程是你自己排的，导课表不会动它。",
                color = C.txt3, fontSize = 12.5.sp, lineHeight = 20.sp,
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val byDay = all.groupBy { it.day }.toList().sortedByDescending { it.first }
                byDay.forEach { (day, list) ->
                    item(key = "h_$day") {
                        Text(
                            dayLabel(day),
                            color = C.txt3, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                        )
                    }
                    items(list, key = { it.id }) { p ->
                        PlanRow(p) { db.dao() }
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                "改", color = C.txt3, fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable { editing = p; showEditor = true }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            // 删除已经挪到行尾的 ✕（同一页只有一个删除入口，不留第二处）
                        }
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }

    if (showEditor) {
        PlanEditor(
            init = editing,
            onDismiss = { showEditor = false },
            onSave = { ev ->
                scope.launch {
                    db.dao().putPlan(ev)
                    showEditor = false
                }
            },
        )
    }
}

private fun dayLabel(day: String): String {
    val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    val today = LocalDate.now()
    return when (d) {
        today -> "今天 · $day"
        today.plusDays(1) -> "明天 · $day"
        else -> day
    }
}

/**
 * 编辑弹层。
 *
 * 输入框就用最朴素的 BasicTextField：日期/时间都是手打 `2026-10-18` / `16:00`，
 * 不做滚轮选择器 —— 滚轮在 4 寸小屏上要滑半天，打字两个字符更快。
 * 保存前做最小校验（日期/时间格式、内容不能空），错了在下面用一句话说清楚。
 */
@Composable
private fun PlanEditor(init: PlanEvent?, onDismiss: () -> Unit, onSave: (PlanEvent) -> Unit) {
    var title by remember { mutableStateOf(init?.title.orEmpty()) }
    var day by remember { mutableStateOf(init?.day ?: today()) }
    var start by remember { mutableStateOf(init?.start ?: hhmmNow()) }
    var end by remember { mutableStateOf(init?.end.orEmpty()) }
    var note by remember { mutableStateOf(init?.note.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.bg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Text(
                if (init == null) "新建日程" else "改日程",
                color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Field("内容", title, "例如：交实验报告 / 去图书馆") { title = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.3f)) { Field("日期", day, "2026-10-18") { day = it } }
                Column(Modifier.weight(1f)) { Field("开始", start, "16:00") { start = it } }
                Column(Modifier.weight(1f)) { Field("结束", end, "可空") { end = it } }
            }
            Spacer(Modifier.height(10.dp))
            Field("备注", note, "可选：地点、要带什么") { note = it }
            err?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF6B81), fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f).height(46.dp)
                        .border(1.dp, C.line, RoundedCornerShape(12.dp))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text("取消", color = C.txt2, fontSize = 13.sp) }
                Box(
                    Modifier
                        .weight(1.4f).height(46.dp)
                        .background(C.violet, RoundedCornerShape(12.dp))
                        .clickable {
                            val t = title.trim()
                            when {
                                t.isEmpty() -> err = "写一句内容吧"
                                !day.matches(Regex("""\d{4}-\d{2}-\d{2}""")) -> err = "日期要写成 2026-10-18"
                                !start.matches(Regex("""\d{1,2}:\d{2}""")) -> err = "开始时间要写成 16:00"
                                end.isNotBlank() && !end.matches(Regex("""\d{1,2}:\d{2}""")) -> err = "结束时间要写成 17:00"
                                else -> onSave(
                                    PlanEvent(
                                        id = init?.id ?: 0,
                                        day = day.trim(),
                                        start = pad(start),
                                        end = if (end.isBlank()) "" else pad(end),
                                        title = t,
                                        note = note.trim().ifBlank { null },
                                        done = init?.done ?: 0,
                                    ),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("保存", color = Color(0xFF160F22), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun pad(t: String): String {
    val s = t.trim()
    val h = s.substringBefore(":").trim().toIntOrNull() ?: return s
    val m = s.substringAfter(":", "0").trim().take(2).toIntOrNull() ?: 0
    return "%02d:%02d".format(h, m)
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = C.txt3, fontSize = 10.5.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(C.card, RoundedCornerShape(10.dp))
                .border(1.dp, C.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) Text(hint, color = C.txt3, fontSize = 12.5.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = C.txt, fontSize = 12.5.sp),
                cursorBrush = SolidColor(C.violet),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
