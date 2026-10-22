package top.ccbase.campus.ui.me

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.ccbase.campus.alarm.Rescheduler
import top.ccbase.campus.data.local.*
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.domain.PeriodTable
import top.ccbase.campus.domain.PeriodUnit
import top.ccbase.campus.domain.weekSet
import top.ccbase.campus.domain.weeksLabel
import top.ccbase.campus.ui.theme.C
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * 编辑课表（2026-10-21 整体重做）。
 *
 * 用户那天要的四件事都落在这一屏：
 * ① 「按照时间顺序来排」—— 一天从早自习、第 1 节…最后一节、晚自习**按时间轴纵向排**；
 *    老版把 7 天并成 7 列横向挤，手机一屏只看得到半天，也看不出先后。
 * ② 「没有课的卡片要空在那」—— 每一节都有一张卡：有课是课程卡，没课是一张空的
 *    「＋ 第 N 节 · 09:30-10:15」，点它当场新增。老版没课的格子不画，
 *    用户得先按「＋ 课程」再自己选星期/时间，根本不知道空在哪。
 * ③ 「支持点击即改」—— 点卡片直接进表单，节次/星期/周次/地点全是点选。
 * ④ 「改一个地点同步到所有相关课程」—— 地点下面一行两个选择：
 *    「只改这一条」/「同步到同一门课的 N 处」（早晚自习同理：所有早自习/所有晚自习）。
 *
 * 第二个页签是「作息时间表」—— 每节课多久、课间/大课间、上午下午晚上第一节、
 * 早晚自习（可以设成没有），见 [PeriodTableEditor]。
 */
@Composable
fun ScheduleEditor(ctx: Context, db: CampusDb, close: () -> Unit) {
    // 保存**不能**挂在 composition 的 scope 上：用户点完「保存」顺手按返回/切页，
    // composition 一销毁 scope 就被取消，保存被半路掐掉（Surface 已经关了，看起来像存上了）。
    // 用进程级 scope + IO 线程，写完再改状态。
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var plan by remember { mutableStateOf<Seed?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var day by remember { mutableIntStateOf(LocalDate.now().dayOfWeek.value.coerceIn(1, 7)) }
    var form by remember { mutableStateOf<EditState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 作息表先读：空卡片上的钟点全来自它
        PeriodTableStore.load(db)
        plan = ManualSchedule.snapshot(db)
    }

    fun commit(next: Seed, note: String? = null, closeForm: Boolean = true) {
        scope.launch {
            saving = true
            try {
                ManualSchedule.save(db, next)
                plan = next
                if (closeForm) form = null
                Rescheduler.request(ctx, "manual_schedule")
                message = note ?: "已保存到本机，云端刷新不会覆盖这条安排"
            } catch (e: Exception) {
                message = "保存失败，原安排未改变"
            } finally {
                saving = false
            }
        }
    }

    fun applyEdit(f: EditState) {
        val p = plan ?: return
        val err = f.validate(p)
        if (err != null) {
            message = err
            return
        }
        val note = if (f.syncRoom && f.siblings(p) > 1) {
            if (f.studyKind != null) "已保存，并把${f.studyKind}的地点同步到另外 ${f.siblings(p) - 1} 天" else "已保存，地点已同步到同一门课的另外 ${f.siblings(p) - 1} 处"
        } else {
            null
        }
        commit(f.applyTo(p), note)
    }

    BackHandler { if (form != null) form = null else close() }

    Surface(Modifier.fillMaxSize(), color = C.bg) {
        val f = form
        if (f != null) {
            val id = f.slotId
            EditArrangement(
                state = f,
                plan = plan,
                saving = saving,
                onChange = { form = it },
                onSave = { applyEdit(f) },
                onDelete = if (id != null) ({ askDelete = true }) else null,
                onCancel = { form = null },
                // 拆成单节：只对「按节次排、并且真的跨节」的已有课程开放
                onSplit = if (id != null && f.studyKind == null && !f.customTime && f.endUnit > f.startUnit) {
                    {
                        val p = plan
                        if (p != null) {
                            // 先按表单**当前**的值落一次（用户可能刚把 1-2 改成 1-3 就点拆分），
                            // 再拆 —— 否则拆的是数据库里的旧节次，界面会回退一下。
                            val cur = f.applyTo(p)
                            val (next, n) = ManualSchedule.splitSpan(cur, id)
                            val first = next.slots.firstOrNull {
                                it.weekday == f.weekday && it.p_start == f.startUnit && it.course_id == f.courseId
                            }
                            if (n == 0) {
                                message = "拆不开：这一天第 ${f.startUnit}-${f.endUnit} 节里已经有别的课占着，先把那一条挪走"
                            } else {
                                form = first?.let { EditState.of(next, it) }
                                commit(
                                    next,
                                    note = "已拆成 $n 条单节，从此每一节都能单独改" +
                                        (if (first != null) "；已给你打开第 ${f.startUnit} 节" else ""),
                                    closeForm = first == null,
                                )
                            }
                        }
                    }
                } else null,
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                EditorHeader(tab = tab, onTab = { tab = it }, onClose = close)
                if (tab == 0) {
                    DayEditor(
                        plan = plan,
                        day = day,
                        onDay = { day = it },
                        onPickSlot = { slot -> form = EditState.of(plan, slot) },
                        onPickStudy = { study -> form = EditState.ofStudy(plan, study) },
                        onNewCourse = { unit -> form = EditState.newCourse(day, unit) },
                        onNewCustomCourse = { form = EditState.newCourse(day, 1).copy(customTime = true) },
                        onNewStudy = { kind -> form = EditState.newStudy(day, kind, plan) },
                        onOpenTable = { tab = 1 },
                    )
                } else {
                    PeriodTableEditor(
                        saving = saving,
                        onSave = { table, applyToStudy ->
                            scope.launch {
                                saving = true
                                try {
                                    // 先落作息表（它自己当场生效），再把自习时间同步进本机快照；
                                    // 反过来的话界面会先用旧表重算一遍自习时间。
                                    PeriodTableStore.save(db, table)
                                    val base = plan
                                    var removed = 0
                                    val touched = if (applyToStudy && base != null) {
                                        val (next, cut) = ManualSchedule.applyStudyWindow(base, table)
                                        removed = cut
                                        next
                                    } else {
                                        base
                                    }
                                    if (touched != null && touched != base) ManualSchedule.save(db, touched)
                                    if (touched != null) plan = touched
                                    Rescheduler.request(ctx, "period_table")
                                    message = when {
                                        !applyToStudy -> "作息表已保存"
                                        removed > 0 -> "作息表已保存；作息表里没有的那 $removed 条自习也删了"
                                        else -> "作息表已保存，已排的早晚自习时间也一起改了"
                                    }
                                } catch (e: Exception) {
                                    message = "保存失败，作息表未改变"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("删除这条安排？") },
            text = { Text("只删除这个时段，其他时段和课程都不动。") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    val p = plan
                    val f = form
                    if (p != null && f?.slotId != null) {
                        val next = if (f.studyKind != null) {
                            p.copy(selfstudy = p.selfstudy.filter { it.id != f.slotId })
                        } else {
                            p.copy(slots = p.slots.filter { it.id != f.slotId })
                        }
                        commit(next, note = "已删除这条安排")
                    }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } },
        )
    }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("操作结果") },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

// ─────────────────────────────────────────────── 页头

@Composable
private fun EditorHeader(tab: Int, onTab: (Int) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("编辑课表", style = MaterialTheme.typography.headlineSmall, color = C.txt)
                Text("点任意一张卡就能改；空卡点一下就是新增", color = C.txt2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onClose) { Text("完成") }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("按天编辑", "作息时间表").forEachIndexed { i, label ->
                val on = i == tab
                Text(
                    label,
                    color = if (on) C.bg else C.txt,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (on) C.cyan else C.card, RoundedCornerShape(20.dp))
                        .then(if (on) Modifier else Modifier.border(1.dp, C.line, RoundedCornerShape(20.dp)))
                        .clickable { onTab(i) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────── 按天编辑

@Composable
private fun DayEditor(
    plan: Seed?,
    day: Int,
    onDay: (Int) -> Unit,
    onPickSlot: (Slot) -> Unit,
    onPickStudy: (SelfStudy) -> Unit,
    onNewCourse: (Int) -> Unit,
    onNewCustomCourse: () -> Unit,
    onNewStudy: (String) -> Unit,
    onOpenTable: () -> Unit,
) {
    val table = PeriodTableStore.table
    val units = PeriodTableStore.units()
    val slots = plan?.slots.orEmpty()
    val studies = plan?.selfstudy.orEmpty()
    val courses = plan?.courses.orEmpty()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
    ) {
        // 星期条：带当天段数，一眼看出哪天还空着
        Row(
            Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (1..7).forEach { d ->
                val n = slots.count { it.weekday == d } + studies.count { it.weekday == d }
                val on = d == day
                Column(
                    Modifier
                        .width(46.dp)
                        .testTag("day:$d")
                        .background(if (on) C.violet.copy(alpha = .22f) else C.card, RoundedCornerShape(12.dp))
                        .border(
                            if (on) 1.dp else 0.8.dp,
                            if (on) C.violet.copy(alpha = .7f) else C.line,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onDay(d) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "周" + "一二三四五六日"[d - 1],
                        color = if (on) C.txt else C.txt2,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(if (n == 0) "空" else "$n 段", color = if (n == 0) C.txt3 else C.cyan, fontSize = 10.sp)
                }
            }
        }

        val daySlots = slots.filter { it.weekday == day }
        val inGrid = daySlots.filter { s -> units.any { it.index == s.p_start } }
        val offGrid = daySlots.filter { s -> units.none { it.index == s.p_start } }
            .sortedBy { s -> s.time_text.orEmpty() }

        // —— 早自习
        val mornings = studies.filter { it.weekday == day && it.kind == PeriodTable.MORNING_STUDY }
        // 已经排过的条目**永远画出来**，哪怕作息表里关了早自习：
        // 藏起来的话，今日页还在显示它，编辑页却找不到，用户没法删。
        mornings.forEach { s -> StudyCard(s.kind, s.start, s.end, s.place.orEmpty(), "study:${s.id}") { onPickStudy(s) } }
        if (table.morningStudy.enabled) {
            if (mornings.isEmpty()) {
                EmptyRow(
                    title = "早自习",
                    sub = "${table.morningStudy.start}-${table.morningStudy.end} · 还没排",
                    tag = "empty:morning",
                    onClick = { onNewStudy(PeriodTable.MORNING_STUDY) },
                )
            }
        } else {
            StudyOffRow("早自习", "作息表里设成了没有早自习", onOpenTable)
        }

        // —— 按节次，一节一行；跨节的课占它起始那一行（标签写「第 1-2 节」）
        var u = 0
        while (u < units.size) {
            val unit = units[u]
            val here = inGrid.filter { it.p_start == unit.index }
            if (here.isEmpty()) {
                EmptyRow(
                    title = "第 ${unit.index} 节",
                    sub = "${unit.start}-${unit.end} · ${unit.segment}",
                    tag = "empty:unit:${unit.index}",
                    onClick = { onNewCourse(unit.index) },
                )
                u += 1
            } else {
                val span = here.maxOf { s -> ((s.p_end ?: unit.index) - unit.index + 1) }.coerceAtLeast(1)
                val end = unit.index + span - 1
                val time = PeriodTableStore.span(unit.index, end)?.let { "${it.first}-${it.second}" }.orEmpty()
                here.forEach { s ->
                    SlotCard(
                        title = courses.firstOrNull { it.id == s.course_id }?.name ?: "未命名课程",
                        tag = if (span > 1) "第 ${unit.index}-$end 节" else "第 ${unit.index} 节",
                        testTag = "slot:${s.id}",
                        time = time,
                        place = s.room.orEmpty(),
                        weeks = weeksLabel(s),
                        accent = C.violet,
                        onClick = { onPickSlot(s) },
                    )
                }
                u += span
            }
        }

        // —— 晚自习
        val evenings = studies.filter { it.weekday == day && it.kind == PeriodTable.EVENING_STUDY }
        evenings.forEach { s -> StudyCard(s.kind, s.start, s.end, s.place.orEmpty(), "study:${s.id}") { onPickStudy(s) } }
        if (table.eveningStudy.enabled) {
            if (evenings.isEmpty()) {
                EmptyRow(
                    title = "晚自习",
                    sub = "${table.eveningStudy.start}-${table.eveningStudy.end} · 还没排",
                    tag = "empty:evening",
                    onClick = { onNewStudy(PeriodTable.EVENING_STUDY) },
                )
            }
        } else {
            StudyOffRow("晚自习", "作息表里设成了没有晚自习", onOpenTable)
        }

        if (offGrid.isNotEmpty()) {
            Text(
                "其他时间（不按节次）",
                color = C.txt2,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            offGrid.forEach { s ->
                SlotCard(
                    title = courses.firstOrNull { it.id == s.course_id }?.name ?: "未命名课程",
                    tag = "自定义时间",
                    testTag = "slot:${s.id}",
                    time = s.time_text.orEmpty(),
                    place = s.room.orEmpty(),
                    weeks = weeksLabel(s),
                    accent = C.amber,
                    onClick = { onPickSlot(s) },
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        OutlinedButton(
            onClick = onNewCustomCourse,
            enabled = plan != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("＋ 自定义时间的课（不按节次）") }
        Text(
            "改地点时可以一键同步到同一门课的所有时段（早晚自习同理）",
            color = C.txt3,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 没课的卡片：**空在那**，点一下就按这一节的默认时间新增 */
@Composable
private fun EmptyRow(title: String, sub: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(tag)
            .border(0.9.dp, C.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("＋", color = C.txt3, fontSize = 15.sp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.txt2, fontSize = 13.sp)
            Text(sub, color = C.txt3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text("添加", color = C.cyan, fontSize = 11.sp)
    }
}

@Composable
private fun SlotCard(
    title: String,
    tag: String,
    testTag: String,
    time: String,
    place: String,
    weeks: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(testTag)
            .background(C.card, RoundedCornerShape(12.dp))
            .border(0.9.dp, accent.copy(alpha = .45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.txt, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("编辑", color = C.txt3, fontSize = 10.5.sp)
        }
        Text(
            listOf(tag, time).filter { it.isNotBlank() }.joinToString(" · "),
            color = accent, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp),
        )
        if (place.isNotBlank() || weeks.isNotBlank()) {
            Text(
                listOf(place, weeks).filter { it.isNotBlank() }.joinToString(" · "),
                color = C.txt3, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StudyCard(kind: String, start: String, end: String, place: String, testTag: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(testTag)
            .background(C.card, RoundedCornerShape(12.dp))
            .border(0.9.dp, C.green.copy(alpha = .45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$kind $start-$end", color = C.txt, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("编辑", color = C.txt3, fontSize = 10.5.sp)
        }
        if (place.isNotBlank()) Text(place, color = C.txt3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun StudyOffRow(label: String, hint: String, onOpenTable: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .border(0.9.dp, C.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenTable)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.txt3, fontSize = 13.sp)
            Text(hint, color = C.txt3, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text("去设置", color = C.cyan, fontSize = 11.sp)
    }
}

// ─────────────────────────────────────────────── 单条编辑表单

/**
 * 表单的**全部**状态。
 *
 * 为什么收成一个 data class：老版是十几个 `remember` 变量 + 一个 13 个参数的
 * `EditArrangement(...)`，加一个字段就得改签名、改调用点，最容易漏的是
 * 「新建」和「编辑」两条路各自回了默认值。现在只有 [of]/[ofStudy]/[newCourse]/[newStudy]
 * 四个入口造状态，[applyTo] 一处写回，来回切换不会串。
 */
internal data class EditState(
    val slotId: Int? = null,
    /** null = 课程；"早自习"/"晚自习" = 自习 */
    val studyKind: String? = null,
    val courseId: Int? = null,
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val weekday: Int = 1,
    val startUnit: Int = 1,
    val endUnit: Int = 1,
    /** true = 用钟点（老数据/特殊时间），false = 用节次（跟作息表联动） */
    val customTime: Boolean = false,
    val start: String = "08:00",
    val end: String = "08:45",
    val weeks: Set<Int> = (1..20).toSet(),
    /** 地点是否同步到同一门课（同类自习）的所有时段 */
    val syncRoom: Boolean = true,
) {
    /** 同一门课 / 同类自习总共有几条（同步选项要给它看） */
    fun siblings(plan: Seed?): Int {
        if (plan == null) return 0
        val kind = studyKind
        if (kind != null) return plan.selfstudy.count { it.kind == kind }.coerceAtLeast(1)
        val cid = courseId ?: return 1
        return plan.slots.count { it.course_id == cid }.coerceAtLeast(1)
    }

    fun validate(plan: Seed?): String? {
        if (studyKind != null) {
            val w = PeriodTableStore.table.study(studyKind)
            if (w != null && !w.enabled) return "作息表里没有$studyKind，先去「作息时间表」打开"
            if (!validRange(start, end)) return "$studyKind 时间要写成 HH:mm，且结束晚于开始"
            if (plan != null && plan.selfstudy.any { it.id != slotId && it.weekday == weekday && it.kind == studyKind }) {
                return "这一天已经有$studyKind：点那张卡改就行"
            }
            return null
        }
        if (name.isBlank()) return "请填写课程名称"
        if (weeks.isEmpty()) return "至少选一周"
        if (!customTime) {
            val total = PeriodTableStore.table.totalUnits
            if (startUnit < 1 || endUnit > total) return "节次超出作息表范围（现在一共 $total 节）"
            if (endUnit < startUnit) return "结束节次不能早于开始节次"
        } else if (!validRange(start, end)) {
            return "时间要写成 HH:mm，且结束晚于开始"
        }
        return null
    }

    fun applyTo(plan: Seed): Seed {
        val used = plan.slots.map { it.id } + plan.selfstudy.map { it.id }
        val newId = (used.filter { it in 1_500_000_000..1_599_999_999 }.maxOrNull() ?: 1_500_000_000) + 1
        val kind = studyKind
        if (kind != null) {
            val id = slotId ?: newId
            var next = plan.copy(
                selfstudy = plan.selfstudy.filter { it.id != id } +
                    SelfStudy(id, weekday, kind, start, end, room.trim().ifBlank { null }),
            )
            if (syncRoom) {
                next = ManualSchedule.syncStudyPlace(next, kind, room.trim().ifBlank { null })
            }
            return next
        }

        val id = slotId ?: newId
        val existing = plan.slots.firstOrNull { it.id == id }
        val oldCourseId = existing?.course_id
        val old = oldCourseId?.let { cid -> plan.courses.firstOrNull { it.id == cid } }
        val shareCount = oldCourseId?.let { cid -> plan.slots.count { it.course_id == cid } } ?: 0
        // 改课名有两种结果（2026-10-22）：
        //  · 这门课只有这一节 → 原地改名（和以前一样）；
        //  · 这门课还有别的时段（典型：刚把 1-2 节拆成两条）→ **给这一节单开一门新课**。
        //    用户要的是「每节课都能改」：把第 2 节换成另一门课时，第 1 节不能跟着被改名 ——
        //    原地改名是悄悄发生的，用户要等到下次上课看课表才发现。
        val forkCourse = existing != null && oldCourseId != null && shareCount > 1 &&
            name.trim() != old?.name.orEmpty()
        val courseId = if (forkCourse) {
            (plan.slots.map { it.id } + plan.selfstudy.map { it.id } + plan.courses.map { it.id }).maxOrNull()!! + 1
        } else {
            oldCourseId ?: courseId ?: id
        }
        val sorted = weeks.sorted()
        val slot = Slot(
            id = id,
            weekday = weekday,
            course_id = courseId,
            p_start = if (customTime) null else startUnit,
            p_end = if (customTime) null else endUnit,
            // 按节次排的时段**不写死 time_text**：钟点由作息表算（改了作息表，课表时间自动跟着对）。
            // 自定义时间才把钟点写进去，因为那时候没有节次可算。
            time_text = if (customTime) "$start-$end" else null,
            room = room.trim().ifBlank { null },
            week_from = sorted.first(),
            week_to = sorted.last(),
            weeks = sorted.joinToString(","),
        )
        val courses = if (forkCourse) {
            plan.courses + Course(courseId, name.trim()).copy(
                short = name.trim().take(8),
                teacher = teacher.trim().ifBlank { null },
            )
        } else {
            plan.courses.filter { it.id != courseId } + (old ?: Course(courseId, name.trim())).copy(
                name = name.trim(),
                short = name.trim().take(8),
                teacher = teacher.trim().ifBlank { null },
            )
        }
        var next = plan.copy(
            courses = courses,
            slots = plan.slots.filter { it.id != id } + slot,
        )
        if (syncRoom) {
            next = ManualSchedule.syncRoom(next, courseId, room.trim().ifBlank { null })
        }
        return next
    }

    companion object {
        fun of(plan: Seed?, slot: Slot): EditState {
            val course = plan?.courses?.firstOrNull { it.id == slot.course_id }
            val weeks = weekSet(slot.weeks)
                ?: ((slot.week_from ?: 1)..(slot.week_to ?: 20)).toSet()
            val custom = slot.p_start == null
            val times = slot.time_text?.split('-').orEmpty()
            val fallbackStart = times.firstOrNull()?.trim().orEmpty()
            val fallbackEnd = times.getOrNull(1)?.trim().orEmpty()
            val n = slot.course_id?.let { cid -> plan?.slots?.count { it.course_id == cid } } ?: 1
            return EditState(
                slotId = slot.id,
                courseId = slot.course_id,
                name = course?.name.orEmpty(),
                teacher = course?.teacher.orEmpty(),
                room = slot.room.orEmpty(),
                weekday = slot.weekday,
                startUnit = slot.p_start ?: 1,
                endUnit = slot.p_end ?: slot.p_start ?: 1,
                customTime = custom,
                start = fallbackStart.ifBlank { "08:00" },
                end = fallbackEnd.ifBlank { "08:45" },
                weeks = weeks.ifEmpty { (1..20).toSet() },
                syncRoom = n > 1,
            )
        }

        fun ofStudy(plan: Seed?, study: SelfStudy): EditState = EditState(
            slotId = study.id,
            studyKind = study.kind,
            name = study.kind,
            room = study.place.orEmpty(),
            weekday = study.weekday,
            customTime = true,
            start = study.start,
            end = study.end,
            syncRoom = (plan?.selfstudy?.count { it.kind == study.kind } ?: 1) > 1,
        )

        fun newCourse(day: Int, unit: Int): EditState {
            val u = PeriodTableStore.unit(unit) ?: PeriodTableStore.units().firstOrNull()
            return EditState(
                weekday = day,
                startUnit = unit,
                endUnit = unit,
                start = u?.start ?: "08:00",
                end = u?.end ?: "08:45",
                syncRoom = false,
            )
        }

        fun newStudy(day: Int, kind: String, plan: Seed?): EditState {
            val w = PeriodTableStore.table.study(kind)
            // 地点默认抄同类的另一天：同学的自习基本都在同一间教室，免得天天手打
            val place = plan?.selfstudy?.firstOrNull { it.kind == kind }?.place.orEmpty()
            return EditState(
                studyKind = kind,
                name = kind,
                room = place,
                weekday = day,
                customTime = true,
                start = w?.start ?: "20:30",
                end = w?.end ?: "22:05",
                syncRoom = (plan?.selfstudy?.count { it.kind == kind } ?: 0) > 0,
            )
        }
    }
}

private fun validRange(start: String, end: String): Boolean {
    val a = runCatching { LocalTime.parse(start) }.getOrNull() ?: return false
    val b = runCatching { LocalTime.parse(end) }.getOrNull() ?: return false
    return b.isAfter(a)
}

@Composable
internal fun TimeChoice(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    OutlinedButton(
        onClick = {
            val p = value.split(':')
            android.app.TimePickerDialog(
                ctx,
                { _, h, m -> onChange("%02d:%02d".format(Locale.ROOT, h, m)) },
                p.firstOrNull()?.toIntOrNull() ?: 8,
                p.getOrNull(1)?.toIntOrNull() ?: 0,
                true,
            ).show()
        },
        modifier = modifier,
    ) { Text("$label  $value", maxLines = 1) }
}

@Composable
private fun EditArrangement(
    state: EditState,
    plan: Seed?,
    saving: Boolean,
    onChange: (EditState) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    /** 「拆成单节」—— 只有跨节、按节次排的课才有（null = 这个表单不提供） */
    onSplit: (() -> Unit)? = null,
) {
    val table = PeriodTableStore.table
    val units = PeriodTableStore.units()
    val isStudy = state.studyKind != null
    val siblings = state.siblings(plan)
    val preview = if (isStudy) "" else PeriodTableStore.span(state.startUnit, state.endUnit)
        ?.let {
            val label = if (state.startUnit == state.endUnit) "第 ${state.startUnit} 节" else "第 ${state.startUnit}-${state.endUnit} 节"
            "$label · ${it.first}-${it.second}"
        }
        .orEmpty()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Text(if (isStudy) "编辑${state.studyKind}" else "编辑课程", style = MaterialTheme.typography.headlineSmall, color = C.txt)
        Text(
            if (state.slotId == null) "新增一条" else "改完点保存，只影响本机的课表",
            color = C.txt2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
        )

        if (isStudy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PeriodTable.MORNING_STUDY, PeriodTable.EVENING_STUDY).forEach { t ->
                    FilterChip(
                        selected = state.studyKind == t,
                        onClick = { onChange(state.copy(studyKind = t, name = t)) },
                        label = { Text(t) },
                    )
                }
            }
        } else {
            OutlinedTextField(
                state.name,
                { onChange(state.copy(name = it)) },
                label = { Text("课程名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state.teacher,
                { onChange(state.copy(teacher = it)) },
                label = { Text("教师（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            )
        }

        // —— 地点 + 同步
        OutlinedTextField(
            state.room,
            { onChange(state.copy(room = it)) },
            label = { Text("地点（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        )
        if (siblings > 1) {
            Text(
                if (isStudy) "地点要一起改吗？同类自习还有 ${siblings - 1} 天" else "地点要一起改吗？同一门课还有 ${siblings - 1} 处",
                color = C.txt2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.syncRoom,
                    onClick = { onChange(state.copy(syncRoom = false)) },
                    label = { Text("只改这一条") },
                )
                FilterChip(
                    selected = state.syncRoom,
                    onClick = { onChange(state.copy(syncRoom = true)) },
                    label = { Text("同步到全部 $siblings 条") },
                )
            }
        }

        // —— 星期
        Text("星期", color = C.txt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { d ->
                FilterChip(
                    selected = state.weekday == d,
                    onClick = { onChange(state.copy(weekday = d)) },
                    label = { Text("周" + "一二三四五六日"[d - 1]) },
                )
            }
        }

        // —— 时间：节次（跟作息表联动）或自定义钟点
        Text("时间", color = C.txt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        if (!isStudy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("按作息表的节次", color = C.txt2, fontSize = 12.sp)
                    Text("改了作息表，这里的时间会跟着变", color = C.txt3, fontSize = 10.5.sp)
                }
                Switch(checked = !state.customTime, onCheckedChange = { onChange(state.copy(customTime = !it)) })
            }
        }
        if (isStudy || state.customTime) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                TimeChoice("开始", state.start, { onChange(state.copy(start = it)) }, Modifier.weight(1f))
                TimeChoice("结束", state.end, { onChange(state.copy(end = it)) }, Modifier.weight(1f))
            }
            if (isStudy) {
                val w = table.study(state.studyKind ?: "")
                if (w != null) {
                    Text(
                        "作息表里的${state.studyKind}：${w.start}-${w.end}",
                        color = C.txt3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            Text("开始节次", color = C.txt2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 5.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                units.forEach { u ->
                    FilterChip(
                        selected = state.startUnit == u.index,
                        onClick = {
                            onChange(
                                state.copy(
                                    startUnit = u.index,
                                    endUnit = if (state.endUnit < u.index) u.index else state.endUnit,
                                ),
                            )
                        },
                        label = { Text(u.index.toString()) },
                    )
                }
            }
            Text("结束节次", color = C.txt2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 5.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                units.filter { it.index >= state.startUnit }.forEach { u ->
                    FilterChip(
                        selected = state.endUnit == u.index,
                        onClick = { onChange(state.copy(endUnit = u.index)) },
                        label = { Text(u.index.toString()) },
                    )
                }
            }
            if (preview.isNotBlank()) {
                Text(preview, color = C.cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            // —— 跨节的课要能拆成单节（2026-10-22 用户：每节课都要能单独改）
            val spanUnits = state.endUnit - state.startUnit + 1
            if (onSplit != null && spanUnits > 1) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).testTag("splitHint")
                        .background(C.card, RoundedCornerShape(12.dp))
                        .border(0.9.dp, C.line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "这一门课占了 $spanUnits 节，现在是一条记录",
                        color = C.txt, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "拆开后每一节都能单独改（换课、换教室、换周次都行）；课程和教室照抄现在这份",
                        color = C.txt3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp),
                    )
                    OutlinedButton(
                        onClick = onSplit,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("splitSpan"),
                    ) { Text("拆成 $spanUnits 条单节") }
                }
            }
        }

        // —— 周次（只有课程需要）
        if (!isStudy) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("上课周次", color = C.txt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(state.copy(weeks = (1..20).toSet())) }) { Text("全选") }
                TextButton(onClick = { onChange(state.copy(weeks = (1..20).filter { it % 2 == 1 }.toSet())) }) { Text("单周") }
                TextButton(onClick = { onChange(state.copy(weeks = (1..20).filter { it % 2 == 0 }.toSet())) }) { Text("双周") }
            }
            (0..3).forEach { row ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { w ->
                        val n = row * 5 + w
                        FilterChip(
                            selected = n in state.weeks,
                            onClick = { onChange(state.copy(weeks = if (n in state.weeks) state.weeks - n else state.weeks + n)) },
                            label = { Text("$n") },
                        )
                    }
                }
            }
            Text(
                "已选 " + (if (state.weeks.isEmpty()) "0 周" else "${state.weeks.min()}-${state.weeks.max()} 周共 ${state.weeks.size} 周"),
                color = C.txt3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else "保存到本机") }
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }
        onDelete?.let {
            TextButton(onClick = it, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("删除这条安排", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
