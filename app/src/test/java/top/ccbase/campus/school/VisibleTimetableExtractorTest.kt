package top.ccbase.campus.school

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class VisibleTimetableExtractorTest {
    private val good = """[{"courseName":"数学","weekday":1,"startUnit":2,"endUnit":3,"weekIndexes":[1,3],"password":"never","cookie":"never"}]"""
    private fun wrap(raw: String) = JsonPrimitive(raw).toString()
    @Test fun whitelistAndPreview() {
        val clean = VisibleTimetableExtractor.decode(wrap(good))
        assertFalse(clean.contains("password")); assertFalse(clean.contains("cookie"))
        assertTrue(VisibleTimetableExtractor.preview(clean).contains("数学"))
    }
    @Test fun rejectsUnknownOrIncompleteBatch() {
        listOf("[]", "null", "{}", good.replace("\"weekday\":1", "\"weekday\":0"),
            good.replace("[1,3]", "[]"), good.replace("[1,3]", "[\"前半学期\"]"),
            good.replace("\"endUnit\":3", "\"endUnit\":1"),
            good.dropLast(1) + ",{\"courseName\":\"不完整\"}]").forEach {
            assertTrue(it, runCatching { VisibleTimetableExtractor.decode(wrap(it)) }.isFailure)
        }
        assertTrue(runCatching { VisibleTimetableExtractor.decode("null") }.isFailure)
    }
    @Test fun strictHttpsOrigins() {
        val hosts = setOf("authserver.aust.edu.cn")
        assertTrue(VisibleTimetableExtractor.allowed(AustLoginPolicy.ENTRY, hosts))
        listOf("http://authserver.aust.edu.cn/", "https://user@authserver.aust.edu.cn/", "https://authserver.aust.edu.cn:444/", "https://authserver.aust.edu.cn.evil/", "https://jwgl.aust.edu.cn/").forEach {
            assertFalse(VisibleTimetableExtractor.allowed(it, hosts))
        }
    }
    @Test fun scriptHasNoSensitiveOrNetworkReaders() {
        val script = VisibleTimetableExtractor.script(setOf("authserver.aust.edu.cn"))
        assertTrue(script.contains("models[0].activities"))
        assertTrue(script.contains("location.origin"))
        listOf("innerHTML", "outerHTML", "document.cookie", "input", "fetch(", "XMLHttpRequest", "localStorage", "sessionStorage", "password").forEach {
            assertFalse(it, script.contains(it))
        }
        assertFalse(script.contains("JSON.stringify(rows)"))
    }
}
