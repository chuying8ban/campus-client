package top.ccbase.campus.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.domain.inWeek
import top.ccbase.campus.domain.slotLabel
import top.ccbase.campus.domain.timeRows
import top.ccbase.campus.domain.weekNo
import top.ccbase.campus.domain.weeksWithClass
import top.ccbase.campus.ui.common.RefreshBar
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * 课表页 —— 周视图（时间 × 周一~周日）。
 *
 * 手机上 7 列很窄，所以单元格只放"短名 + 教室"，靠颜色区分科目；
 * 完整课程名在今日页看。周次可以左右翻，因为每门课的起止周不一样
 * （比如 3~19 周的课，翻到第 1 周就是空的 —— 那也算有用信息）。
 */
@Composable
fun ScheduleScreen(db: CampusDb, onUpdate: () -> Unit = {}, onEdit: () -> Unit = {}) {
    // 外层必须是 Box：详情面板要靠它做浮层。
    // 教训：把浮层塞进别处的 Box（比如"加载中"那个）会变成"点了没反应"。
    var pickedCourse by remember { mutableStateOf<Course?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                androidx.compose.material3.TextButton(onClick = onUpdate) { Text("更新课表") }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onEdit) { Text("编辑课表") }
            }
            Box(Modifier.weight(1f)) { ScheduleBody(db = db, onPickCourse = { pickedCourse = it }) }
        }
        pickedCourse?.let { picked ->
            CourseDetailPanel(db = db, course = picked, onClose = { pickedCourse = null })
        }
    }
}

@Composable
private fun ScheduleBody(db: CampusDb, onPickCourse: (Course) -> Unit) {
    val ctx = LocalContext.current
    var slots by remember { mutableStateOf<List<Slot>>(emptyList()) }
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var week by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
        slots = db.dao().slots().first()
        courses = db.dao().courses().first()
        val start = db.dao().metaGet("semester_start") ?: "2026-08-31"
        week = weekNo(start, LocalDate.now())
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize().padding(22.dp)) {
            Text("正在读取课表…", color = C.txt3, fontSize = 13.sp)
        }
        return
    }

    val rng = weeksWithClass(slots)
    val palette = listOf(C.violet, C.cyan, C.amber, C.green, C.magenta, C.red)
    val rows = timeRows(slots)
    val todayWd = LocalDate.now().dayOfWeek.value

    // 横屏可用高度只有三百多 dp：表头一钉住，靠 weight(1f) 的课表格子就被挤成 0 高，
    // 看起来"横屏什么都没有"。所以外层整体可滚 —— 高度不够时滚一下就能看到课表。
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // -------- 顶部：周次切换 --------
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = if (land) 8.dp else 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!land) Text("课表", color = C.cyan, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${courses.size} 门 · 第 ${rng.first}~${rng.last} 周", color = C.txt3, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Arrow("‹") { if (week > rng.first) week-- }
                Spacer(Modifier.weight(1f))
                Text("第 $week 周", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, style = Digits)
                Spacer(Modifier.weight(1f))
                Arrow("›") { if (week < rng.last) week++ }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        }

        // 刷新条（课表/任务从服务器重取）
        Spacer(Modifier.height(4.dp))

        // （原来这里还有一行「一 二 三 四 五 六 日」的旧表头：它是老版周视图的表头，
        //   固定 7 列，而现在的表格只画"有课的星期"（常见 5 列），两行叠在一起列数还对不齐，
        //   看着很乱。表格自己有「星期一…星期五」表头，所以这行删掉。）

        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            // 竖屏**默认也走周视图**（2026-09-17 用户要求"做成教务系统那种形式的，这样直观"）。
            // 之前竖屏默认列表，是因为老版周视图把一课压成 40dp 小方块、名字截成四行；
            // 现在行按节次排、课块跨节、块里写全名+教室+教师，窄屏也读得下来。
            // 列表保留为备用视图（一门课一行，横向信息最全）。
            var listMode by remember { mutableStateOf(false) }
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("周视图" to false, "列表" to true).forEach { (label, mode) ->
                    val on = mode == listMode
                    // 未选中那个以前是"深底 + 灰字"，几乎看不见（用户反馈「上面的按钮看不清」）。
                    // 改成亮字 + 一圈描边，两个按钮都能看清哪个是当前态。
                    Text(
                        label,
                        color = if (on) C.bg else C.txt, fontSize = 11.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .background(if (on) C.cyan else C.card, RoundedCornerShape(20.dp))
                            .then(
                                if (on) Modifier
                                else Modifier.border(1.dp, C.line, RoundedCornerShape(20.dp)),
                            )
                            .clickable { listMode = mode }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (listMode) "一门课一行，时间/教师/教室都写全" else "和教务一样的表：左边节次、右边一周",
                    color = C.txt3, fontSize = 10.sp,
                )
            }
            if (listMode) {
                (1..7).forEach { wd ->
                    val day = slots
                        .filter { it.weekday == wd && it.inWeek(week) }
                        .sortedBy { it.p_start ?: 0 }
                    if (day.isNotEmpty()) {
                        val isToday = wd == todayWd
                        Text(
                            "周" + "一二三四五六日"[wd - 1] + if (isToday) " · 今天" else "",
                            color = if (isToday) C.violet else C.txt2,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
                        )
                        day.forEach { s ->
                            val c = courses.firstOrNull { it.id == s.course_id }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = c != null) { c?.let(onPickCourse) }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    slotLabel(s),
                                    color = C.cyan, fontSize = 11.sp, style = Digits,
                                    modifier = Modifier.width(96.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(c?.name ?: "?", color = C.txt, fontSize = 12.5.sp)
                                    val sub = listOfNotNull(
                                        c?.teacher?.takeIf { it.isNotBlank() },
                                        s.room,
                                    ).joinToString(" · ")
                                    if (sub.isNotBlank()) {
                                        Text(sub, color = C.txt3, fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 教务系统那张表：左边节次+时间，右边周一到周日，课占整块。
                WeekGrid(
                    slots = slots,
                    courses = courses,
                    week = week,
                    todayWd = todayWd,
                    onPickCourse = onPickCourse,
                )
            }
        }
    }
}

@Composable
private fun Arrow(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(0.8.dp, C.lineHi, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = C.txt2, fontSize = 17.sp)
    }
}
