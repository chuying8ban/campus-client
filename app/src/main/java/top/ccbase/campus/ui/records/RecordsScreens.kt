package top.ccbase.campus.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.school.EamsRecords
import top.ccbase.campus.school.ExamEntry
import top.ccbase.campus.school.GradeEntry
import top.ccbase.campus.school.SchoolRecordsCache
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.pageSurface
import top.ccbase.campus.ui.theme.Digits

/**
 * 考试安排 / 成绩单 —— 这两个页面是「导入课表时顺手从教务读回来的快照」，
 * 不是实时接口（教务这两页没有 JSON 接口，只能扒 HTML，见 `EamsRecords` 的注释）。
 *
 * 所以页面的口径必须诚实：
 *  - 顶部写明**这份是什么时候读的**，以及在哪儿刷新（更新课表时一起刷）；
 *  - 空就说空（"这次读取时教务里还没有考试安排"），不摆一张空卡片；
 *  - 没有数据也绝不显示 0 分 / 0 学分那种"看起来像成绩"的东西。
 */
@Composable
fun ExamScreen(db: CampusDb, onBack: () -> Unit) {
    RecordsScaffold("考试安排", db, onBack) { records, at ->
        val exams = records?.exams.orEmpty()
        if (exams.isEmpty()) {
            item {
                EmptyHint(
                    "这次读取时教务里还没有考试安排。",
                    "期末前教务会把考试时间、考场放出来；到「课表 → 更新课表」重读一次就会带过来。",
                )
            }
            return@RecordsScaffold
        }
        val today = LocalDate.now()
        items(exams) { e -> ExamRow(e, today) }
        item { SourceNote(at) }
    }
}

@Composable
fun GradeScreen(db: CampusDb, onBack: () -> Unit) {
    RecordsScaffold("成绩", db, onBack) { records, at ->
        val grades = records?.grades.orEmpty()
        if (grades.isEmpty()) {
            item {
                EmptyHint(
                    "还没有可显示的成绩。",
                    "教务通常在期末后几天出成绩；到「课表 → 更新课表」重读一次就会带过来。",
                )
            }
            return@RecordsScaffold
        }
        item { GradeSummary(records!!) }
        items(grades) { g -> GradeRow(g) }
        item { SourceNote(at) }
    }
}

// ------------------------------------------------------------------ 骨架

@Composable
private fun RecordsScaffold(
    title: String,
    db: CampusDb,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.(EamsRecords?, String?) -> Unit,
) {
    var records by remember { mutableStateOf<EamsRecords?>(null) }
    var at by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (r, t) = withContext(Dispatchers.IO) {
            SchoolRecordsCache.load(db) to SchoolRecordsCache.updatedAt(db)
        }
        records = r
        at = t
        loaded = true
    }

    Column(Modifier.fillMaxSize().pageSurface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 22.dp, top = 26.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", fontSize = 26.sp, color = C.txt2, modifier = Modifier.clickable { onBack() }.padding(8.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = C.txt)
        }
        if (!loaded) {
            Text("正在读取…", color = C.txt3, fontSize = 13.sp, modifier = Modifier.padding(22.dp))
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        ) {
            content(records, at)
        }
    }
}

// ------------------------------------------------------------------ 考试

@Composable
private fun ExamRow(e: ExamEntry, today: LocalDate) {
    val days = e.daysFrom(today)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.txt)
            Spacer(Modifier.weight(1f))
            // 倒计时只在"未来 60 天内"给，太远的写成日期就够，免得天天看着一个巨大的数字
            if (days != null && days in 0..60) {
                Text(
                    when (days) {
                        0L -> "今天"
                        1L -> "明天"
                        else -> "$days 天后"
                    },
                    fontSize = 12.sp, color = if (days <= 3) C.red else C.txt2, style = Digits,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val line = listOfNotNull(e.time, e.room?.let { "考场 $it" }).joinToString(" · ")
        if (line.isNotBlank()) Text(line, fontSize = 12.sp, color = C.txt2)
        val tags = listOfNotNull(e.type, e.status).filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(tags.joinToString(" · "), fontSize = 11.sp, color = C.txt3)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
}

// ------------------------------------------------------------------ 成绩

@Composable
private fun GradeSummary(records: EamsRecords) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(C.card, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text("${records.grades.size} 门课程", fontSize = 13.sp, color = C.txt2)
        Spacer(Modifier.height(6.dp))
        Row {
            records.averagePoint?.let {
                Text(
                    "平均绩点 " + String.format("%.2f", it),
                    fontSize = 14.sp, color = C.txt, style = Digits,
                )
            }
            if (records.totalCredits > 0) {
                Spacer(Modifier.width(16.dp))
                Text("已修 ${trimZeros(records.totalCredits)} 学分", fontSize = 14.sp, color = C.txt, style = Digits)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("平均绩点按教务给的单科绩点直接平均（未按学分加权）", fontSize = 10.sp, color = C.txt3)
    }
}

private fun trimZeros(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else v.toString()

@Composable
private fun GradeRow(g: GradeEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.name.orEmpty(), fontSize = 14.sp, color = C.txt)
            val sub = listOfNotNull(
                g.credits?.let { "$it 学分" },
                g.point?.let { "绩点 $it" },
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(sub, fontSize = 11.sp, color = C.txt3)
            }
        }
        Text(g.score ?: "—", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.txt, style = Digits)
    }
}

// ------------------------------------------------------------------ 共用

@Composable
private fun EmptyHint(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(title, fontSize = 14.sp, color = C.txt2)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 12.sp, lineHeight = 19.sp, color = C.txt3)
    }
}

@Composable
private fun SourceNote(at: String?) {
    Spacer(Modifier.height(18.dp))
    Text(
        "读自教务「" + "考试信息 / 学生成绩单" + "」" +
            (at?.let { "，本机快照 $it" } ?: "") +
            "。更新课表时会一起刷新；只保存在这台手机上。",
        fontSize = 10.sp, lineHeight = 16.sp, color = C.txt3,
    )
}
