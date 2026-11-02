// FeaturedThemesTest — 「精选主题」这一块真的画出来了、点得动
//
// 为什么要一条 Compose 测试：这块的失败模式全是"界面上看不出来"——
// 清单解析挂了就整排空白、点了没反应、点完不知道成没成。
// 这里喂假清单，断言：名字出现、点一下调用套用、成功后给回执。
// 真的去服务器取清单不放在单测里（网络一抖整套红），另由发布前的
// 线上核对脚本查 index.json 与 8 张图的 200/尺寸。
package top.ccbase.campus.ui.me

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.ccbase.campus.ui.theme.CampusTheme
import top.ccbase.campus.ui.theme.ThemePack
import top.ccbase.campus.ui.theme.ThemePacks

@RunWith(RobolectricTestRunner::class)
class FeaturedThemesTest {

    @get:Rule
    val compose = createComposeRule()

    private val packs = listOf(
        ThemePack(
            id = "starry", name = "星夜", tag = "深色 · 静谧", desc = "安静",
            mode = "dark", accent = "diy:329:1.0", alpha = 0.58f, tint = "auto",
            bgType = "image",
            bgUrl = "https://dl.ccbase.top/themes/starry/bg.jpg",
            coverUrl = "https://dl.ccbase.top/themes/starry/cover.jpg",
        ),
        ThemePack(
            id = "sea", name = "海边", tag = "浅色 · 晴", desc = "晴",
            mode = "light", accent = "diy:215:1.0", alpha = 0.38f, tint = "auto",
            bgType = "image",
            bgUrl = "https://dl.ccbase.top/themes/sea/bg.jpg",
            coverUrl = "https://dl.ccbase.top/themes/sea/cover.jpg",
        ),
    )

    @Test
    fun `清单里的名字都出现在这一排上`() {
        compose.setContent {
            CampusTheme {
                FeaturedThemesSection(load = { packs }, applyPack = { _, _ -> ThemePacks.Result(true) })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("星夜").assertIsDisplayed()
        compose.onNodeWithText("海边").assertIsDisplayed()
        compose.onNodeWithText("深色 · 静谧").assertIsDisplayed()
    }

    @Test
    fun `点一张卡就调用套用，并给出回执`() {
        var applied: String? = null
        compose.setContent {
            CampusTheme {
                FeaturedThemesSection(
                    load = { packs },
                    applyPack = { _, p -> applied = p.id; ThemePacks.Result(true) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("星夜").performClick()
        compose.waitForIdle()
        assertEquals("starry", applied)
        compose.onNodeWithText("已经换成「星夜」").assertIsDisplayed()
    }

    @Test
    fun `套用失败时把原因说出来，不能装作成功`() {
        compose.setContent {
            CampusTheme {
                FeaturedThemesSection(
                    load = { packs },
                    applyPack = { _, _ -> ThemePacks.Result(false, "背景没下下来，检查网络再点一次") },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("海边").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("背景没下下来，检查网络再点一次").assertIsDisplayed()
    }

    @Test
    fun `清单取不到时给重试，而不是一片空白`() {
        compose.setContent {
            CampusTheme {
                FeaturedThemesSection(
                    load = { throw IllegalStateException("offline") },
                    applyPack = { _, _ -> ThemePacks.Result(true) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }
}
