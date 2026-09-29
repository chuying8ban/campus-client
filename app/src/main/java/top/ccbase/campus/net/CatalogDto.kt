package top.ccbase.campus.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「公共学习资源目录」（`GET /api/v2/catalog`）的接口契约。
 *
 * 字段名逐字对齐服务端 `catalog.py` 的输出，不是猜的。
 *
 * **所有字段都给默认值**：服务端漏一个字段只该让"这一条不好看"，绝不能升级成
 * "整页打不开"。这个坑本项目踩过两次（PlanRes.title/url、PlanItem.title）——
 * 一个必填字段在解析时抛异常，用户看到的是一整页空白。
 *
 * 这里**没有 `why`**，是刻意的：目录汇总的是别人的收藏，`why` 是"为什么配给他"的
 * 个人语境（技能 selfhosted-mobile-pwa 的隐私红线）。类型里没有这个字段，
 * 代码里就不可能把它带进来 —— 比"记得别显示"可靠。
 */
@Serializable
data class CatalogItem(
    val url: String = "",
    val title: String = "",
    /** video / doc / course / practice / other —— 服务端已归一，App 不再自己判 */
    val kind: String = "",
    @SerialName("kind_label") val kindLabel: String = "",
    /** 来源站名（B站 / 中国大学MOOC …） */
    val source: String = "",
    /** 课程归属；没挂课程的条目服务端已归到「自学 / 技能包」 */
    val course: String = "",
    /** 通用说明（类型 · 来源），给卡片用；**不是**别人的 why */
    val note: String = "",
)

@Serializable
data class CatalogCourse(
    val name: String = "",
    /** 全班这门课的公开资料条数 */
    val count: Int = 0,
    /** 有几个人在学（服务端按 uid 去重后的**人数**，不是名单）— 老服务端没有这个键 */
    val learners: Int = 0,
    /** 任课教师（全班同名取一个确定值）— 老服务端没有 */
    val teacher: String = "",
    /** 学分 — 老服务端没有 */
    val credits: Double = 0.0,
)

@Serializable
data class CatalogKind(val key: String = "", val label: String = "", val count: Int = 0)

/** 「挑一门课进我的课表」（`POST /api/v2/library/pick`）的回执。 */
@Serializable
data class PickCourseRes(
    val ok: Boolean = false,
    /** false = 之前就加过（服务端幂等，不算失败） */
    val created: Boolean = false,
    val name: String = "",
)

@Serializable
data class Catalog(
    val items: List<CatalogItem> = emptyList(),
    /** 服务端给的课程汇总（数量降序）；界面直接用它渲染课程列表，不自己再统计一遍 */
    val courses: List<CatalogCourse> = emptyList(),
    /** 四个固定分类（count 可能为 0）——「今天没人收藏慕课」不等于那格该消失 */
    val kinds: List<CatalogKind> = emptyList(),
    val total: Int = 0,
    @SerialName("generated_at") val generatedAt: String = "",
)
