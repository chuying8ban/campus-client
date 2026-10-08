package top.ccbase.campus.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolImportPolicyTest {

    private val json = Json

    // ------------------------------------------------------------------ URL

    @Test
    fun `仅放行教务默认基址与同源路径`() {
        assertTrue(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn"))
        assertTrue(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn/"))
        assertTrue(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn/student/login"))
        assertTrue(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn/student/print-data?semesterId=2026#table"))
        assertTrue(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn:443/student/login"))
    }

    @Test
    fun `拒绝 http`() {
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("http://eams.cupk.edu.cn/student/login"))
    }

    @Test
    fun `拒绝其它主机和后缀伪装`() {
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn.evil.example/"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://evil.example/eams.cupk.edu.cn/"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://www.cupk.edu.cn/"))
    }

    @Test
    fun `拒绝非默认端口`() {
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn:80/student/login"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn:8443/student/login"))
    }

    @Test
    fun `拒绝 userinfo 伪装与畸形地址`() {
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://eams.cupk.edu.cn@evil.example/"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("https://user:password@eams.cupk.edu.cn/"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("javascript:alert(1)"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl("eams.cupk.edu.cn/student/login"))
        assertFalse(SchoolImportPolicy.isAllowedSchoolUrl(""))
    }

    // ----------------------------------------------------------- cookie 清理

    @Test
    fun `无论取消与否都清 cookie`() {
        assertTrue(SchoolImportPolicy.shouldClearCookiesOnFinish(cancelled = true))
        assertTrue(SchoolImportPolicy.shouldClearCookiesOnFinish(cancelled = false))
    }

    // --------------------------------------------------------- 活动字段白名单

    @Test
    fun `只保留白名单字段并剔除密码cookiehtml`() {
        val input = """
            [
              {
                "courseName": "数学分析",
                "lessonName": "数学分析（I）",
                "courseCode": "MATH1001",
                "lessonId": "9911",
                "lessonCode": "MATH1001-01",
                "weekday": 1,
                "startUnit": 1,
                "endUnit": 2,
                "weekIndexes": [1, 2, 3, 4],
                "room": "A101",
                "teachers": ["张三"],
                "credits": 5,
                "courseType": {"nameZh": "理论课"},
                "startTime": "10:00",
                "endTime": "11:40",
                "weeksStr": "1-4周",
                "password": "secret",
                "cookie": "SESSION=abc",
                "html": "<script>alert(1)</script>",
                "internalId": 999
              }
            ]
        """.trimIndent()
        val out = SchoolImportPolicy.activityAllowlist(input)
        val arr = json.parseToJsonElement(out).jsonArray
        assertEquals(1, arr.size)
        val obj = arr[0].jsonObject
        assertEquals(
            setOf(
                "courseName", "lessonName", "courseCode", "lessonId", "lessonCode",
                "weekday", "startUnit", "endUnit", "weekIndexes", "room", "teachers",
                "credits", "courseType", "startTime", "endTime", "weeksStr",
            ),
            obj.keys,
        )
        assertEquals(json.parseToJsonElement("""[1, 2, 3, 4]"""), obj["weekIndexes"])
    }

    @Test
    fun `嵌套字段里的密码cookie也不许留下`() {
        val input = """
            [
              {
                "courseName": "大学英语",
                "courseType": {"nameZh": "理论课", "password": "nested-secret", "cookie": "SESSION=leak"},
                "teachers": [{"name": "王五", "token": "abc"}, "李六"],
                "room": "D401",
                "extra": {"deep": {"secret": "x", "keep": "ok"}}
              }
            ]
        """.trimIndent()
        val out = SchoolImportPolicy.activityAllowlist(input)
        assertFalse("嵌套 password 必须被剔除", out.contains("nested-secret"))
        assertFalse("嵌套 cookie 必须被剔除", out.contains("SESSION=leak"))
        assertFalse("嵌套 token 必须被剔除", out.contains("\"abc\""))
        assertFalse("顶层的 extra 不在白名单，整块丢弃", out.contains("\"extra\""))
        assertTrue("courseType 的非敏感子字段要保留", out.contains("理论课"))
    }

    @Test
    fun `courseName 或 lessonName 都可作为课程名`() {
        val input = """
            [
              {"courseName": "程序设计基础", "weekday": 3, "startUnit": 5, "endUnit": 6, "weekIndexes": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16], "room": "B201", "teachers": ["李四"]},
              {"lessonName": "大学体育", "weekday": 4, "startUnit": 7, "endUnit": 8, "weekIndexes": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16], "room": "操场", "teachers": ["王五"]}
            ]
        """.trimIndent()
        val arr = json.parseToJsonElement(SchoolImportPolicy.activityAllowlist(input)).jsonArray
        assertEquals(2, arr.size)
        assertTrue("courseName" in arr[0].jsonObject)
        assertTrue("lessonName" in arr[1].jsonObject)
    }

    @Test
    fun `丢弃非对象无课程名以及只有敏感字段的条目`() {
        val input = """
            [
              {"courseName": "高数", "weekday": 2, "startUnit": 1, "endUnit": 2, "weekIndexes": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16], "room": "C303", "teachers": ["赵六"]},
              "not-an-object",
              42,
              null,
              {"internalId": 1},
              {"password": "hunter2", "cookie": "SESSION=x"}
            ]
        """.trimIndent()
        val arr = json.parseToJsonElement(SchoolImportPolicy.activityAllowlist(input)).jsonArray
        assertEquals(1, arr.size)
        assertEquals("高数", arr[0].jsonObject["courseName"]?.toString()?.trim('"'))
    }

    @Test
    fun `非法或非数组输入安全返回空数组`() {
        assertEquals("[]", SchoolImportPolicy.activityAllowlist("not json"))
        assertEquals("[]", SchoolImportPolicy.activityAllowlist("""{"activities":[]}"""))
    }
}
