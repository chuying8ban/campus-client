package top.ccbase.campus.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.plan.ResetPlan
import top.ccbase.campus.data.plan.ResetStore
import top.ccbase.campus.data.plan.TplLoader
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.data.local.DeletedTask
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.domain.TaskRow
import top.ccbase.campus.domain.TaskSection
import top.ccbase.campus.domain.groupTasks
import top.ccbase.campus.domain.minutesText
import top.ccbase.campus.domain.resourceKindLabel
import top.ccbase.campus.domain.stepKindLabel
import top.ccbase.campus.domain.matchesTrack
import top.ccbase.campus.domain.trackLabel
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import top.ccbase.campus.util.Links
import java.time.LocalDate

/**
 * 任务页 —— 你的 39 个任务按阶段分组。点进去看步骤和资源。
 *
 * 为什么按"阶段"分组而不是按"类型"：阶段就是时间轴（本学期 → 寒假 → 大一下 …），
 * 这才是路线图的读法。类型（正课/自学/课外/手续）作为行内标签保留。
 *
 * `onOpenPlan`：进「AI 规划学习计划」那一页。
 * 2026-09-18 用户要求把它从「我的」搬进来，并且「学习」标签页里要能一眼看到 ——
 * 所以入口是一个整行可点的卡片 + 一句说明，不是一行小字链接。
 *
 * ⚠️ 这个参数现在**只是保留**（签名兼容，调用处/测试还按这个签名走）：
 * 入口的渲染**已经不在本页**了。底栏「学习」那一格（CampusApp.TasksLearnScreen）
 * 把它摆在整页内容**最上面**，用户怎么滚都在原处、都点得到，
 * 且**全工程只渲染那一处**（本页若也渲染，页面上会同时出现两份）。
 * PlanEntry 这个 Composable 仍然住在本文件里，给那一页调用。
 *
 * 2026-09-18 追加：这一页的**内容**现在与「学习」那一格共用 —— 见 [tasksContent]。
 * 内容必须是 LazyListScope 扩展，而不是自带滚动容器的页面：LazyColumn 不能嵌套
 * LazyColumn（内层拿不到有界高度，直接崩），承载它的那一页得能自己决定怎么铺。
 * [TasksScreen] 自己现在只是"给这条内容配一个滚动容器"的薄壳（测试直接渲染它）。
 *
 * 2026-09-19：原先并列铺在这条内容后面的「学习内容半」已按用户要求整份删掉
 * （它和「学习库」重复），这一页现在只有任务。
 */
@Composable
fun TasksScreen(db: CampusDb, onOpenPlan: () -> Unit = {}) {
    var open by remember { mutableStateOf<Int?>(null) }
    // null = 全部。分类色/筛选项都走 domain/TaskTrack，配色在 TrackColors.kt
    var onlyTrack by remember { mutableStateOf<String?>(null) }
    // 库内容被改过（删 / 恢复）就 +1 —— 任务分组与"已删除"那段都靠它重读
    var tick by remember { mutableIntStateOf(0) }
    // 页首「整理」那一层开没开
    var more by remember { mutableStateOf(false) }
    val del = rememberTaskDelete(db, tick) {
        // 删掉的若是正在看的那条，详情必须退出：它已经不在清单里，留着就是个死页
        open = null
        tick++
    }
    val reset = rememberReset(db) {
        open = null
        tick++
    }

    val sections = rememberTasksSections(db, tick)

    // 确认层要盖住整页（列表 or 详情），所以根上包一层 Box
    Box(Modifier.fillMaxSize()) {
        val id = open
        if (id != null) {
            TaskDetailScreen(db, id, onBack = { open = null }, onDelete = { title -> del.ask(id, title) })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 28.dp),
            ) {
                tasksContent(sections, onlyTrack, { onlyTrack = it }, { open = it }, del, onOpenMore = { more = true }, onOpenPlan = onOpenPlan)
            }
        }
        del.asking?.let { p ->
            TaskDeleteConfirm(p.title, onCancel = { del.cancel() }, onConfirm = { del.confirm() })
        }
        if (more) MoreSheet(reset, onClose = { more = false })
        if (reset.asking) {
            ResetConfirm(onCancel = reset.cancel, onConfirm = {
                reset.confirm()
                more = false
            })
        }
    }
}

/** 任务半的数据；`null` = 还没读完库（调用方据此显示加载态）。`tick` 一变就重读。 */
@Composable
internal fun rememberTasksSections(db: CampusDb, tick: Int = 0): List<TaskSection>? {
    val ctx = LocalContext.current
    var sections by remember { mutableStateOf<List<TaskSection>?>(null) }
    LaunchedEffect(tick) {
        (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
        // ⚠️ 只认「你自己的计划」：plan_source == remote 才是服务器按**你**的课表发下来的。
        // 不是 remote，就说明库里那份来路不明（随包的作者数据、老装机残留）——
        // 宁可显示"还没有你的计划"，也不拿别人的课表冒充（用户原话：宁可没数据也不要造假数据）。
        val mine = db.dao().metaGet(PlanApplier.K_SOURCE) == PlanApplier.REMOTE
        val all = db.dao().visibleTasks().first()
        // 「学习库」里挑的资料是**用户自己**挑的（挑选记录存在 meta，不属于任何人的计划），
        // 所以计划还没同步 / 刚被重置成空的时候，也要把他自己挑出来的那几条显示出来 ——
        // 否则用户在「学习库」点完"加到我的清单"、切到学习页却一片空白，看着就像没加上。
        val own = if (mine) all else all.filter { Library.isLibraryTask(it.id) }
        sections = if (!mine && own.isEmpty()) {
            emptyList()
        } else {
            groupTasks(
                // visibleTasks()：删掉的那些不该再算进"39 项"和阶段分组里
                tasks = own,
                steps = db.dao().allSteps().first(),
                resources = db.dao().resources().first(),
            )
        }
    }
    return sections
}

/**
 * 任务半的内容，直接铺进调用方的 LazyListScope。
 *
 * 写成 LazyListScope 扩展而不是 Composable，是为了让合并页能用**一条** LazyColumn
 * 同时装下任务半和学习半（去掉内层子切换后两半同页；两层 LazyColumn 嵌套会崩）。
 * 状态（分类筛选、点开哪一条）由调用方持有 —— 独立页与合并页各持一份，互不干扰。
 */
internal fun LazyListScope.tasksContent(
    sections: List<TaskSection>?,
    onlyTrack: String?,
    onPickTrack: (String?) -> Unit,
    onOpenTask: (Int) -> Unit,
    /**
     * 删除流程：确认层、底部「已删除」段、恢复动作。
     * 默认 `null` —— 只关心排版的老调用点（含用例）不用跟着改。
     */
    delete: TaskDelete? = null,
    /** 页首「整理」入口的点击（null = 不渲染这个入口，老调用点/用例不受影响） */
    onOpenMore: (() -> Unit)? = null,
    /** 空计划时那个「去生成 / 同步」的落点（null = 只说明、不给按钮） */
    onOpenPlan: (() -> Unit)? = null,
) {
    if (sections == null) {
        item(key = "tasks-loading") {
            Box(Modifier.fillMaxWidth()) {
                Text("正在读取任务…", color = C.txt3, fontSize = 13.sp)
            }
        }
        return
    }

    val totalTasks = sections.sumOf { it.rows.size }
    val doneSteps = sections.sumOf { it.rows.sumOf { r -> r.progress.done } }
    val allSteps = sections.sumOf { it.rows.sumOf { r -> r.progress.total } }

    item(key = "tasks-head") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("任务", color = C.green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val shownCount = sections.sumOf { sec -> sec.rows.count { matchesTrack(it.track, onlyTrack) } }
                val countText = if (onlyTrack == null) "$totalTasks 项" else "$shownCount / $totalTasks 项"
                Text("$countText · 步骤 $doneSteps/$allSteps", color = C.txt3, fontSize = 11.sp, style = Digits)
                if (onOpenMore != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        PlanLogic.MORE_ENTRY,
                        color = C.cyan, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, C.lineHi, RoundedCornerShape(8.dp))
                            .clickable { onOpenMore() }
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
            // 一行定位说明：上一版按"精简"把整句说明删了，结果用户看着这页问"这些东西到底是干嘛的"。
            // 所以留**一行**（不堆字）：这页是什么、怎么用。空计划时不显示（空态自己有话说）。
            if (totalTasks > 0) {
                Text("你的学习计划 · 点开看步骤和资源，勾步骤推进度", color = C.txt3, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        }
    }

    // ⚠️ 「AI 规划学习计划」的入口**不在这里渲染** —— 它在「学习」那一页整页内容之上
    // （CampusApp.TasksLearnScreen 里那一处），滚到哪都看得见；
    // 用户已经明确讨厌要摸索/得先切一层才看得见的入口，两处都放则页面上会冒出两份。

    // 一条任务都没有 = 这位同学的计划还没拉到（或还没生成）。
    // 这里**绝不**回落到随包的作者数据：空着 + 说清缘由，比拿别人的课表冒充强得多。
    if (totalTasks == 0) {
        item(key = "tasks-empty") {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Text(PlanLogic.EMPTY_TITLE, color = C.txt, fontSize = 14.5.sp)
                Spacer(Modifier.height(6.dp))
                Text(PlanLogic.EMPTY_BODY, color = C.txt3, fontSize = 12.sp, lineHeight = 18.sp)
                if (onOpenPlan != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        PlanLogic.EMPTY_ACTION,
                        color = C.cyan, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.dp, C.cyan.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                            .clickable { onOpenPlan() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        return
    }

    // 分类筛选：只留「全部 / 正课 / 自学 / 其他」四个 —— 原先 6 个要横着滑才看全，
    // 而横滑出来的东西用户不知道它在那儿（等于没有）。四个刚好一行，一眼看全。
    val allRows = sections.flatMap { it.rows }
    val present = top.ccbase.campus.domain.trackFiltersOf(allRows.map { it.track })
    // 「其他」只在真有条目时才算一类：0 条的筛选项看着像坏了，也是纯噪音
    val others = allRows.count { it.track.isNullOrBlank() || top.ccbase.campus.domain.trackLabel(it.track) !in top.ccbase.campus.domain.TRACK_ORDER }
    val picks = listOf("正课", "自学").filter { present.contains(it) } +
        listOf("其他").filter { others > 0 }
    val chips = picks.distinct()
    if (chips.isNotEmpty()) {
        item(key = "track-filter") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                TrackChip("全部", null, allRows.size, onlyTrack) { onPickTrack(it) }
                chips.forEach { k ->
                    TrackChip(k, k, allRows.count { matchesTrack(it.track, k) }, onlyTrack) { onPickTrack(it) }
                }
            }
        }
    }

    sections.forEach { sec ->
        val rows = sec.rows.filter { matchesTrack(it.track, onlyTrack) }
        if (rows.isEmpty()) return@forEach
        item(key = "h-${sec.phase}") {
            Column(Modifier.padding(top = 26.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(sec.phase, color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${rows.size} 项", color = C.txt3, fontSize = 11.sp)
                }
            }
        }
        items(rows, key = { "t-${it.id}" }) { r ->
            TaskListRow(r, { onOpenTask(r.id) }, onDelete = { delete?.ask(r.id, r.title) })
        }
    }

    // 「已删除」那段永远排在最后：它是收拾残局的地方，不该插在路线图中间抢注意力。
    // 一项都没有时整段不渲染（没删过的人根本不该看到"回收站"这种东西）。
    if (delete != null && delete.deleted.isNotEmpty()) {
        item(key = "deleted-section") { DeletedSection(delete) }
    }
}

/**
 * 「AI 规划学习计划」的入口卡片 —— 由**合并页**（CampusApp.TasksLearnScreen）渲染在
 * 整页内容**最上面**（在承载任务半 + 学习半的那条 LazyColumn 之外），
 * 所以不管用户往下滚到哪一半，它都在原处、都点得到。
 *
 * 为什么写成卡片而不是一行小字：
 * 用户明确反感"只能靠改地址/藏在二级页里的入口"，也说过好几次"功能要摆在明处"。
 * 所以这里 ① 位置醒目（进「学习」这个标签页第一眼就是它，往下滚也不会滚走）；
 * ② 有一句说明它到底干什么（给建议、你自己挑、能撤销 —— 免得被当成"自动改我计划"的开关）；
 * ③ 整行可点。
 *
 * 可见性 internal：它是跨包（ui.tasks → ui）唯一的调用点，但**不许**再有第二个渲染处 ——
 * 两个渲染处 = 页面上出现两份入口。
 *
 * 文案一律走 [PlanLogic] 里的常量，测试引用同一份，改字不会把测试改红。
 */
@Composable
internal fun PlanEntry(onOpenPlan: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.card)
            .border(1.dp, C.cyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenPlan)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(PlanLogic.TITLE, color = C.cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(PlanLogic.ENTRY_HINT, color = C.txt3, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = C.txt3, fontSize = 16.sp)
    }
}

@Composable
private fun TrackChip(
    label: String,
    track: String?,
    count: Int,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    val on = selected == track
    val c = trackColor(track)
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            // ⚠️ C.card 自带 alpha（0x0AFFFFFF = 4% 白）。用 copy(alpha = x) 会把它**覆盖**掉，
            // 直接变成"x 的白" —— 曾经写成 copy(alpha = .6f)，筛选片就成了浅灰底 + 灰字，
            // 字糊在片里（用户截图反馈「看不清」）。要更亮的底就用现成的 C.cardHi，别 copy。
            .background(if (on) c.copy(alpha = 0.22f) else C.card)
            .clickable { onPick(if (on) null else track) }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Spacer(Modifier.width(6.dp))
        Text(
            "$label $count",
            color = if (on) c else C.txt2,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TaskListRow(r: TaskRow, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 整行的标签：用例（和无障碍工具）按 id 认"哪一条"，不按列表顺序（组内会按进度重排）
            .testTag(taskRowTag(r.id))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧进度条：极细，只为"扫一眼看到哪几项推进过"服务
        Column(Modifier.padding(top = 3.dp).width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(C.card)) {
            if (r.progress.total > 0 && r.progress.done > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(r.progress.ratio.coerceIn(0f, 1f))
                        .background(C.green.copy(alpha = 0.75f)),
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.title,
                color = if (r.active) C.txt else C.txt3,
                fontSize = 14.5.sp, lineHeight = 21.sp,
            )
            // 行内只留两样：进度 + 分类色标。
            // 「动作目标（deliverable）· 时长 · 几个资源 · 后续阶段」全部挪进详情页 ——
            // 47 行每行堆 6 块信息，翻起来就是一团（用户原话：看着太繁琐）。
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(trackColor(r.track).copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(trackLabel(r.track), color = trackColor(r.track), fontSize = 10.sp)
                }
                if (r.progress.total > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(r.progress.text, color = C.txt3, fontSize = 10.5.sp)
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        // 行尾两个独立热区：点行本身 = 打开详情，点「⋯」= 删除入口。
        // 为什么不长按菜单：长按没有任何视觉提示，用户永远发现不了（"入口要摆在明处"）。
        // 也不把整行做成删除热区 —— 误触代价太大。
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .testTag(deleteTag(r.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⋯", color = C.txt3, fontSize = 17.sp)
        }
        Text("›", color = C.txt3, fontSize = 16.sp)
    }
}

/**
 * 任务详情：步骤（可单独打勾）+ 学习内容（点开在 App 内滑出对应平台）。
 *
 * 可见性 internal：合并页点开一条任务时，整页切到它（与独立任务页的行为一致）。
 */
@Composable
internal fun TaskDetailScreen(
    db: CampusDb,
    taskId: Int,
    onBack: () -> Unit,
    /** 从详情里删除（标题交给确认层显示）。null = 这一处不给删（例如被别处当成只读视图用时） */
    onDelete: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<TaskRow?>(null) }
    var steps by remember { mutableStateOf<List<StudyStep>>(emptyList()) }
    var res by remember { mutableStateOf<List<Resource>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    // 待确认"完成"的步骤 id。整行都是热区，原来点一下就完成 —— 太容易误触（用户反馈）。
    // 撤销仍然一点就回：撤销是安全的，不该再挡一道。
    var confirmStep by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(taskId, reload) {
        (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
        val all = db.dao().tasks().first()
        val t = all.firstOrNull { it.id == taskId } ?: return@LaunchedEffect
        val st = db.dao().allSteps().first().filter { it.task_id == taskId }.sortedBy { it.seq }
        steps = st
        res = db.dao().resources().first().filter { it.task_id == taskId }.sortedBy { it.sort }
        task = groupTasks(listOf(t), st, emptyList()).firstOrNull()?.rows?.firstOrNull()
    }

    val t = task ?: run {
        Box(Modifier.fillMaxSize().padding(22.dp)) { Text("读取中…", color = C.txt3, fontSize = 13.sp) }
        return
    }
    val day = LocalDate.now().toString()
    val doneCount = steps.count { it.done_day != null }

    // 根包一层 Box：完成确认层要能盖住整页（撤掉 Column 的旧写法见下）
    Box(Modifier.fillMaxSize()) {

    Column(
        Modifier
            .fillMaxSize()
            .testTag(DETAIL_ROOT_TAG)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 30.dp),
    ) {
        Row(
            Modifier.clickable(onClick = onBack).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", color = C.txt2, fontSize = 20.sp)
            Spacer(Modifier.width(7.dp))
            Text("任务", color = C.txt2, fontSize = 13.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text(t.title, color = C.txt, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)

        if (!t.detail.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(t.detail, color = C.txt2, fontSize = 13.sp, lineHeight = 21.sp)
        }
        if (!t.deliverable.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Row {
                // 用一根细竖线标"产出物"，不用填充卡片
                Box(Modifier.width(2.dp).height(34.dp).background(C.green.copy(alpha = 0.6f)))
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("产出物", color = C.txt3, fontSize = 10.5.sp)
                    Text(t.deliverable, color = C.txt, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        // ---------------- 学习步骤 ----------------
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("学习步骤", color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("$doneCount/${steps.size} · 约 ${minutesText(t.minutes)}", color = C.txt3, fontSize = 10.5.sp)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            steps.forEach { s ->
                val done = s.done_day != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (done) {
                                // 撤销：直接回退，顺手（撤销是安全的，不再挡一道）
                                scope.launch { db.dao().unmarkStep(s.id); reload++ }
                            } else {
                                // 完成：先确认。整行都是热区，滑屏/手抖都会点到，
                                // 误触的代价是"其实没做却显示做了"（用户反馈）。
                                confirmStep = s.id
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(if (done) C.green else Color.Transparent)
                            .border(1.dp, if (done) C.green else C.lineHi, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${s.seq}. ${s.text}",
                            color = if (done) C.txt3 else C.txt,
                            fontSize = 13.5.sp, lineHeight = 20.sp,
                        )
                    }
                    val k = stepKindLabel(s.kind)
                    if (k.isNotEmpty() || (s.minutes ?: 0) > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(k.takeIf { it.isNotEmpty() }, (s.minutes ?: 0).takeIf { it > 0 }?.let { "${it}分" }).joinToString(" "),
                            color = C.txt3, fontSize = 10.sp, style = Digits,
                        )
                    }
                }
            }
        }

        // ---------------- 学习内容（外链） ----------------
        if (res.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("学习内容", color = C.txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${res.size} 个 · 点开去对应平台看", color = C.txt3, fontSize = 10.5.sp)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))

            // 同一任务里可能重复挂同一个链接，按 url 去重（去重后仍保留第一个的说明）
            res.distinctBy { it.url }.forEach { r ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { Links.open(ctx, r.url) }
                        .padding(vertical = 13.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(resourceKindLabel(r.kind), color = C.cyan, fontSize = 10.5.sp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title ?: r.url ?: "", color = C.txt, fontSize = 13.5.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(listOfNotNull(r.source, r.url?.substringAfter("//")?.substringBefore("/")).joinToString(" · "), color = C.txt3, fontSize = 10.5.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("打开 ↗", color = C.cyan, fontSize = 10.5.sp)
                    }
                    if (!r.why.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(r.why, color = C.txt3, fontSize = 11.5.sp, lineHeight = 17.sp)
                    }
                }
            }
        }

        if (steps.isEmpty() && res.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("这一项没有排步骤和资源。", color = C.txt2, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Text("它更像一条备忘录 —— 做到就行，不用按部就班。", color = C.txt3, fontSize = 12.sp)
        }

        // 删除入口放在详情最底下：不该抢"打勾 / 看资源"的注意力，
        // 但用户顺着"看到这条不想做"的路径进来时，必须在这里也能删掉它。
        // 用暗红而不是红底按钮：它是低频、需要犹豫一下的动作，不该长得像主按钮。
        if (onDelete != null) {
            Spacer(Modifier.height(30.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(DETAIL_DELETE_TAG)
                    .clickable { onDelete(t.title) }
                    .padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("从清单里删除这项任务", color = C.red.copy(alpha = 0.9f), fontSize = 13.5.sp)
            }
        }
    }

    // 完成确认层：盖住整页。点空白处 = 取消 —— 会误触的人下一步也可能误触，
    // 所以"取消"必须随便点一下就能做到。
    confirmStep?.let { id ->
        StepDoneConfirm(
            text = steps.firstOrNull { it.id == id }?.text ?: "",
            onCancel = { confirmStep = null },
            onConfirm = {
                confirmStep = null
                scope.launch { db.dao().markStep(id, day); reload++ }
            },
        )
    }
    }
}

/**
 * 「确认完成？」确认层（普通 Composable，不是 Dialog）。
 *
 * 为什么不是 Dialog：Robolectric 看不见 Dialog 窗口，等于这个交互**无法被测试**，
 * 只能靠真机手点 —— 而它恰恰是防误触的，改坏了没人会发现。普通 Composable
 * 既能盖住整页，又能被测试抓到（GrabScreen 的确认层也是这么做的）。
 */
@Composable
private fun StepDoneConfirm(text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60B0910))
            .clickable { onCancel() }
            .padding(horizontal = 24.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.bgSoft, RoundedCornerShape(16.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(16.dp))
                // 卡片本身吃掉点击，免得点在卡片空白处被当成"取消"
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text("确认完成？", color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(text, color = C.txt2, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ConfirmBtn("取消", onCancel)
                Spacer(Modifier.width(10.dp))
                ConfirmBtn("确认完成", onConfirm, primary = true)
            }
        }
    }
}

@Composable
private fun ConfirmBtn(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    /** 危险动作（删除）：用红而不是绿 —— "绿=确认"用在这里会让人以为是安全操作 */
    danger: Boolean = false,
) {
    val accent = if (danger) C.red else C.green
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) accent else Color.Transparent)
            .border(1.dp, if (primary) accent else C.lineHi, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (primary) Color(0xFF06110A) else C.txt2,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ================================================================ 从我的清单里删除

/** 任务行删除入口的标签。按 id 定位，不靠文案 —— 文案会改，id 不会。 */
internal fun deleteTag(taskId: Int): String = "task-delete-$taskId"

/**
 * 任务行**整行**的标签（点它 = 打开详情）。
 *
 * 为什么要按 id：阶段组内现在按进度排序（未完成在前、已完成沉底），一条任务的打勾一变
 * 就可能换位置 —— 用例若靠"文字下标第 0 个"或"列表顺序"去找它，正好在**排序真的生效**时
 * 点空（表现为 15s 超时 / 找不到节点，看着像功能坏了）。id 不随排序变。
 */
internal fun taskRowTag(taskId: Int): String = "task-row-$taskId"

/**
 * 「已删除」里那一条的恢复按钮标签。
 *
 * 为什么要按 id：一个列表里会有好几个文案一模一样的「恢复」，用例（或无障碍工具）
 * 只能按下标取第一个 —— 那就等于赌"列表顺序永远是我想的那样"；一旦别的用例留下了墓碑、
 * 或者排序变了，点下去恢复的就是**另一条**，用例表现成"等目标回来 15s 超时"，
 * 看着像功能坏了，其实只是点错了行。
 */
internal fun restoreTag(taskId: Int): String = "task-restore-$taskId"

/** 确认层标题。用例引用同一份常量，改文案不会把测试改红。 */
internal const val DELETE_CONFIRM_TITLE = "从清单里删除？"

/** 底部「已删除 N 项」那一行的标签 */
internal const val RESTORE_TOGGLE_TAG = "task-restore-toggle"

/** 详情页里「从清单里删除这项任务」那一行的标签 */
internal const val DETAIL_DELETE_TAG = "task-detail-delete"

/** 详情页根节点的标签（"删完必须退出详情"靠它断言） */
internal const val DETAIL_ROOT_TAG = "task-detail-root"

/** 正在等确认的那一条 */
internal data class PendingDelete(val taskId: Int, val title: String)

/**
 * 删除流程的状态与动作。
 *
 * 单独抽出来是因为任务列表有**两个**渲染处（独立任务页 + 底栏那个合并页），
 * 两处行为必须一模一样 ——「一处能删、另一处不能删」是这类改动最典型的半边工程。
 */
@Stable
internal class TaskDelete(
    /** 已从清单里删掉的（墓碑里的那些；恢复列表要按标题显示，所以是任务本体不是 id） */
    val deleted: List<Task>,
    /** 底部那段是否展开 */
    val expanded: Boolean,
    /** 正在等确认的删除（null = 没有确认层） */
    val asking: PendingDelete?,
    val ask: (Int, String) -> Unit,
    val cancel: () -> Unit,
    val toggle: () -> Unit,
    val confirm: () -> Unit,
    val restore: (Int) -> Unit,
    val restoreAll: () -> Unit,
)

/**
 * 删除流程的实现：状态在这里、库操作也在这里，页面只负责渲染。
 *
 * `onChanged` 由调用方提供，做两件事：把 tick +1 触发重读，以及"如果删的正是开着的那条详情，
 * 就把详情关掉"（否则会停在一个已经不在清单上的死页面上）。
 */
@Composable
internal fun rememberTaskDelete(db: CampusDb, tick: Int, onChanged: () -> Unit): TaskDelete {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleted by remember { mutableStateOf<List<Task>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<PendingDelete?>(null) }

    LaunchedEffect(tick) {
        (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
        deleted = db.dao().deletedTasks().first()
        // 一条都不剩时把那段收起来，否则页尾会挂着一个空的"已删除 0 项"
        if (deleted.isEmpty()) expanded = false
    }

    return TaskDelete(
        deleted = deleted,
        expanded = expanded,
        asking = asking,
        ask = { id, title -> asking = PendingDelete(id, title) },
        cancel = { asking = null },
        toggle = { expanded = !expanded },
        confirm = {
            val p = asking
            if (p != null) {
                asking = null
                scope.launch {
                    // 打墓碑，**不删任务行** —— 见 DeletedTask 的说明：真删了会被下一次同步写回来
                    db.dao().markTaskDeleted(DeletedTask(p.taskId, LocalDate.now().toString()))
                    onChanged()
                }
            }
        },
        restore = { id ->
            scope.launch {
                db.dao().undeleteTask(id)
                onChanged()
            }
        },
        restoreAll = {
            scope.launch {
                db.dao().undeleteAllTasks()
                onChanged()
            }
        },
    )
}

/**
 * 「从清单里删除？」确认层。
 *
 * 与「确认完成？」同一个套路：普通 Composable，不是 Dialog —— Robolectric 看不见 Dialog 窗口，
 * 那种"防误触"的交互就变成测不到、只能真机手点。文案要一眼说清两件事：
 * 删的是哪一条、删了还能不能回来（能，页尾「已删除」里随时恢复）。
 */
@Composable
internal fun TaskDeleteConfirm(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60B0910))
            // 点空白处 = 取消：会误触的人下一步也可能误触，所以"取消"必须随便点一下就能做到
            .clickable { onCancel() }
            .padding(horizontal = 24.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.bgSoft, RoundedCornerShape(16.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(16.dp))
                // 卡片本身吃掉点击，免得点在卡片空白处被当成"取消"
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text(DELETE_CONFIRM_TITLE, color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (title.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(title, color = C.txt2, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只从你的清单里移掉，任务本身还在；页尾的「已删除」里随时能恢复。",
                color = C.txt3, fontSize = 11.5.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ConfirmBtn("取消", onCancel)
                Spacer(Modifier.width(10.dp))
                ConfirmBtn("删除", onConfirm, primary = true, danger = true)
            }
        }
    }
}

/**
 * 整页最末的「重置学习任务」。
 *
 * 为什么要它：用户原话「加一个重置学习任务的功能」 * 「重置后这里就应该没有任务了」。
 *
 * 2026-09-19 改口径：重置是**清空**、不再铺模板任务；来源标成 NONE，自动同步（打开 App /
 * 回前台）会跳过它 —— 否则服务端那份计划会立刻把清单塞回来，等于白按。
 * 计划被 AI 建议、模块包、手删搅过几轮之后，需要一条**一键回到内置那套**的路，
 * 而不是"逐条删"或者"卸载重装"。
 *
 * 三件必须一起做到的事（缺一件这个功能就不该上）：
 * ① **两步**：点一下只弹确认层，第一下不该有任何后果；
 * ② **先备份**：重置前把现状整份存进 filesDir，随后页尾挂出「恢复上次重置」；
 * ③ **不真删服务端来的行**：只打墓碑 —— 真删会被下一次同步写回来（见 DeletedTask 的说明）。
 *
 * 文案一律走 [PlanLogic] 常量，测试引用同一份。
 *
 * 重置这件事的状态：**确认层必须画在整页之上，不能画在列表项里**。
 *
 * 踩过的坑（用例当场抓住）：第一版把确认层塞在 LazyColumn 的 item 里，于是它被排到列表末尾，
 * 弹出来时整个框在视口外（证据：`Node at (l=46 t=478 r=274 b=704)` 而屏高只有 470），
 * 按钮点不到，用户看到的是"点了没反应"。
 * 删除流程早就这么分了（入口在列表、确认层挂在页根 `del.asking?.let { … }`），照抄它。
 */
internal class ResetHandle(
    val asking: Boolean,
    val busy: Boolean,
    val canUndo: Boolean,
    val ask: () -> Unit,
    val cancel: () -> Unit,
    val confirm: () -> Unit,
    val undo: () -> Unit,
)

@Composable
internal fun rememberReset(db: CampusDb, onChanged: () -> Unit): ResetHandle {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(ResetStore.file(ctx).exists()) }

    fun after() {
        busy = false
        canUndo = ResetStore.file(ctx).exists()
        onChanged()
    }

    return ResetHandle(
        asking = asking,
        busy = busy,
        canUndo = canUndo,
        ask = { asking = true },
        cancel = { asking = false },
        confirm = {
            asking = false
            scope.launch {
                busy = true
                withContext(Dispatchers.IO) {
                    (ctx.applicationContext as? CampusApplication)?.seedJob?.join()
                    val tpl = runCatching { TplLoader.load(ctx) }.getOrNull()
                    if (tpl != null) {
                        val courses = db.dao().courses().first().map { it.id to it.name }
                        val snap = ResetPlan.apply(db, tpl, courses, stampNow())
                        ResetStore.save(ctx, snap)
                    }
                }
                after()
            }
        },
        undo = {
            scope.launch {
                busy = true
                withContext(Dispatchers.IO) {
                    val snap = ResetStore.load(ctx)
                    if (snap != null) {
                        ResetPlan.restore(db, snap)
                        // 恢复一次就删掉备份：同一份再"恢复"一遍只会把现状又盖回去
                        ResetStore.clear(ctx)
                    }
                }
                after()
            }
        },
    )
}

/**
 * 页首「整理」那一层：凡是要动**整份清单**的操作都收在这里。
 *
 * 为什么做成一层而不是列表里的行：它原先排在 47 项任务之后，用户根本翻不到
 * （上线第一句反馈就是「没看到重置功能」）。放页首按钮 + 一层弹出，一眼可见、
 * 又不占清单的地儿。点空白处 = 关掉，不做任何事。
 */
@Composable
internal fun MoreSheet(h: ResetHandle, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C0B0910))
            .clickable { onClose() }
            .padding(horizontal = 22.dp, vertical = 40.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.bgSoft, RoundedCornerShape(16.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(PlanLogic.MORE_TITLE, color = C.txt, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SheetRow(
                label = if (h.busy) PlanLogic.RESET_BUSY else PlanLogic.RESET_ENTRY,
                hint = PlanLogic.RESET_HINT,
                enabled = !h.busy,
            ) { h.ask() }
            if (h.canUndo) {
                Spacer(Modifier.height(10.dp))
                SheetRow(
                    label = PlanLogic.RESET_UNDO,
                    hint = PlanLogic.UNDO_HINT,
                    enabled = !h.busy,
                ) { h.undo() }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                PlanLogic.RESET_CANCEL,
                color = C.txt3, fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

/** 那一层里的一行：标题 + 一句说明（说明是为了"不用猜会发生什么"） */
@Composable
private fun SheetRow(label: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = C.txt, fontSize = 14.sp)
        Spacer(Modifier.height(3.dp))
        Text(hint, color = C.txt3, fontSize = 11.5.sp, lineHeight = 17.sp)
    }
}

private fun stampNow(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

/** 「重置学习任务？」确认层。与「删除」同一套路：普通 Composable（Robolectric 看不见 Dialog 窗口）。 */
@Composable
internal fun ResetConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60B0910))
            .clickable { onCancel() }
            .padding(horizontal = 24.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.bgSoft, RoundedCornerShape(16.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text(PlanLogic.RESET_TITLE, color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(PlanLogic.RESET_BODY, color = C.txt2, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(6.dp))
            Text(PlanLogic.RESET_BACKUP_NOTE, color = C.txt3, fontSize = 11.5.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ConfirmBtn(PlanLogic.RESET_CANCEL, onCancel)
                Spacer(Modifier.width(10.dp))
                ConfirmBtn(PlanLogic.RESET_CONFIRM, onConfirm, primary = true, danger = true)
            }
        }
    }
}

/**
 * 页尾「已删除 N 项」—— 删除的后悔药。
 *
 * 为什么不用自动消失的 Snackbar/撤销条：靠时间控制的 UI 在测试里容易变成假绿，
 * 而且错过那几秒就真找不回了；常驻一段"点开就能恢复"比 6 秒倒计时可靠得多。
 * 为什么固定在最后：它属于收拾残局，不该挤在路线图中间抢注意力；一条都没删过时整段不渲染。
 */
@Composable
private fun DeletedSection(d: TaskDelete) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(RESTORE_TOGGLE_TAG)
                .clickable { d.toggle() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已删除 ${d.deleted.size} 项", color = C.txt3, fontSize = 12.5.sp)
            Spacer(Modifier.weight(1f))
            Text(if (d.expanded) "收起 ▴" else "恢复 ▾", color = C.cyan, fontSize = 12.sp)
        }
        if (d.expanded) {
            d.deleted.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t.title,
                        color = C.txt3, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.dp, C.lineHi, RoundedCornerShape(9.dp))
                            .clickable { d.restore(t.id) }
                            .testTag(restoreTag(t.id))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("恢复", color = C.cyan, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, C.lineHi, RoundedCornerShape(9.dp))
                        .clickable { d.restoreAll() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("全部恢复", color = C.txt2, fontSize = 12.sp) }
            }
        }
    }
}
