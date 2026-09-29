package top.ccbase.campus.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 教务课表 JSON 的**取数**部分（纯逻辑，好测）。
 *
 * 关键取舍：**不做数据建模，只把 `activities` 原样取出来**。
 * 理由：`activities` 就是教务课表页正在渲染的那份数据，weekday/startUnit/endUnit/
 * room/weekIndexes 全是字段，服务端 `slots_from_activities` 直接吃它。App 在中间
 * 重新拼一遍模型，只会引入解析漂移（"哪个字段丢了"这类 bug 最难查）。
 */
private val J = Json { ignoreUnknownKeys = true; isLenient = true }

/** print-data → activities。拿不到就 null（调用方决定是报「会话失效」还是「课表为空」）。 */
fun activitiesFrom(printData: String): JsonArray? {
    val root = runCatching { J.parseToJsonElement(printData).jsonObject }.getOrNull() ?: return null
    val vms = root["studentTableVms"] as? JsonArray ?: return null
    val vm = vms.firstOrNull() as? JsonObject ?: return null
    return vm["activities"] as? JsonArray
}

/** 取一条活动的字符串字段（数字也行 —— 教务的 id 有时是数字有时是字符串）。 */
private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNullLenient()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullLenient(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

/** 一条课的摘要 —— 给界面显示「爬到了什么」+ 给日志留证据。 */
data class EamsActivity(
    val course: String,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val room: String?,
    val weeks: List<Int>,
    val startTime: String?,
    val endTime: String?,
) {
    val unitSpan: Int get() = (endUnit - startUnit + 1).coerceAtLeast(1)
}

fun parseActivities(acts: JsonArray): List<EamsActivity> = acts.mapNotNull { el ->
    val a = el as? JsonObject ?: return@mapNotNull null
    val name = (a.str("courseName") ?: a.str("lessonName"))?.trim().orEmpty()
    if (name.isEmpty()) return@mapNotNull null
    val wd = a.str("weekday")?.toDoubleOrNull()?.toInt() ?: 1
    val su = a.str("startUnit")?.toDoubleOrNull()?.toInt() ?: 1
    val eu = a.str("endUnit")?.toDoubleOrNull()?.toInt() ?: su
    if (wd !in 1..7 || su < 1) return@mapNotNull null
    // weekIndexes 是官方给的**精确周次集合**（单双周/散周都靠它），优先用它
    val weeks = (a["weekIndexes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullLenient()?.toDoubleOrNull()?.toInt() }
        ?.filter { it > 0 }?.distinct()?.sorted() ?: emptyList()
    EamsActivity(
        course = name, weekday = wd, startUnit = su, endUnit = eu,
        room = a.str("room")?.trim()?.takeIf { it.isNotEmpty() },
        weeks = weeks, startTime = a.str("startTime"), endTime = a.str("endTime"),
    )
}

/** 「爬到了什么」的一句话 —— 给学生看，也进日志。 */
fun summarize(acts: List<EamsActivity>): String {
    if (acts.isEmpty()) return "没爬到任何课"
    val courses = acts.map { it.course }.distinct()
    val rooms = acts.mapNotNull { it.room }.distinct().size
    return "${courses.size} 门课 / ${acts.size} 段 / $rooms 个教室"
}
