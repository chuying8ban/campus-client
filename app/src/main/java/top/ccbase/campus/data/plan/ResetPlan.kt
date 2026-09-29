package top.ccbase.campus.data.plan

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.DeletedTask
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.data.local.TaskDone

/**
 * 「重置学习任务」。
 *
 * 用户原话：「重置后回到内置模板那套默认任务」—— 所以重置**不是**清空了事：
 * 清完必须还有东西，而且是每门课那条内置的（数学分析（I）那套课后题+真题、程序设计基础（B）那套超前学+期末程序、
 * 英语词汇那套；认不出的课走 generic 一次性块）。
 *
 * id 三段互不撞号（服务端同步整体替换内容时只碰第一段）：
 *   · 服务端下发    < 1_000_000
 *   · 内置模块包    1_000_000+（[Modules.ID_BASE]）—— 用户 onboarding 自己挑的，重置**保留**
 *   · 模板默认任务  2_000_000+（[ID_BASE]，本文件）—— 只有重置会写
 * 同一门课固定占 [SPAN] 一段，重复重置不会换 id（幂等，不会插出两份）。
 *
 * 纪律：
 * ① **服务端来的行只打墓碑、绝不真删**（`tasks` 那行还要留给同步对账，真删会被写回来）；
 * ② 本地行（≥1M）真删，连 steps/resources 一起，否则会留下悬空的"步骤属于不存在的任务"；
 * ③ 打勾（task_done）归零 —— "重置"的题中之义，但要先备份，错了能恢复；
 * ④ 重置前把现状整份存下来（[Snapshot]），界面写进 filesDir，用户后悔能一键恢复。
 */
object ResetPlan {

    const val ID_BASE = 2_000_000
    const val SPAN = 1_000
    const val SERVER_MAX = 1_000_000
    const val PHASE = "内置计划"
    private const val PHASE_ORDER = 95          // 排在模块包（90）之后，不抢作者原有阶段的位置
    private const val DONE_FROM = "0000-00-00"  // 取"全部打勾记录"用的下界（按字符串比大小）

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

    data class Rows(
        val tasks: List<Task> = emptyList(),
        val steps: List<StudyStep> = emptyList(),
        val resources: List<Resource> = emptyList(),
    )

    data class Decision(val tombstone: List<Int>, val deleteLocal: List<Int>)

    // ------------------------------------------------------------------ 纯函数

    /** 块名去掉括号后缀：`数学分析（I）` → `数学分析`（课表里就叫这个）。 */
    fun keyBase(k: String): String = k.replace(Regex("[（(].*?[)）]"), "").trim()

    /**
     * 这门课该用哪一块内置模板？
     *
     * 模板里的 `match` 是空的（JSON 里就是 null），所以按**块名与课名互相包含**认：
     * 课表叫「数学分析」而块叫「数学分析（I）」→ 认得上；「军事理论导论」谁也不像 → generic。
     */
    fun matchCourse(tpl: Templates, courseName: String): String? {
        val n = courseName.replace(" ", "").lowercase()
        if (n.isEmpty()) return null
        return tpl.courses.entries.firstOrNull { (k, b) ->
            val keys = (b.match + keyBase(k)).map { it.replace(" ", "").lowercase() }.filter { it.isNotEmpty() }
            keys.any { n.contains(it) || it.contains(n) }
        }?.key
    }

    /**
     * 生成"每门课一套默认任务" + generic 一次性块（认不出的课整块只来一次 ——
     * generic 里是「问教务老师确认方向选择规则」这种一次性手续，按课重复会变成几十条噪音）。
     */
    fun build(tpl: Templates, courses: List<Pair<Int, String>>): Rows {
        val tasks = mutableListOf<Task>()
        val steps = mutableListOf<StudyStep>()
        val resources = mutableListOf<Resource>()
        var seg = 0

        fun put(block: ModulePack, tag: String) {
            val base = ID_BASE + seg * SPAN
            seg++
            block.tasks.forEachIndexed { ti, t ->
                val taskId = base + ti
                tasks += Task(
                    id = taskId,
                    phase = t.phase ?: PHASE,
                    phase_order = PHASE_ORDER,
                    title = t.title,
                    detail = t.detail?.let { "$it（内置模板 · $tag）" },
                    course_id = null,
                    track = t.track,
                    priority = t.priority,
                    cadence = t.cadence,
                    weekday = t.weekday,
                    deliverable = t.deliverable,
                    active = 1,
                    sort = ti + 1,
                )
                t.steps.forEachIndexed { si, s ->
                    steps += StudyStep(
                        id = base + ti * 100 + si, task_id = taskId, seq = s.seq,
                        text = s.text, minutes = s.minutes, kind = s.kind, done_day = null,
                    )
                }
                t.resources.forEachIndexed { ri, r ->
                    resources += Resource(
                        id = base + ti * 100 + ri, task_id = taskId, kind = r.kind,
                        title = r.title, url = r.url, source = r.source, why = r.why,
                        http = null, embed = r.embed, sort = ri,
                    )
                }
            }
        }

        val unmatched = mutableListOf<String>()
        courses.forEach { (_, name) ->
            val key = matchCourse(tpl, name)
            val block = key?.let { tpl.courses[it] }?.let {
                ModulePack(id = key, name = it.name, desc = it.desc, tasks = it.tasks)
            }
            if (block != null) put(block, key!!) else unmatched += name
        }
        // 一门课都没认出来时也要给点东西（否则"重置"完是一片空白，比不重置还糟）
        if (unmatched.isNotEmpty() || tasks.isEmpty()) put(tpl.generic, "通用")
        return Rows(tasks, steps, resources)
    }

    /** 哪些行打墓碑、哪些行真删。纯函数：给定现有任务，给决定。 */
    fun decide(existing: List<Task>): Decision = Decision(
        tombstone = existing.filter { it.id < SERVER_MAX }.map { it.id }.sorted(),
        deleteLocal = existing.filter { it.id >= SERVER_MAX }.map { it.id }.sorted(),
    )

    // ------------------------------------------------------------------ 备份

    @Serializable
    data class Snapshot(
        val at: String = "",
        val tasks: List<Task> = emptyList(),
        val steps: List<StudyStep> = emptyList(),
        val resources: List<Resource> = emptyList(),
        val done: List<TaskDone> = emptyList(),
        val modules: List<String> = emptyList(),
        /** 重置那一刻的计划来源（`remote` / `none`…）。恢复时要把它也放回去 ——
         *  否则重置完是空的、恢复完却还是空（来源没还原，界面照样不认）。 */
        val source: String = PlanApplier.REMOTE,
    ) {
        val isEmpty: Boolean get() = tasks.isEmpty() && done.isEmpty()
    }

    fun encode(s: Snapshot): String = json.encodeToString(Snapshot.serializer(), s)

    fun decode(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)

    // ------------------------------------------------------------------ 落库

    suspend fun snapshot(db: CampusDb, now: String): Snapshot {
        val dao = db.dao()
        val existing = dao.visibleTasks().first()
        return Snapshot(
            at = now,
            tasks = existing,
            steps = existing.flatMap { dao.stepsOfTask(it.id).first() },
            resources = existing.flatMap { dao.resourcesOfTask(it.id).first() },
            done = dao.doneSince(DONE_FROM),
            modules = Modules.picked(db),
            source = dao.metaGet(PlanApplier.K_SOURCE) ?: "",
        )
    }

    /**
     * 执行重置：备份 → 打墓碑/真删 → 打勾归零 → 铺模板任务 → 把用户挑过的模块包原样补回。
     * 返回**重置前**的备份（调用方负责存盘，用户后悔时用 [restore]）。
     */
    suspend fun apply(db: CampusDb, tpl: Templates, courses: List<Pair<Int, String>>, now: String): Snapshot {
        val dao = db.dao()
        val snap = snapshot(db, now)
        val existing = snap.tasks
        val d = decide(existing)

        d.tombstone.forEach { dao.markTaskDeleted(DeletedTask(task_id = it, at = now)) }

        if (d.deleteLocal.isNotEmpty()) {
            val localIds = d.deleteLocal.toSet()
            val gone = existing.filter { it.id in localIds }
            val st = gone.flatMap { dao.stepsOfTask(it.id).first() }.mapNotNull { it.id }
            val rs = gone.flatMap { dao.resourcesOfTask(it.id).first() }.mapNotNull { it.id }
            if (st.isNotEmpty()) dao.deleteSteps(st)
            if (rs.isNotEmpty()) dao.deleteResources(rs)
            dao.deleteTasks(d.deleteLocal)
        }

        dao.clearDone()                                     // 打勾归零

        // 这里以前会 build(tpl, courses) 铺一套**内置默认任务**（每门课一条 + generic 一次性块）。
        // 2026-09-19 按用户要求改掉：他说「重置后这里就应该没有任务了」——
        // 重置的语义是**清空**，不是"换成另一套任务"。
        // 连带两条纪律：
        //   · 不再补回模块包 —— 那也是任务，重置后应当一起消失；后悔了用 [restore] 整份还原；
        //   · 来源标成 NONE 而不是 REMOTE —— 标 REMOTE 界面就会把这套当"你自己的计划"显示，
        //     而且自动同步会立刻把服务端那份写回来，"清空"等于白清。
        dao.putMeta(listOf(Meta(PlanApplier.K_SOURCE, PlanApplier.NONE)))

        return snap
    }

    /** 恢复：把备份里的行放回去，并撤掉这次重置打的墓碑。 */
    suspend fun restore(db: CampusDb, s: Snapshot) {
        val dao = db.dao()
        s.tasks.filter { it.id < SERVER_MAX }.forEach { dao.undeleteTask(it.id) }
        if (s.tasks.isNotEmpty()) dao.putTasks(s.tasks)
        if (s.steps.isNotEmpty()) dao.putSteps(s.steps)
        if (s.resources.isNotEmpty()) dao.putResources(s.resources)
        dao.clearDone()
        s.done.forEach { dao.markDone(it.copy(id = 0)) }
        // 来源也要还原：重置把它改成了 NONE，不还原的话恢复完界面仍然认为"暂不要计划"，
        // 数据都在、却还显示空态（假失败）。
        dao.putMeta(listOf(Meta(PlanApplier.K_SOURCE, s.source.ifEmpty { PlanApplier.REMOTE })))
    }
}
