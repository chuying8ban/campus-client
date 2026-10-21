package top.ccbase.campus.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.widget.ArtRepo

/**
 * 「下载都走快的那台」—— 2026-10-21 用户口径：
 * 「图库和字体的下载也使用那个快速的下载源」。
 *
 * 这台是 dl 镜像（47.116.73.244，出口 ~140Mbps），更新包也在它上面。
 * 把它钉成用例的理由：这类常量最容易在某次"顺手改回主站"里悄悄退回去 ——
 * 功能照样工作，只是用户每次换字体/换壁纸都要多等几秒，没人会报 bug，只有人会烦。
 *
 * 允许留在 study（主站）的东西：更新清单（13KB，慢无所谓）、公告、崩溃上报 ——
 * 都是一次几百字节的请求，跟大文件下载不是一回事。
 */
class FastSourceTest {

    private fun host(url: String) = url.substringAfter("://").substringBefore('/')

    @Test
    fun `字体清单和字体文件都走 dl 快源`() {
        assertEquals("dl.ccbase.top", host(FontCatalog.BASE))
        assertTrue("字体目录要挂在 dl 的 /fonts/ 下", FontCatalog.BASE.endsWith("/fonts/"))
    }

    @Test
    fun `图库的图片和搜索接口也走 dl 快源`() {
        assertEquals("dl.ccbase.top", host(ArtRepo.BASE))
        assertEquals("图库目录要挂在 dl 的 /art/ 下", "/art", ArtRepo.DIR)
    }
}
