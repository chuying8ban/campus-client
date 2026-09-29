package top.ccbase.campus.data.library

import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.net.Catalog
import top.ccbase.campus.net.CatalogCourse
import top.ccbase.campus.net.CatalogItem

/**
 * 「学习库」：把公共资源目录里**用户自己挑的**条目，落成本机的一条学习任务。
 *
 * ## 为什么不是"再做一个只读的资源页"
 *
 * 用户拍板的落地方式：**给这门课建一条学习任务，把相中的资料挂在这条任务上**。
 * 这样步骤 / 进度 / 详情页三样都不用重写 —— 挑中的资料就是这条任务的"学习步骤"，
 * 在「学习」页里跟别的任务一样推进度、能打勾。
 *
 * ## 为什么要有这个文件（而不是在界面里直接写库）
 *
 * 两个最容易在后续改动里被破坏、又最难在界面上看出来的规矩：
 *   ① **同一门课永远只有一条任务** —— 再挑几条资料是"加进那条"，不是"再来一条"；
 *   ② **同一条链接不许加两遍** —— 加两遍就是两张一模一样的卡片。
 * 所以规则的实现放在纯函数里（[rows] / [merge]），用例给一组输入就能断言，
 * 不用起界面、不用起数据库。
 *
 * ## id 分段（本项目反复踩过的坑）
 *
 * 服务端每次同步会 `Content.replace` **整体替换**内容表：不在它那份计划里的行会被
 * `prune*` 删掉。所以本机自己生成的行必须占**服务端碰不到的高段位**：
 *
 * | 段 | 归属 |
 * |---|---|
 * | `< 1_000_000` | 服务端下发 |
 * | `1_000_000+` | 内置模块包（[top.ccbase.campus.data.plan.Modules]） |
 * | `2_000_000+` | 内置模板默认任务（[top.ccbase.campus.data.plan.ResetPlan]） |
 * | `3_000_000+` | **学习库任务**（本文件；＝ 3_000_000 + 课程 id） |
 * | `4_000_000+` | **学习库步骤** |
 * | `5_000_000+` | **学习库资料** |
 *
 * 任务 id 直接取「段首 + 课程 id」：课程 id 唯一 → 任务 id 唯一，
 * 而且**不需要额外记一份"课 → 槽位"的映射**（任何需要映射的方案，一旦映射表丢了
 * 就会给同一门课再建一条任务）。认不出课程的条目归到自学桶（课程 id 用 0）。
 */
object Library {

    /** 挑选记录（整份 JSON 存在 meta 里）。服务端同步不会覆盖 meta，所以它是可靠的重放源。 */
    const val KEY_PICKS = "library_picks"

    /** 下一个可用的步骤/资料序号；只用来自增，不用来推断"加了多少条" */
    const val KEY_SEQ = "library_seq"

    const val TASK_BASE = 3_000_000
    const val STEP_BASE = 4_000_000
    const val RES_BASE = 5_000_000

    /** 学习库任务所在的阶段名。独立一组，不跟"本学期"的任务混在一起。 */
    const val PHASE = "学习库"

    /** 认不出课程的条目归到这个桶 —— 与服务端 `catalog.SELF_STUDY` 逐字一致 */
    const val SELF_KEY = "自学 / 技能包"

    private const val PHASE_ORDER = 95          // 排在其它阶段之后（作者数据的最大阶段在 90 上下）
    private const val TASK_TRACK = "自学"        // 「学习」页的分类筛选按它归类

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 显式 serializer 形式（与 ResetPlan 一致）：省掉对 reified 扩展 import 的依赖，
    // 那种 import 在不同 kotlinx.serialization 版本里位置不一样，写错就是编译错
    private val listSer = ListSerializer(LibPick.serializer())

    @Serializable
    data class LibPick(
        val url: String,
        val title: String,
        val kind: String = "",
        val source: String = "",
        /** 课程显示名（服务端给的那个，可能是"自学 / 技能包"） */
        val course: String = "",
        /** 本机 `courses.id`；null = 认不出课程 → 自学桶 */
        @SerialName("course_id") val courseId: Int? = null,
        val note: String = "",
        /** 落库用的 id：生成一次就固定住，重放时原样写回（打勾记录挂在它是上） */
        @SerialName("step_id") val stepId: Int = 0,
        @SerialName("res_id") val resId: Int = 0,
    )

    data class Rows(val tasks: List<Task>, val steps: List<StudyStep>, val resources: List<Resource>)

    // ------------------------------------------------------------ 纯逻辑

    /** 任务 id：段首 + 课程 id（认不出课程 → 0，即自学桶）。 */
    fun taskIdFor(courseId: Int?): Int = TASK_BASE + (courseId ?: 0)

    /** 这条任务是不是学习库生成的本机任务（界面用它决定"来源为空时也显示"）。 */
    fun isLibraryTask(id: Int): Boolean = id >= TASK_BASE && id < STEP_BASE

    fun taskTitle(course: String, count: Int): String {
        val c = course.trim().ifEmpty { SELF_KEY }
        return "$c · 精选资料（$count 条）"
    }

    /** 目录条目 → 待落库的挑选项（分配 id 由 [merge] 做，这里只管字段搬运）。 */
    fun pickOf(item: CatalogItem, courseId: Int?, note: String = item.note): LibPick = LibPick(
        url = item.url.trim(),
        title = item.title.trim(),
        kind = item.kind.trim(),
        source = item.source.trim(),
        course = item.course.trim().ifEmpty { SELF_KEY },
        courseId = courseId,
        note = note,
    )

    /**
     * 目录里的课程名 → 本机 `courses.id`。
     *
     * 认不出返回 null（归自学桶）。**双向 contains** 是必要的：目录里的名字来自
     * 别的同学的课表，全角/半角括号、后缀的有无都可能差一点
     * （`数学分析（I）` ↔ `数学分析`），逐字相等会大面积认不出，
     * 于是所有资料都掉进自学桶 —— 界面看着没错，只是"按课程"这一列变得没意义。
     */
    fun matchCourse(courses: List<Course>, name: String): Int? {
        val n = norm(name)
        if (n.isEmpty() || n == norm(SELF_KEY)) return null
        courses.firstOrNull { norm(it.name) == n }?.let { return it.id }
        return courses.firstOrNull { loose(it.name, name) }?.id
    }

    /** 宽松同名：抹平括号/空格/大小写之后互相包含（`数学分析` ↔ `数学分析（I）`）。 */
    private fun loose(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    // -------------------------------------------- 「按课程」那一列（课程列表 + 课程详情）
    //
    // 用户原话：「我希望用户可以在学习库中查看每个课程的详细内容」。所以这一列要回答两件事：
    //   ① 我有哪些课、每门课攒了多少条资料（全班还没有资料的课**也要列出来**）；
    //   ② 点进去能看到这门课本身（教师 / 学分 / 周次 / 上课时间地点）和它的资料（按类型分好组）。
    // 全是纯函数：界面只负责画，规则在用例里钉住 —— 分组这种"看着没错其实丢了东西"的错，
    // 只有在纯函数上才测得动。

    /** 四种已知类型的固定顺序；未知值一律归 `other`（与服务端 `catalog.kind_key` 同一套取值）。 */
    private val KIND_ORDER = listOf("video", "doc", "course", "practice")

    // ------------------------------------------------------------------ 搜索
    // 纯函数放这儿而不是写在界面里：搜索规则要能被单测钉住 —— 尤其是
    // 「全角/半角括号、大小写、空格」这些中文课程名上天天出事的细节（复用同一套 [norm]）。

    /** 这条资料搜得到吗：标题 / 来源 / 课程 / 链接 任一含搜索词就算命中。 */
    fun hitItem(it: CatalogItem, q: String): Boolean {
        val s = norm(q)
        if (s.isEmpty()) return true
        return norm(it.title).contains(s) || norm(it.source).contains(s) ||
            norm(it.course).contains(s) || norm(it.url).contains(s)
    }

    fun searchItems(items: List<CatalogItem>, q: String): List<CatalogItem> =
        if (norm(q).isEmpty()) items else items.filter { hitItem(it, q) }

    /** 课程行搜得到吗：课名 / 目录名 / 教师。 */
    fun searchCourses(rows: List<CourseRow>, q: String): List<CourseRow> {
        val s = norm(q)
        if (s.isEmpty()) return rows
        return rows.filter {
            norm(it.name).contains(s) || norm(it.catalogName).contains(s) ||
                norm(it.course?.teacher.orEmpty()).contains(s)
        }
    }

    /** 「按课程」那一列按搜索词过滤：三块都过一遍（过滤后空的那块就不渲染了）。 */
    fun searchCourseList(l: CourseList, q: String): CourseList {
        if (norm(q).isEmpty()) return l
        return CourseList(
            body = searchCourses(l.body, q),
            empty = searchCourses(l.empty, q),
            self = l.self?.takeIf { searchCourses(listOf(it), q).isNotEmpty() },
        )
    }

    fun kindKey(kind: String): String = kind.trim().lowercase().takeIf { it in KIND_ORDER } ?: "other"

    fun kindLabelOf(kind: String): String = when (kindKey(kind)) {
        "video" -> "视频"
        "doc" -> "文档"
        "course" -> "慕课"
        "practice" -> "练习"
        else -> "资料"
    }

    /** 一门课的资料按类型分好的组（空组不占位置：没有练习就别画一行「练习 0」）。 */
    data class KindGroup(val key: String, val label: String, val items: List<CatalogItem>)

    fun kindGroups(items: List<CatalogItem>): List<KindGroup> {
        val buckets = LinkedHashMap<String, MutableList<CatalogItem>>()
        for (i in items) buckets.getOrPut(kindKey(i.kind)) { mutableListOf() } += i
        // 顺序必须确定（KIND_ORDER 而不是 map 的迭代顺序），否则同一份数据两次渲染不一样
        return (KIND_ORDER + "other").filter { buckets.containsKey(it) }
            .map { KindGroup(it, kindLabelOf(it), buckets.getValue(it)) }
    }

    /**
     * 课程列表的一行。
     *
     * @param name 显示名 —— 本机课表里的名字优先（用户认的是自己课表上那个）
     * @param catalogName 目录里的课名；[itemsOf] 用它取资料（两边可能差一个全角括号）
     * @param course 本机 `courses` 行；null = 自学桶、或只存在于目录里的课（没有课程信息可显示）
     */
    data class CourseRow(
        val name: String,
        val catalogName: String,
        val count: Int,
        val course: Course?,
        /**
         * 学习库里这门课的**公开信息**（几个人在学 / 教师 / 学分）。
         *
         * 别人在学、我还没加入的课也有它 —— 详情页就靠它显示课程信息；
         * null = 自学桶（它不是一门课）。
         */
        val shared: CatalogCourse? = null,
    )

    /**
     * 「按课程」那一列的完整内容。
     *
     * 分三块是有意的：
     *   · [body] 有资料的课（条数降序）；
     *   · [empty] 我课表里有、全班还一条没攒的课 —— **也列出来**：用户要的是"每个课程"都能
     *     进去看，藏起来他会以为漏了自己的课；
     *   · [self] 自学桶永远排最后（它是兜底，不是一门课）。
     */
    data class CourseList(val body: List<CourseRow>, val empty: List<CourseRow>, val self: CourseRow?)

    fun courseRows(cat: Catalog, courses: List<Course>): CourseList {
        // 条数以 items 为准：`cat.courses` 只是服务端顺手给的汇总，两边万一不一致，
        // 以"真的能点开的东西"为准（列表上数出来的条数必须和点进去看到的一致）。
        val counts = LinkedHashMap<String, Int>()
        for (i in cat.items) {
            val n = i.course.trim().ifEmpty { SELF_KEY }
            counts[n] = (counts[n] ?: 0) + 1
        }
        // 学习库给的公开信息按课名索引（宽松匹配，理由同 norm 的注释）——
        // 详情页对"不是我的课"就靠它显示教师/学分/几个人在学
        val sharedByName = cat.courses.associateBy { norm(it.name) }
        val claimed = HashSet<String>()
        val rows = ArrayList<CourseRow>()
        // 认领目录课：**先整名相等，再宽松匹配**。两趟是必要的 —— 一趟到底的话
        // `数学分析` 会先把 `数学分析（I）` 认领走，真正同名的那门课反而变成"暂无资料"。
        // 而且一门目录课只许被一门本机课认领：同一批资料挂在两门课上，用户点哪门都看到同一批。
        val owner = HashSet<Int>()
        for (exact in listOf(true, false)) {
            for (c in courses) {
                if (c.id in owner) continue
                val hit = counts.keys.firstOrNull {
                    it != SELF_KEY && it !in claimed &&
                        (if (exact) norm(it) == norm(c.name) else loose(it, c.name))
                } ?: continue
                claimed += hit
                owner += c.id
                rows += CourseRow(c.name, hit, counts[hit] ?: 0, c, sharedByName[norm(hit)])
            }
        }
        for (c in courses) {
            if (rows.none { it.course?.id == c.id }) {
                rows += CourseRow(c.name, c.name, 0, c, sharedByName[norm(c.name)])
            }
        }
        // 顺序按课表（courses 是 ORDER BY sort, id），认领结果不影响行的先后
        rows.sortBy { r -> courses.indexOfFirst { it.id == r.course?.id } }
        // 目录里有、我课表里认不出的课（别的班的 / 改过名的）—— 一个都不许丢，否则那门
        // 有资料的课在列表里凭空消失，用户根本点不到同学探过的那几条链接
        for ((n, cnt) in counts) {
            if (n == SELF_KEY || n in claimed) continue
            rows += CourseRow(n, n, cnt, null, sharedByName[norm(n)])
        }
        val body = rows.filter { it.count > 0 }
            .sortedWith(compareByDescending<CourseRow> { it.count }.thenBy { it.name })
        val empty = rows.filter { it.count == 0 }
        val selfCount = counts[SELF_KEY] ?: 0
        return CourseList(body, empty, if (selfCount > 0) CourseRow(SELF_KEY, SELF_KEY, selfCount, null) else null)
    }

    /** 这一行对应的资料（按**目录里的**课名取 —— 拿本机课名去比会漏掉括号不同的那门课）。 */
    fun itemsOf(cat: Catalog, row: CourseRow): List<CatalogItem> =
        cat.items.filter { it.course.trim().ifEmpty { SELF_KEY } == row.catalogName }

    /**
     * 比对课程名之前先抹平这三种差异：全角/半角括号、空格、大小写。
     *
     * 目录里的课程名来自**别的同学**的课表，本机课程名来自自己的计划 —— 两边同源时一模一样，
     * 但只要有一边把 `程序设计基础(B)` 写成 `程序设计基础（B）`，逐字比较就全军覆没，
     * 于是所有资料都掉进自学桶（界面上看着都对，只是"按课程"这一列变得没意义）。
     */
    internal fun norm(s: String): String = s.trim()
        .replace('（', '(').replace('）', ')')
        .replace('\u3000', ' ')
        .replace(" ", "")
        .lowercase()

    /**
     * 合并新挑的条目：分配 id、按（任务 × url）去重。
     *
     * @return 合并后的完整清单（顺序不变，新条目追加在后面）+ 新的序号游标
     */
    fun merge(existing: List<LibPick>, added: List<LibPick>, seq: Int): Pair<List<LibPick>, Int> {
        // 已经有的（按任务分段）不许再加：同一个链接加两遍就是两张一样的卡片
        val used = existing.groupBy { taskIdFor(it.courseId) }
            .mapValues { (_, v) -> v.map { it.url }.toMutableSet() }
            .toMutableMap()

        var n = if (seq <= 0) 0 else seq
        val fresh = ArrayList<LibPick>(added.size)
        for (p in added) {
            if (p.url.isEmpty()) continue
            val slot = taskIdFor(p.courseId)
            val seen = used.getOrPut(slot) { mutableSetOf() }
            if (!seen.add(p.url)) continue
            // 同一条重复调用 merge（比如连点两次"加入"）也只会分配一次 id
            fresh += p.copy(stepId = STEP_BASE + n, resId = RES_BASE + n)
            n++
        }
        return (existing + fresh) to n
    }

    /**
     * 挑选项 → 要写进库的行。
     *
     * @param done 已有的打勾记录（步骤 id → done_day）。**必须传进来**：
     *   `putSteps` 是 REPLACE，重放时不带上它，用户打过勾的步骤会被悄悄清成未完成
     *   —— 而他只是改了个课表。
     */
    fun rows(picks: List<LibPick>, done: Map<Int, String?> = emptyMap(), knownCourses: Set<Int>? = null): Rows {
        val byTask = picks.groupBy { taskIdFor(it.courseId) }
        val tasks = ArrayList<Task>(byTask.size)
        val steps = ArrayList<StudyStep>(picks.size)
        val resources = ArrayList<Resource>(picks.size)

        var taskSort = 0
        for ((tid, items) in byTask) {
            val head = items.first()
            // 课被同步删掉时（转专业、退课、服务端重建计划）不能照原样写 course_id：
            // tasks.course_id 是指向 courses 的外键且 onDelete=CASCADE，
            // 指向不存在的课 → 插入直接抛外键失败 → 整个重放炸掉（同步也跟着挂）。
            // 降级成自学桶只是"分组挪了一下"，比炸掉好得多。
            val cid = head.courseId?.takeIf { knownCourses == null || it in knownCourses }
            tasks += Task(
                id = tid,
                phase = PHASE,
                phase_order = PHASE_ORDER,
                title = taskTitle(head.course, items.size),
                detail = "从「学习库」挑的资料，都是同学探过、能打开的链接",
                course_id = cid,
                track = TASK_TRACK,
                priority = 3,
                cadence = null,
                weekday = null,
                deliverable = null,
                active = 1,
                sort = taskSort++,
            )
            items.forEachIndexed { i, p ->
                steps += StudyStep(
                    id = p.stepId,
                    task_id = tid,
                    seq = i + 1,
                    text = p.title,
                    minutes = 0,
                    kind = stepKind(p.kind),
                    done_day = done[p.stepId],
                )
                resources += Resource(
                    id = p.resId,
                    task_id = tid,
                    kind = p.kind.ifEmpty { null },
                    title = p.title,
                    url = p.url,
                    source = p.source.ifEmpty { null },
                    // why 一律留空：这是别人的收藏汇总来的，"为什么推荐"属于个人语境，
                    // 详情页看到一条空 why 会跳过不画（1.68 起），不会出现半句解释。
                    why = null,
                    http = 200,
                    embed = 0,
                    sort = i,
                )
            }
        }
        return Rows(tasks, steps, resources)
    }

    /** 目录分类 → 步骤类型（步骤左侧那个"看/读/练"小标签）。 */
    fun stepKind(kind: String): String? = when (kind.trim()) {
        "video" -> "watch"
        "course" -> "watch"
        "practice" -> "practice"
        "doc" -> "read"
        else -> "read"
    }

    // ------------------------------------------------------------ 落库

    fun decode(raw: String?): List<LibPick> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSer, raw) }.getOrDefault(emptyList())
    }

    fun encode(picks: List<LibPick>): String = json.encodeToString(listSer, picks)

    suspend fun picks(db: CampusDb): List<LibPick> = decode(db.dao().metaGet(KEY_PICKS))

    /** 把整份挑选写回内容表（幂等：REPLACE，重复调用结果一样）。 */
    suspend fun apply(db: CampusDb, picks: List<LibPick>) {
        if (picks.isEmpty()) return
        val dao = db.dao()
        // 先读现有的打勾，再整体重写 —— 顺序不能反，否则刚写进去的 done_day 会被自己盖掉
        val done = dao.allSteps().first().associate { it.id to it.done_day }
        val known = dao.courses().first().map { it.id }.toSet()
        val r = rows(picks, done, known)
        dao.putTasks(r.tasks)
        dao.putSteps(r.steps)
        dao.putResources(r.resources)
    }

    /** 已经加进清单的链接 —— 界面靠它给条目打「已加入」标记（别让用户重复挑同一份资料）。 */
    fun pickedUrls(picks: List<LibPick>): Set<String> =
        picks.map { it.url.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * 移出学习库：**选错了能去掉**。
     *
     * 三件事必须一起做，少一件都是"点了没用"：
     *   ① 挑选记录里删掉这几条 —— 否则下次同步重放又把它们写回来，用户删了个寂寞
     *      （`meta['library_picks']` 才是重放源，光删库里的行挡不住重放）；
     *   ② 对应的步骤/资料行真删 —— 它们是本机生成的行，不是用户的记录，留着就是孤儿；
     *   ③ 整门课一条不剩时，任务行连同它的墓碑一起清掉 —— 否则用户以后又把这门课的资料
     *      加回来时，任务 id 还是那一个、被墓碑挡着，"加了却看不见"。
     *
     * @return 真正移出的条数（一条都没匹配上就返回 0，界面照实说，别谎报"已移出"）
     */
    suspend fun unpick(db: CampusDb, urls: Set<String>): Int {
        val clean = urls.map { it.trim() }.toSet()
        if (clean.isEmpty()) return 0
        val dao = db.dao()
        val all = picks(db)
        val gone = all.filter { it.url.trim() in clean }
        if (gone.isEmpty()) return 0
        val left = all.filterNot { it.url.trim() in clean }

        dao.deleteSteps(gone.map { it.stepId })
        dao.deleteResources(gone.map { it.resId })
        val stillUsed = left.map { taskIdFor(it.courseId) }.toSet()
        val emptied = gone.map { taskIdFor(it.courseId) }.filterNot { it in stillUsed }.distinct()
        if (emptied.isNotEmpty()) {
            dao.deleteTasks(emptied)
            dao.undeleteTasks(emptied)
        }
        // 还留着的那些要重写一遍：任务标题里的「（N 条）」得跟着变
        apply(db, left)
        dao.putMeta(listOf(Meta(KEY_PICKS, encode(left))))
        return gone.size
    }

    /**
     * 同步之后重放。
     *
     * 服务端每次落地计划都会整体替换内容表，把本机这两段（任务/步骤/资料）一起清掉 ——
     * 用户的感受是"我挑的东西一同步就没了"。挑选记录在 meta 里不会被覆盖，
     * 所以重放永远是安全且幂等的（与模块包 `Modules.apply` 同一套做法）。
     */
    suspend fun replay(db: CampusDb) {
        val p = picks(db)
        if (p.isNotEmpty()) apply(db, p)
    }

    /**
     * 加入学习库。
     *
     * @return 真正新增的条数（全是重复链接时返回 0 —— 界面照实说"都已经在你的清单里了"，
     *   不要报"已加入 3 条"却什么都没发生）
     */
    suspend fun add(db: CampusDb, added: List<LibPick>): Int {
        val dao = db.dao()
        val existing = picks(db)
        val seq = (dao.metaGet(KEY_SEQ) ?: "").toIntOrNull() ?: 0
        val (all, next) = merge(existing, added, seq)
        val grown = all.size - existing.size
        if (grown <= 0) return 0
        apply(db, all)
        // "重新加进来"就是用户明确要这条任务回来 —— 把它的墓碑撤掉。
        // 不撤的后果：任务行写进了库，却被墓碑挡着，用户回到「学习」页什么都没有，
        // 只会以为 App 坏了（他这次是主动要的，不是"删了又自己冒出来"）。
        dao.undeleteTasks(all.map { taskIdFor(it.courseId) }.distinct())
        dao.putMeta(
            listOf(
                Meta(KEY_PICKS, encode(all)),
                Meta(KEY_SEQ, next.toString()),
            )
        )
        return grown
    }
}
