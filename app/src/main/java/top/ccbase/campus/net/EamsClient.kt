package top.ccbase.campus.net

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 会话失效（教务 302 跳登录页）—— 需要重新登录，不是课表有问题。 */
class EamsAuthLost(message: String) : Exception(message)

/** 账号密码不对（教务 JSON 里 result=false）。 */
class EamsLoginFailed(message: String) : Exception(message)

/**
 * 教务系统（eams.cupk.edu.cn）的**只读**客户端 —— 让 App 自己爬课表。
 *
 * 设计要点（都是真机/真接口换来的）：
 * 1. 认证只靠 Cookie（`SESSION` + `__pstsid__`），没有 JWT —— 所以必须能拿到
 *    响应头（`Transport`/`HttpReply.headers` 就是为此加的）。
 * 2. 密码不发明文：`salt = GET /student/login-salt`，
 *    然后 `password = sha1("<salt>-<明文>")`。
 * 3. **不跟随 302**。认证失效时教务不是返 401/403，而是 302 跳登录页；
 *    跟了就会把登录页 HTML 当 JSON 解，报一个和真因无关的错。
 *    所以这里一律用不通随重定向的原始传输层（NoSniTransport）。
 * 4. 学期 id **不能**用非贪婪正则从 `var currentSemester` 里抓：那个对象里
 *    嵌套的 calendarAssoc 先出现 `'id':1`，会抓错；而 print-data 对无效学期 id
 *    照样 200 且 activities 为空 —— **静默清空课表**。正解是取 `var semesters`
 *    列表，用 currentSemester 的 abbrEn 去匹配。
 */
class EamsClient(
    private val t: Transport,
    private val base: String = DEFAULT_BASE,
) {

    companion object {
        const val DEFAULT_BASE = "https://eams.cupk.edu.cn"
        const val STUDENT = "/student"

        /** 课表首页：`var semesters` / `var currentSemester` 就写在这一页里。 */
        const val COURSE_TABLE_PATH = "$STUDENT/for-std/course-table"

        /**
         * print-data 的**真实**路径（与 [fetchTimetableJson] 同源）。
         * WebView 端必须走这里，不许自己拼一个"看起来对"的 URL —— 猜错学期 id 或参数
         * 教务会照样 200 返回空 activities，静默清空课表。
         */
        fun printDataPath(semesterId: Int): String =
            "$COURSE_TABLE_PATH/semester/$semesterId/print-data?semesterId=$semesterId&hasExperiment=true"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /** 名 → 值（登录后整个会话就靠它） */
    private val cookies = LinkedHashMap<String, String>()

    /** 供测试与诊断：当前会话有哪些 Cookie 名 */
    fun cookieNames(): Set<String> = cookies.keys.toSet()

    /** 教务把多个 Set-Cookie 折叠在一行（用逗号），得按 "名字=" 切，不能按逗号切完事。 */
    private fun absorbCookies(header: String?) {
        if (header.isNullOrBlank()) return
        var i = 0
        val s = header
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val name = s.substring(i, eq).trim().substringAfterLast(',').trim()
            val semi = s.indexOf(';', eq)
            val end = if (semi < 0) s.length else semi
            val value = s.substring(eq + 1, end).trim()
            if (name.isNotEmpty()) cookies[name] = value
            // 跳到下一个 "名字="：先到分号后找逗号；都没了就结束
            val nextComma = s.indexOf(',', end)
            if (nextComma < 0) break
            i = nextComma + 1
            while (i < s.length && s[i] == ' ') i++
        }
    }

    private fun call(method: String, path: String, body: String? = null, extra: Map<String, String> = emptyMap()): HttpReply {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Accept-Language"] = "zh-CN,zh;q=0.9"
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        headers.putAll(extra)
        val r = t.call(method, base + path, headers, body)
        absorbCookies(r.headers["Set-Cookie"] ?: r.headers["set-cookie"])
        return r
    }

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 登录。失败抛 [EamsLoginFailed]（给用户看的都是人话）。 */
    fun login(studentId: String, password: String) {
        val saltReply = call("GET", "$STUDENT/login-salt")
        if (saltReply.code !in 200..299) throw EamsLoginFailed("拿不到登录盐（HTTP ${saltReply.code}）")
        val salt = saltReply.text.trim().trim('"')
        if (salt.isEmpty()) throw EamsLoginFailed("登录盐是空的，多半是服务器不认这段会话")

        val encrypted = sha1Hex("$salt-$password")
        val body = """{"username":"${esc(studentId)}","password":"$encrypted","captchaToken":""}"""
        val r = call(
            "POST", "$STUDENT/login", body,
            mapOf("Content-Type" to "application/json", "Referer" to "$base$STUDENT/login"),
        )
        val j = runCatching { JSON.parseToJsonElement(r.text).jsonObject }.getOrNull()
            ?: throw EamsLoginFailed("登录返回的不是 JSON（HTTP ${r.code}）")
        if (j["result"]?.jsonPrimitive?.booleanOrNull != true) {
            val msg = j["message"]?.jsonPrimitive?.contentOrNullSafe().orEmpty().ifEmpty { "账号或密码不对" }
            throw EamsLoginFailed(msg)
        }
    }

    /**
     * 取当前学期的课表原始 JSON。
     *
     * 两个静默失败点都在这里挡掉：学期 id 找不到、以及会话失效（302 / 不是 JSON）。
     */
    fun fetchTimetableJson(): String {
        val home = call("GET", COURSE_TABLE_PATH)
        if (home.code in 300..399) throw EamsAuthLost("被重定向（HTTP ${home.code}）→ 会话失效，需要重新登录")
        if (home.code !in 200..299) throw EamsAuthLost("课表页打不开（HTTP ${home.code}）")

        val semId = currentSemesterId(home.text)
            ?: throw EamsAuthLost("课表页里没有学期列表 —— 多半是会话失效（被重定向到登录页）")

        val url = printDataPath(semId)
        val r = call("GET", url)
        if (r.code in 300..399) throw EamsAuthLost("被重定向（HTTP ${r.code}）→ 会话失效，需要重新登录")
        if (r.code !in 200..299) throw EamsAuthLost("课表接口返回 HTTP ${r.code}")
        val ct = r.headers["Content-Type"] ?: r.headers["content-type"] ?: ""
        if (!ct.contains("json", ignoreCase = true) && !r.text.trimStart().startsWith("{")) {
            throw EamsAuthLost("返回的不是 JSON（Content-Type=$ct）—— 多半被重定向到登录页")
        }
        return r.text
    }

    /**
     * 当前学期 id：取 `var semesters` 列表，用 `var currentSemester` 的 abbrEn 匹配。
     * （不能用非贪婪正则抓 id —— 会抓到嵌套对象里的 `'id':1`。）
     */
    fun currentSemesterId(html: String): Int? {
        // 教务页面里 currentSemester 用**双引号**（"abbrEn"），semesters 列表里是单引号 —— 两种都得认
        val abbr = Regex("['\"]abbrEn['\"]\\s*:\\s*['\"]([^'\"]+)['\"]")
            .find(html.substringAfter("var currentSemester", ""))?.groupValues?.get(1)
        val raw = Regex("var semesters = JSON\\.parse\\(\\s*'(.*?)'\\s*\\)", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val candidates = listOf(raw, raw.replace("\\'", "'"), raw.replace("\\\"", "\"").replace("\\'", "'"))
        for (c in candidates) {
            val arr = runCatching { JSON.parseToJsonElement(c) }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: continue
            val objs = arr.mapNotNull { it as? JsonObject }
            if (objs.isEmpty()) continue
            val pick = objs.firstOrNull { it["abbrEn"]?.jsonPrimitive?.contentOrNullSafe() == abbr } ?: objs.first()
            pick["id"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull()?.let { return it }
        }
        return null
    }
}

/** 把 JSON 里的值当字符串取：数字也行（教务的 id 有时是数字、有时是字符串） */
internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

internal fun esc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/** 与 Python 端保持一致的 UA —— 教务对陌生 UA 会加验证码 */
internal const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
