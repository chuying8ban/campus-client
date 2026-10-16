package top.ccbase.campus.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.TaskDone
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.domain.TaskNow
import top.ccbase.campus.domain.TodayView
import top.ccbase.campus.domain.UNIT_TIMES
import top.ccbase.campus.domain.resourceKindLabel
import top.ccbase.campus.domain.pickTopRes
import top.ccbase.campus.domain.stepKindLabel
import top.ccbase.campus.util.Links
import kotlinx.coroutines.flow.first
import top.ccbase.campus.domain.loadToday
import top.ccbase.campus.domain.msToNextMinute
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 今日页 —— 打开 App 第一眼看到的东西。
 *
 * 设计原则（对着网页版那套视觉语言，不是照抄布局）：
 * ① 用**间距 + 极细分隔线**区分层次，不用填充卡片 —— 填充底色一多就廉价；
 * ② 时间一列用等宽字体（看起来整齐，这也是"精致"的主要来源之一）；
 * ③ 状态只用一个 7dp 圆点 + 文字明暗表达，不加边框不加图标；
 * ④ 空状态必须说清"下一步干什么"，不能只剩一片空白。
 *
 * 任务行的点击语义（2026-09-18 按用户要求定的，2026-09-19 补了资源行）：
 * 点整行 = **打开这一项的详情**（怎么做 / 有视频就给链接），
 * 完成 / 取消完成是详情里的两个动作 —— 点行本身**不再**改变任何状态。
 * 原因：整行热区又宽又低，"点一下就完成"被误触过；把状态改动收进详情里的
 * 明确按钮，误触就没了根，同时详情也就成了"怎么下手"的落脚点。
 *
 * 任务行下面那一行**资源行**（点它直接开链接）是补的第二件事：用户说
 * 「同学大多是没有用的笔记，却很少有有用的视频课程」—— 视频只有进详情才看得到，
 * 等于没给。现在第一屏就露一条（有视频给视频），点它走链接、点整行仍然开详情，
 * 两件事互不吃掉。没有资源就不画（不留占位）。
 */
@Composable
fun TodayScreen(db: CampusDb) {
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<TodayView?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // 「正在 / 已完成」是**按当前时刻算出来**的：页面开着不动，时间照样在走。
    // 原来只有 reload 才重算，于是 09:15 前打开的页面到 09:19 还挂着早自习「正在」——
    // 不是算错了，是压根没再算过（2026-09-24 用户截图发现）。
    var tick by remember { mutableIntStateOf(0) }
    // 跨天也得自己翻：`today` 原来只 remember 一次，过了零点还认昨天，
    // 于是「今天完成」会记到昨天头上。跟着节拍一起重算。
    val today = remember(tick) { LocalDateTime.now().toLocalDate().toString() }
    val ctx = LocalContext.current

    // 整分节拍：状态的时间粒度本来就是 HH:mm，秒级刷新没有意义、纯白烧电。
    // 闸门挂在 RESUMED —— 切后台自动挂起，**不会每分钟把机器叫醒一次**
    // （那才是真费电）；回到前台立刻先算一次，不让"刚回来"还显示旧状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick++
                delay(msToNextMinute(LocalDateTime.now()))
            }
        }
    }

    LaunchedEffect(reload, tick) {
        // 首次启动时种子可能还在后台导入 —— 不等它就会先渲染出一个空页面，
        // 看起来像"这个 App 没有数据"。等它跑完再读，代价只有几十毫秒。
        (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
        view = loadToday(db)
    }

    val v = view ?: run {
        // 首帧：读库很快，但仍给一句说明而不是空白
        Box(Modifier.fillMaxSize().padding(22.dp)) {
            Text("正在读取今天的安排…", color = C.txt3, fontSize = 13.sp)
        }
        return
    }

    // 把课和自习合成一条时间轴，按时间排 —— 一天的真实顺序本来就该是这样
    val rows = buildList {
        v.classes.forEach {
            add(TRow(timeLabel(it.timeText), it.name, listOfNotNull(it.room, it.teacher).joinToString(" · "), it.status, true, null))
        }
        v.selfstudy.forEach {
            add(TRow(selfStudyLabel(it.start, it.end), it.kind, it.place ?: "", it.status, false, null))
        }
    }.sortedBy { it.time }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 28.dp),
    ) {
        // 刷新条放在列表**内部**当第一项。原因是实测出来的：
        // 先前把它和 LazyColumn 写成兄弟节点，而调用方的容器是 Box，
        // 于是全屏的 LazyColumn 直接盖在刷新条上，两行文字叠成一团。

        item { Head(v) }

        item { SectionTitle("今天", "${v.classes.size} 节课 · ${v.selfstudy.size} 项自习") }

        // 晚自习被假期规则拿掉时要**说出来**，别让它静默消失 —— 那看着像 bug。
        // 文案与服务端同一句（如「中秋节假期的前一天（当晚没有晚自习）」），
        // 只讲晚上：早自习不受影响。
        if (v.selfStudyOff.isNotEmpty()) {
            item {
                Text(
                    v.selfStudyOff,
                    color = C.txt3, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        if (rows.isEmpty()) {
            item { Empty("今天没有已记录的课程或自习安排。", "早晚自习不来自教务课表；旧版安排丢失时可到「我的」恢复，并核对班级通知。") }
        } else {
            items(rows) { r -> TimeRow(r, v.weekNo) }
        }


    }

    }
}

/**
 * 今日页课节左列给**时间范围**（如 `09:30-11:05`）。
 *
 * 用户原话（2026-09-18）：「左列只有开始时间，我想知道上到几点」。
 *
 * ⚠️ 输入不是原始 time_text，而是 `slotTimeLabel()` 拼出来的
 * **「1-2 节 · 09:30-11:05」** —— 中间那个 `·` 是关键分隔。
 * 第一版按"含冒号就取第一个 `-` 之前"写 ✗，在「1-2」的短横线处就切断了。
 * 这里改成：**先把冒号钟点全部抓出来**，不再依赖 `-` 的位置。
 *
 * 三条不许破的规矩：
 *  ① 抓到两个钟点时**两个都补前导零**（`9:30-11:05` → `09:30-11:05`），否则同一列对不齐；
 *  ② 只有一个钟点时给一个钟点，**不编造**结束时间（宁可短，不可假）；
 *  ③ 一个钟点都没有、但有节次 → 查官方作息表，给到**末节的下课时间**
 *     （`1-2 节` → 09:30-11:05，`6-7 节` → 16:00-17:35）。
 *
 * 长度上限就钉在同文件外的测试里：≤ 11 字符（`HH:mm-HH:mm`）。列宽是按它留的，
 * 超了就会被挤/截断 —— 那正是用户抱怨的"看不全"。
 *
 * 同一列里自习行显示的是钟点（08:45 / 19:00），所以课节也必须给钟点 ——
 * 同学看「6」不知道几点，一列两种语言更乱。
 */
internal fun timeLabel(timeText: String): String {
    val t = (timeText ?: "").trim()
    // 「1-2 节 · 09:30-11:05」→ 取「·」后面那段：真实的钟点都在那里
    val after = t.substringAfter("·", t)
    val times = Regex("""\d{1,2}:\d{2}""").findAll(after).map { zeroPad(it.value) }.toList()
    if (times.size >= 2) return "${times[0]}-${times[1]}"
    if (times.size == 1) return times[0]
    // 没有钟点（老数据/模板 seed 的其它形状）→ 有节次就用官方作息表换算成起止
    val units = Regex("""\d+""").findAll(t.substringBefore("节"))
        .mapNotNull { it.value.toIntOrNull() }.toList()
    val a = units.firstOrNull() ?: return t
    val b = units.getOrNull(1) ?: a
    val start = UNIT_TIMES[a]?.first ?: return t
    val end = UNIT_TIMES[b]?.second ?: UNIT_TIMES[a]?.second ?: return t
    return "$start-$end"
}

/** `9:30` → `09:30`（列对齐靠它；已经是 4 位的原样返回） */
private fun zeroPad(hhmm: String): String =
    if (hhmm.length == 4 && hhmm[1] == ':') "0$hhmm" else hhmm

/**
 * 自习行左列：**给范围，不是只给起点**（`08:45-09:15` / `20:30-22:05`）。
 *
 * 用户原话（2026-09-18）：「早自习和晚自习的具体时间也加上，早自习上半个小时，
 * 晚自习上一小时四十五分钟」—— 同一列里课节早就给了范围，唯独自习只有一个起点，
 * 一列两种语言，看着就乱。
 *
 * ⚠️ **末点一律用库里 `selfstudy.end` 原值，不要拿用户口述的"时长"去算。**
 * 他先说晚自习是「一小时四十五分」，次日更正「到十点零五」= 22:05 / 95 分钟
 * （正好是官方作息表第 11 节下课，跟 UNIT_TIMES[10].first…[11].second 对上）。
 * 照口述算出来的 22:15 是错的。本函数只管排版，不做时间运算。
 *
 * 规矩跟 [timeLabel] 一致：只有一个钟点时**不编造**另一个。
 * 长度同样 ≤ 11 字符（`HH:mm-HH:mm`），所以起点要先补前导零再拼。
 */
internal fun selfStudyLabel(start: String, end: String): String {
    val a = (start ?: "").trim()
    val b = (end ?: "").trim()
    if (a.isEmpty()) return b
    if (b.isEmpty() || b == a) return zeroPad(a)
    return "${zeroPad(a)}-${zeroPad(b)}"
}

private data class TRow(
    val time: String,
    val title: String,
    val sub: String,
    val status: String,
    val isClass: Boolean,
    val taskId: Int?,
)

@Composable
private fun Head(v: TodayView) {
    val md = remember { DateTimeFormatter.ofPattern("MM-dd") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("第 ${v.weekNo} 周", color = C.violet, fontSize = 26.sp, fontWeight = FontWeight.Bold, style = Digits)
            Spacer(Modifier.width(10.dp))
            Text(v.weekdayCn, color = C.txt2, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(v.date.substring(5).replace("-", " / "), color = C.txt3, fontSize = 13.sp, style = Digits)
        }
        Text(
            listOfNotNull(v.semesterName, "共 ${v.termWeeks} 周").joinToString(" · "),
            color = C.txt3, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
}

@Composable
private fun SectionTitle(text: String, hint: String) {
    Column(Modifier.padding(top = 26.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text, color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(hint, color = C.txt3, fontSize = 11.sp)
        }
    }
}

/**
 * 左列最小宽度。
 *
 * 左列从「09:30」变成「09:30-11:05」（11 字符）后，**不能再让它自由换行** ——
 * 一行 `HH:mm-HH:mm` 被折成两行会把这个时间轴整列撑高、看着像排版坏了。
 * 这里给一个下限（11 个 13sp 字符 ≈ 78dp）＋ 单行不换行，窄屏也看得全。
 * `widthIn(min=)` 而不是固定 `width()`：用户把系统字号调大时让它自己变宽，
 * 不许把字切掉。
 */
private val TimeColMin = 78.dp

/**
 * 时间轴一行。状态只用圆点颜色 + 文字明暗表达：
 * now = 主色亮起；done = 整体压暗；todo = 常规。
 */
@Composable
private fun TimeRow(r: TRow, week: Int) {
    val dot = when (r.status) {
        "now" -> C.violet
        "done" -> C.lineHi
        else -> C.lineHi
    }
    val titleColor = when (r.status) {
        "now" -> C.txt
        "done" -> C.txt3
        else -> C.txt
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            r.time,
            color = if (r.status == "now") C.violet else C.txt3,
            fontSize = 13.sp,
            style = Digits,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = TimeColMin),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(1.dp).height(28.dp).background(C.line)) {}
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, color = titleColor, fontSize = 15.sp, fontWeight = if (r.status == "now") FontWeight.SemiBold else FontWeight.Normal)
            if (r.sub.isNotBlank()) {
                Text(r.sub, color = C.txt3, fontSize = 11.sp)
            }
        }
        val tag = when (r.status) {
            "now" -> "正在"
            "done" -> "已过"
            else -> if (r.isClass) "" else ""
        }
        if (tag.isNotEmpty()) {
            Text(tag, color = if (r.status == "now") C.violet else C.txt3, fontSize = 11.sp)
        }
    }
}

/** 把资源列表折成一个「只看这个」的入口：有视频优先给视频，没有就给别的。 */
@Composable
private fun Empty(main: String, next: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(main, color = C.txt2, fontSize = 14.sp)
        Text(next, color = C.txt3, fontSize = 12.sp)
    }
}

