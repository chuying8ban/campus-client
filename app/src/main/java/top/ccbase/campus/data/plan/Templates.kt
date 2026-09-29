package top.ccbase.campus.data.plan

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task

/**
 * `plan_templates.json`：课程模板 + 11 个模块包 + 通用块。
 *
 * 这是"按课程补计划"的另一半：
 *  - `courses` —— 按课程名匹配（`数学分析（I）` → 讲师甲视频 + 同济同步练习）
 *  - `modules` —— 11 个可选模块包，**由用户自己挑**（系统不替他决定学什么）
 *  - `generic` —— 思政/军事/专业导航这类没有资源包的课，走通用块（如实说明"暂无资源包"）
 */
@Serializable
data class Templates(
    val version: Int = 1,
    val source: String? = null,
    val note: String? = null,
    val courses: Map<String, CourseBlock> = emptyMap(),
    val modules: List<ModulePack> = emptyList(),
    val generic: ModulePack = ModulePack(id = "generic", name = "通用"),
)

@Serializable
data class CourseBlock(
    val name: String = "",
    val desc: String? = null,
    val match: List<String> = emptyList(),
    val tasks: List<TplTask> = emptyList(),
)

@Serializable
data class ModulePack(
    /** generic 通用块在 JSON 里没有 id，所以给默认值 —— 它不是可挑的模块包 */
    val id: String = "",
    val name: String,
    val desc: String? = null,
    val tasks: List<TplTask> = emptyList(),
)

@Serializable
data class TplTask(
    val title: String,
    val detail: String? = null,
    val track: String? = null,
    val priority: Int = 2,
    val cadence: String? = null,
    val weekday: Int? = null,
    val deliverable: String? = null,
    val phase: String? = null,
    val steps: List<TplStep> = emptyList(),
    val resources: List<TplRes> = emptyList(),
)

@Serializable
data class TplStep(
    val seq: Int = 1,
    val text: String,
    val minutes: Int = 0,
    val kind: String? = null,
)

@Serializable
data class TplRes(
    val kind: String? = null,
    val title: String,
    val url: String,
    val source: String? = null,
    val why: String? = null,
    val embed: Int = 0,
)

object TplLoader {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /**
     * 进程内缓存一份模板。
     *
     * 为什么需要它：整体替换之后的「重放用户自己挑的东西」必须发生在
     * `PlanApplier.apply`（唯一出口），但 apply 的调用链里有的手里有 Context、
     * 有的（引导页 / 等待页）只有 db 和 token。模板是随包发布的**只读**资产，
     * 启动后读一次就够 —— 缓存掉这条路上的 Context 依赖，"哪条路忘了传模板"
     * 就不会再变成"用户挑的模块包被静默清掉"。
     */
    @Volatile
    private var cache: Templates? = null

    fun load(ctx: Context, asset: String = "plan_templates.json"): Templates {
        val text = ctx.assets.open(asset).use { it.readBytes().decodeToString() }
        // 显式标类型：`.also { cache = it }` 挂在 reified 泛型方法上时推导不出来（编译错）
        val t: Templates = json.decodeFromString(text)
        cache = t
        return t
    }

    /** 已缓存的模板；没有就是 null（**不去读资产** —— 没有 Context 时只能这样） */
    fun cached(): Templates? = cache

    /** 有缓存用缓存，没有就现读一次资产；读不出来返回 null（调用方自己决定怎么降级） */
    fun get(ctx: Context): Templates? = cache ?: runCatching { load(ctx) }.getOrNull()

    /** 丢掉缓存。用例要用它造"模板拿不到"的分支，别让结果依赖执行顺序 */
    fun clearCache() {
        cache = null
    }
}

/**
 * 把用户挑的模块包落进他自己的计划。
 *
 * 三条纪律：
 * ① **幂等** —— 已挑过的 id 记在 `meta['modules']`，重复调用不会重复插入任务。
 * ② **不碰用户记录** —— 只写 tasks / study_steps / resources。
 * ③ **id 用高段位**（100 万起）—— 不与服务端下发的行撞号；服务端每次同步会整体替换内容，
 *    挑过的模块靠调用方在同步后**重新应用**（meta 里的 id 列表不会被同步覆盖）。
 *
 * 模块是"用户自己挑的"，所以这里不做任何智能推荐 —— 系统只负责把他选的放进去。
 */
object Modules {

    const val KEY = "modules"
    const val ID_BASE = 1_000_000
    private const val SPAN = 10_000

    /**
     * 每个模块包占一段固定的 id。
     *
     * 用「模块 id 排序后的下标」而不是 JSON 里的顺序 —— 否则以后调整
     * plan_templates.json 里模块的排列，已挑过的模块会换 id，
     * 结果是删不掉旧行、还多出一份重复行。
     */
    fun baseFor(tpl: Templates, moduleId: String): Int {
        val sorted = tpl.modules.map { it.id }.sorted()
        val idx = sorted.indexOf(moduleId)
        return if (idx < 0) -1 else ID_BASE + idx * SPAN
    }

    data class Result(val addedTasks: Int, val addedSteps: Int, val addedResources: Int, val ids: List<String>)

    suspend fun picked(db: CampusDb): List<String> =
        (db.dao().metaGet(KEY) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun apply(db: CampusDb, tpl: Templates, ids: List<String>): Result {
        val dao = db.dao()
        val packs = ids.mapNotNull { id -> tpl.modules.firstOrNull { it.id == id } }
        if (packs.isEmpty()) return Result(0, 0, 0, picked(db))

        var tasks = 0
        var steps = 0
        var res = 0
        val newTasks = mutableListOf<Task>()
        val newSteps = mutableListOf<StudyStep>()
        val newRes = mutableListOf<Resource>()

        packs.forEach { pack ->
            val base = baseFor(tpl, pack.id)
            if (base < 0) return@forEach
            pack.tasks.forEachIndexed { ti, t ->
                val taskId = base + ti
                newTasks += Task(
                    id = taskId,
                    phase = t.phase ?: "本学期",
                    phase_order = 90,          // 排在作者原有阶段之后，不抢位置
                    title = t.title,
                    detail = t.detail,
                    course_id = null,
                    track = t.track,
                    priority = t.priority,
                    cadence = t.cadence,
                    weekday = t.weekday,
                    deliverable = t.deliverable,
                    active = 1,
                    sort = ti + 1,
                )
                tasks++
                t.steps.forEachIndexed { si, s ->
                    newSteps += StudyStep(
                        id = base + ti * 100 + si,
                        task_id = taskId,
                        seq = s.seq,
                        text = s.text,
                        minutes = s.minutes,
                        kind = s.kind,
                        done_day = null,
                    )
                    steps++
                }
                t.resources.forEachIndexed { ri, r ->
                    newRes += Resource(
                        id = base + ti * 100 + 50 + ri,
                        task_id = taskId,
                        kind = r.kind,
                        title = r.title,
                        url = r.url,
                        source = r.source,
                        why = r.why,
                        http = 200,
                        embed = r.embed,
                        sort = ri + 1,
                    )
                    res++
                }
            }
        }

        dao.putTasks(newTasks)
        if (newSteps.isNotEmpty()) dao.putSteps(newSteps)
        if (newRes.isNotEmpty()) dao.putResources(newRes)

        val merged = (picked(db) + ids).distinct()
        dao.putMeta(listOf(Meta(KEY, merged.joinToString(","))))
        return Result(tasks, steps, res, merged)
    }

    suspend fun remove(db: CampusDb, tpl: Templates, id: String) {
        // 只解除"挑选"标记并清掉该模块的任务（用户记录会变成悬空引用，安全）
        val pack = tpl.modules.firstOrNull { it.id == id } ?: return
        val base = baseFor(tpl, id)
        val dao = db.dao()
        if (base >= 0) {
            dao.deleteTasks((0 until pack.tasks.size).map { base + it })
            dao.deleteSteps((0 until pack.tasks.size * 100).map { base + it })
            dao.deleteResources((0 until pack.tasks.size * 100).map { base + it })
        }
        val left = picked(db).filter { it != id }
        dao.putMeta(listOf(Meta(KEY, left.joinToString(","))))
    }
}
