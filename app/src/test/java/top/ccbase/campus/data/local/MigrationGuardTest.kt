package top.ccbase.campus.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 守"手机里的数据不会被无声清空"这条线。
 *
 * 背景：本地库长期带着 `fallbackToDestructiveMigration()` —— 一旦表结构升版本而没人写迁移，
 * Room 会**把整库删掉重建**：连续打卡、专注记录、学习进度一夜归零，而且不报错不提示。
 * 这类事故可怕在"看起来一切正常"，所以不能靠记性，得靠测试。
 *
 * 两个实现上的教训（都真踩过）：
 * ① 版本号**不能**用 `CampusDb::class.java.getAnnotation(Database::class.java)` 读 ——
 *    Room 的 `@Database` 是 `RetentionPolicy.CLASS`，运行期根本读不到（拿到 null）。
 *    改用 Room 真正写进库里的 `SQLite user_version`（`openHelper.writableDatabase.version`），
 *    这才是"设备上真实生效的版本号"。
 * ② **不要**在这条用例里 `db.close()`：`CampusDb.get()` 缓存的是进程级单例，
 *    关掉它会把同一个 Robolectric 沙箱里随后跑的其他测试全毒死
 *    （症状一律是 `Cannot perform this operation because the connection pool has been closed`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationGuardTest {

    /** 设备上真实生效的表结构版本（Room 写在 SQLite 的 user_version 里）。 */
    private fun realSchemaVersion(ctx: Context): Int =
        CampusDb.get(ctx).openHelper.writableDatabase.version

    @Test
    fun `表结构升版本必须配迁移_否则用户攒的打卡记录会被静默清空`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val version = realSchemaVersion(ctx)
        val targets = CampusDb.MIGRATIONS.map { it.endVersion }.sorted()
        assertTrue("版本号应该是个正整数，实际 $version", version >= 1)

        if (version <= 1) {
            assertTrue(
                "现在是 v$version，迁移列表应该是空的；非空说明加了迁移却没把版本号涨上去（迁移永远不会被执行）。现有：$targets",
                CampusDb.MIGRATIONS.isEmpty(),
            )
            return
        }

        assertTrue(
            "表结构已经是 v$version，但 MIGRATIONS 里没有一条落到 v$version 的迁移 → " +
                "这种情况下 Room 会走破坏性重建，等于把用户手机里的连续打卡/专注记录/学习进度无声清掉。" +
                "请补一条 Migration(<旧版本>, $version) 再发布。当前迁移目标版本：$targets",
            targets.contains(version),
        )
    }

    @Test
    fun `迁移必须有连续覆盖_不能跳版本`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val version = realSchemaVersion(ctx)
        val targets = CampusDb.MIGRATIONS.map { it.endVersion }.sorted()
        if (targets.isEmpty() || version <= 1) return

        // 从 v1 一路升到当前版本，每一步都得有人接管：2,3,4… 不许缺
        val expected = (2..version).toList()
        assertEquals("迁移链断了 —— 缺这些版本：${expected - targets.toSet()}", expected, targets)
    }

    @Test
    fun `生产 builder 真能建库读写_不是只有内存库能跑`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = CampusDb.get(ctx)
        withContext(Dispatchers.IO) { db.dao().metaPut(Meta(k = "probe", v = "1")) }
        val got = withContext(Dispatchers.IO) { db.dao().metaGet("probe") }
        assertEquals("生产 builder 建出来的库必须能真写真读", "1", got)

        assertSame(
            "同一个进程里重复调用应该复用同一个实例（每次新建会白白多开一个库连接）",
            db,
            CampusDb.get(ctx),
        )
        // 这里故意不 close()：见类注释 ②
    }

    @After
    fun tearDown() {
        // 生产 builder 缓存的是进程级单例，用过了必须还回去（见 CampusDb.resetForTests 注释）
        CampusDb.resetForTests()
    }
}
