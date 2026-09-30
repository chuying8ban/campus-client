package top.ccbase.campus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import top.ccbase.campus.ui.theme.AppearanceStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.data.plan.TplLoader
import top.ccbase.campus.alarm.Rescheduler
import top.ccbase.campus.data.plan.Templates
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.ui.onboard.BootScreen
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.ui.onboard.OnboardingFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.rememberCoroutineScope
import top.ccbase.campus.net.Api
import top.ccbase.campus.net.Net
import top.ccbase.campus.update.CheckOutcome
import top.ccbase.campus.update.UpdateLogic
import top.ccbase.campus.update.UpdateNotify
import top.ccbase.campus.update.UpdateManifest
import top.ccbase.campus.update.UpdatePrefs
import top.ccbase.campus.update.Updater
import top.ccbase.campus.ui.update.UpdateOverlay
import top.ccbase.campus.ui.update.UpdateUi
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.me.MeScreen
import top.ccbase.campus.ui.me.DiagOverlay
import top.ccbase.campus.ui.board.BoardScreen
import top.ccbase.campus.ui.schedule.ScheduleScreen
import top.ccbase.campus.ui.tasks.PlanEntry
import top.ccbase.campus.ui.tasks.TaskDeleteConfirm
import top.ccbase.campus.ui.tasks.TaskDetailScreen
import top.ccbase.campus.ui.tasks.rememberTaskDelete
import top.ccbase.campus.ui.tasks.rememberTasksSections
import top.ccbase.campus.ui.tasks.ResetConfirm
import top.ccbase.campus.ui.tasks.rememberReset
import top.ccbase.campus.ui.tasks.MoreSheet
import top.ccbase.campus.ui.tasks.tasksContent
import top.ccbase.campus.ui.today.TodayScreen
import top.ccbase.campus.ui.theme.Digits
import top.ccbase.campus.net.StudentError

/**
 * P0.2 —— 五个 tab 的空壳，只验证「导航 + 主题 + 编译链」三件事。
 *
 * tab 顺序与网页版一致（今日 / 课表 / 监控 / 学习 / 我的，看板收进「我的」），
 * 这样你从网页版切过来时肌肉记忆不用改。
 */
enum class CampusTab(val label: String, val icon: ImageVector, val soon: String) {
    TODAY("今日", Icons.Filled.Home, "今天的课 + 早晚自习 + 今日任务 + 课前提醒开关"),
    SCHEDULE("课表", Icons.Filled.DateRange, "周视图 + 当天课程 + 本学期 11 门课"),
    // 枚举 id 保留 GRAB（历史名字）：换 id 会牵动一批接线测试。这一格现在只做监控，不代抢。
    GRAB("监控", Icons.Filled.Star, "盯可选课程余量；只提醒，不代抢"),
    // 2026-09-18 用户要求：「把任务标签改为学习」；2026-09-19 又要求把它里面那半
    // （按课程摊开的资料列表）删掉 —— 那半和「学习库」重复。所以这一格现在**只有任务**，
    // 「AI 规划学习计划」的入口仍钉在这一页顶部；资源一律去「学习库」看。
    TASKS("学习", Icons.Filled.CheckCircle, "阶段切换 + 任务卡 + 学习步骤与进度"),
    // 2026-09-19 用户要求「独立学习库」：不塞进「学习」页里，而是**自己的**一格，
    // 并且是资源的**唯一**入口（点一门课进去看课程信息 + 资料）。
    // 它是公共资源目录（所有同学探过的活链接，按课程 / 按类型摊开），挑中的会落成
    // 「学习」页里的一条任务 —— 所以图标用"目录"那种意思的 List，跟「学习」的勾区分开。
    LIBRARY("学习库", Icons.Filled.List, "公共资源目录：按课程 / 按类型挑链接，加到自己的清单"),
    BOARD("看板", Icons.Filled.List, "4 张统计卡 + 近 14 天图 + 里程碑 + 自检"),
    ME("我的", Icons.Filled.Person, "账户 / 密码管理 / 关于"),
}

// 每个 tab 的专属强调色，页面上给一点色彩区分（跟网页版 per-tab 强调色同思路）
private val CampusTab.accent: androidx.compose.ui.graphics.Color
    get() = when (this) {
        CampusTab.TODAY -> C.violet
        CampusTab.SCHEDULE -> C.cyan
        CampusTab.GRAB -> C.amber
        CampusTab.TASKS -> C.green
        CampusTab.LIBRARY -> C.cyan
        CampusTab.BOARD -> C.magenta
        CampusTab.ME -> C.violet
    }

/**
 * 真正的入口：先决定给用户看引导还是看主界面。
 *
 * 判断依据落在一个数据库标志位上（不是"有没有令牌"）——
 * 因为「先看看演示内容」这条跳过路径同样算走完引导，
 * 用令牌判断会让跳过的人每次启动都被拦回登录页。
 */
@Composable
fun CampusApp(version: String) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CampusApplication
    var ready by remember { mutableStateOf<Boolean?>(null) }
    // 有没有未过期的会话令牌 —— 未登录只给登录页，不允许预览任何内容
    var loggedIn by remember { mutableStateOf(false) }
    // 从引导/登录返回时 +1，触发重新判断（刚登录成功要能进得去）
    var gateTick by remember { mutableStateOf(0) }

    LaunchedEffect(gateTick) {
        // 等种子导完再决定 —— 否则引导第 2 屏会读到空课表。
        // 库操作一律在 IO 上做：主线程读 Room 会抛异常，界面会永远停在"正在准备…"
        // 两个一起算完再赋值：否则会"先闪一下引导页再进主界面"
        val pair = withContext(Dispatchers.IO) {
            runCatching { app.seedJob.join() }
            val onboarded = runCatching { PlanApplier.onboarded(app.db) }.getOrDefault(false)
            val token = runCatching { TokenStore.token(ctx) != null }.getOrDefault(false)
            onboarded to token
        }
        loggedIn = pair.second
        ready = pair.first
        /**
         * 重排提醒闹钟（进 App、以及登录/引导返回后各一次）。
         *
         * 为什么不能只放在「我的 → 课前提醒」那一块：提醒现在是**默认开着**的 ——
         * 别人装上 App 后可能一直不进那一页，闹钟就一次也没排上，功能等于不存在。
         * 这里（gate）是唯一"必然经过"的时机，而且登录后课表才在库里，
         * 卡在这里才能保证"课表一到就能排上"。
         */
        Rescheduler.request(app, reason = "gate")
    }

    var forceOnboard by remember { mutableStateOf(false) }

    when {
        ready == null -> BootScreen()
        // 未登录 = 只能看到登录/引导，**不允许预览任何内容**。
        // 这条覆盖了原先"跳过也能进主界面"的设计：跳过只用在内层引导步骤
        // （不想连教务、不想挑模块），但不能绕过登录本身。
        !loggedIn || forceOnboard -> OnboardingFlow(
            ctx = ctx,
            db = app.db,
            tpl = remember { runCatching { TplLoader.load(ctx) }
                .getOrElse { Templates() } },
            onFinish = { ready = true; forceOnboard = false; gateTick++ },
        )
        else -> CampusShell(version, onLogin = { forceOnboard = true })
    }
}

@Composable
fun CampusShell(version: String, onLogin: () -> Unit) {
    // rememberSaveable：转屏/系统杀进程重建后还停在原来那个 tab
    var idx by rememberSaveable { mutableIntStateOf(0) }

    val appCtx = LocalContext.current
    // 监控那一格按服务端给的 can_grab 显隐（就在下面构造底部栏那里读）；
    // 后台那类"只进作者包"的东西另有一层编译期闸（BuildConfig.AUTHOR_BUILD）。

    // ------------------------------------------------------------------
    // 应用内更新（旁加载的 App 没有应用商店，得自己查版本、自己下、自己拉安装器）
    // 启动时最多 6 小时查一次；「我的 → 检查更新」随时手动查。
    // ------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val versionCode = top.ccbase.campus.BuildConfig.VERSION_CODE
    var update by remember { mutableStateOf<UpdateUi?>(null) }
    var updHint by remember { mutableStateOf<String?>(null) }
    // 网络自检浮层：连不上服务器时用户自己跑一次，把「断在哪一层」摆出来
    var showDiag by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    // 「手机自己抓课表」浮层：教务密码不出手机的抓取路径
    var showCrawl by remember { mutableStateOf(false) }
    // 「AI 规划学习计划」浮层：先看建议再勾选入库。
    // 入口在「学习」标签页（任务页顶部那个明处的卡片）—— 用户要求从「我的」搬过来
    var showPlan by remember { mutableStateOf(false) }
    // 授权/白名单浮层：这些开关缺了对应的功能会静默失效，所以要有地方能直接去要
    var showPerms by remember { mutableStateOf(false) }
    // 后台管理：App 内嵌打开（不丢浏览器 —— 丢浏览器会弹系统框、用户点拒绝还静默无反应）
    var showAdmin by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    // 全 App 共用一个传输层：它自己会在"直连被链路掐掉"时切到不带 SNI 的握手，
    // 而且这个选择要**跨请求记住**（每次新建一个就等于每请求重探一遍）
    val dial = Net.dial

    fun startDownload(m: UpdateManifest, forced: Boolean = false) {
        // 更新前先要权限：系统那层「安装未知应用」没开就先带用户去开，**一个字节都不下**
        if (UpdateLogic.gateBeforeDownload(Updater.canInstall(appCtx)) ==
            UpdateLogic.UpdateGate.AskInstallPermission
        ) {
            update = UpdateUi.AskPermission(m, forced)
            return
        }
        update = UpdateUi.Progress(m, -1f)
        var lastPct = -2
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                Updater.download(appCtx, m, onProgress = { p ->
                    // 每 64KB 回调一次，只在整百分点变化时刷界面，别把主线程刷爆
                    val pct = if (p < 0f) -1 else (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        scope.launch { update = UpdateUi.Progress(m, p) }
                    }
                }, onState = { st ->
                    // 断流重试 / 停滞断开这类"正在发生的事"要立刻反映到界面上：
                    // 退避等待时不刷新的话，用户看到的就是一条不动的进度条（等于静默）
                    scope.launch { update = UpdateUi.Progress(m, st.fraction, st) }
                })
            }
            update = r.fold(
                onSuccess = { f -> UpdateUi.Ready(m, f, needPermission = !Updater.canInstall(appCtx)) },
                onFailure = { e ->
                    // 失败文案由下载引擎写好（含"还剩多少已经下好了、点重试接着下"），
                    // 这里只兜底，绝不出现空白提示
                    UpdateUi.Failed(m, e.message?.takeIf { it.isNotBlank() } ?: "下载没成功，请重试")
                },
            )
        }
    }

    // 「去允许」跳去系统设置期间，这个浮层还在；许可一到位就自己把下载续上，
    // 不让用户回来再点一遍（轮询只在这一态有效，离开这一态就结束）。
    LaunchedEffect(update) {
        val st = update as? UpdateUi.AskPermission ?: return@LaunchedEffect
        while (isActive && update is UpdateUi.AskPermission) {
            delay(700)
            if (Updater.canInstall(appCtx)) startDownload(st.m, st.forced)
        }
    }

    /**
     * @param entry true = 进入 App 时自动查的那次：只隔 1 分钟就允许再查，
     *   保证每次进来都知道有没有新版本（见 UpdateLogic.shouldCheckOnEntry）。
     */
    fun checkUpdate(force: Boolean, entry: Boolean = false) {
        if (update is UpdateUi.Progress) return                       // 下载中别打断
        val last = UpdatePrefs.lastCheck(appCtx)
        val nowMs = System.currentTimeMillis()
        val allowed = force || if (entry) UpdateLogic.shouldCheckOnEntry(last, nowMs)
                               else UpdateLogic.shouldCheckNow(last, nowMs, false)
        if (!allowed) return
        if (force) updHint = "正在检查更新…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                Updater.check(Api.BASE, dial, versionCode, UpdatePrefs.skipped(appCtx), manual = true)
            }
            UpdatePrefs.markChecked(appCtx)
            when (outcome) {
                is CheckOutcome.Update -> {
                    // 记住这个新版本，让「我的」页那行提示长期显示（版本追平后自动不显示）
                    UpdatePrefs.rememberAvailable(appCtx, outcome.manifest.versionCode, outcome.manifest.versionName)
                    updHint = null
                    // 通知栏提醒：进 App 时用户可能在浮层弹出来之前就切走了，加一条系统通知兜住。
                    // 同一个版本只提醒一次；点过「以后再说」的版本不再提醒。
                    val m = outcome.manifest
                    val notifyOk = UpdateLogic.shouldNotify(
                        m, versionCode, UpdatePrefs.skipped(appCtx), UpdatePrefs.notified(appCtx),
                    )
                    if (notifyOk) {
                        val posted = withContext(Dispatchers.IO) { UpdateNotify.post(appCtx, m, version) }
                        // 只有真发出去了才记「已提醒」：通知权限没开时不该白白吞掉这次提醒
                        if (posted) UpdatePrefs.markNotified(appCtx, m.versionCode)
                    }
                    update = UpdateUi.Offer(m, outcome.forced, outcome.why)
                }
                // 静默的自动检查不打扰；手动点的必须给回话，否则用户以为按钮坏了
                CheckOutcome.Latest -> updHint = if (force) "已经是最新版本（$version）" else null
                is CheckOutcome.Failed -> updHint = if (force) "检查更新失败：${outcome.message}" else null
            }
            // **成败都留痕**（「我的」页照实显示）：自动检查失败以前是零留痕，
            // 而"服务端清单 404 / 作者通道的随机段被轮换"这类失效的表现恰好就是"已是最新"——
            // 没有这一行，用户和我们都无从发现，只能等下一次手动点。轮换段那种操作也才有验收依据。
            UpdatePrefs.markResult(appCtx, updateTraceText(outcome))
        }
    }
    // 进入 App 就查一次（1 分钟下限）：用户要求进来就知道有没有新版本
    LaunchedEffect(Unit) { checkUpdate(force = false, entry = true) }

    // ------------------------------------------------------------------
    // 课表自检：**打开 App / 回到前台时自己核对一次**
    //
    //   用户原话：「我希望后续 app 能自己更新课表，不要用户自己发现错误」。
    //   做法：一次 GET /plan，比对服务端给的课表版本号与本机记的号；
    //   号变了就**就地**落地新计划（不额外请求），并把号记下。
    //   服务端还没这个字段（老服务端）→ 判据安静退回，一个字节都不动。
    //
    //   成败都写进 meta（`K_CHECK_RES` / `K_CHECK_AT`），「我的」页照实显示 ——
    //   自动的事也要留痕，否则用户只会看到"课表又不对了"而不知道为什么。
    // ------------------------------------------------------------------
    var lastTtCheck by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun checkTimetable() {
        val now = System.currentTimeMillis()
        // 前后台来回切时别把服务器打爆（每次核对 = 一次 /plan）
        if (now - lastTtCheck < 60_000) return
        lastTtCheck = now
        scope.launch {
            val appNow = appCtx.applicationContext as CampusApplication
            val t = withContext(Dispatchers.IO) { TokenStore.token(appCtx) } ?: return@launch
            try {
                when (val r = withTimeout(60_000) {
                    RemoteSync.checkAndRefresh(appCtx, appNow.db, CampusApi(), t)
                }) {
                    is ApiResult.Ok -> Unit            // 结果已由 checkAndRefresh 记进 meta
                    is ApiResult.Err -> withContext(Dispatchers.IO) {
                        // 自动核对失败**不许静默**：记下来，「我的」页会显示
                        PlanApplier.recordCheck(appNow.db, "自动核对失败：${r.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CampusApp", "课表自检失败", e)
                withContext(Dispatchers.IO) {
                    runCatching { PlanApplier.recordCheck(appNow.db, "自动核对失败：${StudentError.tech(e)}") }
                }
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            // ON_RESUME 在首次进入时也会走一次 —— "打开 App"与"回到前台"共用这一条路
            if (e == Lifecycle.Event.ON_RESUME) {
                checkTimetable()
                // 版本检查也在这一条路上补一次：原来只有 `LaunchedEffect(Unit)`，
                // 而它挂在 root 组合上、**进程常驻时就只在冷启动跑过一次** ——
                // 一直在后台没被杀的用户可能好几天看不到"有新版本"。
                // 这里走 6 小时下限（entry=false ⇒ shouldCheckNow），冷启动那条 1 分钟下限不变。
                checkUpdate(force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 底部放高频入口。看板（打卡热力/里程碑）不再占底部栏，入口收在「我的」页里
    // （CampusTab.BOARD 枚举保留：它还是「我的」里打开的全屏子页）。
    // 「学习」已并进「任务」页（同一页内两段，页内不再有子切换）——
    // 底部不再单独占一格（用户要求精简）。学习内容本身没删：在合并页里往下滚就是它。
    // 「我的」里点进来的全屏子页。
    // 它必须放在底部导航**外面**：点底部任何一格都得能离开子页，
    // 放在里面（Scaffold content 里）就会变成"点了底部 tab 没反应，像卡住"。
    var extra by remember { mutableStateOf<CampusTab?>(null) }

    // 系统返回键 · 子页：退回上一页，而不是把整个 App 关掉
    androidx.activity.compose.BackHandler(enabled = extra != null) { extra = null }

    // 系统返回键 · 主界面：**先提示，再按一次才退出**。
    // 手机一点返回就掉回桌面太容易误触（尤其单手刷课表时）。
    val hostActivity = LocalContext.current as? android.app.Activity
    var exitHint by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler(enabled = extra == null) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            hostActivity?.finish()
        } else {
            lastBackAt = now
            exitHint = true
            scope.launch { kotlinx.coroutines.delay(2000); exitHint = false }
        }
    }

    // 底部 tab 栏：看板收进「我的」页，不再常显；监控那一格**人人都有**。
    //
    // ⚠️ 2026-09-30 用户口径：「新版本每个用户的手机上都会显示监控，而不需要重新登录」——
    // 所以这里**不再拿 TokenStore.canGrab 当判据**：那是登录那一刻写进本地的值，老用户装上
    // 这个版本也不会重新登录，于是永远看不到那一格。服务端同时把监控那几个接口从 can_grab
    // 放开成"登录即可"（`grab/config` 那个总开关仍只给作者），界面和服务端的判据这才对得上。
    val tabs = CampusTab.entries.filter { it != CampusTab.BOARD }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        // 有背景图（预设/自选）时让出底色：这层不透明的 C.bg 会把 Theme 里的背景层整个盖住
        containerColor = if (AppearanceStore.background.value == AppearanceStore.BG_SOLID) C.bg
                         else Color.Transparent,
        bottomBar = {
            NavigationBar(
                containerColor = C.bgSoft,
                tonalElevation = 0.dp,
            ) {
                // 没有 can_grab 的人这里就没有监控那一格（不是灰着，是压根不存在）
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = idx == i,
                        onClick = { idx = i; extra = null },
                        icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(21.dp)) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.accent,
                            selectedTextColor = t.accent,
                            unselectedIconColor = C.txt3,
                            unselectedTextColor = C.txt3,
                            indicatorColor = C.cardHi,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val app = LocalContext.current.applicationContext as CampusApplication
            // tabs 的长度会变（监控那一格随 can_grab 进出），而 idx 是记下来的旧下标
            // （rememberSaveable：转屏/进程重建后还留着）—— 越界就是崩，所以先夹回合法范围。
            val shown = extra ?: tabs[idx.coerceIn(0, tabs.lastIndex)]
            when (shown) {
                CampusTab.TODAY -> TodayScreen(app.db)
                CampusTab.SCHEDULE -> ScheduleScreen(app.db)
                CampusTab.TASKS -> TasksLearnScreen(app.db, onOpenPlan = { showPlan = true })
                CampusTab.LIBRARY -> top.ccbase.campus.ui.library.LibraryScreen(app.db)
                CampusTab.GRAB -> top.ccbase.campus.ui.grab.GrabScreen(appCtx)
                CampusTab.BOARD -> BoardScreen(app.db)
                CampusTab.ME -> MeScreen(
                    onOpenBoard = { extra = CampusTab.BOARD },
                    // 监控**不再从「我的」进**（用户口径 2026-09-30：「把我的里面的监控删了」）——
                    // 入口只剩底部菜单栏那一格，同一件事不留两个入口。
                    ctx = appCtx,
                    db = app.db,
                    api = remember { CampusApi() },
                    version = version,
                    onLogin = onLogin,
                    // 临时的（正在检查 / 失败 / 已是最新）优先，否则长期显示「有新版本可用」
                    updateHint = updHint ?: UpdateLogic.availableHint(
                        versionCode, version,
                        UpdatePrefs.availableCode(appCtx), UpdatePrefs.availableName(appCtx),
                    ),
                    onCheckUpdate = { checkUpdate(force = true) },
                    onOpenDiag = { showDiag = true },
                    onOpenSafety = { showSafety = true },
                    onOpenAppearance = { showAppearance = true },
                    onOpenCrawl = { showCrawl = true },
                    onOpenPermissions = { showPerms = true },
                    onOpenAdmin = { showAdmin = true },
                    onOpenFeedback = { showFeedback = true },
                )
                else -> ShellScreen(shown, version)
            }
            // 子页的返回条放在页面**之后**画：同一个 Box 里后画的在上层。
            // 以前它在页面之前画 → 点击被页面内容吃掉 → 用户说"点进去不能退出"。
            if (extra != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 10.dp)
                        .background(C.bg.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                        .clickable { extra = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("‹ 返回", color = C.txt2, fontSize = 13.sp)
                }
            }
            // 退出提示条：主界面按一次返回时浮出来，两秒后自己收回去
            if (exitHint) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 92.dp)
                        .background(C.cardHi, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("再按一次「返回」就退出", color = C.txt2, fontSize = 12.sp)
                }
            }
        }
    }

    // 更新浮层盖在**最外层**：底部导航也一起盖住 —— 强制更新时不能还让人点走
    if (showDiag) {
        DiagOverlay(
            ctx = appCtx,
            base = Api.BASE,
            version = version,
            transport = dial,
            modeLabel = dial.modeName(Api.BASE),
            token = TokenStore.token(appCtx),
            onClose = { showDiag = false },
        )
    }
    if (showSafety) {
        top.ccbase.campus.ui.me.SafetyScreen(onClose = { showSafety = false })
    }
    if (showAppearance) {
        top.ccbase.campus.ui.me.AppearanceScreen(onClose = { showAppearance = false })
    }
    if (showCrawl) {
        // 这里拿不到下面 when 分支里的局部 app，用 LocalContext 取（和 162/328 行同一个写法）
        val appNow = LocalContext.current.applicationContext as CampusApplication
        top.ccbase.campus.ui.me.CrawlScreen(
            db = appNow.db,
            api = remember { CampusApi() },
            onClose = { showCrawl = false },
            // 登录过期时，抓课表页上的「重新登录」直接落到登录页（不用用户自己回「我的」找）
            onRelogin = { showCrawl = false; onLogin() },
        )
    }
    if (showPlan) {
        // 和上面 CrawlScreen 同一套写法：浮层拿不到 when 分支里的局部 app，用 LocalContext 取
        val appNow = LocalContext.current.applicationContext as CampusApplication
        top.ccbase.campus.ui.tasks.PlanScreen(
            db = appNow.db,
            api = remember { CampusApi() },
            onClose = { showPlan = false },
            // 加入成功后用户点「去「学习」清单看看」→ 关掉计划页并切到「学习」那一格，
            // 让他一眼看到刚加进去的那几条。没这个回调，那个按钮就只是句空话。
            onGoTasks = {
                showPlan = false
                // idx 是**过滤后**那条 tab 栏的下标，不是枚举序号：没有 can_grab 的人
                // 那一格不存在，直接拿枚举序号当 idx 会跳到别的格上。
                // 这条以前真错过 —— 作者自己因为抢课那一格在场，序号碰巧对得上，看不见。
                // （MergeWiringTest 会扫这个文件，别把那个"枚举序号"的写法原样写回来 ——
                //   连注释里出现都会被算成一条引用。）
                idx = tabs.indexOf(CampusTab.TASKS).coerceAtLeast(0)
            },
        )
    }
    if (showPerms) {
        top.ccbase.campus.ui.settings.PermissionsScreen(
            readSnapshot = { top.ccbase.campus.alarm.DiagLog.snapshot(appCtx) },
            onClose = { showPerms = false },
        )
    }
    // 后台管理内嵌页：也盖在最外层（要盖住底部导航），令牌直接交给页面 = 不用再登一次。
    // 外面这层是**编译期**闸：公开包的 AUTHOR_BUILD 恒为 false，整段是死代码
    // （开 R8 会连 AdminWebScreen 一起摘掉）。作者包里才轮到运行期那层（MeScreen 的 isAuthor）。
    if (top.ccbase.campus.BuildConfig.AUTHOR_BUILD && showAdmin) {
        top.ccbase.campus.ui.admin.AdminWebScreen(
            ctx = appCtx,
            token = TokenStore.token(appCtx),
            onClose = { showAdmin = false },
        )
    }
    // 提建议：独立一页，谁都进得来（服务端那条接口对全体用户开放）
    if (showFeedback) {
        top.ccbase.campus.ui.me.FeedbackScreen(
            ctx = appCtx,
            version = version,
            api = remember { CampusApi() },
            onClose = { showFeedback = false },
        )
    }
    update?.let { st ->
        UpdateOverlay(
            state = st,
            currentVersion = version,
            onStart = { (st as? UpdateUi.Offer)?.let { startDownload(it.m, it.forced) } },
            onInstall = {
                val r = st as? UpdateUi.Ready
                if (r != null) {
                    if (Updater.install(appCtx, r.file)) {
                        UpdateNotify.clear(appCtx)   // 已经装上了，提醒不必再留着
                        update = null
                    } else update = UpdateUi.Failed(r.m, "没能拉起系统安装器，请到文件管理里手动打开")
                }
            },
            onSkip = {
                (st as? UpdateUi.Offer)?.let { UpdatePrefs.markSkipped(appCtx, it.m.versionCode) }
                // 用户说了「以后再说」，通知栏那条也一起撤掉 —— 留着就是变相继续催
                UpdateNotify.clear(appCtx)
                update = null
            },
            onDismiss = { update = null },
            onRetry = { (st as? UpdateUi.Failed)?.m?.let { startDownload(it) } ?: checkUpdate(true) },
            onOpenPermission = { Updater.openInstallSettings(appCtx) },
        )
    }
    }
}

/**
 * 空壳页。刻意写清"这一页将来是什么"，而不是放一句 lorem —— 
 * 你装上以后应该能一眼看出每页的定位，顺便确认 5 个 tab 都在。
 */
@Composable
private fun ShellScreen(tab: CampusTab, version: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = tab.label,
            color = tab.accent,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = tab.soon,
            color = C.txt2,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )

        Spacer(Modifier.height(4.dp))

        // 一张"细边框卡片"当视觉样板：网页版的卡片就是这个味道（极细边 + 近透明底）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(C.card, RoundedCornerShape(16.dp))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(tab.accent, RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.size(9.dp))
                    Text("P0 空壳 · 还没接数据", color = C.txt, fontSize = 14.sp)
                }
                Text(
                    text = "版本 $version",
                    color = C.txt3,
                    fontSize = 12.sp,
                    style = Digits,
                )
                Text(
                    text = "下一步（P1）：把这一页接到手机本地的 Room 数据库，飞行模式下也能用。",
                    color = C.txt3,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = "五页都在 = 导航与主题已打通",
            color = C.txt3,
            fontSize = 12.sp,
        )
    }
}


/**
 * 底栏那一格「学习」的页面（原名「任务」）。
 *
 * 2026-09-18 用户要求：这一格改叫「学习」，并把「AI 规划学习计划」的入口搬进来；
 * 同日又要求去掉页内那层「任务 | 学习」子切换，两半铺进同一条滚动页。
 *
 * 2026-09-19 用户指着下面那一半说：「学习库和这里的功能重复了，把这里的删了吧」——
 * 于是**学习内容那半整份删掉**（连 `ui/learn` 包和它的用例一起），这一页只剩任务。
 * 资源浏览统一在「学习库」那一格：点一门课进去看课程信息 + 资料（含课程详情页）。
 * 那一半不是"藏起来"了：它和「学习库」是同一件事的两种读法，留着就是两个入口、
 * 两套课程分组，用户只会更糊涂。
 *
 * 名字仍叫 TasksLearnScreen（不再改）：它是给编译器/用例用的标识，
 * 改名换来的只是一批无意义的改动（MergeWiringTest / LearningTabTest 按这个名字定位这一段）。
 *
 * [PlanEntry] 摆在**整页内容之上**（在 LazyColumn 之外，不是列表里的一项）：
 * 往下翻时它会**收起来**（2026-09-19 用户要求「往下翻时 ai 学习规划应该隐藏」——
 * 那张卡吃掉四分之一屏），翻回顶部它自己回来。因为它不在列表里，
 * 所以不是"滚出去"而是"按规则收放"：往上滚一点点就能看见它回来。
 * 全工程只有这一处渲染（见 MergeWiringTest 的"只渲染一处"）。
 *
 * `onOpenPlan` 一路透传给浮层（PlanScreen）。
 */
@Composable
internal fun TasksLearnScreen(
    db: top.ccbase.campus.data.local.CampusDb,
    onOpenPlan: () -> Unit,
) {
    var openTask by remember { mutableStateOf<Int?>(null) }
    var onlyTrack by remember { mutableStateOf<String?>(null) }
    // 库内容被改过（删 / 恢复）就 +1：任务分组与「已删除」那段都靠它重读
    var tick by remember { mutableIntStateOf(0) }
    val del = rememberTaskDelete(db, tick) {
        // 删掉的若是正在看的那条，详情必须退出（它已经不在清单里了）
        openTask = null
        tick++
    }
    // 重置学习任务：确认层挂在页根（跟删除同一个位置），不能塞进列表项里
    val reset = rememberReset(db) {
        openTask = null
        tick++
    }
    // 数据在这里取：点开任务详情（整页切走）时不重读库，返回后原位还在
    val sections = rememberTasksSections(db, tick)
    // 页首「整理」那一层（重置/恢复都收在里面）
    var more by remember { mutableStateOf(false) }

    // 列表的滚动状态。入口要不要收起看它 —— 用户 2026-09-19：「往下翻时 ai 学习规划应该隐藏」
    val listState = rememberLazyListState()
    // 往下滚过一点点就收起。阈值不取 0：最顶上轻微回弹/惯性抖动时会让卡片一闪一闪
    val entryHidden = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > HIDE_ENTRY_AFTER_PX

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 入口钉在**整页内容之上**：它不是列表里的一项，所以不会被"滚走"；
            // 但**往下翻就收起来**（把整屏让给任务），翻回顶部它自己回来。
            //
            // 为什么不做成 LazyColumn 的第一项：那样它是**真的滚出去**，用户往上滚一点点
            // 也看不见它，会以为入口没了（他明确反感"要摸索才找得到"的入口）。
            // 收放用 AnimatedVisibility 做滑动 + 淡出，不做高度动画 —— 卡片是固定高度，
            // 高度动画每帧都要重新测量，滚动时会掉帧。
            AnimatedVisibility(
                visible = !entryHidden,
                enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it },
            ) {
                PlanEntry(
                    onOpenPlan = onOpenPlan,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp),
                )
            }
            val id = openTask
            if (id != null) {
                TaskDetailScreen(db, id, onBack = { openTask = null }, onDelete = { title -> del.ask(id, title) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag(MERGED_LIST_TAG),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 28.dp),
                ) {
                    tasksContent(sections, onlyTrack, { onlyTrack = it }, { openTask = it }, del, onOpenMore = { more = true }, onOpenPlan = onOpenPlan)
                }
            }
        }
        // 确认层盖在整页之上：列表态与详情态都要能弹出来
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

/**
 * 这一页那条 LazyColumn 的测试标签。
 *
 * 测试要用它在**这一条**列表里滚动 / 定位（任务有 40+ 条，屏幕只放得下一屏）。
 * 名字保留 merged-*：它曾经是"任务半 + 学习半合并页"的证据，现在只是这段列表的把手。
 */
internal const val MERGED_LIST_TAG = "learning-merged-list"

/**
 * 内容往下滚多少像素之后，「AI 规划学习计划」入口就收起来（96px ≈ 32dp，约一行任务）。
 *
 * 不取 0：最顶上轻微回弹/惯性抖动会让卡片一闪一闪。取太大又等于没收起 ——
 * 用户就是嫌它"往下翻还杵在那儿"。
 */
private const val HIDE_ENTRY_AFTER_PX = 96

/**
 * 一次更新检查的结果 →「我的」页那行给人看的字。
 *
 * 三种结果**都要留痕**（包括"已是最新"）：只记失败的话，"检查根本没跑起来"这种更常见的失效
 * 仍然看不见。它与「课表自检」那套（`PlanApplier.recordCheck`）是同一条规矩 ——
 * **自动发生的事必须留痕**，否则用户只会看到现象、看不到原因。
 */
private fun updateTraceText(o: CheckOutcome): String = when (o) {
    is CheckOutcome.Update -> "有新版本 ${o.manifest.versionName}（${o.manifest.versionCode}）"
    CheckOutcome.Latest -> "已是最新版本"
    is CheckOutcome.Failed -> "检查失败：${o.message}"
}
