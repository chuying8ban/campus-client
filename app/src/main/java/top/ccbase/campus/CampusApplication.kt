package top.ccbase.campus

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader

/**
 * 启动入口：建库 + 首次灌种子。
 *
 * 两个刻意的选择：
 * ① **种子导入失败不让 App 崩** —— 库是空的但界面能开、能看能点。
 *    对用户来说"打开是空的"远比"点开就闪退"可接受，而且日志里能查到原因。
 * ② **导入是异步的，但界面必须等它** —— 否则首次启动会先渲染一个空页面，
 *    看起来像"这个 App 什么数据都没有"。所以暴露 seedJob 让界面 await。
 *
 * `open` 是给**测试**用的：Robolectric 上通过 `TestCampusApplication` 注入可逆的
 * TokenStore cipher（JVM 没有 AndroidKeyStore），生产不带任何行为差异。
 */
open class CampusApplication : Application() {

    lateinit var db: CampusDb
        private set

    /** 界面在读库之前先 join 这个，避免首次启动读到空库 */
    lateinit var seedJob: Job
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = CampusDb.get(this)
        // 全 App 共用一个传输层，并把它选定的链路路线（直连/不带 SNI）落盘 —— 只探一次
        top.ccbase.campus.net.Net.init(this)
        // 这台设备的身份：作者功能（后台/抢课）绑设备要用它，必须早于任何一次请求
        top.ccbase.campus.net.DeviceId.init(this)
        // 计划模板（随包发布的只读资产）先读一次进进程缓存。不是为了快：
        // 整体替换之后的「重放用户挑的模块包」跑在 PlanApplier.apply 里，
        // 而它的调用链有的（引导页 / 等待页）手里没有 Context —— 靠这份缓存兜住，
        // 才能保证"哪条路上都不会静默跳过重放"。
        runCatching { top.ccbase.campus.data.plan.TplLoader.load(this) }
        // 外观（主题档 + 背景）**在进程启动时读一次**，之后只走内存里的快照状态。
        //
        // 为什么不放在 CampusTheme 里用 LaunchedEffect 读：那样每个页面/每次组合都会发起一次
        // "读 SharedPreferences → 写状态"的活儿，而它在 Robolectric+Espresso 下会表现为
        // "主 looper 一直不空闲"（`AppNotIdleException`，2026-09-30 实见：全量跑时 11 条红、单类全绿）。
        // 读一次、之后纯内存，既省事也把这个测试环境陷阱消掉。
        runCatching { top.ccbase.campus.ui.theme.AppearanceStore.bind(this) }
        // 抢课提醒：开关开着就把闹钟补上（重启、升级、被杀进程后都能自愈）
        runCatching { top.ccbase.campus.alarm.RetiredMonitorCleanup.cancel(this) }
        seedJob = scope.launch {
            try {
                val full = SeedLoader.load(this@CampusApplication)
                // ⚠️ 只灌「学校里通用的东西」（节次时间 / 自习时段）。
                // 随包 seed.json 里还有**作者本人**的课程 / 任务 / 步骤 / 资源 / 清单 / 里程碑 ——
                // 那些一律不灌：每个人的任务必须由他自己的课表推出来，
                // 谁还没同步到自己的计划，谁就会看到别人的东西（串数据）。
                // 用户定的规矩：宁可空着，也不拿假数据凑 —— 空的时候界面会明说「还没有你的计划」。
                val generic = full.copy(
                    courses = emptyList(),
                    tasks = emptyList(),
                    study_steps = emptyList(),
                    resources = emptyList(),
                    checklist = emptyList(),
                    milestones = emptyList(),
                )
                val r = SeedImporter.import(db, generic)
                Log.i(TAG, if (r.imported) "通用数据已导入：${r.counts}" else "种子版本未变，跳过导入")
            } catch (e: Exception) {
                // 不抛出去：让 App 能开；库里没数据时界面会显示空计划说明
                Log.e(TAG, "种子导入失败：${e.javaClass.simpleName} ${e.message}", e)
            }
        }
    }

    private companion object {
        const val TAG = "Campus"
    }
}
