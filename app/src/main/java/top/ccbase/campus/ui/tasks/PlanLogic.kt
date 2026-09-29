package top.ccbase.campus.ui.tasks

import top.ccbase.campus.net.PlanItem
import top.ccbase.campus.net.PlanPrefs
import top.ccbase.campus.net.PlanSubject

/**
 * 「AI 规划学习计划」这一页的取值与文案。
 *
 * 为什么单独拎一个对象：
 * ① 测试要断言"页面上到底写了什么"。把文案抄进测试里，等于给自己埋一颗雷 ——
 *    改一次按钮就得同时改测试，久而久之没人敢改文案，或者干脆把测试删了。
 *    这里放一份，测试引用常量，文案和测试一起动。
 * ② 取值必须**和服务端对齐**，不然后端 clamp/截断之后，界面显示的和落库的不是一回事：
 *    - focus / style：服务端 `_prefs()` 只做 strip + 切到 20 字 + 最多 6 项（不校验内容）
 *    - hours：服务端 clamp 到 1~40
 *    - level：服务端默认 "入门"
 */
object PlanLogic {

    /**
     * 方向 / 小时 / 形式 —— 取值**必须与服务端对齐**，不然后端 clamp/截断之后，
     * 界面显示的和真正落库的不是一回事。
     *
     * 小时档位尤其要注意：服务端把 hours 夹在 1~40，老界面却只给 2/5/10 还写着
     * 「服务端上限 40」—— 自相矛盾（用户 14:4x 就是拿这条截图的）。现在档位落到 40。
     */
    val FOCUS = listOf("编程", "数学", "英语", "竞赛", "写作")
    val HOURS = listOf(2, 5, 10, 15, 20, 30, 40)
    val STYLE = listOf("看视频", "做项目", "刷题", "读书")

    // ---- 新增分组：取值逐字抄服务端 ai_plan.GOALS / PERIODS / PACES（白名单外的值会被丢）----
    val GOALS = listOf("补弱", "冲绩点", "备四级", "竞赛", "考研预习", "兴趣拓展")
    val PERIODS = listOf("早", "午", "晚")
    val PACES = listOf("每天固定时长", "周末集中", "冲刺式")

    /**
     * 「为什么推荐它」这一行要不要画。
     *
     * 服务端有时给空串（缺 why 的条目）—— 直接画出来就是**一行空白 + 多出来的间距**，
     * 看着像界面坏了。空/纯空格/null 一律不画（用户对"占位没内容"特别反感）。
     */
    fun whyLine(why: String?): String? = why?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 计划页真正要显示的条目。
     *
     * ⚠️ 回的是 **[IndexedValue]** 而不是裸列表：勾选状态是按**原始下标**存的
     * （`on = i in picked`）。如果这里把空标题条目滤掉、外面还用新下标去比，
     * 勾选就会整体错位 —— 选中的是 A，加进清单的是 B。所以过滤必须带着原下标一起走。
     */
    fun visibleItems(items: List<PlanItem>): List<IndexedValue<PlanItem>> =
        items.withIndex().filter { it.value.title.isNotBlank() }
    val DAYS = listOf(1, 2, 3, 4, 5, 6, 7)

    /** 逐门课自评的档位（服务端 `SUBJECT_HINT` 原话：1 想加强 … 5 已掌握） */
    val SUBJECT_SCORES = listOf(1, 2, 3, 4, 5)
    const val SUBJECT_HINT = "1 想加强 … 5 已掌握"
    const val SUBJECT_LIMIT = 12        // 服务端 ai_plan.MAX_SUBJECTS
    const val SUBJECT_NAME_LIMIT = 20   // 服务端 name[:20]
    const val AVOID_LIMIT = 60          // 服务端 ai_plan.MAX_AVOID

    const val TITLE = "AI 规划学习计划"
    const val BACK = "← 返回"

    const val SECTION_FOCUS = "想加强的方向"
    const val SECTION_HOURS = "每周能投入多少时间"
    const val SECTION_STYLE = "偏好形式"
    const val SECTION_GOALS = "学习目标"
    const val SECTION_WHEN = "什么时候能学"
    const val SECTION_SUBJECTS = "逐门课自评"
    const val SECTION_PACE = "节奏偏好"
    const val SECTION_AVOID = "不想被安排的"

    // ---- 「重置学习任务」（现在收在页首「整理」层里）的文案：测试引用同一份，改字不会把测试改红 ----
    const val RESET_ENTRY = "重置学习任务"
    const val RESET_TITLE = "重置学习任务？"
    /**
     * 2026-09-19 改口径：原来写的是"换成内置模板那套默认任务（每门课一条）"，
     * 用户明确纠正「重置后这里就应该没有任务了」—— 重置 = **清空**，不是换成另一套任务。
     */
    const val RESET_BODY = "会清掉现在的任务和所有打勾记录；重置后这一页会空着，要计划再自己点「去生成 / 同步」。"
    const val RESET_BACKUP_NOTE = "重置前会自动存一份备份，页尾随后出现「恢复上次重置」，点错了能回来。"
    const val RESET_CONFIRM = "确定重置"
    const val RESET_CANCEL = "取消"
    const val RESET_UNDO = "恢复上次重置"
    const val RESET_BUSY = "正在重置…"
    /**
     * 页首右侧的「整理」入口。
     *
     * 为什么从页尾搬到这里：重置入口原先排在 47 项任务**之后**，等于藏起来了 ——
     * 用户上线后第一句反馈就是「我没在学习页面看到重置功能」。凡是"动整份清单"的操作
     * 都收进这一层，页首一眼可见，页尾也不用再多挂两行。
     */
    const val MORE_ENTRY = "整理"
    const val MORE_TITLE = "整理学习任务"
    /** 入口层的说明：一句话讲清会发生什么（不说原理） */
    const val RESET_HINT = "清空现在的任务和打勾，重置后这一页空着；想要计划再点「去生成 / 同步」"
    /** 只有真存过备份才出现 */
    const val UNDO_HINT = "把上一次重置前的任务和打勾都放回来"
    /**
     * 没有自己的计划时显示什么。
     *
     * 为什么宁可空着：随包的 `seed.json` 是**作者本人的**课表/任务/步骤/资源，
     * 谁还没同步到自己的计划，谁就会看到他的东西 —— 那就是串数据。
     * 规矩：自己的计划没拉到就空着，并说清为什么空、去哪儿拉。
     */
    const val EMPTY_TITLE = "还没有你的计划"
    const val EMPTY_BODY = "计划是按你自己的课表生成的。现在还没拉到，所以先空着 —— 不拿别人的数据凑。"
    const val EMPTY_ACTION = "去生成 / 同步"

    // ------------------------------------------------------------------
    // 选项文案：**每一项都要说清"选了会怎样影响推荐"**。
    //
    // 用户 2026-09-20 的原话：「把 AI 学习规划里面的选项做的易懂一些，要让用户知道
    // 自己的选择对课程的推荐到底有什么影响」。原来写的「影响推荐的形式」「影响推快推慢」
    // 等于没说 —— 他看不出选与不选差别在哪。
    //
    // 硬规矩：每句话都必须对得上服务端 `ai_plan.build_prompt` / `_pref_lines` 的真实行为
    // （那几项会被原样写成"我的目标 / 我的时间 / 科目侧重 / 节奏偏好 / 我不想要这些"，
    // 并要求模型"照着来、明确排除的不要出现"）。**不许编效果**，也不许承诺提分。
    // ------------------------------------------------------------------
    const val PREFS_NOTE = "这上面填的会原样写进给模型的要求里，它照着出建议。"
    const val GOALS_HINT = "选中就写进要求：任务围绕这些目标来（可多选；一个都不选＝不限方向）"
    const val FOCUS_HINT = "选了就往这个方向多推几条（可多选；不选＝不特别加强哪一门）"
    const val HOURS_HINT = "告诉模型每周能投入几小时，总量按它摊：填 2 小时就是两三个小任务，填 20 小时会排满得多（上限 40）"
    const val WHEN_HINT = "只把任务排在这些时段（可多选；不选＝全周都可以）"
    const val DAYS_HINT = "一周排几天：任务摊成这几天（不点＝由模型自己定）"
    const val SUBJECTS_HINT = "给每门课打分（1 想加强 … 5 已掌握）：分数低的课多推、分数高的少推。没评的课不参与，不用全填"
    const val STYLE_HINT = "选中的形式优先推荐（可多选；不选＝形式不限）"
    const val PACE_HINT = "任务怎么摊开：每天固定时长＝每天一小块；周末集中＝工作日少、周末成块；冲刺式＝平时轻、临近考试集中"
    const val AVOID_HINT = "写进来的不推荐，时段和内容都行。例：周日别排、别给我推英语听力"
    const val COURSES_EMPTY = "课表里还没有课；先去抓一次课表，这里就能逐门评"

    /** 「你的选择 → 会怎样推荐」预览块的无障碍说明（也是测试找它的抓手） */
    const val EFFECT_DESC = "选择影响预览"

    /**
     * 「你的选择 → 会怎样推荐」的一句话预览（纯函数，测试直接打这一层）。
     *
     * 为什么光有每组提示还不够：他填七八项之后需要**一眼看到合起来**是什么效果。
     * 这里只做**翻译**（把填过的值翻成人话），不承诺效果、不自己编偏好；
     * 一项都没填 → 返回 null，界面就不显示这块（免得摆一坨空话）。
     */
    fun effectSummary(
        focus: List<String> = emptyList(),
        hours: Int = 0,
        style: List<String> = emptyList(),
        goals: List<String> = emptyList(),
        periods: List<String> = emptyList(),
        daysPerWeek: Int = 0,
        scored: List<Pair<String, Int>> = emptyList(),
        pace: String = "",
        avoid: String = "",
    ): String? {
        val parts = mutableListOf<String>()
        if (goals.isNotEmpty()) parts += "围绕「${goals.joinToString("、")}」"
        if (focus.isNotEmpty()) parts += "多推${focus.joinToString("、")}"
        if (hours > 0) parts += "每周约 $hours 小时的量"
        if (periods.isNotEmpty() || daysPerWeek > 0) {
            val bit = mutableListOf<String>()
            if (periods.isNotEmpty()) bit += "只排在「${periods.joinToString("、")}」"
            if (daysPerWeek > 0) bit += "一周 $daysPerWeek 天"
            parts += bit.joinToString("、")
        }
        if (style.isNotEmpty()) parts += "优先${style.joinToString("、")}的形式"
        if (pace.isNotBlank()) parts += "按「$pace」摊"
        // 自评：只列评过的课，分数低的写"多推"、高的写"少推"，中间档不写方向（服务端也是软的侧重）
        val down = scored.filter { it.second <= 2 }.map { it.first }
        val up = scored.filter { it.second >= 4 }.map { it.first }
        if (down.isNotEmpty()) parts += "${down.joinToString("、")}多推"
        if (up.isNotEmpty()) parts += "${up.joinToString("、")}少推"
        val av = avoid.trim()
        if (av.isNotEmpty()) parts += "不出现「$av」"
        if (parts.isEmpty()) return null
        return "会这样推荐：" + parts.joinToString("，") + "。"
    }

    /** 逐门课自评每个档位按钮的无障碍说明（也是测试点按的抓手：课名+档位唯一） */
    fun scoreDesc(course: String, score: Int): String = "$course 自评 $score"

    /** 排除项输入框的无障碍说明 */
    const val AVOID_FIELD_DESC = "排除项输入"

    const val GENERATE = "生成建议"
    const val GENERATING = "正在按你的课表和偏好出建议…（一般 20 秒内；等太久会自动再试一次，别退出）"
    const val APPLY = "加入任务清单"
    const val APPLYING = "正在加入任务清单…"
    const val REFRESHING = "正在刷新本机任务清单…"

    // ---- 「加入清单」之后的可见反馈 ----
    // 用户原话：「把推荐的课程加入任务清单要有交互感，让用户知道课程真的加入清单了」。
    // 光弹一句「已加入」不算交互 —— 所以：按钮换样子、逐条打标、再给一个跳去清单的入口。
    const val APPLIED_BADGE = "已加入 ✓"
    const val APPLY_DONE = "已经加进清单了"
    const val GO_TASKS = "去「学习」清单看看 →"

    /**
     * 加入按钮上写什么 —— 三种态分开，别让用户猜。
     *  `step` 非空 = 正在忙（生成/加入/刷新），照实显示在按钮上；
     *  [appliedCount] > 0 = 这一批已经进去了，按钮不能再写着「加入任务清单」。
     */
    fun applyLabel(step: String, appliedCount: Int): String = when {
        step.isNotBlank() -> step
        appliedCount > 0 -> "$APPLY_DONE（$appliedCount 条）"
        else -> APPLY
    }
    const val RETRY_REFRESH = "重试刷新本机清单"

    const val UNDO = "撤销本次加入"
    const val UNDO_ASK = "撤销这次加入的任务？"
    const val UNDO_YES = "确认撤销"
    const val CANCEL = "取消"

    /** 每条建议都必须写清"为什么推荐它" —— 服务端专门算好 why 一起回来的 */
    const val WHY_PREFIX = "为什么推荐："
    /** 服务端没给 why 时也这么写（`normalize_items` 里 why 允许为空）—— 不许自己编一句凑上 */
    const val WHY_EMPTY = "服务端这次没给理由"

    const val PICKED = "✓ 已选"
    const val UNPICKED = "未选"
    const val NO_ITEMS = "服务端这次没给出任何建议"

    /**
     * 页头那句实话。**不许写效果承诺**（"保证提分""一周见效"这类一律不许）。
     * 这条有测试钉着：改文案前先看 PlanScreenTest。
     */
    const val NOTE = "按你的课表和偏好给几条建议，你自己挑；效果不承诺，不合适就撤销。"

    /** 撤销按钮旁边那行解释：它到底会删掉什么。二次确认层里原样再讲一遍。 */
    const val UNDO_BODY =
        "只会删掉通过「AI 规划」加入的任务，你自己建的任务和课表带来的任务都不动。"

    /** 服务端 `ai_plan.PHASE`：AI 新增的任务都挂在这个阶段下。客户端只用它来数本地条数。 */
    const val PHASE_AI = "AI 规划"

    /**
     * 「学习」标签页顶部那个入口的说明。
     *
     * 必须说清两件事：① 它给的是**建议**不是自动改计划；② 加不加由你自己挑。
     * 用户明确反感"藏起来的入口"，所以入口配一句实话摆在最上面 —— 文案放这里，
     * 测试引用常量，改文案不用到处翻。
     */
    const val ENTRY_HINT = "按你的课表和偏好给几条建议，你自己勾选后加进任务清单；不合适可以撤销。"

    // ------------------------------------------------ 表单 → 入参（纯函数，测试直接打这一层）

    /**
     * 逐门课自评的行。
     *
     * 课程名**只从本地课表来**，绝不写死任何课程名：课表会变（学生重抓、教务加课），
     * 写死的名字服务端一门都匹配不上。去空、去重（同名课分班/分老师很常见）、
     * 截到服务端上限，顺序跟随课表。
     */
    fun subjectNames(names: List<String>): List<String> =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(SUBJECT_LIMIT)

    /**
     * 排除项：空白压成单空格 + 截到服务端上限。
     *
     * 界面里显示的必须**就是**发出去的那份 —— 让他看到 80 个字、实际只发 60 个，等于骗他。
     * 口径与服务端一致（`" ".join(avoid.split())` 再 [:60]）。
     */
    fun avoidText(raw: String): String =
        raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(AVOID_LIMIT)

    /** 小时：夹到服务端同一个区间（1~40），界面不会给出服务端算不出来的数 */
    fun clampHours(h: Int): Int = h.coerceIn(1, 40)

    fun daysLabel(d: Int): String = "每周 $d 天"

    /**
     * 组装 `/plan/suggest` 的入参。
     *
     * 纯函数（不碰网络、不碰界面），契约测试直接打它。**没填的分组一律 null**：
     * 编码器 explicitNulls = false 会把 null 整个省掉，于是老服务端收到的还是老 4 个字段。
     * 逐门课只带**用户真的评过**的那些（不动默认就是"不参与"），不往里塞一堆中间档当噪音。
     */
    fun prefs(
        focus: Collection<String> = emptyList(),
        hours: Int = HOURS[1],
        style: Collection<String> = emptyList(),
        goals: Collection<String> = emptyList(),
        periods: Collection<String> = emptyList(),
        daysPerWeek: Int = 0,
        scores: Map<String, Int> = emptyMap(),
        pace: String = "",
        avoid: String = "",
        level: String = "入门",
    ): PlanPrefs = PlanPrefs(
        focus = focus.filter { it in FOCUS },
        hours = clampHours(hours),
        style = style.filter { it in STYLE },
        level = level,
        goals = goals.filter { it in GOALS }.takeIf { it.isNotEmpty() },
        periods = periods.filter { it in PERIODS }.takeIf { it.isNotEmpty() },
        daysPerWeek = daysPerWeek.takeIf { it in 1..7 },
        subjects = scores.entries
            .filter { (n, s) -> n.isNotBlank() && s in SUBJECT_SCORES }
            .map { (n, s) -> PlanSubject(n.trim().take(SUBJECT_NAME_LIMIT), s) }
            .take(SUBJECT_LIMIT)
            .takeIf { it.isNotEmpty() },
        pace = pace.takeIf { it in PACES },
        avoid = avoidText(avoid).takeIf { it.isNotEmpty() },
    )

    fun hoursLabel(h: Int): String = "每周 $h 小时"

    fun pickedCount(taken: Int, total: Int): String = "已选 $taken / $total 条"

    /**
     * 这次建议是**怎么来的**。必须照实说 —— 一律标成"AI 推荐"就是在骗人：
     *   ai       → 模型给的，不用额外说明
     *   cache    → 服务端拿同一套偏好的缓存（没重复花钱）
     *   fallback → 模型没调通，服务端按课表推的保守版（服务端注释：宁可朴素，不要假装聪明）
     */
    fun sourceNote(source: String, reason: String): String = when (source) {
        "cache" -> "这份建议是同一套偏好下出过的，服务端直接给了缓存（没有重复调用模型）。"
        "fallback" -> "这次模型没给成建议（服务端原话：${reason.ifBlank { "没说原因" }}）；" +
            "下面是服务端按你课表推的保守版本，不是模型给的。"
        else -> ""
    }

    // ---- 建议条目挂的资源（视频 / 课程 / …）—— 选的时候就要看得见 ----

    /** 资源行那个「打开 ↗」：只在这里写一次，测试也拿它当抓手 */
    const val RES_OPEN = "打开 ↗"

    /**
     * 资源类型的文字前缀。
     *
     * 为什么认不出也写中文：服务端随时会加新 kind（`book` 就是后来加的），
     * 老 App 把 `podcast` / `webinar` 照原样显示出去，用户只会以为界面坏了 ——
     * 所以认不出的统一写「链接」：说不清是什么，但至少说明"这是个能点开的网页"。
     */
    fun resLabel(kind: String): String = when (kind.lowercase()) {
        "video" -> "视频"
        "course" -> "课程"
        "practice" -> "练习"
        "doc" -> "文档"
        "book" -> "书"
        else -> "链接"
    }
}
