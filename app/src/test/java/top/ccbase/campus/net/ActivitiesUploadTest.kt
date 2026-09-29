package top.ccbase.campus.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.domain.activitiesFrom

/**
 * 「手机自己爬课表 → 交给服务端重建规划」这条上传链路。
 *
 * 这段最怕的是**静默丢字段**：App 把教务 activities 解析成自己的模型、再拼成
 * 新 JSON 发出去，看着"成功"了，但 weekIndexes（精确周次，单双周/中途换机房靠它）
 * 一旦被拼丢，课表就会悄悄变样 —— 而服务端只会照着收到的东西建规划，无从察觉。
 *
 * 所以这里不硬编码任何数字：拿**真教务样本**跑一遍，再把**实际发出去**的请求体
 * 解析回来，跟样本里的 activities 做**逐字段相等**的断言。拼丢一个字段就红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class ActivitiesUploadTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    /** 真教务课表页（print-data）的逐字副本 */
    private val printData = sample("eams/print_data.json")

    /** 记下真正发出去的请求，顺带断言令牌带上了 */
    private class Seen {
        var method = ""
        var path = ""
        var auth: String? = null
        var body = ""
    }

    private fun api(code: Int, body: String, seen: Seen) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { m, url, headers, payload ->
            seen.method = m
            seen.path = url.substringAfter("example.invalid")
            seen.auth = headers["Authorization"]
            seen.body = payload ?: ""
            HttpReply(code, body)
        },
    )

    @Test
    fun `上传的是教务原样 activities_一个字段都不能少`() = runBlocking {
        val acts = activitiesFrom(printData) ?: error("样本里没读 activities")
        assertTrue("样本本身应该有课", acts.size > 0)

        val seen = Seen()
        val r = api(200, """{"ok":true,"counts":{},"stats":{}}""", seen)
            .uploadActivities("tok-1", acts.toString())

        assertTrue("上传应当成功：$r", r is ApiResult.Ok)
        assertEquals("POST", seen.method)
        assertEquals("/api/v2/plan/from-activities", seen.path)
        assertEquals("Bearer tok-1", seen.auth)

        val sent = json.parseToJsonElement(seen.body).jsonObject["activities"]!!.jsonArray
        assertEquals("发出去的条数必须和教务给的一致", acts.size, sent.size)
        // 逐字段相等 —— 不是"看着差不多"，是 JsonElement 完全相等
        assertEquals("activities 必须原样上传（拼丢 weekIndexes 这类字段就挂在这里）",
            acts, sent)
    }

    @Test
    fun `上传体里带着精确周次_不是只有课程名`() = runBlocking {
        val acts = activitiesFrom(printData) ?: error("样本里没读 activities")
        val seen = Seen()
        api(200, """{"ok":true}""", seen).uploadActivities("t", acts.toString())
        // 单双周/中途换机房全在 weekIndexes 里；这里确认它真的被发出去了
        assertTrue("请求体里必须出现 weekIndexes（精确周次的载体）",
            seen.body.contains("weekIndexes"))
    }

    @Test
    fun `服务端拒绝时_把服务端那句话带给用户`() = runBlocking {
        val seen = Seen()
        val r = api(400, """{"detail":"课表数据是空的（没有读到上课活动）"}""", seen)
            .uploadActivities("t", "[]")
        assertTrue(r is ApiResult.Err)
        val msg = (r as ApiResult.Err).message
        assertTrue("要原样带出服务端的话：$msg", msg.contains("课表数据是空的"))
    }

    @Test
    fun `没令牌时服务端会401_不能被当成网络故障`() = runBlocking {
        val seen = Seen()
        val r = api(401, """{"detail":"未登录或登录已失效"}""", seen)
            .uploadActivities("bad", "[]")
        assertTrue(r is ApiResult.Err)
        assertEquals("401 必须保留原始状态码（UI 靠它区分「要重新登录」和「网络不通」）",
            401, (r as ApiResult.Err).code)
    }
}
