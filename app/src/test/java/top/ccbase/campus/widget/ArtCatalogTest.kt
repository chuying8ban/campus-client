package top.ccbase.campus.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在线图库清单解析 + 搜索。
 *
 * 两件必须成立的事：
 *  ① 清单里坏掉一条（缺 file）不能整页空白 —— 只跳过那一条；
 *  ② 搜索要"输入即筛"且大小写无关 —— 用户搜"星空"能出「星夜」，搜"赛博"能出赛博那张。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtCatalogTest {

    private val json = """
        {"version":1,"items":[
          {"id":"01_starry","title":"星夜","tags":["星空","少女","夜色"],"file":"01_starry.jpg","thumb":"01_starry_t.jpg","author":"校园助手","license":"原创生成"},
          {"id":"03_cyber","title":"赛博街","tags":["赛博","霓虹","雨夜"],"file":"03_cyber.jpg"},
          {"id":"web_01","title":"星空","tags":["夜空"],"file":"web_01.jpg","kind":"photo","orient":"portrait","author":"某人","license":"CC0 1.0"},
          {"id":"broken","title":"坏条目"}
        ]}
    """.trimIndent()

    @Test
    fun `解析跳过坏条目 并把相对路径拼成完整地址`() {
        val items = ArtRepo.parseCatalog(json)
        assertEquals("缺 file 的条目应被跳过，不能拖垮整页", 3, items.size)
        assertEquals("01_starry", items[0].id)
        assertEquals("https://dl.ccbase.top/art/01_starry.jpg", items[0].url)
        assertEquals("https://dl.ccbase.top/art/01_starry_t.jpg", items[0].thumbUrl)
        // 没写 thumb 时退回原图，不能变成空链接
        assertEquals(items[1].url, items[1].thumbUrl)
        assertEquals("校园助手", items[0].author)
        // 没写 kind 的条目按插画算（老清单不带这个字段也要能显示）
        assertEquals("illustration", items[0].kind)
    }

    @Test
    fun `搜索命中标题或标签 空查询返回全部`() {
        val items = ArtRepo.parseCatalog(json)
        assertTrue("搜「星」应命中星夜", items[0].matches("星"))
        assertTrue("搜标签「霓虹」应命中赛博街", items[1].matches("霓虹"))
        assertTrue("大小写无关：id 是 03_cyber，搜 CYBER 也要命中", items[1].matches("CYBER"))
        assertEquals(3, items.count { it.matches("") })
        assertEquals(1, items.count { it.ofKind("photo") })
        assertEquals(2, items.count { it.ofKind("illustration") })
        // 没写 orient 的一律当横图（老清单）；写了 portrait 的进竖版图库
        assertEquals(2, items.count { it.ofOrient("landscape") })
        assertEquals(1, items.count { it.ofOrient("portrait") })
        assertEquals(0, items.count { it.matches("樱花") })
    }
}
