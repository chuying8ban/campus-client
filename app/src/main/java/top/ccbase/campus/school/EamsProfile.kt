package top.ccbase.campus.school

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta

/**
 * 教务「学籍 - 详情」里**对日常有用**的那几行（不是整页档案）。
 *
 * 为什么只取这几项：页面里还有考生号、录取通知书号、身份证号、银行卡号、户口性质……
 * 这些对「今天要不要去上课、请假找谁」没有任何帮助，多存一份就是多一份泄露面。
 * 学院/专业/班级/辅导员/学籍状态/校区 —— 这六项才是学生真会看的。
 *
 * 数据来源：内置浏览器里对 `/student/for-std/student-info` 发一次同源 GET，页面里用
 * `ShuweiExtraction.profileScript` 就地解析成 JSON（见那边的注释：教务这页是服务端渲染的
 * HTML，没有 JSON 接口，解析逻辑放 JS 里比在 Kotlin 里对着标签做正则稳得多）。
 */
@Serializable
data class EamsProfile(
    val college: String? = null,
    val major: String? = null,
    val adminClass: String? = null,
    val counselor: String? = null,
    val status: String? = null,
    val campus: String? = null,
    val grade: String? = null,
    val program: String? = null,
    val studentId: String? = null,
) {
    /** 一条有用的都没有就当没取到（页面解析失败时会出现这种"全空"）。 */
    val hasAnything: Boolean
        get() = listOfNotNull(college, major, adminClass, counselor).isNotEmpty()

    /** 界面直接照这个顺序摆；只有有值的才出现（缺项不显示" — "占位，避免一行行全是横杠）。 */
    fun rows(): List<Pair<String, String>> = buildList {
        college?.let { add("学院" to it) }
        major?.let { add("专业" to it) }
        adminClass?.let { add("班级" to it) }
        counselor?.let { add("辅导员" to it) }
        status?.let { add("学籍" to it) }
        campus?.let { add("校区" to it) }
    }
}

/**
 * 学籍卡片的本机缓存。
 *
 * 存在 `meta` 表里（和 semester_start 这些同一种东西），**只在本机**：
 * 不上传、不进云端同步、退出登录也不清（它描述的是"你是谁"，不是会话）。
 * 用户点「忘记登录」时 cookie 掉了，这张卡片照旧能看 —— 这符合直觉：
 * 班级和辅导员不会因为你退出教务就变。
 */
object SchoolProfileCache {
    const val K_PROFILE = "eams_profile"
    const val K_AT = "eams_profile_at"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 把 JS 交回来的 JSON 变成模型；空串/"--" 一律当没这一项。 */
    fun decode(raw: String?): EamsProfile? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        val parsed = runCatching {
            json.decodeFromString(EamsProfile.serializer(), text)
        }.getOrNull() ?: return null
        val cleaned = parsed.copy(
            college = clean(parsed.college),
            major = clean(parsed.major),
            adminClass = clean(parsed.adminClass),
            counselor = clean(parsed.counselor),
            status = clean(parsed.status),
            campus = clean(parsed.campus),
            grade = clean(parsed.grade),
            program = clean(parsed.program),
            studentId = clean(parsed.studentId),
        )
        return if (cleaned.hasAnything) cleaned else null
    }

    /** 教务用 `--` 表示"没填"；它不该被当成一个真的值显示出来。 */
    private fun clean(v: String?): String? {
        val t = v?.trim().orEmpty()
        return if (t.isEmpty() || t == "--" || t == "—" || t == "null") null else t
    }

    suspend fun load(db: CampusDb): EamsProfile? = decode(db.dao().metaGet(K_PROFILE))

    suspend fun updatedAt(db: CampusDb): String? = db.dao().metaGet(K_AT)

    /** 写入；解析不出有用字段时**不覆盖**旧数据（宁可用上次的，也不把卡片清空）。 */
    suspend fun save(db: CampusDb, raw: String, at: LocalDate = LocalDate.now()): Boolean {
        val p = decode(raw) ?: return false
        db.dao().metaPut(
            Meta(K_PROFILE, json.encodeToString(EamsProfile.serializer(), p)),
        )
        db.dao().metaPut(Meta(K_AT, at.toString()))
        return true
    }
}
