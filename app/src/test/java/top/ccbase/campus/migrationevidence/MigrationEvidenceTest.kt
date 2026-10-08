package top.ccbase.campus.migrationevidence

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.EamsLocalImport
import top.ccbase.campus.domain.activitiesFrom
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.SchoolImportPolicy
import top.ccbase.campus.ui.me.CrawlScreen
import top.ccbase.campus.ui.me.SafetyScreen
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 迁移验收证据：把新 UI 的**语义树**与新导入链**本地结果**落成文件，供人工核对。
 *
 * 为什么不是 PNG：本项目 Robolectric 整窗截屏必超时（见 TodayDetailShotTest 说明，2026-09-18 试过），
 * 所以"看起来什么样"只能真机；这里保证的是**文案与结构**是真的、可复读的。
 * 学校真实登录同样仍需真机/真账号，本文件不覆盖那一层。
 *
 * 产物写到仓库 `migration/evidence/`（测试工作目录是 `app/`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class MigrationEvidenceTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    private fun evidenceDir(): File = File("../migration/evidence").apply { mkdirs() }

    private fun dump(rel: String, content: String) {
        val f = File(evidenceDir(), rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        println("EVIDENCE $rel -> ${f.absolutePath} (${content.length} chars)")
    }

    @Test
    fun `导入介绍页_语义树证据`() {
        rule.setContent {
            CampusTheme {
                CrawlScreen(db = app().db, api = CampusApi(), onClose = {})
            }
        }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("打开官方教务登录").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        val tree = rule.onRoot().printToString(maxDepth = 30)
        dump("import_intro_tree.txt", tree)
        assertTrue("介绍页该有官方登录入口", tree.contains("打开官方教务登录"))
        assertTrue("介绍页该写明密码不读取/不保存", tree.contains("不读取"))
    }

    @Test
    fun `安全与隐私页_语义树证据`() {
        rule.setContent { CampusTheme { SafetyScreen(onClose = {}) } }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("安全与隐私").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        val tree = rule.onRoot().printToString(maxDepth = 30)
        dump("privacy_tree.txt", tree)
        assertTrue("必须写明不向我们的服务器上传密码", tree.contains("服务器"))
    }

    @Test
    fun `真实教务样本本地导入_结果证据`() {
        val raw = javaClass.classLoader!!.getResourceAsStream("eams/print_data.json")!!
            .bufferedReader().readText()
        val acts = activitiesFrom(raw)!!
        val allowlisted = SchoolImportPolicy.activityAllowlist(acts.toString())
        val plan = EamsLocalImport.build(allowlisted)

        dump("local_import_allowlisted.json", allowlisted)
        val summary = buildString {
            appendLine("courses=${plan.courses.size}")
            appendLine("slots=${plan.slots.size}")
            appendLine("--- courses ---")
            plan.courses.forEach { appendLine("${it.id}\t${it.name}") }
            appendLine("--- slots (weekday p_start-p_end weeks) ---")
            plan.slots.sortedWith(compareBy({ it.weekday }, { it.p_start })).forEach {
                appendLine("wd=${it.weekday} ${it.p_start}-${it.p_end} room=${it.room} weeks=${it.weeks}")
            }
        }
        dump("local_import_summary.txt", summary)

        assertTrue("真样本必须导入 9 门课", plan.courses.size == 9)
        assertTrue("真样本必须 19 段", plan.slots.size == 19)
        // 清洗后的 JSON 里绝不能出现敏感字段
        assertTrue(!allowlisted.contains("password", ignoreCase = true))
        assertTrue(!allowlisted.contains("cookie", ignoreCase = true))
    }
}
