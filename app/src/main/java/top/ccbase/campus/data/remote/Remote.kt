package top.ccbase.campus.data.remote

import android.content.Context
import top.ccbase.campus.data.local.CampusDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.Content
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.data.plan.Modules
import top.ccbase.campus.data.plan.Templates
import top.ccbase.campus.data.plan.TplLoader
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.net.TimetableStatus

/**
 * 登录令牌的本地保存。
 *
 * 用普通 SharedPreferences：它在 App 私有目录里，非 root 设备上别的 App 读不到。
 * 上 EncryptedSharedPreferences 要多一个依赖，而这里存的只是一个 30 天到期、
 * 可被服务端随时吊销的登录令牌 —— 收益和成本不成比例。
 * 真正不能落地的（教务密码）从来就没有离开过服务器。
 */
object TokenStore {
    private const val PREF = "campus_session"
    private const val K_TOKEN = "token"
    private const val K_EXP = "expires_at"
    private const val K_NAME = "name"
    private const val K_SID = "student_id"
    private const val K_GRAB = "can_grab"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(ctx: Context, token: String, expiresAt: String, user: ApiUser) {
        sp(ctx).edit()
            .putString(K_TOKEN, token)
            .putString(K_EXP, expiresAt)
            .putString(K_NAME, user.name)
            .putString(K_SID, user.student_id)
            .putBoolean(K_GRAB, user.canGrab)
            .apply()
    }

    fun token(ctx: Context): String? = sp(ctx).getString(K_TOKEN, null)

    fun studentId(ctx: Context): String? = sp(ctx).getString(K_SID, null)

    fun name(ctx: Context): String? = sp(ctx).getString(K_NAME, null)

    /** 令牌到期时刻（服务端登录时给的 `expires_at`，30 天） */
    fun expiresAt(ctx: Context): String? = sp(ctx).getString(K_EXP, null)

    /**
     * 本机记的到期时间是不是已经过了。
     *
     * 为什么以前没这个判据不行：`expires_at` 一直都在存，**从来没人读**。
     * 于是令牌过期后 App 还照常拿它发请求，直到某次操作被服务端回 401 才知道 ——
     * 「手机自己抓课表」就是在最后一步（上传）才撞上的：用户白填了一遍教务密码。
     *
     * ⚠️ 这只是**提前提示**用的近似判断（手机时间可能不准、服务端也可能提前吊销）：
     * 真正的判据永远是服务端返回的 401。所以**解析不出来时一律当没过期**，
     * 绝不能因为时间格式变了就把人挡在外面。
     */
    fun expired(ctx: Context, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Boolean {
        val raw = expiresAt(ctx)?.trim().orEmpty()
        if (raw.isBlank()) return false
        val at = runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(raw).toLocalDateTime() }.getOrNull()
            ?: return false
        return !at.isAfter(now)
    }

    /** 抢课权限：**只用来决定要不要显示入口**。真正的边界在服务端，客户端这个值可以随便改。 */
    fun canGrab(ctx: Context): Boolean = sp(ctx).getBoolean(K_GRAB, false)

    fun clear(ctx: Context) = sp(ctx).edit().clear().apply()
}

/**
 * 把服务端下发的计划写进本地库。
 *
 * 与内置种子导入用**同一套纪律**（这是全项目最不可接受的数据事故）：
 * ① **绝不碰用户记录** —— task_done / checkins / sessions 一个字不动。
 *    重灌计划而丢掉打卡记录，用户一夜之间连续天数归零，比任何功能缺失都致命。
 * ② **幂等** —— 同一份计划重复写入结果一致。
 * ③ 只替换"模板性质"的表：课程 / 时段 / 自习 / 任务 / 步骤 / 资源 / 里程碑 / 清单。
 *
 * 写成 `plan_source = remote` 之后，内置种子导入会自动让位（否则下次启动
 * 会用作者本人的计划把这位同学的覆盖掉 —— 那是最容易发生、又最难查的串数据）。
 */
object PlanApplier {

    const val K_SOURCE = "plan_source"
    const val K_AT = "plan_synced_at"
    const val K_ONBOARD = "onboarded"
    const val REMOTE = "remote"
    const val SEED = "seed"

    /**
     * 用户按过「重置」：本地计划已清空，**并且明确表示暂时不要计划**。
     *
     * 必须区别于"还没同步过"（那时 source 是 null）：
     *   · 界面遇到 NONE 显示空态 + 「去生成 / 同步」；
     *   · **自动同步（打开 App / 回前台）必须跳过** —— 否则服务端那份计划会立刻把清单塞回来，
     *     "重置"就白按了（用户原话：重置后这里就应该没有任务了）。
     */
    const val NONE = "none"

    /** 服务端课表版本号（`/plan` 的 `timetable.version`）。只用来比对要不要重拉 */
    const val K_TT_VER = "timetable_version"
    /** 版本号变了的时刻 = 课表真的更新过的时刻 */
    const val K_TT_AT = "timetable_updated_at"
    /** 自动核对的时刻 / 结果 —— 给「我的」页照实显示，不许静默 */
    const val K_CHECK_AT = "timetable_checked_at"
    const val K_CHECK_RES = "timetable_check_result"
    /** 服务端支不支持「重读教务课表」：yes / no（404）/ 没有 = 还不知道 */
    const val K_RESYNC = "timetable_resync"

    data class Result(val counts: Map<String, Int>)

    /**
     * 把服务端下发的那份计划落到本机 —— 这是**整体替换的唯一出口**。
     *
     * @param templates 内置模板，只有「重放用户挑过的模块包」用得到。
     *   手里有 Context 的调用链传 [TplLoader.get] 的结果；只有 db / token 的老调用链
     *   留空即可 —— [TplLoader] 的进程缓存会兜住（见 [replayUserPicks]）。
     */
    suspend fun apply(
        db: CampusDb,
        plan: Seed,
        source: String = REMOTE,
        at: String = "",
        templates: Templates? = null,
    ): Result {
        // 空计划 = 服务端**还没有这位用户的内容**（新用户还没被播种），
        // 不是"用户把自己清空了"。拿它去整体替换，会把本地内容
        // （内置模板 + 用户刚挑好的技能包）全部清掉 ——
        // 同学第一次点「手动同步」就会丢数据。
        //
        // 判据：一门课、一个任务都没有，不可能是有人认真编辑过的计划。
        if (plan.courses.isEmpty() && plan.tasks.isEmpty()) {
            return Result(emptyMap())
        }
        // 内容整体替换（含清理多余行）统一走 Content.replace，
        // 与内置种子导入只此一份规则 —— 两套替换逻辑迟早会分叉
        val c = Content.replace(
            db, plan,
            extraMeta = listOf(Meta(K_SOURCE, source), Meta(K_AT, at)),
        )
        replayUserPicks(db, templates)
        return Result(c.counts)
    }

    /**
     * 重建「用户自己挑的东西」：学习库资料 + 挑过的内置模块包。
     *
     * ⚠️ 整体替换（[Content.replace]）会把**本机自己生成的行**一起 prune 掉 ——
     * 它们不在服务端那份计划里。挑选记录只存在 meta 里（replace 只往 meta 里加、从不清空），
     * 所以重放永远安全、且是幂等的（按固定 id REPLACE，重复调用结果一样）。
     *
     * **只在这里重放**，因为这里是整体替换的唯一出口：`RemoteSync.sync`（手动同步 / 下拉刷新）
     * 与 `checkAndRefresh`（打开 App、回前台自动发现课表变了）都收敛到 apply。
     * 分散到各调用点就必然会漏掉一条 —— 1.71 的模块包就是在 `sync()` 里单独重放了一次，
     * 「自动发现课表变了」那条路漏了整整一版：用户看到的是"我挑的技能包一自动更新就没了"，
     * 界面不报错、日志不吭声（`AutoRefreshReplayTest` 钉住这一点）。
     *
     * 配角的纪律：计划落地是主角，重放失败不许把整次同步带崩；但**也不许静默** ——
     * 静默的后果是用户丢了东西而日志里一个字都没有。
     */
    private suspend fun replayUserPicks(db: CampusDb, templates: Templates?) {
        runCatching { Library.replay(db) }.onFailure {
            android.util.Log.w("Remote", "学习库重放失败：${it.message}")
        }

        val picked = runCatching { Modules.picked(db) }.getOrDefault(emptyList())
        if (picked.isEmpty()) return
        val tpl = templates ?: TplLoader.cached()
        if (tpl == null) {
            // 拿不到模板就只能跳过（重建不出那些行）—— 但必须出声，别让用户自己发现
            android.util.Log.w("Remote", "拿不到计划模板，${picked.size} 个已挑模块包这次没能重放")
            return
        }
        runCatching { Modules.apply(db, tpl, picked) }.onFailure {
            android.util.Log.w("Remote", "模块包重放失败：${it.message}")
        }
    }

    /** 本机记着的课表版本号（没记过 → null） */
    suspend fun timetableVersion(db: CampusDb): String? =
        db.dao().metaGet(K_TT_VER)?.takeIf { it.isNotBlank() }

    /**
     * 把服务端的课表状态记到 meta。
     *
     * 只在**真的落地了一份计划**之后调用（见 [RemoteSync.sync]）：如果计划是空的、
     * 什么都没写进库，却把版本号记成"已同步"，以后就再也不会自动重拉了 ——
     * 那是把"课表不更新"这件事写进了代码里。
     */
    suspend fun recordTimetable(db: CampusDb, tt: TimetableStatus?) {
        tt ?: return
        val rows = mutableListOf(Meta(K_RESYNC, "yes"))   // 有 timetable 键 = 服务端支持重读
        if (tt.hasVersion) {
            rows += Meta(K_TT_VER, tt.version)
            if (tt.updatedAt.isNotBlank()) rows += Meta(K_TT_AT, tt.updatedAt)
        }
        db.dao().putMeta(rows)
    }

    /** 记一次自动核对的结果（成败都记，「我的」页照实显示） */
    suspend fun recordCheck(db: CampusDb, result: String, at: String = nowIso()) {
        db.dao().putMeta(listOf(Meta(K_CHECK_AT, at), Meta(K_CHECK_RES, result)))
    }

    /** 引导走完了没 —— 走完就不再拦在引导页（没走完但也不强求，随时可跳过） */
    suspend fun onboarded(db: CampusDb): Boolean = db.dao().metaGet(K_ONBOARD) == "1"

    suspend fun markOnboarded(db: CampusDb) {
        db.dao().putMeta(listOf(Meta(K_ONBOARD, "1")))
    }

    /** 当前内容来自哪里 —— 退出登录时要清掉，内置种子才能重新接管。 */
    suspend fun source(db: CampusDb): String? = db.dao().metaGet(K_SOURCE)

    suspend fun releaseToSeed(db: CampusDb) {
        // 只清来源标记。**不删内容** —— 用户此刻还在离线用这份计划，
        // 删了他打开就是空白；等种子/新计划真正写入时再覆盖。
        db.dao().putMeta(listOf(Meta(K_SOURCE, SEED)))
    }
}

/**
 * 「课表新不新鲜」的判据。**纯函数**，先红后绿钉住 ——
 * 这是"App 自己发现课表变了"这件事唯一的决策点，判错了就是
 * 要么永远不更新（用户又得自己发现），要么每次开 App 都白拉一遍 87KB。
 *
 * 三条分寸（都对应真实会发生的场景）：
 *  ① **老服务端没有版本号**（键不存在 / 空串）→ 不比对、不报错、安静退回原行为。
 *     新客户端必须能和老服务端共存（服务端是逐步上线的）。
 *  ② 本机没记过版本 → 拉一次对齐（这同时把版本号记下来，下次就能比了）。
 *  ③ 版本号一致 → **什么都不做**。用户改自己的目标分/备注不会换号（服务端
 *     指纹只放课表事实），所以这里不会因为"用户动了自己的数据"而白拉。
 */
object PlanFreshness {

    /** 老服务端 / 服务端还没同步过课表时的原话（照实显示给用户） */
    const val NO_VERSION = "服务器没给课表版本号（服务端较旧），保持原样"

    data class Verdict(val refresh: Boolean, val reason: String)

    fun verdict(remote: TimetableStatus?, local: String?): Verdict = when {
        remote == null -> Verdict(false, NO_VERSION)
        !remote.hasVersion -> Verdict(false, NO_VERSION)
        local.isNullOrBlank() -> Verdict(true, "本机还没记过课表版本，先对齐一次")
        remote.version == local -> Verdict(false, "课表版本一致，不用重拉")
        else -> Verdict(true, "服务器上的课表变了（${local.take(8)}… → ${remote.version.take(8)}…）")
    }
}

/**
 * 「拉一次远端计划」这个动作的完整流程。
 *
 * 顺序不能反、步骤不能少：
 *   ① 拉计划 → ② `PlanApplier.apply`（整体替换内容 + **重放用户自己挑的东西**）
 *            → ③ 记下服务端的课表版本号
 *
 * ⚠️ 重放（模块包 / 学习库资料）在 ② 里面，本对象**不许自己再重放一份** ——
 * 整体替换的唯一出口只有 `PlanApplier.apply`，两条路都收敛到那里；
 * 各写一份的话必然会漏一条（1.71 就在这里单独重放模块包，导致自动刷新那条路丢模块包）。
 *
 * ③ 只在**真的落地了内容**之后做（空计划不记）—— 否则等于把"以后不用再更新了"写进库。
 */
object RemoteSync {

    /** 自动核对的结果（给界面照实显示，不含异常文本） */
    data class Freshness(val applied: Boolean, val reason: String)

    suspend fun sync(
        ctx: Context,
        db: CampusDb,
        api: CampusApi,
        token: String,
        templates: Templates? = null,
    ): ApiResult<PlanApplier.Result> = withContext(Dispatchers.IO) {
        when (val p = api.planBundle(token)) {
            is ApiResult.Err -> p
            is ApiResult.Ok -> {
                val res = PlanApplier.apply(
                    db, p.value.seed, at = nowIso(),
                    templates = templates ?: TplLoader.get(ctx),
                )
                if (res.counts.isNotEmpty()) PlanApplier.recordTimetable(db, p.value.timetable)
                ApiResult.Ok(res)
            }
        }
    }

    /**
     * 「App 自己发现课表变了」这一步（打开 App / 回到前台时跑）。
     *
     * 只花**一次** GET /plan：响应里既有计划、也有课表版本号。
     *   - 版本号变了（或本机没记过）→ 就地落地新计划（不用第二次请求），并把号记下；
     *   - 版本号一样 → **什么都不动**（这也是它比"每次开 App 都整份覆盖"安全的地方）；
     *   - 老服务端没有版本号 → 安静退回去，一步都不动。
     *
     * 失败一律**如实返回**给调用方去记（`K_CHECK_RES`）—— 不许吞掉：
     * 用户原话就是"不要用户自己发现错误"，那出问题时至少界面上要看得见。
     */
    suspend fun checkAndRefresh(
        ctx: Context,
        db: CampusDb,
        api: CampusApi,
        token: String,
    ): ApiResult<Freshness> = withContext(Dispatchers.IO) {
        val at = nowIso()
        // 用户按过「重置」→ 这一步什么都不做，绝不自动把服务端计划塞回来。
        // （他点「去生成 / 同步」是显式动作，走 sync()，不受这里影响。）
        if (PlanApplier.source(db) == PlanApplier.NONE) {
            return@withContext ApiResult.Ok(Freshness(false, "已重置：等你自己点「去生成 / 同步」"))
        }
        when (val p = api.planBundle(token)) {
            is ApiResult.Err -> p
            is ApiResult.Ok -> {
                val v = PlanFreshness.verdict(p.value.timetable, PlanApplier.timetableVersion(db))
                if (v.refresh) {
                    // 这里也要把模板给 apply：本次落地同样是一次整体替换，
                    // 用户挑过的模块包不重放就会被清掉（重放只在 apply 那一处）
                    val res = PlanApplier.apply(db, p.value.seed, at = at, templates = TplLoader.get(ctx))
                    if (res.counts.isNotEmpty()) {
                        PlanApplier.recordTimetable(db, p.value.timetable)
                        PlanApplier.recordCheck(
                            db,
                            "已自动更新课表：${v.reason}（${res.counts["courses"] ?: 0} 门课 · " +
                                "${res.counts["tasks"] ?: 0} 项任务）",
                            at,
                        )
                    } else {
                        // 版本号变了、但计划是空的（服务端还没播种）→ 不假装更新过
                        PlanApplier.recordCheck(db, "服务器还没给出计划内容，本次没动课表", at)
                    }
                    ApiResult.Ok(Freshness(res.counts.isNotEmpty(), v.reason))
                } else {
                    PlanApplier.recordTimetable(db, p.value.timetable)
                    // 老服务端（没有版本号）时说"已是最新"是不诚实的：我们**根本没比过**
                    PlanApplier.recordCheck(
                        db,
                        if (v.reason == PlanFreshness.NO_VERSION) {
                            "服务器没给课表版本号（老服务端），这次没比对，保持原样"
                        } else "课表已是最新：${v.reason}",
                        at,
                    )
                    ApiResult.Ok(Freshness(false, v.reason))
                }
            }
        }
    }

}

/**
 * 统一的时刻格式（`yyyy-MM-dd'T'HH:mm:ss`）—— 落库、日志、界面都读同一个，
 * 免得"上次更新"和"课表自检"两个时间长得不一样。
 */
internal fun nowIso(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date())
