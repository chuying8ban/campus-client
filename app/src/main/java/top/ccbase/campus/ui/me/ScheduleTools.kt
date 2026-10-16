package top.ccbase.campus.ui.me

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.ccbase.campus.alarm.Rescheduler
import top.ccbase.campus.data.local.*
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.ui.theme.C

@Composable
private fun TimeChoice(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    OutlinedButton(onClick = {
        val p = value.split(':')
        android.app.TimePickerDialog(ctx, { _, h, m -> onChange("%02d:%02d".format(java.util.Locale.ROOT, h, m)) }, p.firstOrNull()?.toIntOrNull() ?: 8, p.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
    }, modifier = modifier) { Text("$label  $value") }
}

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private val timePresets = listOf("08:00–08:45", "08:45–09:15", "09:30–11:05", "14:30–16:05", "19:00–20:30", "20:30–22:05")

@Composable
fun ScheduleEditor(ctx: Context, db: CampusDb, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<Seed?>(null) }
    var editing by remember { mutableStateOf(false) }
    var sid by remember { mutableStateOf<Int?>(null) }
    var isStudy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var day by remember { mutableIntStateOf(1) }
    var start by remember { mutableStateOf("08:45") }
    var end by remember { mutableStateOf("09:15") }
    var weeks by remember { mutableStateOf(setOf<Int>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { plan = ManualSchedule.snapshot(db) }

    fun edit(slot: Slot? = null, study: SelfStudy? = null) {
        if (plan == null || saving) return
        sid = slot?.id ?: study?.id
        isStudy = study != null
        val course = plan?.courses?.find { it.id == slot?.course_id }
        name = course?.name ?: study?.kind.orEmpty()
        teacher = course?.teacher.orEmpty()
        room = slot?.room ?: study?.place.orEmpty()
        day = slot?.weekday ?: study?.weekday ?: 1
        val times = slot?.time_text?.split('-')
        start = times?.firstOrNull() ?: study?.start ?: "08:45"
        end = times?.getOrNull(1) ?: study?.end ?: "09:15"
        weeks = slot?.weeks?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?: ((slot?.week_from ?: 1)..(slot?.week_to ?: 20)).toSet()
        editing = true
    }

    fun commit(next: Seed) {
        scope.launch {
            saving = true
            try {
                ManualSchedule.save(db, next)
                plan = next
                editing = false
                Rescheduler.request(ctx, "manual_schedule")
                message = "已保存到本机，云端刷新不会覆盖这条安排"
            } catch (e: Exception) {
                message = "保存失败，原安排未改变"
            } finally { saving = false }
        }
    }

    fun applyEdit() {
        val p = plan ?: return
        val err = ManualSchedule.validate(name, day, start, end, if (isStudy) "1" else weeks.sorted().joinToString(","))
        if (err != null) { message = err; return }
        if (isStudy && name !in listOf("早自习", "晚自习")) { message = "请选择早自习或晚自习"; return }
        val used = p.slots.map { it.id } + p.selfstudy.map { it.id }
        val id = sid ?: ((used.filter { it in 1_500_000_000..1_599_999_999 }.maxOrNull() ?: 1_500_000_000) + 1)
        if (isStudy) {
            if (p.selfstudy.any { it.id != sid && it.weekday == day && it.kind == name }) { message = "这一天已有同类自习"; return }
            commit(p.copy(selfstudy = p.selfstudy.filter { it.id != sid } + SelfStudy(id, day, name, start, end, room)))
        } else {
            val courseId = p.slots.find { it.id == sid }?.course_id ?: id
            val old = p.courses.find { it.id == courseId } ?: Course(courseId, name)
            val selected = weeks.sorted()
            commit(p.copy(
                courses = p.courses.filter { it.id != courseId } + old.copy(name = name, short = name.take(8), teacher = teacher),
                slots = p.slots.filter { it.id != sid } + Slot(id, day, courseId, time_text = "$start-$end", room = room, week_from = selected.first(), week_to = selected.last(), weeks = selected.joinToString(",")),
            ))
        }
    }

    BackHandler { if (editing) editing = false else close() }
    Surface(Modifier.fillMaxSize(), color = C.bg) {
        if (!editing) {
            ScheduleList(plan, onNewCourse = { edit() }, onNewStudy = { edit(); isStudy = true; name = "早自习" }, onEditSlot = { edit(slot = it) }, onEditStudy = { edit(study = it) }, onClose = close)
        } else {
            EditArrangement(
                isStudy, name, { name = it }, teacher, { teacher = it }, room, { room = it }, day, { day = it }, start, { start = it }, end, { end = it }, weeks, { weeks = it }, saving,
                onSave = ::applyEdit, onDelete = if (sid != null) {{ deleting = true }} else null, onCancel = { editing = false },
            )
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这条安排？") }, text = { Text("只删除这个时段，不删除其他课程。") }, confirmButton = { TextButton(onClick = { deleting = false; plan?.let { p -> commit(if (isStudy) p.copy(selfstudy = p.selfstudy.filter { it.id != sid }) else p.copy(slots = p.slots.filter { it.id != sid })) } }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, title = { Text("操作结果") }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}

@Composable
private fun ScheduleList(plan: Seed?, onNewCourse: () -> Unit, onNewStudy: () -> Unit, onEditSlot: (Slot) -> Unit, onEditStudy: (SelfStudy) -> Unit, onClose: () -> Unit) {
    val days = listOf("一", "二", "三", "四", "五", "六", "日")
    val slots = plan?.slots.orEmpty()
    val studies = plan?.selfstudy.orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("编辑课表", style = MaterialTheme.typography.headlineSmall, color = C.txt)
                Text("点课程块直接编辑，仅修改本机", color = C.txt2, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onClose) { Text("完成") }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewCourse, enabled = plan != null) { Text("＋ 课程") }
            OutlinedButton(onClick = onNewStudy, enabled = plan != null) { Text("＋ 自习") }
        }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { index, day ->
                val daySlots = slots.filter { it.weekday == index + 1 }
                val dayStudies = studies.filter { it.weekday == index + 1 }
                Column(Modifier.width(118.dp)) {
                    Surface(color = if (index < 5) C.cyan.copy(alpha = .18f) else C.card, shape = MaterialTheme.shapes.medium) {
                        Text("周$day  ·  ${daySlots.size + dayStudies.size}", color = C.txt, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    daySlots.forEach { slot ->
                        val title = plan?.courses?.find { it.id == slot.course_id }?.name ?: "未命名课程"
                        WeekBlock(title, slot.time_text.orEmpty(), slot.room.orEmpty(), false) { onEditSlot(slot) }
                    }
                    dayStudies.forEach { study ->
                        WeekBlock(study.kind, "${study.start}–${study.end}", study.place.orEmpty(), true) { onEditStudy(study) }
                    }
                    if (daySlots.isEmpty() && dayStudies.isEmpty()) Text("暂无", color = C.txt3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }
        Text("点开课程块可修改名称、时间、地点和周次", color = C.txt3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun WeekBlock(title: String, time: String, place: String, study: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp), colors = CardDefaults.outlinedCardColors(containerColor = if (study) C.violet.copy(alpha = .18f) else C.card)) {
        Column(Modifier.padding(9.dp)) {
            Text(title, color = C.txt, style = MaterialTheme.typography.labelLarge, maxLines = 2)
            Text(time, color = if (study) C.violet else C.cyan, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (place.isNotBlank()) Text(place, color = C.txt3, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            Text("编辑", color = C.txt3, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun EditArrangement(isStudy: Boolean, name: String, onName: (String) -> Unit, teacher: String, onTeacher: (String) -> Unit, room: String, onRoom: (String) -> Unit, day: Int, onDay: (Int) -> Unit, start: String, onStart: (String) -> Unit, end: String, onEnd: (String) -> Unit, weeks: Set<Int>, onWeeks: (Set<Int>) -> Unit, saving: Boolean, onSave: () -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text(if (isStudy) "编辑早晚自习" else "编辑课程", style = MaterialTheme.typography.headlineSmall, color = C.txt)
        Text("点选选项，比手写数字更不容易出错", color = C.txt2, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
        if (isStudy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("早自习", "晚自习").forEach { t -> FilterChip(selected = name == t, onClick = { onName(t) }, label = { Text(t) }) }
        } else OutlinedTextField(name, onName, label = { Text("课程名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!isStudy) OutlinedTextField(teacher, onTeacher, label = { Text("教师（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 9.dp))
        OutlinedTextField(room, onRoom, label = { Text("地点（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 9.dp))
        Text("星期", color = C.txt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp, bottom = 5.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { weekdays.forEachIndexed { i, label -> FilterChip(selected = day == i + 1, onClick = { onDay(i + 1) }, label = { Text("周$label") }) } }
        Text("时间", color = C.txt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp, bottom = 5.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { timePresets.forEach { preset -> FilterChip(selected = preset == "$start–$end", onClick = { val p = preset.split('–'); onStart(p[0]); onEnd(p[1]) }, label = { Text(preset) }) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TimeChoice("开始", start, onStart, Modifier.weight(1f))
            TimeChoice("结束", end, onEnd, Modifier.weight(1f))
        }
        if (!isStudy) Text("修改课程名称或教师会同步影响同一课程的其他时段。", color = C.txt2, style = MaterialTheme.typography.bodySmall)
        if (!isStudy) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("上课周次", color = C.txt, style = MaterialTheme.typography.titleSmall); TextButton(onClick = { onWeeks((1..20).toSet()) }) { Text("全选") }; TextButton(onClick = { onWeeks(emptySet()) }) { Text("清空") } }
            Row { TextButton(onClick={onWeeks((1..20).filter{it%2==1}.toSet())}){Text("单周")}; TextButton(onClick={onWeeks((1..20).filter{it%2==0}.toSet())}){Text("双周")} }
            (0..3).forEach { row -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { w -> val n = row * 5 + w; FilterChip(selected = n in weeks, onClick = { onWeeks(if (n in weeks) weeks - n else weeks + n) }, label = { Text("第${n}周") }) } } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onSave, enabled = !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else "保存到本机") }; OutlinedButton(onClick = onCancel) { Text("取消") } }
        onDelete?.let { TextButton(onClick = it, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("删除这条安排", color = MaterialTheme.colorScheme.error) } }
    }
}
