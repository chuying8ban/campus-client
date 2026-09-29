package top.ccbase.campus.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权清单的两条要害：
 *
 *   ① 「安装更新」必须进授权页清单 —— 没它的话，用户点了更新会在**最后一步**失败，
 *      而界面上一个字都不说（1.30 那版真卡过一次，就是缺这个开关）。
 *   ② 它**不能**混进静音体检的结论里 —— 否则结论会说出
 *      「静音不会生效，缺：安装更新」这种牛头不对马嘴的话，用户会去开错东西。
 */
class SilenceDiagPermissionsTest {

    private fun snap(install: Boolean, rest: Boolean = true) =
        DiagSnapshot(rest, rest, rest, rest, rest, install)

    @Test
    fun `授权页清单比静音体检多且只多一项安装更新`() {
        val s = snap(install = false)
        val perm = SilenceDiag.permissionItems(s)
        assertEquals(SilenceDiag.items(s).size + 1, perm.size)
        assertTrue(perm.any { it.name == "安装更新" })
        assertFalse("安装更新不该出现在静音体检里", SilenceDiag.items(s).any { it.name == "安装更新" })
    }

    @Test
    fun `静音权限齐了_结论就不能被安装更新带偏`() {
        val items = SilenceDiag.items(snap(install = false, rest = true))
        assertEquals("静音具备生效条件", SilenceDiag.verdict(items))
    }

    @Test
    fun `没给安装权限时要写清后果和去哪开`() {
        val item = SilenceDiag.permissionItems(snap(install = false)).first { it.name == "安装更新" }
        assertFalse(item.ok)
        assertTrue("没说清后果：${item.detail}", item.detail.contains("更新"))
        assertTrue("没给路径", item.steps.any { it.contains("安装未知应用") })
        assertTrue("没给按钮文案", item.action.isNotBlank())
    }

    @Test
    fun `已经给了就不该再教怎么开`() {
        val item = SilenceDiag.permissionItems(snap(install = true)).first { it.name == "安装更新" }
        assertTrue(item.ok)
        assertTrue("已授权还列一堆步骤只会让人以为有问题", item.steps.isEmpty())
    }

    @Test
    fun `厂商指引只给路径_不给状态`() {
        // 系统查不到自启动/省电策略，所以这里不能出现"已开启"这类断言式文案 ——
        // 写一个假的 ✓ 比不写更坏：用户会以为已经好了。
        val guides = SilenceDiag.vendorGuides()
        assertEquals(3, guides.size)
        assertTrue(guides.any { it.title.contains("自启动") })
        assertTrue(guides.any { it.title.contains("省电策略") })
        assertTrue(guides.any { it.title.contains("加锁") })
        guides.forEach { g ->
            assertTrue("「${g.title}」没给怎么走", g.steps.any { it.contains("路径") || it.contains("做法") })
            assertTrue("「${g.title}」没说不开会怎样", g.steps.any { it.contains("后果") || it.contains("作用") })
        }
    }
}
