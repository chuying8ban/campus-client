package top.ccbase.campus.net

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「学校课表导入」这一小步的**安全边界**。
 *
 * 三件事在这里钉死，别让"别人给的一段 URL / 一段 JSON"进到 App 深处：
 *  ① URL 只认教务自己的 https 同源，其余一律不认；
 *  ② 导入结束（无论取消还是完成）必须清 Cookie；
 *  ③ 活动 JSON 只留下课表渲染真需要的字段，密码/Cookie/HTML 等脏字段当场扔掉。
 */
object SchoolImportPolicy {

    private const val ALLOWED_SCHEME = "https"
    private const val ALLOWED_HOST = "eams.cupk.edu.cn"
    private const val DEFAULT_PORT = 443

    private const val NAME_FIELD_1 = "courseName"
    private const val NAME_FIELD_2 = "lessonName"
    private val ALLOWED_FIELDS = listOf(
        NAME_FIELD_1,
        NAME_FIELD_2,
        // 分组与同课多段都靠这两个 id 区分；漏了会让不同课程被并成一门
        "courseCode",
        "lessonId",
        "lessonCode",
        "weekday",
        "startUnit",
        "endUnit",
        "weekIndexes",
        "room",
        "teachers",
        "credits",
        "courseType",
        "startTime",
        "endTime",
        "weeksStr",
    )

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * 任何一层出现这些字段名（大小写无关、包含即算）都要就地丢掉。
     *
     * 白名单只钉顶层键名是不够的：`courseType`、`teachers` 这类字段的值本身可能是对象/数组，
     * 攻击者（或教务以后改版）往里塞一个 `password`/`cookie`，会被原样带进 App 深处。
     * 所以清洗必须递归到每一层。
     */
    private val SENSITIVE_MARKERS = listOf(
        "password", "passwd", "pwd", "secret", "cookie", "session",
        "token", "authorization", "credential", "html", "script",
    )

    private fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return SENSITIVE_MARKERS.any { k.contains(it) }
    }

    /**
     * 只放行 `https://eams.cupk.edu.cn` 同源地址。
     *
     * 明确拒绝：http、其它主机、带 userinfo 的伪装、非默认端口（默认 443）。
     */
    fun isAllowedSchoolUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!uri.isAbsolute) return false
        if (uri.scheme?.lowercase() != ALLOWED_SCHEME) return false
        if (uri.host?.lowercase() != ALLOWED_HOST) return false
        if (!uri.rawUserInfo.isNullOrEmpty()) return false
        if (uri.port != -1 && uri.port != DEFAULT_PORT) return false
        return true
    }

    /** 导入结束必须清 Cookie；取消和完成是同一件事，不存在"取消就留着"的例外。 */
    fun shouldClearCookiesOnFinish(cancelled: Boolean): Boolean = true

    /**
     * 把活动数组清洗成字段白名单版本。
     *
     * 只保留有课程名（courseName 或 lessonName）的对象，并且每条只留白名单字段；
     * password/cookie/html 等字段、非对象条目、非法 JSON / 非数组输入都会被丢掉。
     */
    fun activityAllowlist(json: String): String {
        val root = runCatching { JSON.parseToJsonElement(json) }.getOrNull()
            ?: return "[]"
        val arr = root as? JsonArray ?: return "[]"

        val cleaned = buildJsonArray {
            for (element in arr) {
                val obj = element as? JsonObject ?: continue
                if (!hasCourseName(obj)) continue
                add(sanitized(obj))
            }
        }
        return cleaned.toString()
    }

    private fun hasCourseName(obj: JsonObject): Boolean =
        listOf(NAME_FIELD_1, NAME_FIELD_2).any { key -> nonBlankPrimitive(obj[key]) }

    private fun nonBlankPrimitive(value: JsonElement?): Boolean {
        if (value is JsonNull) return false
        val primitive = value as? JsonPrimitive ?: return false
        return primitive.content.trim().isNotEmpty()
    }

    private fun sanitized(obj: JsonObject): JsonObject = buildJsonObject {
        for (field in ALLOWED_FIELDS) {
            val value = obj[field] ?: continue
            if (value is JsonNull) continue
            put(field, scrub(value))
        }
    }

    /** 递归清洗：对象里丢掉敏感键，数组逐元素清洗，标量原样保留。 */
    private fun scrub(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> buildJsonObject {
            for ((k, v) in value) {
                if (isSensitiveKey(k)) continue
                if (v is JsonNull) continue
                put(k, scrub(v))
            }
        }
        is JsonArray -> buildJsonArray { for (el in value) { if (el !is JsonNull) add(scrub(el)) } }
        else -> value
    }
}
