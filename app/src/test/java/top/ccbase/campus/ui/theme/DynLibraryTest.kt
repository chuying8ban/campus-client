package top.ccbase.campus.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.widget.DynPainter
import top.ccbase.campus.widget.WidgetDyn

/**
 * 动态主题库的目录学（2026-10-18 用户："动态主题更丰富一些…做一个动态主题搜索库"）。
 *
 * 这些断言守的是"库的规模与可搜性"，不是画面好不好看 —— 画面靠肉眼，规模靠代码。
 */
class DynLibraryTest {

    @Test
    fun `库里有足够多的动态主题`() {
        // 2026-10-19 新增三支缓动的（绫罗/暮色/涟漪）→ 15 种
        assertEquals("15 种动效", 15, DynPainter.Kind.entries.size)
        assertEquals("动效数 × 色系", DynPainter.Kind.entries.size * 6, DYN_PRESETS.size)
        assertEquals("每款 id 唯一", DYN_PRESETS.size, DYN_PRESETS.map { it.id }.toSet().size)
    }

    @Test
    fun `中文关键词能搜到对应的动效`() {
        assertTrue("搜「雪」要能出来飘雪", dynPresetsMatching("雪").all { it.kind == DynPainter.Kind.SNOW })
        assertTrue(dynPresetsMatching("雪").isNotEmpty())
        // 「雨」同时命中细雨和「数字雨」（流码），这是合理的多义，不是 bug
        val rain = dynPresetsMatching("雨")
        assertTrue("搜「雨」至少能出来细雨", rain.any { it.kind == DynPainter.Kind.RAIN })
        assertTrue(
            "命中的只能是带「雨」字的动效",
            rain.all { it.kind == DynPainter.Kind.RAIN || it.kind == DynPainter.Kind.MATRIX },
        )
        assertTrue("搜「星」至少能命中星轨/星云", dynPresetsMatching("星").isNotEmpty())
        assertTrue("搜色系「粉」应按色系过滤", dynPresetsMatching("粉").all { it.tint.label == "粉" })
        assertTrue("完全不认识的词返回空", dynPresetsMatching("qqqq").isEmpty())
        assertEquals("空查询返回全部", DYN_PRESETS.size, dynPresetsMatching("  ").size)
    }

    @Test
    fun `spec 能带色存回来`() {
        val p = DYN_PRESETS.first()
        val parsed = parseAnim(p.spec)
        assertNotNull("spec 必须解析得回来：${p.spec}", parsed)
        assertEquals(p.kind, parsed!!.first)
        assertEquals("色值要原样还原", p.tint.argb, parsed.second)
    }

    @Test
    fun `老版本的 anim id 仍能认出来`() {
        // 2.9.x 存的是 anim:aurora（没有色值），升级后不能变成"未知背景"
        val parsed = parseAnim("anim:aurora")
        assertEquals(DynPainter.Kind.AURORA, parsed?.first)
    }

    @Test
    fun `小组件的动态 spec 与 App 同源`() {
        val p = DYN_PRESETS[7]
        val spec = WidgetDyn.spec(p.kind, p.tint.argb)
        assertTrue(spec.startsWith("dynw:"))
        val parsed = WidgetDyn.parse(spec)
        assertEquals(p.kind, parsed?.first)
        assertEquals(p.tint.argb, parsed?.second)
        assertEquals("不是动态 spec 不该被认出来", null, WidgetDyn.parse("preset:aurora"))
    }

    @Test
    fun `图片浓淡默认百分之五十`() {
        assertEquals(0.5f, AppearanceStore.bgAlpha.value, 0.001f)
    }
}
