package top.ccbase.campus.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.domain.UNIT_TIMES

/**
 * 官方教务 `activities` → 本地 courses/slots 的纯映射。
 *
 * 这是「默认本地导入」的落地层。它与服务端 `timetable_parse.parse` 保持同一口径，
 * 但**只替换课程与时段**：任务、步骤、资源、打卡、专注记录一个字都不碰。
 *
 * ID 刻意从固定高位开始，避开内置种子/旧远端计划的课程 id（那些是网页版真实 id）。
 * 稳定且幂等：同一份课表重复导入结果一致。
 */
object EamsLocalImport {

    const val COURSE_ID_BASE = 1_000_000_000
    const val SLOT_ID_BASE = 2_000_000_000
    const val SOURCE = PlanApplier.LOCAL

    data class LocalTimetable(val courses: List<Course>, val slots: List<Slot>)

    data class ImportResult(val imported: Boolean, val counts: Map<String, Int>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun build(activitiesJson: String): LocalTimetable {
        val arr = runCatching { json.parseToJsonElement(activitiesJson) }.getOrNull() as? JsonArray
            ?: return LocalTimetable(emptyList(), emptyList())

        val courses = mutableListOf<Course>()
        val slots = mutableListOf<Slot>()
        val courseIndex = mutableMapOf<String, Int>()

        arr.forEachIndexed { i, el ->
            val a = el as? JsonObject ?: return@forEachIndexed
            val name = text(a["courseName"])?.trim()?.takeIf { it.isNotEmpty() }
                ?: text(a["lessonName"])?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEachIndexed

            val weekdays = weekIndexes(a["weekIndexes"], a["weeksStr"])
            val weekday = int(a["weekday"])?.takeIf { it in 1..7 } ?: return@forEachIndexed
            val pStart = int(a["startUnit"])?.takeIf { it >= 1 } ?: return@forEachIndexed
            val pEnd = int(a["endUnit"])?.takeIf { it >= pStart } ?: pStart

            val key = text(a["courseCode"])?.trim()?.takeIf { it.isNotEmpty() }
                ?: text(a["lessonId"])?.trim()?.takeIf { it.isNotEmpty() }
                ?: name
            val existing = courseIndex[key]
            val isNew = existing == null
            val courseId = existing ?: (COURSE_ID_BASE + courseIndex.size + 1).also {
                courseIndex[key] = it
            }

            if (isNew) {
                courses += Course(
                    id = courseId,
                    name = name,
                    short = name.take(8),
                    credits = double(a["credits"]),
                    teacher = teachers(a["teachers"]),
                    category = courseType(a["courseType"]),
                    week_from = weekdays.firstOrNull() ?: 1,
                    week_to = weekdays.lastOrNull() ?: 30,
                    is_focus = 0,
                    target = null,
                    note = null,
                    sort = courseIndex.size,
                )
            }

            val startTime = text(a["startTime"])?.trim().orEmpty()
            val endTime = text(a["endTime"])?.trim().orEmpty()
            val timeText = when {
                startTime.isNotEmpty() && endTime.isNotEmpty() -> "$startTime-$endTime"
                else -> {
                    val u = UNIT_TIMES[pStart]
                    if (u != null) "${u.first}-${UNIT_TIMES[pEnd]?.second ?: u.second}" else ""
                }
            }

            slots += Slot(
                id = SLOT_ID_BASE + slots.size + 1,
                weekday = weekday,
                course_id = courseId,
                p_start = pStart,
                p_end = pEnd,
                time_text = timeText.ifBlank { null },
                room = text(a["room"])?.trim()?.takeIf { it.isNotEmpty() },
                week_from = weekdays.firstOrNull() ?: 1,
                week_to = weekdays.lastOrNull() ?: 30,
                weeks = weekdays.takeIf { it.isNotEmpty() }?.joinToString(","),
                sort = i + 1,
            )
        }

        return LocalTimetable(courses, slots)
    }

    /**
     * 只替换课程与时段；用户记录（task_done/checkins/sessions）不碰。
     *
     * 三件事必须成立，否则要么丢用户进度、要么留下半张表：
     *   · **整体一个事务**：任何一步抛异常都回滚，旧课表原样保留，不会卡在中途；
     *   · **复用库里同名课程的既有 id**：新 id 会让引用旧 id 的任务/步骤变孤儿，
     *     同一门课还会在课表里出现两次 —— 所以先按名字对齐 id，再写；
     *   · 空/无效课表直接返回未导入，绝不覆盖有效旧数据。
     */
    suspend fun import(db: CampusDb, activitiesJson: String, keepManual: Boolean = true): ImportResult {
        val plan = build(activitiesJson)
        if (plan.courses.isEmpty() && plan.slots.isEmpty()) {
            return ImportResult(false, mapOf("courses" to 0, "slots" to 0))
        }

        val at = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        return db.withTransaction {
            val dao = db.dao()

            // 只在一个 plan 课程名唯一、且库里已有同名课程时复用 id（同名多课才不敢合并）。
            val existingByName = dao.allCoursesOnce().groupBy { it.name }
                .mapValues { (_, rows) -> rows.minByOrNull { it.id }!!.id }
            val planNameCounts = plan.courses.groupingBy { it.name }.eachCount()
            val remap = HashMap<Int, Int>()
            val courses = plan.courses.map { c ->
                val keep = existingByName[c.name]?.takeIf { planNameCounts[c.name] == 1 && it != c.id }
                if (keep != null) {
                    remap[c.id] = keep
                    c.copy(id = keep)
                } else c
            }
            val slots = plan.slots.map { s -> remap[s.course_id]?.let { s.copy(course_id = it) } ?: s }

            val courseIds = courses.map { it.id }.ifEmpty { listOf(-1) }
            dao.putCourses(courses)
            dao.pruneCourses(courseIds)
            dao.clearSlots()
            dao.putSlots(slots)

            dao.putMeta(
                listOf(
                    Meta(PlanApplier.K_SOURCE, SOURCE),
                    Meta(PlanApplier.K_AT, at),
                    Meta(PlanApplier.K_ONBOARD, "1"),
                ),
            )

            if (keepManual) {
                val merged = ManualSchedule.overlay(db, top.ccbase.campus.data.seed.Seed(courses=courses, slots=slots, selfstudy=dao.selfStudy().first()))
                if (dao.metaGet(ManualSchedule.KEY) != null) ManualSchedule.save(db, merged)
            } else {
                dao.putMeta(listOf(Meta(ManualSchedule.KEY, null)))
            }
            ImportResult(
                imported = true,
                counts = mapOf("courses" to courses.size, "slots" to slots.size),
            )
        }
    }

    // ------------------------------------------------------------ field helpers

    private fun text(v: JsonElement?): String? = when {
        v == null || v is JsonNull -> null
        v is JsonPrimitive -> v.content.trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun int(v: JsonElement?): Int? = when (v) {
        is JsonPrimitive -> v.content.toIntOrNull() ?: v.content.toDoubleOrNull()?.toInt()
        else -> null
    }

    private fun double(v: JsonElement?): Double = when (v) {
        is JsonPrimitive -> v.content.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun teachers(v: JsonElement?): String? {
        val raw = when (v) {
            is JsonArray -> v.mapNotNull { text(it) }.filter { it.isNotBlank() }.joinToString("、")
            is JsonPrimitive -> v.content
            else -> ""
        }
        return raw.split("、", "，", ",")
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.substringBefore("(")?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinct()
            .joinToString("、")
            .ifBlank { null }
    }

    private fun courseType(v: JsonElement?): String? = when (v) {
        is JsonObject -> text(v["nameZh"]) ?: text(v["name"])
        is JsonPrimitive -> v.content.trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun weekIndexes(indexes: JsonElement?, weeksStr: JsonElement?): List<Int> {
        val explicit = when (indexes) {
            is JsonArray -> indexes.mapNotNull { int(it) }.filter { it > 0 }
            else -> emptyList()
        }
        if (explicit.isNotEmpty()) return explicit.distinct().sorted()

        return weeksFromText(text(weeksStr).orEmpty())
    }

    /** `weeksStr` 是教务的展示文本（`3~19` / `1-8周` / `1,3,5` / `1-16周(单)`），把它扩成精确周次。 */
    private fun weeksFromText(raw: String): List<Int> {
        val out = linkedSetOf<Int>()
        Regex("""(\d+)\s*[-~—到]\s*(\d+)""").findAll(raw).forEach { m ->
            val from = m.groupValues[1].toIntOrNull()
            val to = m.groupValues[2].toIntOrNull()
            if (from != null && to != null && from in 1..to) {
                for (week in from..to) out += week
            }
        }
        Regex("""\d+""").findAll(raw).forEach { m ->
            m.value.toIntOrNull()?.takeIf { it > 0 }?.let { out += it }
        }
        // 「单/双周」：先把周次集合展开，再按奇偶筛；两者同时出现视为自相矛盾，保持全集。
        val single = raw.contains("单")
        val double = raw.contains("双")
        return when {
            single && !double -> out.filter { it % 2 == 1 }
            double && !single -> out.filter { it % 2 == 0 }
            else -> out
        }.sorted()
    }
}
