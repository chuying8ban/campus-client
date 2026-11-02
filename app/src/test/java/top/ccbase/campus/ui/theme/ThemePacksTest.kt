// ThemePacksTest — 守「精选主题」清单的解析与限界
//
// 清单来自服务器，是**外部数据**：字段可以缺、可以错、可以是别的版本写的。
// 这里钉死三条底线：
//  ① 坏一条跳一条，不能整块变空白（图库清单当年就是整页空白栽的）；
//  ② `accent` 会原样存进偏好再解析成颜色，所以只收白名单三种写法；
//  ③ alpha/mode 这类会影响"看不看得清"的数值必须夹到安全范围。
package top.ccbase.campus.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemePacksTest {

    private fun json(items: String) = """{"version":1,"items":[$items]}"""

    private val ok1 = """
        {"id":"starry","name":"星夜","tag":"深色 · 静谧","desc":"安静",
         "mode":"dark","accent":"diy:329:1.0","alpha":0.58,"tint":"auto",
         "bg":{"type":"image","url":"https://dl.ccbase.top/themes/starry/bg.jpg",
               "cover":"https://dl.ccbase.top/themes/starry/cover.jpg"}}
    """.trimIndent()

    @Test
    fun `正常条目字段都映射上`() {
        val p = ThemePacks.parse(json(ok1)).single()
        assertEquals("starry", p.id)
        assertEquals("星夜", p.name)
        assertEquals("dark", p.mode)
        assertEquals("diy:329:1.0", p.accent)
        assertEquals(0.58f, p.alpha, 0.001f)
        assertEquals("image", p.bgType)
        assertTrue(p.bgUrl.endsWith("/themes/starry/bg.jpg"))
        assertTrue(p.coverUrl.endsWith("/themes/starry/cover.jpg"))
    }

    @Test
    fun `缺 id 或 name 的条目跳过`() {
        val two = ok1 + """,""" + """
            {"id":"","name":"没 id","bg":{"type":"image","url":"https://x/y.jpg"}}
        """.trimIndent()
        assertEquals(1, ThemePacks.parse(json(two)).size)
    }

    @Test
    fun `图片主题没有地址就跳过，而不是套出一片空白`() {
        val bad = """
            {"id":"broken","name":"坏图","bg":{"type":"image"}}
        """.trimIndent()
        assertTrue(ThemePacks.parse(json(bad)).isEmpty())
    }

    @Test
    fun `动效主题没有 kind 就跳过`() {
        val bad = """{"id":"a","name":"动效","bg":{"type":"anim"}}"""
        assertTrue(ThemePacks.parse(json(bad)).isEmpty())
    }

    @Test
    fun `未知背景类型按纯色处理，不当成坏数据`() {
        val p = ThemePacks.parse(json("""{"id":"s","name":"纯色","bg":{"type":"video"}}""")).single()
        assertEquals("solid", p.bgType)
    }

    @Test
    fun `未知明暗档回落到跟随系统`() {
        val p = ThemePacks.parse(
            json("""{"id":"x","name":"x","mode":"sepia","bg":{"type":"solid"}}"""),
        ).single()
        assertEquals(AppearanceStore.SYSTEM, p.mode)
    }

    @Test
    fun `浓淡夹在 0_1 到 1 之间`() {
        val lo = ThemePacks.parse(json("""{"id":"a","name":"a","alpha":0.01,"bg":{"type":"solid"}}""")).single()
        val hi = ThemePacks.parse(json("""{"id":"b","name":"b","alpha":9,"bg":{"type":"solid"}}""")).single()
        assertEquals(0.1f, lo.alpha, 0.001f)
        assertEquals(1f, hi.alpha, 0.001f)
    }

    @Test
    fun `强调色只收 default preset 和 diy`() {
        assertEquals("default", ThemePacks.sanitizeAccent("default"))
        assertEquals("preset:teal", ThemePacks.sanitizeAccent("preset:teal"))
        assertEquals("diy:329:1.0", ThemePacks.sanitizeAccent("diy:329:1.0"))
        // 下面这些都该被挡掉：会被原样存进偏好，等于让服务器决定本机存什么
        assertEquals("default", ThemePacks.sanitizeAccent("javascript:alert(1)"))
        assertEquals("default", ThemePacks.sanitizeAccent("preset:../../etc/passwd"))
        assertEquals("default", ThemePacks.sanitizeAccent("diy:abc"))
        assertEquals("default", ThemePacks.sanitizeAccent(""))
    }

    @Test
    fun `清单整个不是 JSON 也不抛，返回空`() {
        assertTrue(ThemePacks.parse("<html>502</html>").isEmpty())
        assertTrue(ThemePacks.parse("{}").isEmpty())
    }

    /**
     * 精选主题只**下载**，不上传任何东西。
     *
     * 外观页有一条老守卫（AppearanceGuardTest：外观相关文件里不许有网络调用），
     * 它护的是"背景图不出网"。主题要能换、能加，必须走网络取清单 —— 所以这里把
     * 性质钉死：只准 GET，不准把本机任何东西发出去。
     */
    @Test
    fun `精选主题只下载不上传`() {
        val files = listOf(
            "src/main/java/top/ccbase/campus/ui/theme/ThemePacks.kt",
            "src/main/java/top/ccbase/campus/ui/me/FeaturedThemes.kt",
        )
        val forbidden = listOf("\"POST\"", "\"PUT\"", "\"PATCH\"", "Multipart", "upload")
        files.forEach { rel ->
            val f = java.io.File(rel)
            assertTrue("找不到 $rel", f.isFile)
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                forbidden.forEach { t ->
                    assertTrue("$rel:${i + 1} 出现了上传迹象「$t」：${code.trim().take(80)}", !code.contains(t))
                }
            }
        }
    }
}
