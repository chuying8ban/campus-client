package top.ccbase.campus.ui.grab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import top.ccbase.campus.alarm.GrabWatch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.GrabLesson
import top.ccbase.campus.net.GrabLog
import top.ccbase.campus.net.GrabStatus
import top.ccbase.campus.net.GrabTarget
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits

/**
 * 监控页（底部栏那一格按服务端给的 can_grab 显隐，页顶有口径说明；
 * 服务端仍然会拿 can_grab 再拦一道）。
 *
 * 这一页只做两件事：**看见**（哪些课现在能拿）、**盯住**（加入监控），
 * 并且**随时能停**（移除目标 / 关掉监控）。
 *
 * 一条纪律：**页面上出现的每个状态都来自服务端那一次 GET /grab/status**，
 * 客户端自己猜的状态不能上屏 —— 上一次这一页显示的是"监控状态无法解析"，
 * 那次事故的教训就是：客户端自己猜的状态，用户会当真。
 *
 * 冲突提示只是**信息**：提醒这门课即使选上也不一定上得了，App 不代选。
 */
@Composable
fun GrabScreen(ctx: Context, api: CampusApi = remember { CampusApi() }) {
    val token = TokenStore.token(ctx)
    var status by remember { mutableStateOf<GrabStatus?>(null) }
    var lessons by remember { mutableStateOf<List<GrabLesson>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var flt by remember { mutableStateOf("ok") }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<GrabLogic.Notice?>(null) }
    var showAllLogs by remember { mutableStateOf(false) }
    /** 页顶那段免责说明展开没有（默认收起：话要说清，但不能挡住下面的操作） */
    var showDisclaimer by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    // ── 手机直接提醒（和飞书/QQ 推送无关：这台手机自己弹）
    var watchOn by remember { mutableStateOf(GrabWatch.isOn(ctx)) }
    var notifOk by remember { mutableStateOf(false) }
    var watchLast by remember { mutableStateOf(GrabWatch.lastResult(ctx)) }

    val scope = rememberCoroutineScope()

    /**
     * 上一次的报错要在这次**成功之后**消失。
     * 反面教材就是真机截图那一幕：一条假报错写进 notice 之后没人清，
     * 页面一直红着 —— 用户以为 App 坏了，其实数据早拉回来了。
     */
    fun clearError() {
        if (notice?.isError == true) notice = null
    }

    suspend fun pullStatus() {
        if (token == null) return
        when (val r = api.grabStatus(token)) {
            is ApiResult.Ok -> { status = r.value; clearError() }
            is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
        }
    }

    /**
     * 开启「手机直接提醒」。首次开启会立刻跑一轮 —— 让用户当场看到它在工作，
     * 而这一轮只记账不弹旧提醒（否则一开就被历史提醒轰一遍）。
     */
    fun enableWatch() {
        GrabWatch.setOn(ctx, true)
        watchOn = true
        notice = GrabLogic.Notice("已开启：这台手机会自己弹提醒")
        scope.launch {
            watchLast = withContext(Dispatchers.IO) { GrabWatch.runOnce(ctx, api) }
            pullStatus()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifOk = granted || NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (notifOk) enableWatch()
        else notice = GrabLogic.Notice("没给通知权限，提醒发不出来 —— 去系统设置里允许通知再开一次", 3)
    }
    LaunchedEffect(Unit) { notifOk = NotificationManagerCompat.from(ctx).areNotificationsEnabled() }

    suspend fun pullList(reset: Boolean) {
        if (token == null) {
            notice = GrabLogic.Notice("还没登录", 1)
            return
        }
        busy = true
        try {
            val off = if (reset) 0 else lessons.size
            when (val r = api.grabCatalog(token, q = q, flt = flt, limit = 60, offset = off)) {
                is ApiResult.Ok -> {
                    total = r.value.total
                    lessons = if (reset) r.value.lessons else lessons + r.value.lessons
                    if (reset) status = status?.copy(catalog = r.value.stats)
                    clearError()
                }
                is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
            }
        } finally {
            busy = false   // 取消/异常都不能把"读取中"卡死
        }
    }

    /**
     * 加入监控：只把课记下来盯着，**不代你抢**，也**不要求监控已经在跑**。
     *
     * 2026-09-30 的死锁：这个按钮原来要求"正在盯（开关开 且 目标非空）"才可点 —— 目标为空时
     * 那个条件恒为假 ⇒ **第一门课永远加不进去**，用户看到的只有一句「监控没在跑，加入监控不会
     * 生效」。加一门课只是"记下来"这件事本身，跟开关在不在跑无关：现在恒可点，加完在弹窗里
     * 说清"会不会开始提醒 + 要点哪里开"。
     */
    suspend fun addTarget(l: GrabLesson) {
        val tk = token ?: run { notice = GrabLogic.Notice("还没登录，先把登录做了", 3); return }
        when (val r = api.grabAddTarget(tk, l)) {
            is ApiResult.Ok -> {
                pullStatus()   // 先回读，才知道这会儿到底会不会提醒
                val head = GrabLogic.addedText(l.course, r.value.clash_text)
                val tail = if (status?.watching == true) ""
                else GrabLogic.notWatchingHint(status?.can_manage == true)
                notice = GrabLogic.Notice(
                    if (tail.isBlank()) head else "$head\n$tail",
                    if (r.value.clash_text.isNotBlank() || tail.isNotBlank()) 1 else 2,
                )
            }
            is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
        }
    }

    /**
     * 开/关**服务端**的监控总开关（走 `POST /api/v2/grab/config`）。
     *
     * 关监控**不会顺手删目标** —— 目标是用户一门门点出来的，暂停监控不该把清单清空。
     * 关掉之后服务端不再查余量、不再推送提醒（cfg 落到共用库，v1 的监控线程读的是同一份）。
     */
    suspend fun setMonitor(on: Boolean) {
        val tk = token ?: return
        val n = status?.targets?.size ?: 0
        when (val r = api.grabConfig(tk, monitorOn = on)) {
            is ApiResult.Ok -> {
                pullStatus()
                val real = status?.monitor_on ?: r.value.monitor_on
                val num = status?.targets?.size ?: n
                notice = when {
                    real && num == 0 -> GrabLogic.Notice(
                        "监控开了，但一个目标都没有 —— 去清单里点「盯」加两门，不然它没东西可盯", 1)
                    real -> GrabLogic.Notice("监控已开：服务端盯着 $num 门课，有余位就推送", 2)
                    on -> GrabLogic.Notice("服务端没接受开启（回读还是关）—— 点「刷新」再看一次", 1)
                    else -> GrabLogic.Notice(
                        "监控已关：不再查余量、不再推送提醒；$num 个目标给你留着", 2)
                }
            }
            is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
        }
    }

    /**
     * 打开网页版（重建清单 / 改教务凭据 / 通知渠道那些 App 还没有的事）。
     * runCatching：手机上没有浏览器或被限制时不该崩，静默失败即可。
     */
    fun openWeb() {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(GrabLogic.WEB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    suspend fun removeTarget(t: GrabTarget) {
        val tk = token ?: return
        when (val r = api.grabDelTarget(tk, t.id)) {
            is ApiResult.Ok -> {
                notice = GrabLogic.Notice("已取消监控：${t.course}", 2)
                pullStatus()
            }
            is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
        }
    }

    LaunchedEffect(token, flt) {
        pullStatus()
        pullList(reset = true)
    }

    Box(Modifier.fillMaxSize()) {
        // ── 整页只留**一个**滚动容器（用户口径 2026-09-30：「往下翻的时候上面的标签可以全部收起」）。
        // 说明行、状态行、搜索框、筛选标签原来钉在列表上方的固定头里，一直占着小半屏、往下翻也不走；
        // 现在它们只是列表最前面的三项，往下滚就跟着滚走，课程列表能占满整屏。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "h-head") {
                Column {
                    // ── 页顶说明：只盯余量、不替抢；把"它做到哪、做不到哪"说清楚。
                    // 收起时就是**一行字**（用户口径 2026-09-30：上面太占空间）—— 不用 Card 包，
                    // 省掉卡片自己的内边距；点右边「展开」看全文（跟「最近动作」同一套展开/收起写法）。
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            GrabLogic.DISCLAIMER_HEAD,
                            color = C.amber, fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (showDisclaimer) "收起" else "展开",
                            color = C.cyan, fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { showDisclaimer = !showDisclaimer }
                                .padding(4.dp),
                        )
                    }
                    if (showDisclaimer) {
                        Text(
                            GrabLogic.DISCLAIMER_BODY,
                            color = C.txt2, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // ── 状态行：我们的监控 / 教务登录，分开说
                    val dot = when (GrabLogic.statusLevel(status)) {
                        1 -> C.green
                        2 -> C.amber
                        3 -> C.red
                        else -> C.txt3
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(GrabLogic.statusLine(status), color = C.txt, fontSize = 13.sp)
                            // 底下原来两行（轮询间隔/目标数 + 清单统计）合成一行，中间用 · 隔开，
                            // 太宽自己折行（用户口径 2026-09-30：上面太占空间）
                            val tail = listOf(GrabLogic.metaLine(status), GrabLogic.statsLine(status))
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            if (tail.isNotBlank()) {
                                Text(tail, color = C.txt3, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                        Text(
                            if (busy) "…" else "刷新",
                            color = C.violet, fontSize = 12.sp,
                            modifier = Modifier
                                .clickable {
                                    scope.launch {
                                        pullStatus()
                                        pullList(reset = true)
                                        // 用户口径 2026-09-30：所有操作做完都要有反馈 ——
                                        // 刷新也要说一句"刷完了"，否则点一下没动静，像没反应。
                                        if (notice?.isError != true) {
                                            notice = GrabLogic.Notice("已刷新：状态和课程余量都是最新的", 2)
                                        }
                                    }
                                }
                                .padding(6.dp),
                        )
                    }

                    // ── 服务端报的错（登录过期 / 教务登录失败…）—— 原话保留，别吞掉
                    status?.last_error?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "服务端上次检查报错：$it",
                            color = C.red, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ── 搜索：526 门课，不给搜索框就只能靠翻。
            // 做成**紧凑的一行**（用户口径 2026-09-30：上面太占空间）—— 原来的
            // OutlinedTextField 自带 ~56dp 高，光它一个就吃掉一屏的 7%。
            item(key = "h-search") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(C.card, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔍", fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (q.isEmpty()) {
                            Text("搜课程 / 教师 / 班级", color = C.txt3, fontSize = 12.sp)
                        }
                        BasicTextField(
                            value = q,
                            onValueChange = { q = it },
                            singleLine = true,
                            textStyle = TextStyle(color = C.txt, fontSize = 13.sp),
                            cursorBrush = SolidColor(C.violet),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── 筛选档位
            item(key = "h-filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(GrabLogic.FILTERS) { f ->
                        val on = f.second == flt
                        Text(
                            text = f.first,
                            color = if (on) C.bg else C.txt2,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .background(if (on) C.violet else C.card, RoundedCornerShape(20.dp))
                                .clickable { flt = f.second }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
                val s = status
                val tg = s?.targets.orEmpty()
                val isWatching = s?.watching == true

                // ── 监控 / 手机直接提醒 总区（合成一张卡：信息多但不堆）
                item(key = "switch-card") {
                    Card {
                        Column {
                            // 服务端那个总开关是**作者自己那台引擎**的开关（它登录教务用的是作者
                            // 的账号，关掉会连累所有人的余量数据），所以只给能管的人看。
                            // 判据用服务端现算的 can_manage，不用本地缓存的 can_grab ——
                            // 用户口径 2026-09-30：「不需要重新登录」。
                            if (s?.can_manage == true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("监控（服务端开关）", color = C.txt2, fontSize = 11.sp)
                                        Text(
                                            GrabLogic.monitorSwitchText(s),
                                            color = when {
                                                // 进了这个分支 s 必非空（s?.can_manage == true 智能转换过），
                                                // 不能再写 s == null 那一支（编译器会告"恒为 false"）
                                                !s.configured -> C.txt3
                                                s.monitor_on -> C.green
                                                else -> C.amber
                                            },
                                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    if (s?.monitor_on == true) {
                                        Action("关闭监控", C.red) { sheet = Sheet.MONITOR_OFF }
                                    } else {
                                        Action("开启监控", C.violet) { sheet = Sheet.MONITOR_ON }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("手机直接提醒", color = C.txt2, fontSize = 11.sp)
                                    Text(
                                        GrabLogic.watchStateText(watchOn, notifOk, watchLast),
                                        color = when {
                                            !watchOn -> C.txt3
                                            !notifOk -> C.amber
                                            else -> C.green
                                        },
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    )
                                }
                                if (watchOn) {
                                    Action("关掉", C.red) {
                                        GrabWatch.setOn(ctx, false)
                                        watchOn = false
                                        watchLast = null
                                        notice = GrabLogic.Notice("已关掉手机直接提醒（服务端监控不受影响）")
                                    }
                                } else {
                                    Action("开启", C.violet) {
                                        if (Build.VERSION.SDK_INT >= 33 && !notifOk) {
                                            // 没通知权限的话 notify() 会被系统静默丢掉，
                                            // 用户只会觉得"提醒不灵" —— 所以先当场问他要
                                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            enableWatch()
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(GrabLogic.watchHint(s?.monitor_on == true), color = C.txt3, fontSize = 11.sp)
                        }
                    }
                }

                // ── 正在盯：这一页存在的理由
                item(key = "h-targets") { SectionTitle("正在盯（${tg.size}）") }
                if (tg.isEmpty()) {
                    item(key = "targets-empty") {
                        Text(
                            // 监控开关开着但目标为空，也是"没有在盯" —— 不许说成监控中
                            if (s != null && !s.watching)
                                "现在没有在盯任何课（服务端开关：${GrabLogic.monitorSwitchText(s)}）"
                            else "还没有监控目标 —— 在下面的清单里点「加入监控」",
                            color = C.txt3, fontSize = 11.sp,
                        )
                    }
                }
                items(tg, key = { "t${it.id}" }) { t ->
                    TargetCard(
                        t = t,
                        lesson = lessons.firstOrNull { it.lesson_id == t.lesson_id },
                        onRemove = { scope.launch { removeTarget(t) } },
                    )
                }

                // ── 最近动作：不信在跑，就什么都不信
                item(key = "h-logs") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("最近动作")
                        Spacer(Modifier.width(8.dp))
                        Text(GrabLogic.logsSummary(s?.logs.orEmpty()), color = C.txt3, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        val logs = s?.logs.orEmpty()
                        if (logs.size > LOGS_COLLAPSED) {
                            Text(
                                if (showAllLogs) "收起" else "展开全部 ${logs.size} 条",
                                color = C.cyan, fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable { showAllLogs = !showAllLogs }
                                    .padding(4.dp),
                            )
                        }
                    }
                }
                val logs = s?.logs.orEmpty()
                if (logs.isEmpty()) {
                    item(key = "logs-empty") { Text("还没有动作记录", color = C.txt3, fontSize = 11.sp) }
                }
                val shown = if (showAllLogs) logs else logs.take(LOGS_COLLAPSED)
                items(shown, key = { "g${it.id}" }) { g -> LogRow(g) }
                // ── 可选课程
                item(key = "h-catalog") {
                    Column {
                        SectionTitle("可选课程（$total）")
                        val fresh = GrabLogic.catalogFreshness(s)
                        if (fresh.isNotBlank()) Text(fresh, color = C.txt3, fontSize = 10.sp)
                        if (s != null && !isWatching) {
                            Text(
                                "现在没在盯任何课：加进监控也不会被轮询。" +
                                    "要真正开始盯，点上面「监控」右边的「开启监控」" +
                                    "（App 自己就能开，不用去网页版）。",
                                color = C.amber, fontSize = 11.sp, lineHeight = 16.sp,
                            )
                        }
                        // 重建清单 App 还做不到（服务端没这个口子）—— 给一个能真按下去的入口，
                        // 而不是写一句"请去网页版"让用户自己找
                        if (s != null && s.catalog.total == 0) {
                            Text(
                                "打开网页版重建清单 ›",
                                color = C.violet, fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable { openWeb() }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }

                if (lessons.isEmpty()) {
                    item(key = "empty") {
                        Text(GrabLogic.emptyHint(flt, q), color = C.txt3, fontSize = 12.sp)
                    }
                }

                items(lessons, key = { "l${it.lesson_id}" }) { l ->
                    // 已经在监控里的课，卡片上直接给「取消监控」—— 免得用户再点一次「加入监控」
                    // 却看不出有没有生效（用户口径 2026-09-30：不能让人不知道自己干了什么）。
                    val monitored = tg.firstOrNull { it.lesson_id == l.lesson_id }
                    LessonCard(
                        l = l,
                        monitored = monitored != null,
                        onAdd = { scope.launch { addTarget(l) } },
                        onRemove = { monitored?.let { t -> scope.launch { removeTarget(t) } } },
                    )
                }

                if (lessons.size < total) {
                    item(key = "more") {
                        Text(
                            "加载更多（已 ${lessons.size}/$total）",
                            color = C.cyan, fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { scope.launch { pullList(reset = false) } }
                                .padding(vertical = 10.dp),
                        )
                    }
                }

        }

        // ── 刚才那一下操作的结果：**弹窗**（用户口径 2026-09-30：「里面的所有操作做完都会有弹窗
        // 提示，否则用户都不知道自己干了什么」）。原来是页顶一行 11sp 小字，扫一眼就滑过去了。
        notice?.let { n -> NoticePopup(n) { notice = null } }

        sheet?.let { sh ->
            Overlay(
                sheet = sh,
                status = status,
                onDismiss = { sheet = null },
                onConfirmMonitorOn = {
                    scope.launch {
                        sheet = null
                        setMonitor(true)
                    }
                },
                onConfirmMonitorOff = {
                    scope.launch {
                        sheet = null
                        setMonitor(false)
                    }
                },
            )
        }
    }
}

/**
 * 收起时露几条动作记录。用户口径（2026-09-30）原话「最近动作也太占空间了，默认收起，
 * 收起时只展示一个」—— 所以是 1，不是"几条"。收起状态下它是**一行提示**，
 * 想看得多点右边「展开全部 N 条」。
 */
private const val LOGS_COLLAPSED = 1

/** 当前弹出的浮层。用普通 Box 画 —— Robolectric 里 Dialog 是独立窗口，测试看不见。 */
enum class Sheet { MONITOR_OFF, MONITOR_ON }

@Composable
private fun LogRow(g: GrabLog) {
    val lvl = GrabLogic.logLevel(g.level)
    val col = when (lvl) {
        3 -> C.red
        2 -> C.amber
        1 -> C.amber
        else -> C.txt3
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(GrabLogic.logTime(g.ts), color = C.txt3, fontSize = 10.sp, style = Digits)
        Spacer(Modifier.width(6.dp))
        Text(
            GrabLogic.logLabel(g.level),
            color = col, fontSize = 10.sp,
            modifier = Modifier
                .background(C.cardHi, RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(g.msg, color = if (lvl >= 2) C.txt else C.txt2, fontSize = 11.sp)
    }
}

@Composable
private fun TargetCard(
    t: GrabTarget,
    lesson: GrabLesson?,
    onRemove: () -> Unit,
) {
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.course.ifBlank { "教学班 ${t.lesson_id}" },
                    color = C.txt, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                val lvl = GrabLogic.targetStateLevel(t)
                Text(
                    GrabLogic.targetStateText(t),
                    color = when (lvl) {
                        2 -> C.green
                        1 -> C.amber
                        else -> C.cyan
                    },
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(C.cardHi, RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            val line = GrabLogic.targetLine(t)
            if (line.isNotBlank()) Text(line, color = C.txt3, fontSize = 11.sp)
            GrabLogic.targetSeat(t, listOfNotNull(lesson))?.let {
                Text("余量：$it", color = C.txt2, fontSize = 11.sp)
            }
            lesson?.let {
                val cls = GrabLogic.classText(it)
                if (cls.isNotBlank()) Text(cls, color = C.txt3, fontSize = 11.sp)
            }
            if (t.clash_text.isNotBlank()) {
                Text("⛔ 和课表冲突：${t.clash_text}", color = C.amber, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    "移除",
                    color = C.red, fontSize = 12.sp,
                    modifier = Modifier.clickable { onRemove() }.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun LessonCard(
    l: GrabLesson,
    monitored: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column {
            Text(
                l.course,
                color = C.txt, fontSize = 14.sp,
                fontWeight = if (l.grabbable) FontWeight.Medium else FontWeight.Normal,
            )
            Text(GrabLogic.lessonSub(l), color = C.txt3, fontSize = 11.sp)
            Row {
                Text(GrabLogic.seatsText(l),
                    color = if (l.grabbable) C.green else C.txt2, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(GrabLogic.seatsDetail(l), color = C.txt3, fontSize = 11.sp)
            }
            val slots = GrabLogic.timeSlots(l.place)
            slots.take(2).forEach { Text(it, color = C.txt2, fontSize = 11.sp) }
            if (slots.size > 2) Text("等 ${slots.size} 个时段", color = C.txt3, fontSize = 10.sp)
            val cls = GrabLogic.classText(l)
            if (cls.isNotBlank()) Text(cls, color = C.txt3, fontSize = 10.sp)
            GrabLogic.probeText(l)?.let { Text(it, color = C.amber, fontSize = 11.sp) }
            if (l.cn > 0) {
                val why = if (l.clash.isNotEmpty()) GrabLogic.clashText(l) else "撞课表 ${l.cn} 节"
                Text("⛔ 和课表冲突：$why", color = C.amber, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    l.mine != 0 ->
                        Text("已在我的课表里", color = C.txt3, fontSize = 11.sp)
                    monitored ->
                        Text("已在监控", color = C.green, fontSize = 12.sp)
                    else ->
                        // **恒为紫、恒可点**：加一门课只是"记下来盯着"，跟提醒开关在不在跑无关。
                        // 2026-09-30 的 bug 就出在这里 —— 原来它要求"正在盯"才可点，而目标为空时
                        // 那个条件恒为假 ⇒ 第一门课永远加不进去（用户：为什么无法加入监控）。
                        Text(
                            "加入监控",
                            color = C.violet, fontSize = 12.sp,
                            modifier = Modifier.clickable { onAdd() }.padding(6.dp),
                        )
                }
                if (monitored) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "取消监控",
                        color = C.red, fontSize = 12.sp,
                        modifier = Modifier.clickable { onRemove() }.padding(6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 操作结果**弹窗**（用户口径 2026-09-30：「里面的所有操作做完都会有弹窗提示，否则用户都不知道
 * 自己干了什么」）。原来是页顶一行 11sp 的小字，在长页面里扫一眼就滑过去了。
 *
 * 为什么用普通 Box 画，而不是 Dialog / Snackbar：Robolectric 里 Dialog 是独立窗口，测试看不见；
 * Snackbar 要 Scaffold。规矩跟下面的 Overlay 一样 —— 用 Box 画，测试和真机看到的是同一份。
 * 成功/提示 3 秒后自己消失（扫一眼够了）；**失败留着**，得让人看清错在哪，点它才关掉。
 */
@Composable
private fun NoticePopup(n: GrabLogic.Notice, onDismiss: () -> Unit) {
    val cc = when {
        n.isError -> C.red
        n.isOk -> C.green
        n.level == 1 -> C.amber
        else -> C.txt2
    }
    val title = when {
        n.isError -> "没成功"
        n.isOk -> "已完成"
        n.level == 1 -> "请注意"
        else -> "已完成"
    }
    LaunchedEffect(n) {
        if (!n.isError) {
            delay(3000)
            onDismiss()
        }
    }
    // 只占屏幕底部一条：外层 Box 没有点击修饰符，所以**点弹窗以外的地方照常点到列表**，
    // 不会像模态那样把页面挡住。
    Box(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp)
                .background(C.bgSoft, RoundedCornerShape(12.dp))
                .border(1.dp, cc, RoundedCornerShape(12.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(cc, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Text(title, color = cc, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("点一下关掉", color = C.txt3, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(n.text, color = C.txt, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun Overlay(
    sheet: Sheet,
    status: GrabStatus?,
    onDismiss: () -> Unit,
    onConfirmMonitorOn: () -> Unit,
    onConfirmMonitorOff: () -> Unit,
) {
    val tg = status?.targets.orEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60B0910))
            .clickable { onDismiss() }
            .padding(horizontal = 18.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(C.bgSoft, RoundedCornerShape(16.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Column {
                when (sheet) {
                    Sheet.MONITOR_OFF -> {
                        Text("关闭监控？", color = C.txt, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "关掉之后服务端不再查余量、不再推送提醒。",
                            color = C.txt, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "这 ${tg.size} 个监控目标**留着**（关监控不等于清空清单），" +
                                "随时可以再开。想清掉某一门，去卡片里点它的「删」。",
                            color = C.txt3, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogActions(
                            cancel = "取消" to onDismiss,
                            confirm = "确认关闭" to onConfirmMonitorOff,
                            confirmEnabled = true,
                            danger = true,
                        )
                    }

                    Sheet.MONITOR_ON -> {
                        Text("开启监控？", color = C.txt, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "服务端会一直按固定间隔查这些课的余量（默认 20 秒），有余位就推送到手机。" +
                                "它只盯余量，不代你抢。",
                            color = C.txt, fontSize = 13.sp,
                        )
                        if (tg.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("要盯的 ${tg.size} 门：", color = C.txt2, fontSize = 12.sp)
                            tg.forEach { Text("· ${it.course}", color = C.txt, fontSize = 12.sp) }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "现在一个目标都没有 —— 先开监控也行，回头去清单里点「盯」加课。",
                                color = C.amber, fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        DialogActions(
                            cancel = "取消" to onDismiss,
                            confirm = "确认开启" to onConfirmMonitorOn,
                            confirmEnabled = true,
                            danger = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogActions(
    cancel: Pair<String, () -> Unit>,
    confirm: Pair<String, () -> Unit>?,
    confirmEnabled: Boolean,
    danger: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            cancel.first,
            color = C.txt2, fontSize = 13.sp,
            modifier = Modifier
                .background(C.card, RoundedCornerShape(8.dp))
                .clickable { cancel.second() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        confirm?.let { c ->
            Text(
                c.first,
                color = if (confirmEnabled) C.bg else C.txt3,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(
                        if (!confirmEnabled) C.card else if (danger) C.red else C.violet,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = confirmEnabled) { c.second() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Action(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .background(C.cardHi, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = C.txt2,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** 卡片 = 极淡底 + 一根细边，不用阴影（这个项目的观感基调） */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(C.card, RoundedCornerShape(10.dp))
            .border(0.5.dp, C.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) { content() }
}
