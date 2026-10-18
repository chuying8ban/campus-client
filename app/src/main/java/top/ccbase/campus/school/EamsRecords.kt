package top.ccbase.campus.school

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta

/**
 * 教务的「考试信息」与「学生成绩单」。
 *
 * 这两页是**服务端渲染的 HTML / 内联 var 数组**，没有正经 JSON 接口（2026-10-18 实测），
 * 所以取数分两段：
 *   ① WebView 里 `ShuweiExtraction.recordsScript` 把每页的**内联数组**和**表格**都抠出来，
 *      原样交成一份 harvest JSON（不认识字段，只负责搬运）；
 *   ② 这里按字段名/表头做归类（[parse]），这样教务改字段顺序、加列都不至于全废。
 *
 * 新生账号现在两边都是空的（没考试、没成绩），**空 ≠ 出错**：空就显示"还没有"，
 * 而不是显示一张空白卡片让人以为坏了。这条在测试里钉死了。
 */
@Serializable
data class ExamEntry(
    val name: String? = null,
    val time: String? = null,
    val room: String? = null,
    val type: String? = null,
    val status: String? = null,
) {
    /** 从 `2027-01-05(Tue) 09:00-11:00` 里取日期；取不到就 null（不猜）。 */
    val date: LocalDate?
        get() = parseDate(time)

    fun daysFrom(today: LocalDate): Long? = date?.let { ChronoUnit.DAYS.between(today, it) }
}

@Serializable
data class GradeEntry(
    val name: String? = null,
    val credits: String? = null,
    val point: String? = null,
    val score: String? = null,
)

@Serializable
data class EamsRecords(
    val exams: List<ExamEntry> = emptyList(),
    val grades: List<GradeEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = exams.isEmpty() && grades.isEmpty()

    /** 未加权 GPA（有绩点的课才算）—— 教务自己的"GPA及排名"要另一条接口，这里只给本地估算。 */
    val averagePoint: Double?
        get() {
            val pts = grades.mapNotNull { it.point?.toDoubleOrNull() }
            return if (pts.isEmpty()) null else pts.average()
        }

    val totalCredits: Double
        get() = grades.mapNotNull { it.credits?.toDoubleOrNull() }.sum()

    /** 下一场还没过去的考试（按日期升序）。 */
    fun nextExam(today: LocalDate): ExamEntry? =
        exams.filter { e -> e.date?.let { !it.isBefore(today) } == true }
            .minByOrNull { e -> e.date!! }
}

internal fun parseDate(raw: String?): LocalDate? {
    val s = raw.orEmpty()
    val m1 = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)
    val m2 = Regex("""(\d{4})/(\d{1,2})/(\d{1,2})""").find(s)
    val m3 = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""").find(s)
    val m = m1 ?: m2 ?: m3 ?: return null
    val (y, mo, d) = m.destructured
    return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = this[k] ?: continue
        val text = when (v) {
            is JsonPrimitive -> v.content
            is JsonObject -> (v["nameZh"] as? JsonPrimitive)?.content
                ?: (v["name"] as? JsonPrimitive)?.content
                ?: (v["value"] as? JsonPrimitive)?.content
            else -> null
        }?.trim()
        if (!text.isNullOrEmpty() && text != "--" && text != "—" && text != "null") return text
    }
    return null
}

/**
 * harvest JSON → 模型。**纯函数**，喂假数据就能验：
 *
 *  - `arrays`：教务内联的 `var studentExamList = [...]` 之类，按字段名归类；
 *  - `tables`：页面直接渲染出来的表格，按**表头位置**归类（列顺序变了也不会错位）。
 */
fun parseRecords(harvest: String?): EamsRecords? {
    val text = harvest?.trim().orEmpty()
    if (text.isEmpty() || text == "null") return null
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null

    val exams = rowsOf(root["exams"])
        .mapNotNull { row ->
            val name = row.str("courseName", "course", "name", "courseNameZh")
            if (name == null) return@mapNotNull null
            ExamEntry(
                name = name,
                time = row.str("examTime", "time", "startTime", "examDate", "beginTime"),
                room = row.str("room", "examRoom", "place", "classroom", "roomName"),
                type = row.str("examType", "type", "examTypeName"),
                status = row.str("status", "examStatus", "statusName"),
            )
        }
        .distinctBy { listOf(it.name, it.time, it.room).joinToString("|") }
        .sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.time.orEmpty() }))

    val grades = rowsOf(root["grades"])
        .mapNotNull { row ->
            val name = row.str("courseName", "course", "name", "courseNameZh")
            if (name == null) return@mapNotNull null
            GradeEntry(
                name = name,
                credits = row.str("credits", "credit", "courseCredits"),
                point = row.str("gradePoint", "point", "gp", "gpa"),
                score = row.str("score", "grade", "finalScore", "totalScore"),
            )
        }
        .distinctBy { it.name }

    if (exams.isEmpty() && grades.isEmpty()) return null
    return EamsRecords(exams = exams, grades = grades)
}

/** 一页 harvest 里的所有"行"，数组对象与表格行统一成 Map。 */
private fun rowsOf(page: JsonElement?): List<JsonObject> {
    val obj = page as? JsonObject ?: return emptyList()
    val out = ArrayList<JsonObject>()

    (obj["arrays"] as? JsonArray)?.forEach { el ->
        val arr = (el as? JsonObject)?.get("rows") as? JsonArray ?: return@forEach
        arr.forEach { r -> (r as? JsonObject)?.let { out.add(it) } }
    }

    (obj["tables"] as? JsonArray)?.forEach { el ->
        val t = el as? JsonObject ?: return@forEach
        val headers = (t["headers"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val rows = t["rows"] as? JsonArray ?: return@forEach
        rows.forEach { r ->
            val cells = (r as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
            if (cells.isEmpty()) return@forEach
            val m = LinkedHashMap<String, JsonPrimitive>()
            headers.forEachIndexed { i, h ->
                val key = HEADER_KEYS[h] ?: return@forEachIndexed
                val v = cells.getOrNull(i)?.trim().orEmpty()
                if (v.isNotEmpty()) m[key] = JsonPrimitive(v)
            }
            if (m.isNotEmpty()) out.add(JsonObject(m))
        }
    }
    return out
}

/** 表头 → 字段名。只收录**认得**的表头（多出来的列直接忽略，不塞垃圾）。 */
private val HEADER_KEYS = mapOf(
    "课程名称" to "courseName",
    "课程代码" to "courseCode",
    "考试时间" to "examTime",
    "考场" to "room",
    "考试类型" to "examType",
    "考试状态" to "status",
    "学分" to "credits",
    "绩点" to "gradePoint",
    "成绩" to "score",
    "总成绩" to "totalScore",
)

/**
 * 考试/成绩的本机缓存。和学籍卡片一样：只在 `meta` 表、只在这台手机上。
 * **取不到不覆盖**上一份 —— 教务偶发 500（`/student/notice` 就是长期 500）时，
 * 不能把用户上次看到的考试安排擦掉。
 */
object SchoolRecordsCache {
    const val K = "eams_records"
    const val K_AT = "eams_records_at"

    suspend fun load(db: CampusDb): EamsRecords? =
        runCatching { json.decodeFromString(EamsRecords.serializer(), db.dao().metaGet(K).orEmpty()) }
            .getOrNull()?.takeUnless { it.isEmpty }

    suspend fun updatedAt(db: CampusDb): String? = db.dao().metaGet(K_AT)

    suspend fun save(db: CampusDb, raw: String?, at: LocalDate = LocalDate.now()): Boolean {
        val parsed = parseRecords(raw) ?: return false
        db.dao().metaPut(Meta(K, json.encodeToString(EamsRecords.serializer(), parsed)))
        db.dao().metaPut(Meta(K_AT, at.toString()))
        return true
    }
}
