package top.ccbase.campus.ui.schedule

import top.ccbase.campus.util.Links
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import top.ccbase.campus.domain.slotTimeLabel
import top.ccbase.campus.domain.weeksLabel

/**
 * 课程详情面板。
 *
 * 课表上的课格子原来点了没反应 —— 而库里其实早就存着教师、学分、周次、
 * 教室、关联任务和资源。这里只是把这些东西给一个出口。
 *
 * 视觉：细竖线 + 大留白，不用填充卡片堆；外链只出资源里真实存在的 url，不自己造。
 */
private val WD = listOf("", "一", "二", "三", "四", "五", "六", "日")

@Composable
fun CourseDetailPanel(db: CampusDb, course: Course, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var slots by remember(course.id) { mutableStateOf<List<Slot>>(emptyList()) }
    var tasks by remember(course.id) { mutableStateOf<List<Task>>(emptyList()) }
    var resources by remember(course.id) { mutableStateOf<List<Resource>>(emptyList()) }

    LaunchedEffect(course.id) {
        withContext(Dispatchers.IO) {
            slots = runCatching { db.dao().slotsOfCourse(course.id).first() }.getOrDefault(emptyList())
            val ts = runCatching { db.dao().tasksOfCourse(course.id).first() }.getOrDefault(emptyList())
            tasks = ts
            // 资源按 task_id 过滤 —— 一次读全表再筛，避免每个任务一次查询
            val ids = ts.map { it.id }.toSet()
            resources = runCatching {
                db.dao().resources().first().filter { it.task_id in ids }
            }.getOrDefault(emptyList())
        }
    }

    // 普通 Box 即可 —— 但**必须**挂在一个以 Box 为根的容器里（见 ScheduleScreen）。
    // 之前它被插进了「加载中」分支，导致点了永不显示；Dialog 方案在测试里又看不见，
    // 所以走"外层 Box + 浮层"这条确定性的路。
    Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.86f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(C.card)
                .border(0.8.dp, C.line, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState())
                // 面板内部的点击不该穿透到遮罩
                .clickable(enabled = false) {},
        ) {
            // ---------------------------------------------------------- 头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.name,
                    color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("关闭", color = C.txt3, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClose))
            }
            Spacer(Modifier.height(6.dp))

            // 一行式元信息：教师 · 学分 · 类别
            val meta = buildList {
                course.teacher?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (course.credits > 0) add("${fmt(course.credits)} 学分")
                course.category?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), color = C.txt2, fontSize = 12.5.sp)
                Spacer(Modifier.height(6.dp))
            }

            // 周次 / 重点 / 目标分
            val flags = buildList {
                add(weeksText(course))
                if (course.is_focus == 1) add("重点盯防")
                course.target?.let { add("目标 $it 分") }
            }
            Text(flags.joinToString(" · "), color = C.txt3, fontSize = 11.5.sp, style = Digits)

            course.note?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = C.txt2, fontSize = 12.sp, lineHeight = 17.sp)
            }

            // ---------------------------------------------------------- 上课时间
            Section("上课时间")
            if (slots.isEmpty()) {
                Hint("还没有这门课的时间段（课表刷新后会有）。")
            } else {
                slots.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("周${WD.getOrElse(s.weekday) { "?" }}", color = C.txt2, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            slotTimeLabel(s),
                            color = C.txt, fontSize = 12.sp, style = Digits,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(s.room.orEmpty(), color = C.txt3, fontSize = 11.5.sp)
                    }
                    // 精确周次（`3-5、8、10、11 周`）—— 单双周/散周只靠"第X~Y周"看不出来，
                    // 而它就是同学"这周到底去哪个教室"的答案，不能省。
                    Text(weeksLabel(s), color = C.txt3, fontSize = 10.5.sp, style = Digits,
                         modifier = Modifier.padding(start = 34.dp))
                    Spacer(Modifier.height(5.dp))
                }
            }

            // ---------------------------------------------------------- 关联任务
            Section("这门课的学习任务")
            if (tasks.isEmpty()) {
                Hint("还没有和这门课绑定的任务。")
            } else {
                tasks.forEach { t ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(4.dp).background(C.cyan, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(t.title, color = C.txt, fontSize = 12.5.sp, lineHeight = 17.sp)
                            val sub = buildList {
                                t.track?.takeIf { it.isNotBlank() }?.let { add(it) }
                                t.deliverable?.takeIf { it.isNotBlank() }?.let { add("产出：$it") }
                            }
                            if (sub.isNotEmpty()) {
                                Text(sub.joinToString(" · "), color = C.txt3, fontSize = 10.5.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---------------------------------------------------------- 资源外链
            if (resources.isNotEmpty()) {
                Section("去对应平台")
                resources.forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            // 走 [Links.open]，别在这儿裸 ACTION_VIEW：
                            //  ① 它只放行 http/https —— `intent://`、`file://`、`javascript:` 这类
                            //     直接启动就是给人当跳板用，从源头掐掉（资源行是**共享库**来的，
                            //     别人收录的链接也在里面，不能当自家常量使）；
                            //  ② 打不开时它会 toast 一句，而不是 runCatching 吞掉让用户「点了没反应」。
                            Links.open(ctx, r.url)
                        },
                    ) {
                        Text(r.title, color = C.cyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        // 域名露出来：共享库/计划里的链接来自别人收录，点之前先看清去哪
                        val host = Links.hostOf(r.url)
                        if (host.isNotBlank()) {
                            Text(host, color = C.txt3, fontSize = 10.5.sp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("打开 ›", color = C.txt3, fontSize = 11.sp)
                    }
                    if (!r.why.isNullOrBlank()) {
                        Text(r.why, color = C.txt3, fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(12.dp).background(C.cyan, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, color = C.txt2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = C.txt3, fontSize = 11.5.sp)
}

/** 学分去掉多余的 .0（3.0 → 3，2.5 → 2.5） */
internal fun fmt(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/** 周次文案。缺省时给一句人话，不要输出 "null~null"。 */
internal fun weeksText(c: Course): String {
    val a = c.week_from
    val b = c.week_to
    return when {
        a == null && b == null -> "周次未标注"
        a != null && b == null -> "第 $a 周起"
        a == null -> "第 $b 周止"
        a == b -> "第 $a 周"
        else -> "第 $a~$b 周"
    }
}
