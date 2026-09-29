package top.ccbase.campus.domain

import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task

/**
 * 任务页的纯逻辑：分组、进度、文案。
 *
 * 抽成纯函数的理由同 Today.kt —— 本机没模拟器，界面看不到，
 * 但"哪些任务归哪个阶段、进度算得对不对"是能在 JVM 上真跑验证的。
 * 基准值取自 study.db 的真实统计（7 个阶段 14/3/5/5/4/4/4，见 TaskListLogicTest）。
 */

data class TaskProgress(val done: Int, val total: Int) {
    val ratio: Float get() = if (total == 0) 0f else done.toFloat() / total
    val text: String get() = if (total == 0) "" else "$done/$total 步"
}

data class TaskRow(
    val id: Int,
    val title: String,
    val detail: String?,
    val deliverable: String?,
    val track: String?,
    val cadence: String?,
    /** active=1 = 当前学期要做的（14 项）；active=0 = 后续阶段，还没轮到 */
    val active: Boolean,
    val progress: TaskProgress,
    /** 预计投入分钟数（该任务所有步骤之和），0 表示没排步骤 */
    val minutes: Int,
    val resourceCount: Int,
)

data class TaskSection(val phase: String, val rows: List<TaskRow>)

fun progressOf(steps: List<StudyStep>): TaskProgress =
    TaskProgress(done = steps.count { it.done_day != null }, total = steps.size)

/**
 * 按阶段（本学期 / 寒假 / 大一下 …）分组 —— 这是"路线图"的读法，
 * 比按类型分更接近你原本的排法。阶段内按 sort 排，保持你写的顺序。
 */
fun groupTasks(
    tasks: List<Task>,
    steps: List<StudyStep>,
    resources: List<Resource>,
): List<TaskSection> {
    val stepByTask = steps.groupBy { it.task_id }
    val resByTask = resources.groupBy { it.task_id }
    return tasks
        .sortedWith(compareBy({ it.phase_order }, { it.sort }, { it.id }))
        .groupBy { it.phase ?: "未分阶段" }
        .map { (phase, list) ->
            TaskSection(
                phase = phase,
                rows = list.map { t ->
                    val st = stepByTask[t.id].orEmpty()
                    TaskRow(
                        id = t.id,
                        title = t.title,
                        detail = t.detail,
                        deliverable = t.deliverable,
                        track = t.track,
                        cadence = t.cadence,
                        active = t.active == 1,
                        progress = progressOf(st),
                        minutes = st.sumOf { it.minutes ?: 0 },
                        resourceCount = resByTask[t.id]?.size ?: 0,
                    )
                }
                    // 「按时间和进度」：阶段先后由外层保证（时间），组内让**没完成的在前**、
                    // 同是未完成的**按进度从低到高**（0/4 → 1/4 → …），已完成的沉底；
                    // 最后一级回落到 id，保证每次渲染顺序稳定、不会自己跳来跳去。
                    .sortedWith(
                        compareBy(
                            { r -> if (r.progress.total > 0 && r.progress.done >= r.progress.total) 1 else 0 },
                            { r -> if (r.progress.total > 0) r.progress.done.toDouble() / r.progress.total else 0.0 },
                            { r -> r.id },
                        )
                    ),
            )
        }
        // 阶段顺序跟随任务自身的 phase_order，不靠字符串排序
        .sortedBy { sec -> tasks.filter { it.phase == sec.phase }.minOf { it.phase_order } }
}

/** 步骤类型 → 给用户看的词（不要直接把 watch/practice 这种内部值显示出来） */
fun stepKindLabel(kind: String?): String = when (kind) {
    "watch" -> "看"
    "practice" -> "练"
    "read" -> "读"
    "write" -> "写"
    "do" -> "做"
    "check" -> "查"
    // 服务端词表（study-app/load_steps.py 头注释）：watch 看 / read 读 / practice 练 / produce 产出。
    // 漏了 produce → 数学分析（I）那些"抄错题本/产出 .c 文件"的步骤在界面上直接显示英文，2026-09-18 我从
    // 真机数据里摊出来才发现（种子里有 32 条 produce）。词表要对齐服务端，不能各写一套。
    "produce" -> "产出"
    null, "" -> ""
    else -> kind
}

/** 资源类型 → 图标位的一句话（用文字，不引图标资源） */
fun resourceKindLabel(kind: String?): String = when (kind) {
    "video" -> "视频"
    "doc" -> "文档"
    "practice" -> "练习"
    "course" -> "课程"
    null, "" -> "资源"
    else -> kind
}

/**
 * 第一屏（今日页）只露**一条**资源时，露哪一条？
 *
 * 顺序：视频 > 课程 > 练习 > 文档 > 书 > 其他。
 * 依据是用户的原话——「同学大多是没有用的笔记，却很少有有用的视频课程」：
 * 既然最缺的是"能看的课"，列表里就先给视频。
 *
 * 两条硬规矩：
 *  ① **没有链接的不算**（url 空/空白一律跳过）：画出来点不动的行，用户只会觉得界面坏了；
 *  ② 认不出的 kind 仍然可以用（排最后，但别丢）——数据演进时宁可多露一条，也别空着。
 */
fun pickTopRes(list: List<Resource>): Resource? {
    val order = listOf("video", "course", "practice", "doc", "book")
    fun rank(kind: String?): Int {
        val i = order.indexOf(kind ?: "")
        return if (i < 0) order.size else i
    }
    return list
        .filter { !it.url.isNullOrBlank() }
        .minWithOrNull(compareBy({ rank(it.kind) }, { it.sort }, { it.id }))
}

/** 分钟数说人话：90 → "1.5 小时" */
fun minutesText(minutes: Int): String = when {
    minutes <= 0 -> ""
    minutes < 60 -> "$minutes 分钟"
    else -> {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) "$h 小时" else "$h 小时 $m 分"
    }
}
