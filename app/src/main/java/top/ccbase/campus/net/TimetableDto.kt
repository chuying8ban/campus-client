package top.ccbase.campus.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务端 `/plan` 里新增的**课表状态**（增量字段，见服务端 `multiuser_api.plan` 的
 * `"timetable": tts.status(conn, uid)`）。
 *
 * 它要解决的是用户原话那句：「我希望后续 app 能自己更新课表，不要用户自己发现错误」——
 * 有了版本号，App 在打开/回前台时自己比对一次就知道要不要重拉，不用等同学发现少了一门课。
 *
 * ⚠️ **必须防御式**：这是个**只在服务端新的库里才有**的键。
 *   - 老服务端没有 `timetable` → `from()` 返回 null，调用方安静退回旧行为；
 *   - 字段在、但类型不对（`version` 是数字 / `courses` 是字符串 / `timetable` 是数组…）
 *     → 一律吞掉，**绝不许抛**：一个新增的可选字段不能把 `/plan` 整条路打挂
 *     （那会让所有人开 App 就报「计划数据无法解析」）。
 * 所以这里不直接 `decodeFromString<TimetableStatus>`，而是逐字段取 + 局部兜底。
 */
data class TimetableStatus(
    /** 课表内容指纹；只有课表**真变了**才换号 */
    val version: String = "",
    /** 版本号变了的时刻 = 课表真的更新过的时刻 */
    val updatedAt: String = "",
    /** 最近一次成功核对的时刻（没变也会往前走） */
    val checkedAt: String = "",
    /** activities / place —— 服务端这次是从哪读到的 */
    val source: String = "",
    /** ok / failed / nopwd / rejected / none */
    val status: String = "",
    val error: String = "",
    val courses: Int = 0,
    val slots: Int = 0,
) {
    /** 能不能拿去比对：空版本号（老服务端 / 服务端还没同步过）一律不比对 */
    val hasVersion: Boolean get() = version.isNotBlank()

    /** 服务端自己说这次核对是失败的（读教务失败 / 没存密码）—— 界面要照实转达 */
    val failed: Boolean get() = status.isNotEmpty() && status != "ok" && status != "none"

    companion object {
        /** 从 `/plan` 的响应体里抠出 `timetable`。拿不到 / 形状不对 → null（不抛）。 */
        fun fromBody(json: kotlinx.serialization.json.Json, body: String): TimetableStatus? =
            runCatching { json.parseToJsonElement(body).jsonObject["timetable"] }.getOrNull()
                ?.let { from(it) }

        fun from(el: JsonElement?): TimetableStatus? {
            val o = el as? JsonObject ?: return null
            return runCatching {
                TimetableStatus(
                    version = o.str("version"),
                    updatedAt = o.str("updated_at"),
                    checkedAt = o.str("checked_at"),
                    source = o.str("source"),
                    status = o.str("status"),
                    error = o.str("error"),
                    courses = o.num("courses"),
                    slots = o.num("slots"),
                )
            }.getOrNull()
        }

        /** 每个字段单独兜底：这一个字段形状不对，不能连累其它字段 */
        private fun JsonObject.str(k: String): String =
            runCatching { this[k]?.jsonPrimitive?.contentOrNull ?: "" }.getOrDefault("")

        private fun JsonObject.num(k: String): Int =
            runCatching { this[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0 }
                .getOrDefault(0)
    }
}

/**
 * `POST /api/v2/timetable/sync` 的响应（让**服务端**按 EAMS 重读一次课表）。
 *
 * 字段全给默认值：服务端以后加字段、或某次只回 `{"ok":false}`，解析都不许挂。
 */
@Serializable
data class TimetableSyncRes(
    val ok: Boolean = false,
    /** 这次真的换掉几行（0 = 读到了，但课表本来就是这个） */
    val changed: Int = 0,
    /** 没有存教务密码、代读不了的 uid 列表 */
    @SerialName("no_creds") val noCreds: List<Int> = emptyList(),
    /** 每个 uid 的结果（形状由服务端定，App 只显示条数，不猜字段） */
    val results: List<JsonElement> = emptyList(),
)
