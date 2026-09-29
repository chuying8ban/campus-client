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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
 * 抢课页（**测试功能**：底部栏那一格按服务端给的 can_grab 显隐，页顶有免责说明；
 * 服务端仍然会拿 can_grab 再拦一道）。
 *
 * 这一页要做三件事：**看见**（哪些课现在能拿）、**盯住**（加入监控）、
 * **随时能停**（移除目标 / 关掉自动提交）。
 *
 * 一条纪律：**页面上出现的每个状态都来自服务端那一次 GET /grab/status**。
 * 客户端可以请求（比如"顺带把这门课设成自动提交"），但要不要显示成"已开"，
 * 只认服务端回什么 —— 上一次这一页显示的是"监控状态无法解析"，
 * 那次事故的教训就是：客户端自己猜的状态，用户会当真。
 *
 * 关于自动抢课：服务端的 `/api/v2/grab/target` 目前**固定把 auto_submit 写成 0**
 * （服务端在冻结期，不改）。所以这一页的"开启"是：
 * **必须过一次确认层 → 提交请求 → 回读服务端 → 照实显示**。
 * 服务端回读没接受就明确写"没开成"，绝不放绿灯（显示只认回读）；
 * 而"停"是真的能停（同一个接口写 0 是生效的）。
 *
 * 冲突规则（用户定的）：只在**没有任何时间冲突**的前提下才允许自动抢某节课。
 * 有冲突的课可以加入监控（盯余量没问题），但自动抢开关**置灰不可选**，
 * 并且必须写清为什么。⚠️ 客户端这层过滤不是安全边界，真正的判定归服务端。
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
    var sheetTarget by remember { mutableStateOf<GrabTarget?>(null) }
    /**
     * 监控目标在**当前筛选档位下**未必出现在清单里（默认档位是「能抢且不冲突」），
     * 而确认层必须写清"哪些课、哪个教学班、余量多少" —— 所以确认层打开时
     * 按课程名单独查一次，把缺的教学班/余量补上（查不到就照实少显示，不编）。
     */
    var extraLessons by remember { mutableStateOf<Map<Int, GrabLesson>>(emptyMap()) }

    // ── 手机直接提醒（和飞书/QQ 推送无关：这台手机自己弹）
    var watchOn by remember { mutableStateOf(GrabWatch.isOn(ctx)) }
    var notifOk by remember { mutableStateOf(false) }
    var watchLast by remember { mutableStateOf(GrabWatch.lastResult(ctx)) }

    val scope = rememberCoroutineScope()
    val allLessons = lessons + extraLessons.values

    suspend fun enrichForSheet() {
        val tk = token ?: return
        val have = allLessons.map { it.lesson_id }.toSet()
        val add = mutableMapOf<Int, GrabLesson>()
        for (t in status?.targets.orEmpty()) {
            if (t.lesson_id in have) continue
            val r = api.grabCatalog(tk, q = t.course, flt = "all", limit = 20)
            if (r is ApiResult.Ok) {
                r.value.lessons.firstOrNull { it.lesson_id == t.lesson_id }?.let { add[it.lesson_id] = it }
            }
        }
        if (add.isNotEmpty()) extraLessons = extraLessons + add
    }

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

    /** 目标 → 加入监控要用的那一行（清单里没有就用目标本身的信息凑，字段都是服务端给的） */
    fun asLesson(t: GrabTarget): GrabLesson =
        lessons.firstOrNull { it.lesson_id == t.lesson_id }
            ?: GrabLesson(lesson_id = t.lesson_id, turn_id = t.turn_id,
                course = t.course, teacher = t.teacher, limit_cnt = t.limit_cnt)

    /** 加入监控；askedAuto=true 时会"顺带请求"自动提交，但显示只认服务端回的值 */
    suspend fun addTarget(l: GrabLesson, askedAuto: Boolean) {
        val tk = token ?: run { notice = GrabLogic.Notice("还没登录，先把登录做了", 1); return }
        when (val r = api.grabAddTarget(tk, l, autoSubmit = askedAuto)) {
            is ApiResult.Ok -> {
                notice = when {
                    askedAuto && r.value.auto_submit ->
                        GrabLogic.Notice("${l.course}：已加入监控，并已开启自动提交", 2)
                    askedAuto -> GrabLogic.addNotice(l.course, r.value.clash_text, false, true)
                    r.value.clash_text.isNotBlank() ->
                        GrabLogic.Notice(GrabLogic.addedText(l.course, r.value.clash_text), 1)
                    else -> GrabLogic.Notice(GrabLogic.addedText(l.course, ""), 2)
                }
                pullStatus()
            }
            is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
        }
    }

    /**
     * 打开/关闭某一门监控目标的自动提交。
     * 「关」是真的能关（服务端 ON CONFLICT 会把那一列写成 0）；「开」要回读确认。
     */
    suspend fun setTargetAuto(t: GrabTarget, on: Boolean) {
        val tk = token ?: return
        when (val r = api.grabAddTarget(tk, asLesson(t), autoSubmit = on)) {
            is ApiResult.Ok -> {
                pullStatus()
                val now = status?.targets?.firstOrNull { it.lesson_id == t.lesson_id }?.auto_submit
                    ?: r.value.auto_submit
                notice = when {
                    on && now && status?.autoEnabled != true -> GrabLogic.Notice(
                        "${t.course}：这门课开了，但「自动抢课」总开关还是关的 —— " +
                            "去上面把总开关也打开，才会真的提交", 1)
                    on && now -> GrabLogic.Notice("已开启自动抢课：${t.course}", 2)
                    on -> GrabLogic.Notice(
                        "${t.course}：服务端回读仍是关（没接受）—— 点「刷新」再看看，别当它开着了", 1)
                    now -> GrabLogic.Notice(
                        "${t.course}：请求已发出，但服务端仍显示自动提交是开着的 —— 以服务端为准", 1)
                    else -> GrabLogic.Notice("已关闭自动抢课：${t.course}", 2)
                }
            }
            is ApiResult.Err -> notice = GrabLogic.Notice(GrabLogic.errorText(r.code, r.message), 3)
        }
    }

    /**
     * 一键开/关自动提交。
     *
     * 服务端要求**全局开关和单门标记同时为真**才会提交，所以两处都要动：
     * 「开」= 先开全局，再把无冲突的课逐个标记；
     * 「停」= **先关全局**（一步就停住，不依赖后面那串请求），再把单门标记清掉。
     */
    suspend fun setAllAuto(on: Boolean) {
        val tk = token ?: return
        val all = status?.targets.orEmpty()
        if (all.isEmpty()) {
            notice = GrabLogic.Notice("一个监控目标都没有 —— 先去清单里加两门", 1)
            return
        }
        when (val g = api.grabConfig(tk, autoSubmit = on)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Err -> {
                notice = GrabLogic.Notice(GrabLogic.errorText(g.code, g.message), 3)
                return
            }
        }
        val tg = if (on) all.filter { GrabLogic.canAuto(it) } else all
        var accepted = 0
        for (t in tg) {
            when (val r = api.grabAddTarget(tk, asLesson(t), autoSubmit = on)) {
                is ApiResult.Ok -> if (r.value.auto_submit == on) accepted++
                is ApiResult.Err -> Unit
            }
        }
        pullStatus()
        val skipped = all.size - tg.size
        notice = when {
            on && accepted > 0 -> GrabLogic.Notice(
                "已开启自动提交：$accepted 门" +
                    if (skipped > 0) "（另有 $skipped 门和课表冲突，只提醒不动手）" else "", 2)
            on && tg.isEmpty() -> GrabLogic.Notice("没有可开的目标 —— 先加几门不冲突的课", 1)
            on -> GrabLogic.Notice(
                "服务端没接受开启（回读还是关）—— 点「刷新」再看一次，别当它开着了", 1)
            else -> GrabLogic.Notice("已停止全部自动提交：全局关掉了，$accepted 门的单课开关也清了", 2)
        }
    }

    /**
     * 开/关**服务端**的监控总开关（走 `POST /api/v2/grab/config`）。
     *
     * 关监控**不会顺手删目标** —— 目标是用户一门门点出来的，暂停监控不该把清单清空。
     * 关掉之后服务端不查余量、不推送、更不会提交（cfg 落到共用库，v1 的监控线程读的是同一份）。
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
                        "监控已关：不再查余量、不再推送、也不会提交；$num 个目标给你留着", 2)
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
        Column(Modifier.fillMaxSize()) {

            // ── 页顶免责：抢课是测试功能，先把"它做到哪、做不到哪、出事谁担"说清楚。
            // 默认只占一行，点「展开」看全文（和下面「最近动作」那个展开/收起同一套写法）；
            // 用这一页自己的 Card 装，横向留白跟其它块一致（22.dp）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 14.dp),
            ) {
                Card {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                GrabLogic.DISCLAIMER_HEAD,
                                color = C.amber, fontSize = 12.sp,
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
                                color = C.txt2, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            // ── 状态行：我们的监控 / 教务登录，分开说
            val dot = when (GrabLogic.statusLevel(status)) {
                1 -> C.green
                2 -> C.amber
                3 -> C.red
                else -> C.txt3
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(GrabLogic.statusLine(status), color = C.txt, fontSize = 13.sp)
                    val meta = GrabLogic.metaLine(status)
                    if (meta.isNotBlank()) Text(meta, color = C.txt3, fontSize = 11.sp)
                    val st = GrabLogic.statsLine(status)
                    if (st.isNotBlank()) Text(st, color = C.txt3, fontSize = 11.sp)
                }
                Text(
                    if (busy) "…" else "刷新",
                    color = C.violet, fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                pullStatus()
                                pullList(reset = true)
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
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }

            // ── 刚才那一下操作的结果（成功/失败都说清，别静默）
            notice?.let { n ->
                val nc = when {
                    n.isError -> C.red
                    n.isOk -> C.green
                    n.level == 1 -> C.amber
                    else -> C.txt2
                }
                Text(
                    n.text,
                    color = nc, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }

            // ── 搜索：526 门课，不给搜索框就只能靠翻
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text("搜课程 / 教师 / 班级", color = C.txt3, fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )

            // ── 筛选档位
            LazyRow(
                contentPadding = PaddingValues(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val s = status
                val tg = s?.targets.orEmpty()
                val isWatching = s?.watching == true

                // ── 自动提交的大字提示：不能只靠一个小开关的颜色
                if (s?.autoEnabled == true) {
                    item(key = "banner-auto") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(C.red.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                .border(1.dp, C.red.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Column {
                                Text("⚡ 自动提交中", color = C.red, fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold)
                                Text(
                                    "服务端已开启自动抢课：有余位会直接向教务系统提交选课。" +
                                        "不想让它替你做决定，就立刻停。",
                                    color = C.txt, fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "立刻停止全部自动提交",
                                    color = C.bg, fontSize = 12.sp,
                                    modifier = Modifier
                                        .background(C.red, RoundedCornerShape(8.dp))
                                        .clickable { sheet = Sheet.AUTO_OFF }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }

                // ── 监控 / 自动抢课 总区（合成一张卡：信息多但不堆）
                item(key = "switch-card") {
                    Card {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("监控（服务端开关）", color = C.txt2, fontSize = 11.sp)
                                    Text(
                                        GrabLogic.monitorSwitchText(s),
                                        color = when {
                                            s == null -> C.txt3
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
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("自动抢课", color = C.txt2, fontSize = 11.sp)
                                    Text(
                                        if (s?.autoEnabled == true) "服务端已开" else "关",
                                        color = if (s?.autoEnabled == true) C.red else C.txt3,
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    )
                                }
                                if (s?.autoEnabled == true) {
                                    Action("停止", C.red) { sheet = Sheet.AUTO_OFF }
                                } else {
                                    Action("开启…", C.violet) {
                                        sheet = Sheet.AUTO_ON
                                        scope.launch { enrichForSheet() }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(GrabLogic.AUTO_RULE, color = C.amber, fontSize = 11.sp)

                            Spacer(Modifier.height(12.dp))
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
                        onAuto = {
                            // 开：必须过确认层；关：直接关（停要顺手）
                            if (t.auto_submit) scope.launch { setTargetAuto(t, false) }
                            else {
                                sheetTarget = t
                                sheet = Sheet.AUTO_ONE
                                scope.launch { enrichForSheet() }
                            }
                        },
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
                GrabLogic.autoSubmitHeadline(logs).takeIf { it.isNotBlank() }?.let {
                    item(key = "logs-auto-note") { Text(it, color = C.amber, fontSize = 11.sp) }
                }

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
                    LessonCard(
                        l = l,
                        watchOn = isWatching,
                        onAdd = { scope.launch { addTarget(l, askedAuto = false) } },
                        onAddBlocked = {
                            notice = GrabLogic.Notice(
                                "监控没在跑，加入监控不会生效 —— 先点上面的「开启监控」", 1)
                        },
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

                item(key = "note-auto") {
                    Column {
                        Text(GrabLogic.AUTO_SUBMIT_NOTE, color = C.txt3, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp))
                        Text(
                            "「冲突就不许抢」这条目前只在 App 里过滤；服务端还没有做提交前重判，" +
                                "所以用第三方工具直接调接口仍可能提交上冲突的课。",
                            color = C.txt3, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        sheet?.let { sh ->
            Overlay(
                sheet = sh,
                target = sheetTarget,
                status = status,
                lessons = allLessons,
                onDismiss = { sheet = null; sheetTarget = null },
                onConfirmOne = { t ->
                    scope.launch {
                        sheet = null
                        sheetTarget = null
                        setTargetAuto(t, true)
                    }
                },
                onConfirmAuto = { on ->
                    scope.launch {
                        sheet = null
                        setAllAuto(on)
                    }
                },
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

/** 日志默认只露这么多条 —— 一屏塞 20 条就不是"看见"，是噪音 */
private const val LOGS_COLLAPSED = 5

/** 当前弹出的浮层。用普通 Box 画 —— Robolectric 里 Dialog 是独立窗口，测试看不见。 */
enum class Sheet { AUTO_ON, AUTO_ONE, AUTO_OFF, MONITOR_OFF, MONITOR_ON }

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
    onAuto: () -> Unit,
    onRemove: () -> Unit,
) {
    val allowAuto = GrabLogic.canAuto(t)
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
                Text(
                    if (t.auto_submit) "自动抢：开" else "自动抢：关",
                    color = if (t.auto_submit) C.red else if (allowAuto) C.txt2 else C.txt3,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(C.cardHi, RoundedCornerShape(20.dp))
                        // 有冲突的课：开关置灰不可选（用户定的规则）
                        .clickable(enabled = allowAuto) { onAuto() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (!allowAuto) {
                    Text(
                        "不可自动抢 —— ${GrabLogic.autoBlockedReason(t)}",
                        color = C.amber, fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
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
    watchOn: Boolean,
    onAdd: () -> Unit,
    onAddBlocked: () -> Unit,
) {
    val blocked = GrabLogic.autoBlockedReason(l)
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
                if (l.mine == 0) {
                    Text(
                        "加入监控",
                        color = if (watchOn) C.violet else C.txt3, fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { if (watchOn) onAdd() else onAddBlocked() }
                            .padding(6.dp),
                    )
                } else {
                    Text("已在我的课表里", color = C.txt3, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                // 自动抢课：有冲突的课**置灰不可选**（用户定的规则：冲突就不许抢）
                Text(
                    if (blocked == null) "自动抢 ✓" else "自动抢 ✕",
                    color = if (blocked == null) C.green else C.txt3,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(C.cardHi, RoundedCornerShape(20.dp))
                        .clickable(enabled = false) {}
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            blocked?.let { Text("不可自动抢 —— $it", color = C.amber, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun Overlay(
    sheet: Sheet,
    target: GrabTarget?,
    status: GrabStatus?,
    lessons: List<GrabLesson>,
    onDismiss: () -> Unit,
    onConfirmOne: (GrabTarget) -> Unit,
    onConfirmAuto: (Boolean) -> Unit,
    onConfirmMonitorOn: () -> Unit,
    onConfirmMonitorOff: () -> Unit,
) {
    val tg = status?.targets.orEmpty()
    val allow = GrabLogic.autoConfirmLines(tg, lessons)
    val excluded = GrabLogic.autoExcludedLines(tg)
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
                    Sheet.AUTO_ON -> {
                        Text("开启自动抢课？", color = C.red, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(GrabLogic.AUTO_CONFIRM_BODY, color = C.txt, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(GrabLogic.AUTO_RULE, color = C.amber, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(GrabLogic.autoConfirmTitle(allow), color = C.txt2, fontSize = 12.sp)
                        allow.forEach { Text("· $it", color = C.txt, fontSize = 12.sp) }
                        if (excluded.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("不会自动抢的（有冲突）：", color = C.amber, fontSize = 12.sp)
                            excluded.forEach { Text("· $it", color = C.txt2, fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "提交后以服务端回读为准：没开成这里会照实说，不会假装开着。",
                            color = C.txt3, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogActions(
                            cancel = "取消" to onDismiss,
                            confirm = "确认开启" to { onConfirmAuto(true) },
                            confirmEnabled = allow.isNotEmpty(),
                            danger = false,
                        )
                    }

                    Sheet.AUTO_ONE -> {
                        val name = target?.course?.ifBlank { "教学班 ${target.lesson_id}" } ?: "这门课"
                        Text("开启自动抢课？", color = C.red, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(GrabLogic.AUTO_CONFIRM_BODY, color = C.txt, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(GrabLogic.AUTO_RULE, color = C.amber, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("会立即变成自动提交的 1 门：", color = C.txt2, fontSize = 12.sp)
                        Text("· " + (GrabLogic.autoConfirmLines(listOfNotNull(target), lessons)
                            .firstOrNull() ?: name), color = C.txt, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "提交后以服务端回读为准：没开成这里会照实说，不会假装开着。",
                            color = C.txt3, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogActions(
                            cancel = "取消" to onDismiss,
                            confirm = "确认开启" to { target?.let(onConfirmOne) },
                            confirmEnabled = target != null,
                            danger = false,
                        )
                    }

                    Sheet.AUTO_OFF -> {
                        Text("停止自动抢课？", color = C.txt, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "会把 ${tg.size} 门监控目标的自动提交关掉。" +
                                "关掉之后服务端只会提醒你，不会替你提交。",
                            color = C.txt, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogActions(
                            cancel = "取消" to onDismiss,
                            confirm = "确认停止" to { onConfirmAuto(false) },
                            confirmEnabled = true,
                            danger = true,
                        )
                    }

                    Sheet.MONITOR_OFF -> {
                        Text("关闭监控？", color = C.txt, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "关掉之后服务端不再查余量、不再推送、也不会替你提交任何课。",
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
                                "它只是看，不会替你提交。",
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
