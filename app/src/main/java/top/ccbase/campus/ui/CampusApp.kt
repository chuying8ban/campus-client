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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
import top.ccbase.campus.update.UpdateGate
import top.ccbase.campus.update.AutoUpdate
import top.ccbase.campus.update.UpdateService
import top.ccbase.campus.ui.update.UpdateUi
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.ui.schedule.DailyCheckBanner
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.ui.me.MeScreen
import top.ccbase.campus.ui.me.DiagOverlay
import top.ccbase.campus.ui.board.BoardScreen
import top.ccbase.campus.ui.schedule.ScheduleScreen
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
    TODAY("今日", Icons.Filled.Home, "今天的课、已设置的自习和课前提醒"),
    SCHEDULE("课表", Icons.Filled.DateRange, "周视图与已导入的课程安排"),
    // 2026-09-18 用户要求：「把任务标签改为学习」；2026-09-19 又要求把它里面那半
    // （按课程摊开的资料列表）删掉 —— 那半和「学习库」重复。所以这一格现在**只有任务**，
    // 「AI 规划学习计划」的入口仍钉在这一页顶部；资源一律去「学习库」看。
    // 学习任务入口已并入学习库；保留本地任务数据，不再暴露独立「学习」标签。
    // 2026-09-19 用户要求「独立学习库」：不塞进「学习」页里，而是**自己的**一格,
    // 并且是资源的**唯一**入口（点一门课进去看课程信息 + 资料）。
    // 它是公共资源目录（所有同学探过的活链接，按课程 / 按类型摊开），挑中的会落成
    // 「学习」页里的一条任务 —— 所以图标用"目录"那种意思的 List，跟「学习」的勾区分开。
    LIBRARY("学习库", Icons.Filled.List, "公共资源目录：按课程 / 按类型挑链接，加到自己的清单"),
    // 2026-10-18 用户要求：「外观单独放在一个标签里，和其他四个标签并列」。
    // 之前是「我的」里的一个入口 + 一个整屏浮层，等于同一件事两个入口；现在只留这一格。
    APPEARANCE("外观", Icons.Filled.Create, "App 主题、背景图、桌面小组件配色"),
    BOARD("看板", Icons.Filled.List, "4 张统计卡 + 近 14 天图 + 里程碑 + 自检"),
    // 考试 / 成绩：从「我的」点进去的次页，不占底部导航（跟 BOARD 一样只在 extra 里出现）
    EXAM("考试", Icons.Filled.DateRange, "考试时间、考场、倒计时"),
    GRADE("成绩", Icons.Filled.List, "成绩单、平均绩点、已修学分"),
    ME("我的", Icons.Filled.Person, "账户 / 密码管理 / 关于"),
}

// 每个 tab 的专属强调色，页面上给一点色彩区分（跟网页版 per-tab 强调色同思路）
private val CampusTab.accent: androidx.compose.ui.graphics.Color
    get() = when (this) {
        CampusTab.TODAY -> C.violet
        CampusTab.SCHEDULE -> C.cyan
        CampusTab.LIBRARY -> C.cyan
        CampusTab.BOARD -> C.magenta
        CampusTab.EXAM -> C.amber
        CampusTab.GRADE -> C.green
        CampusTab.APPEARANCE -> C.magenta
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
    // 是否已走完引导。本地导入默认离线：不再拿「有没有云端令牌」当进门条件。
    var loggedIn by remember { mutableStateOf(false) }
    // 从引导/登录返回时 +1，触发重新判断（刚登录成功要能进得去）
    var gateTick by remember { mutableStateOf(0) }

    LaunchedEffect(gateTick) {
        // 等种子导完再决定 —— 否则引导第 2 屏会读到空课表。
        // 库操作一律在 IO 上做：主线程读 Room 会抛异常，界面会永远停在"正在准备…"
        // 两个一起算完再赋值：否则会"先闪一下引导页再进主界面"
        val onboarded = withContext(Dispatchers.IO) {
            runCatching { app.seedJob.join() }
            runCatching { PlanApplier.onboarded(app.db) }.getOrDefault(false)
        }
        loggedIn = onboarded
        ready = onboarded
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
    val appCtx = LocalContext.current
    // rememberSaveable：转屏/系统杀进程重建后还停在原来那个 tab。
    // 但**装完新版是全新进程**，存档也不保证还在 → 再落一次盘：
    // 更新完重进 App 仍停在原来那一页（用户 2026-10-19：更新时也要能正常用）。
    val uiPrefs = remember { appCtx.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE) }
    var idx by rememberSaveable { mutableIntStateOf(uiPrefs.getInt("tab", 0)) }
    LaunchedEffect(idx) { uiPrefs.edit().putInt("tab", idx).apply() }
    // 监控那一格按服务端给的 can_grab 显隐（就在下面构造底部栏那里读）。
    // （2026-10-07 起没有"只进作者包"的东西了：包只有一个，后台搬去了网页端。）

    // ------------------------------------------------------------------
    // 应用内更新（旁加载的 App 没有应用商店，得自己查版本、自己下、自己拉安装器）
    // 启动时最多 6 小时查一次；「我的 → 检查更新」随时手动查。
    // ------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val versionCode = top.ccbase.campus.BuildConfig.VERSION_CODE
    var update by remember { mutableStateOf<UpdateUi?>(null) }
    // 后台静默更新完成后的 5 秒横幅：版本名 to versionCode
    var silentBanner by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var autoOn by remember { mutableStateOf(AutoUpdate.enabled(appCtx)) }
    // 后台能不能起前台服务（电池白名单）：这是"切走/锁屏后下载还能不能继续"的硬前提，
    // 每次回前台重读一次 —— 用户可能刚在系统设置里点过允许。
    var keepAliveOk by remember { mutableStateOf(UpdateService.canStartFromBackground(appCtx)) }
    var autoWifi by remember { mutableStateOf(AutoUpdate.wifiOnly(appCtx)) }
    var pendingReady by remember { mutableStateOf(UpdateGate.state as? UpdateGate.S.Ready) }
    var updHint by remember { mutableStateOf<String?>(null) }
    // 网络自检浮层：连不上服务器时用户自己跑一次，把「断在哪一层」摆出来
    var showDiag by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var showArtGallery by remember { mutableStateOf(false) }
    // 日程规划：null=不显示；false=列表；true=列表+直接开编辑层（从"＋ 加一条"进来）
    var showPlan by remember { mutableStateOf<Boolean?>(null) }
    // 动态主题库：null = 不显示；"app" / "widget" = 给谁挑
    var dynTarget by remember { mutableStateOf<String?>(null) }
    var fontTarget by remember { mutableStateOf<String?>(null) }
    // 本地素材库（2026-10-20）：App 主题 / 桌面小组件各一本
    var matTarget by remember { mutableStateOf<String?>(null) }
    // 图库有两个入口：小组件要插画、App 背景要摄影 —— 进来前由入口决定看哪一类、选完往哪用
    var artKind by remember { mutableStateOf("landscape") }
    var artTarget by remember { mutableStateOf("widget") }
    // 外观开局落在哪一页：0=App 主题，1=桌面小组件（今日页「换配色」直接进第二页）
    var appearanceTab by remember { mutableIntStateOf(0) }
    // 「手机自己抓课表」浮层：教务密码不出手机的抓取路径
    var showCrawl by remember { mutableStateOf(false) }
    // 「AI 规划学习计划」浮层：先看建议再勾选入库。
    // 入口在「学习」标签页（任务页顶部那个明处的卡片）—— 用户要求从「我的」搬过来
    var showScheduleEditor by remember { mutableStateOf(false) }
    // 授权/白名单浮层：这些开关缺了对应的功能会静默失效，所以要有地方能直接去要
    var showPerms by remember { mutableStateOf(false) }
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
        // 手动点更新 = 把可能在跑的后台静默任务**拉回前台**，绝不另开第二条下载。
        // 「两个任务抢同一个 .part」正是上一代静默更新被砍掉的原因（3.4.40），
        // 现在只有 UpdateGate 一个入口，进度也由它统一报。
        when (UpdateGate.start(appCtx, m, quiet = false)) {
            UpdateGate.Start.READY_ALREADY -> {
                val r = UpdateGate.state as UpdateGate.S.Ready
                update = UpdateUi.Ready(r.m, r.file, needPermission = !Updater.canInstall(appCtx))
            }
            UpdateGate.Start.ATTACHED, UpdateGate.Start.STARTED -> Unit   // 进度走下面那个 observe
            UpdateGate.Start.BUSY_OTHER -> updHint = "另一个版本正在下载，稍等一下再试"
        }
    }

    // 下载状态只有一份（UpdateGate）：静默任务被手动点击"接管"时，这里自动切成前台进度，
    // 显示的就是它已经下到的百分比 —— 不会从 0% 重新开始。
    LaunchedEffect(Unit) {
        UpdateGate.restore(appCtx)
        UpdateGate.observe { s ->
            when (s) {
                is UpdateGate.S.Working -> if (!s.quiet) {
                    update = UpdateUi.Progress(s.m, s.fraction, null)
                }
                is UpdateGate.S.Ready -> {
                    pendingReady = s
                    update = UpdateUi.Ready(s.m, s.file, needPermission = !Updater.canInstall(appCtx))
                    if (s.quiet) {
                        // 后台下好、他还没点过：5 秒横幅（用户 2026-10-20 要求"持续五秒的弹窗"）
                        silentBanner = s.m.versionName to s.m.versionCode
                    }
                }
                is UpdateGate.S.Failed -> if (!s.quiet) update = UpdateUi.Failed(s.m, s.msg)
                UpdateGate.S.Idle -> Unit
            }
        }
    }

    // 横幅只出现 5 秒，然后自己退场；同一个版本只提示一次（重启 App 不会又糊一脸）
    LaunchedEffect(silentBanner) {
        val b = silentBanner ?: return@LaunchedEffect
        if (UpdatePrefs.bannerShown(appCtx) == b.second) { silentBanner = null; return@LaunchedEffect }
        UpdatePrefs.markBanner(appCtx, b.second)
        delay(5000)
        silentBanner = null
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
                Updater.check(top.ccbase.campus.BuildConfig.UPDATE_BASE, dial, versionCode, UpdatePrefs.skipped(appCtx), manual = true)
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
                    // 只有"他自己点的检查"或"服务端强制"才弹框。
                    // 进 App 自动查的那次一律静默（用户 2026-10-19：更新时也要能正常用），
                    // 否则一进 App 就被一个下载框盖住课表 —— 那正是他要免掉的东西。
                    if (force || outcome.forced) update = UpdateUi.Offer(m, outcome.forced, outcome.why)
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
    // 静默更新的兜底时机：系统闹钟在国产 ROM 上不一定准，打开 App 时补一次（6 小时内不重复）
    LaunchedEffect(Unit) { AutoUpdate.kickIfDue(appCtx) }

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
                keepAliveOk = UpdateService.canStartFromBackground(appCtx)
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

    // 系统返回键 · 主界面：交给 MainActivity 那层的 ExitConfirmation 统一处理 ——
    // 根页面第一次返回只浮一条自动消失的轻提示，2 秒内再按一次才退出。
    // （2026-10-16 之前这里自己画过一条提示条，已删：同一个提示只留一处实现。）

    // 底部 tab 栏：看板收进「我的」页，不再常显；监控那一格**人人都有**。
    //
    // ⚠️ 2026-09-30 用户口径：「新版本每个用户的手机上都会显示监控，而不需要重新登录」——
    // 所以这里**不再拿 TokenStore.canGrab 当判据**：那是登录那一刻写进本地的值，老用户装上
    // 这个版本也不会重新登录，于是永远看不到那一格。服务端同时把监控那几个接口从 can_grab
    // 放开成"登录即可"（`grab/config` 那个总开关仍只给作者），界面和服务端的判据这才对得上。
    val tabs = CampusTab.entries.filter { it != CampusTab.BOARD && it != CampusTab.EXAM && it != CampusTab.GRADE }

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
                        onClick = {
                            // 点底部「外观」永远从「App 主题」开始（用户：进入外观默认是 app 主题）。
                            // 例外只有今日页那个「换配色」——它是直接给 appearanceTab 赋值再切过去的。
                            if (t == CampusTab.APPEARANCE) appearanceTab = 0
                            idx = i
                            extra = null
                        },
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
        // 每日课表核对：**常驻在最上方的一条细提示**（用户口径：不要弹窗、无感常驻、不挡操作）。
        // 它不是子页、不是对话框：就占一条 34dp 的细带，滚页面、点按钮都不受影响。
        // 下载中给底部腾出一条的位置：这样浮条是"挤上去"的，不是盖在"今日日程"上
        // （用户 2026-10-19："正在下载的那个标签和app重合了"）。
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(bottom = if (update is UpdateUi.Progress) 82.dp else 0.dp),
        ) {
            DailyCheckBanner(appCtx) { showCrawl = true }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            val app = LocalContext.current.applicationContext as CampusApplication
            // tabs 的长度会变（监控那一格随 can_grab 进出），而 idx 是记下来的旧下标
            // （rememberSaveable：转屏/进程重建后还留着）—— 越界就是崩，所以先夹回合法范围。
            val shown = extra ?: tabs[idx.coerceIn(0, tabs.lastIndex)]
            when (shown) {
                CampusTab.TODAY -> TodayScreen(
                    app.db,
                    onOpenExam = { extra = CampusTab.EXAM },
                    onOpenPlan = { showPlan = false },
                    onAddPlan = { showPlan = true },
                    onOpenWidgetTheme = {
                        appearanceTab = 1
                        idx = tabs.indexOf(CampusTab.APPEARANCE).coerceAtLeast(0)
                    },
                )
                CampusTab.SCHEDULE -> ScheduleScreen(
                    app.db,
                    onUpdate = { showScheduleEditor = false; showCrawl = true },
                    onEdit = { showCrawl = false; showScheduleEditor = true },
                )
                CampusTab.LIBRARY -> top.ccbase.campus.ui.library.LearningHub(app.db)
                // 外观现在是**标签页**而不是浮层：底部栏切过去就是它，不用再「关掉」。
                CampusTab.APPEARANCE -> top.ccbase.campus.ui.me.AppearanceScreen(
                    onClose = {},
                    initialTab = appearanceTab,
                    embedded = true,
                    onOpenDynLibrary = { dynTarget = it },
                    onOpenFontLib = { fontTarget = it },
                    onOpenMaterials = { matTarget = it },
                    onOpenGallery = { kind, target ->
                        artKind = kind
                        artTarget = target
                        showArtGallery = true
                    },
                )
                CampusTab.BOARD -> BoardScreen(app.db)
                CampusTab.EXAM -> top.ccbase.campus.ui.records.ExamScreen(app.db)
                CampusTab.GRADE -> top.ccbase.campus.ui.records.GradeScreen(app.db)
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
                    autoUpdateOn = autoOn,
                    onToggleAutoUpdate = { on ->
                        autoOn = on
                        AutoUpdate.setEnabled(appCtx, on)
                        if (on) {
                            // 先把「安装未知应用」要下来：没有它，静默下好了也装不上（白耗流量）
                            if (!Updater.canInstall(appCtx)) Updater.openInstallSettings(appCtx)
                            AutoUpdate.kickIfDue(appCtx)
                        }
                    },
                    pendingInstallText = pendingReady?.let { "${it.m.versionName} 已下好" },
                    onInstallPending = {
                        pendingReady?.let { r ->
                            update = UpdateUi.Ready(r.m, r.file, needPermission = !Updater.canInstall(appCtx))
                        }
                    },
                    autoUpdateWifiOnly = autoWifi,
                    onToggleAutoWifi = { on -> autoWifi = on; AutoUpdate.setWifiOnly(appCtx, on) },
                    keepAliveOk = keepAliveOk,
                    onOpenKeepAlive = {
                        // 没有电池白名单时，Android 12+ 不许后台起前台服务（闹钟那一路会被掐）
                        runCatching {
                            appCtx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${appCtx.packageName}"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onOpenDiag = { showDiag = true },
                    onOpenSafety = { showSafety = true },
                    onOpenAppearance = {
                        appearanceTab = 0
                        idx = tabs.indexOf(CampusTab.APPEARANCE).coerceAtLeast(0)
                    },
                    onOpenCrawl = { showCrawl = true },
                    onOpenPermissions = { showPerms = true },
                    onOpenFeedback = { showFeedback = true },
                    onOpenExam = { extra = CampusTab.EXAM },
                    onOpenGrade = { extra = CampusTab.GRADE },
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
    showPlan?.let { withEditor ->
        top.ccbase.campus.ui.plan.DayAgendaScreen(
            db = (LocalContext.current.applicationContext as CampusApplication).db,
            onClose = { showPlan = null },
            startWithEditor = withEditor,
        )
    }
    dynTarget?.let { t ->
        top.ccbase.campus.ui.me.DynLibraryScreen(target = t, onClose = { dynTarget = null })
    }
    fontTarget?.let { t ->
        // 关掉时把桌面刷一次：小组件换字体要重新充气布局
        top.ccbase.campus.ui.me.FontLibraryScreen(target = t, onClose = {
            fontTarget = null
            runCatching { top.ccbase.campus.widget.TodayWidgetProvider.refresh(appCtx) }
        })
    }
    matTarget?.let { t ->
        top.ccbase.campus.ui.me.LocalMaterialsScreen(slotKey = t, onClose = {
            matTarget = null
            // 素材库里选完可能已经换了主题，桌面刷一次更稳（字体/素材两类都会走到这里）
            runCatching { top.ccbase.campus.widget.TodayWidgetProvider.refresh(appCtx) }
        })
    }
    if (showArtGallery) {
        top.ccbase.campus.ui.me.ArtGalleryScreen(
            kind = artKind,
            target = artTarget,
            onClose = { showArtGallery = false },
        )
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
    if (showScheduleEditor) {
        top.ccbase.campus.ui.me.ScheduleEditor(appCtx, (appCtx.applicationContext as CampusApplication).db) { showScheduleEditor = false }
    }
    // 背景自愈：设的是视频/gif 主题、但文件被清掉或下载被截断（放不出来）时，
    // 别让用户对着整屏黑 —— 直接退回预设配色，并把坏文件删掉。
    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bgv = top.ccbase.campus.ui.theme.AppearanceStore.background.value
            if (bgv.startsWith(top.ccbase.campus.ui.theme.UserMedia.PREFIX)) {
                val m = top.ccbase.campus.ui.theme.UserMedia.current(appCtx)
                if (m == null || !top.ccbase.campus.ui.theme.UserMedia.playable(m.file)) {
                    top.ccbase.campus.ui.theme.UserMedia.clear(appCtx)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        top.ccbase.campus.ui.theme.AppearanceStore.setBackground(
                            appCtx, top.ccbase.campus.ui.theme.BG_PRESET_PREFIX + "dusk",
                        )
                    }
                }
            }
        }
    }

    // 图库素材还在下（用户 2026-10-19："离开图库后如果有正在下载的图要在上面放一个弹窗
    // 提示正在下载以及下载进度"）。这里读的是全局 MediaJob —— 下载协程活在页面之外，
    // 所以退出图库、切到别的页，这条照样在走。
    val dlId = top.ccbase.campus.ui.me.MediaJob.id.value
    if (dlId != null) {
        val pct = top.ccbase.campus.ui.me.MediaJob.pct.value
        val now = top.ccbase.campus.ui.me.MediaJob.bytes.value
        Box(
            // 用户 2026-10-19："上面的正在下载素材把关闭按钮挡住了，放到下面去"
            // → 贴底（在导航栏上方），顶部给各页留出自己的标题/关闭区。
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(C.bgSoft)
                .border(1.dp, C.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在下载素材", color = C.txt, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        top.ccbase.campus.ui.me.MediaJob.label.value,
                        color = C.txt3, fontSize = 11.sp, modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        if (pct >= 0) "$pct%"
                        else top.ccbase.campus.ui.me.MediaJob.humanBytes(now),
                        color = C.violet, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (pct >= 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { pct / 100f },
                        color = C.violet,
                        trackColor = C.line,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        color = C.violet,
                        trackColor = C.line,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
    }

    // 公告（2026-10-19 用户："给app加一个通知（或者叫公告）通道…支持链接，图片"）。
    // 每次进 App 拉一次服务端的公告列表，没看过的最新一条弹一次，看过就记 id。
    var notice by remember { mutableStateOf<top.ccbase.campus.notice.Notice?>(null) }
    var noticeImg by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        val n = withContext(Dispatchers.IO) { top.ccbase.campus.notice.NoticeRepo.firstUnseen(appCtx) }
        if (n != null) {
            notice = n
            if (n.image.isNotBlank()) {
                noticeImg = withContext(Dispatchers.IO) { top.ccbase.campus.notice.NoticeRepo.image(n.image) }
            }
        }
    }
    notice?.let { n ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                top.ccbase.campus.notice.NoticeRepo.markSeen(appCtx, n.id)
                notice = null
            },
            title = { Text(n.title, color = C.txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    noticeImg?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (n.body.isNotBlank()) {
                        Text(n.body, color = C.txt2, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    if (n.link.isNotBlank()) {
                        Text(
                            "打开链接",
                            color = C.violet, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                runCatching {
                                    appCtx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(n.link),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                top.ccbase.campus.notice.NoticeRepo.markSeen(appCtx, n.id)
                                notice = null
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Text(
                        "知道了",
                        color = C.txt3, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            top.ccbase.campus.notice.NoticeRepo.markSeen(appCtx, n.id)
                            notice = null
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            },
            containerColor = C.bgSoft,
        )
    }

    // 更新说明：装完新版第一次进 App 直接弹（用户 2026-10-19："新版本内容通知要在 app 内出现"）
    var updateNotes by remember { mutableStateOf(top.ccbase.campus.update.UpdateNotice.pendingInApp(appCtx)) }
    updateNotes?.let { (v, body) ->
        top.ccbase.campus.ui.update.UpdateNotesDialog(
            version = v,
            body = body,
            onDismiss = {
                top.ccbase.campus.update.UpdateNotice.markInAppSeen(appCtx)
                updateNotes = null
            },
        )
    }
    if (showPerms) {
        top.ccbase.campus.ui.settings.PermissionsScreen(
            readSnapshot = { top.ccbase.campus.alarm.DiagLog.snapshot(appCtx) },
            onClose = { showPerms = false },
        )
    }
    // 后台内嵌页（AdminWebScreen）2026-10-07 整块删除：双通道拆掉之后包只有一个，
    // 后台搬去了网页端 `https://<站点>/admin/`（只有作者口令能进，与 App 无关）。
    // 好处不只是少一页：那一页的字符串原先要靠 R8 才摘得干净，不开压缩就留在每个人的 dex 里。
    // 提建议：独立一页，谁都进得来（服务端那条接口对全体用户开放）
    if (showFeedback && TokenStore.token(appCtx).isNullOrBlank()) {
        top.ccbase.campus.ui.common.CloudAccessGate(
            onClose = { showFeedback = false },
            onImport = { showFeedback = false; showCrawl = true },
        )
    }
    if (showFeedback && !TokenStore.token(appCtx).isNullOrBlank()) {
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
            // 收起进度条：只把界面藏了，下载协程照跑（它在 CampusShell 的 scope 里）
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

        // 后台静默更新完成后那条 5 秒横幅（用户 2026-10-20 明确要求）。
        // 只提示、不打断：点它才进安装流程；不看就自己走掉。
        silentBanner?.let { (vname, _) ->
            SilentUpdateBanner(
                versionName = vname,
                onTap = {
                    silentBanner = null
                    UpdateGate.pending(appCtx)?.let { r ->
                        update = UpdateUi.Ready(r.m, r.file, needPermission = !Updater.canInstall(appCtx))
                    }
                },
            )
        }
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

/**
 * 「发现新版本，已在后台静默更新」——顶部滑入、5 秒自动退场（计时在调用处）。
 *
 * 为什么不用系统 Toast：Toast 不能点、也不遵守 App 的视觉语言；这里要能点进安装。
 * 为什么不用对话框：用户只说了"弹窗提示"，但一个挡住课表的模态框违背"更新时也要正常用"，
 * 顶部横幅既能看见也不挡操作。
 */
@Composable
private fun SilentUpdateBanner(versionName: String, onTap: () -> Unit) {
    val shown = androidx.compose.animation.core.Animatable(-1f)
    LaunchedEffect(Unit) { shown.animateTo(0f, androidx.compose.animation.core.tween(260)) }
    Box(Modifier.fillMaxSize().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
        androidx.compose.material3.Surface(
            color = Color(0xF21A1A22),
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 10.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .offset(y = (shown.value * 90).dp)
                .clickable(onClick = onTap),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("新版本 $versionName", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text("已在后台静默更新 · 点此安装", color = Color(0xCCFFFFFF), fontSize = 12.sp)
            }
        }
    }
}
