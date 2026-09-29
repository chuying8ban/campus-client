package top.ccbase.campus.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 「AI 规划学习计划」的接口契约。
 *
 * ⚠️ 字段名与取值**逐字抄自服务端**，不是猜的：
 *   - 入参            `multiuser_api.SuggestIn`（focus / hours / style / level）
 *   - 建议条目        `ai_plan.normalize_items()` 造出来的那个 dict
 *                     （title / track / why / priority / cadence / weekday / steps / deliverable / key）
 *   - 落库/撤销计数    `ai_plan.apply_items()` → {tasks, steps}；`undo_items()` → {tasks, steps, resources}
 *   - 阶段名          `ai_plan.PHASE = "AI 规划"`
 *
 * 测试夹具 `src/test/resources/plan_suggest_*.json` 是用服务端那份 ai_plan.py **跑出来的**
 * 真实形状（不是手写的漂亮数据）—— 字段名对不上就直接红。
 */

/**
 * 用户填的喜好。
 *
 * 老 4 项（focus / hours / style / level）在服务端 `_prefs()` 里是**一字不改**的，
 * 改动它们会让老用户的缓存键集体失效；新加的项**一律可为 null**（`explicitNulls = false`
 * 的编码器会把 null 整个省掉）—— 于是「没填」在老服务端看来就等于这个字段不存在，
 * 不会 422、也不会把 prompt 搅浑。
 */
@Serializable
data class PlanPrefs(
    /** 想加强的方向 */
    val focus: List<String> = emptyList(),
    /** 每周可投入小时数（服务端 clamp 1~40） */
    val hours: Int = 5,
    /** 偏好的形式 */
    val style: List<String> = emptyList(),
    /**
     * 老的自评基础（入门 / 会用 / 熟练）。
     *
     * **界面不再露出它** —— 用户明确要求「基础自评指代不清，应该给每个课程分开自评」，
     * 笼统档位由下面的 [subjects] 逐门课取代。字段本身留着按默认值发：
     * 服务端老 4 项的 prompt 与缓存键因此逐字不变。
     */
    val level: String = "入门",

    // —— 以下为新增的「自由选择」项：服务端 multiuser_api.SuggestIn 同名同义 ——
    /** 学习目标：补弱 / 冲绩点 / 备四级 / 竞赛 / 考研预习 / 兴趣拓展（服务端白名单） */
    val goals: List<String>? = null,
    /** 能学的时间：早 / 午 / 晚 */
    val periods: List<String>? = null,
    /** 每周能学几天 1~7；0 = 没填 */
    @SerialName("days_per_week") val daysPerWeek: Int? = null,
    /** 逐门课自评：1 想加强 … 5 已掌握 */
    val subjects: List<PlanSubject>? = null,
    /** 节奏：每天固定时长 / 周末集中 / 冲刺式 */
    val pace: String? = null,
    /** 排除项（唯一自由文本，服务端限长 60） */
    val avoid: String? = null,
)

/**
 * 一门课的自评。
 *
 * 名字**必须来自本地课表**（服务端按名字匹配课程），不能写死 ——
 * 课表会变（学生自己重抓、教务加了课），写死的名字会在服务端对不上任何一门课。
 */
@Serializable
data class PlanSubject(val name: String, val level: Int = 3)

/**
 * 一条建议。**只读展示用**。
 *
 * 写回给服务端时送的是 `PlanSuggest.raw` 里那份原样 JSON，不是这个类重新编码出来的 ——
 * 万一服务端以后给条目加字段、而这里没跟上，重新编码会把那个字段悄悄抹掉。
 *
 * 同理，这里**加**字段也是安全的：[resources] 是新加的，但它改不了送回去的东西 ——
 * apply 送的是 raw 那份原字节（见 [PlanSuggest.raw]），所以老 App 端字段没跟上、
 * 或者这里少认了服务端某个新字段，都不会让服务端收到残缺的条目。
 */
@Serializable
data class PlanItem(
    /**
     * 条目标题。
     *
     * **必须给默认值**：它是必填时，服务端漏一个 title 会让**整份响应的解析掀翻** ——
     * 用户看到的不是"少一条"，而是整页计划打不开（1.67 修的，见 PlanRobustnessTest）。
     */
    val title: String = "",
    /** 课程 / 自学 / 课外 / 考试 / 手续 / 正课 */
    val track: String = "自学",
    /** 为什么推荐它 —— 界面必须逐条写出来，服务端专门算好的 */
    val why: String = "",
    /** 1 高 2 中 3 低 */
    val priority: Int = 2,
    /** daily / weekly / per-class / once */
    val cadence: String = "weekly",
    /** 建议执行日 1~7，可能没有 */
    val weekday: Int? = null,
    val steps: List<String> = emptyList(),
    val deliverable: String = "",
    /** 服务端算的条目指纹（按 title+steps 的 sha256 前 12 位），只用于去重/对账 */
    val key: String = "",
    /**
     * 这条建议配的视频 / 课程等外链（服务端最多给 3 条）。
     *
     * 用户原话：「我同学使用 ai 学习计划时大多数是没有用的笔记，却很少有有用的视频课程」——
     * 所以在**挑**的时候就得看得见它们，而不是等加入清单之后回学习页里翻。
     *
     * 默认空列表：这个字段是服务端后加的，老服务端与老缓存里**根本没有它** ——
     * 缺了就是"没有资源"，界面这一段整段不显示（不写"暂无资源"这种占位）。
     */
    val resources: List<PlanRes> = emptyList(),
)

/**
 * 一条外链资源。
 *
 * 字段名逐字抄服务端（`ai_plan.resources` 那一段）：`kind / title / url / why / source / http / embed`。
 * 除 [url] 和 [title] 外都给默认值 —— 老形状里没有探测结果，缺字段不能变成解析失败
 * （解析一失败，整页建议都出不来，用户看到的是"建议内容无法解析"）。
 *
 * [kind] 用字符串收着不写成 enum：服务端加新类型（`book` 就是这么加的）时，
 * 老 App 必须照样能显示，而不是抛"未知枚举值"。落地成中文由界面决定。
 */
@Serializable
data class PlanRes(
    /** video=视频 / course=课程 / practice=练习平台 / doc=文档 / book=书；认不出的按「链接」显示 */
    val kind: String = "",
    /**
     * 资源名（单行显示，超长由界面省略号处理）。
     *
     * 给默认值不是为了"容错好看"，是为了**别让一条坏数据带走整页**：这两个字段一旦
     * 缺失就是整份响应的解析失败，同学看到的是"建议内容无法解析"，连能用的条目都没了。
     * 空标题/空链接由界面自己跳过（见 PlanScreen.ResLines）。
     */
    val title: String = "",
    /** 一定是 http/https —— 界面直接拿去打开（见 util/Links.open）；空的由界面跳过 */
    val url: String = "",
    /** 服务端写的"为什么给这个"，可能为空 */
    val why: String = "",
    /** 来源站，可能为空 */
    val source: String = "",
    /** 服务端探到的 HTTP 状态；没探过就是 null（别用 0 表示"没探过"） */
    val http: Int? = null,
    /** 1 = 服务端认为可内嵌。本项目仍然一律走外部浏览器（内嵌播放器看 B 站是残废的） */
    val embed: Int = 0,
)


/**
 * 生成建议的返回。
 *
 * `items` 与 `raw` **同序同长**：`items` 给人看，`raw` 原样留着，用户勾完直接送回
 * `/plan/suggest/apply`。服务端在 apply 那侧会再 normalize 一遍，不信任客户端 ——
 * 所以这里只要保证"送回去的正是它给我的那几条"就够了。
 */
data class PlanSuggest(
    val items: List<PlanItem> = emptyList(),
    val raw: List<JsonElement> = emptyList(),
    /** ai / cache / fallback —— 界面上要照实说清这次是哪种，不能一律标"AI 推荐" */
    val source: String = "",
    /** 服务端给的失败/退化原因，可能为空 */
    val reason: String = "",
    /** 新增任务的阶段名，目前是「AI 规划」 */
    val phase: String = "",
)

/** 落库/撤销的行数。undo 会多一个 resources。 */
@Serializable
data class PlanCounts(
    val tasks: Int = 0,
    val steps: Int = 0,
    val resources: Int = 0,
)

/** `/plan/suggest` 的响应外壳（ok/items/source/reason/phase） */
@Serializable
internal data class PlanSuggestEnvelope(
    val items: List<JsonElement> = emptyList(),
    val source: String = "",
    val reason: String = "",
    val phase: String = "",
)

/** `/plan/suggest/apply` 与 `/plan/suggest/undo` 的响应外壳（ok/counts/phase） */
@Serializable
internal data class PlanCountsEnvelope(
    val counts: PlanCounts = PlanCounts(),
    val phase: String = "",
)
