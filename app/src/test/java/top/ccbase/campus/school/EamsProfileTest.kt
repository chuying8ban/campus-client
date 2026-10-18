package top.ccbase.campus.school

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb

/**
 * 学籍卡片的数据层。要钉死的是**"不许把没取到当成取到了"**：
 * 教务把这页当 HTML 渲染，字段名/顺序一旦变了，JS 那边就会交出 `{}`
 * 或者一堆 `--`。那种东西存进去，界面上就会显示成一片空白卡片，
 * 用户会以为"我的班级是空的"。所以：抽不出来就不覆盖旧数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EamsProfileTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun db(): CampusDb = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `真实形状的 JSON 能解出六项`() {
        val raw = """
            {"studentId":"2026015842","college":"数理与人工智能学院","major":"计算机类",
             "adminClass":"计算机类26-6班","counselor":"邢淑汶","status":"在读",
             "campus":"克拉玛依校区","grade":"2026","program":"计算机类专业2026级本科培养方案"}
        """.trimIndent()
        val p = SchoolProfileCache.decode(raw)!!
        assertEquals("计算机类26-6班", p.adminClass)
        assertEquals("邢淑汶", p.counselor)
        assertTrue(p.hasAnything)
        // 界面上一行一项，顺序固定（学院→专业→班级→辅导员→学籍→校区）
        assertEquals(listOf("学院", "专业", "班级", "辅导员", "学籍", "校区"), p.rows().map { it.first })
    }

    @Test
    fun `教务用双减号表示没填 不能当成值显示`() {
        val raw = """{"major":"计算机类","adminClass":"--","counselor":"—","status":""}"""
        val p = SchoolProfileCache.decode(raw)!!
        assertNull(p.adminClass)
        assertNull(p.counselor)
        assertNull(p.status)
        assertEquals(listOf("专业"), p.rows().map { it.first })
    }

    @Test
    fun `全空或非法 JSON 都当没取到`() {
        assertNull(SchoolProfileCache.decode("{}"))
        assertNull(SchoolProfileCache.decode(""))
        assertNull(SchoolProfileCache.decode("null"))
        assertNull(SchoolProfileCache.decode("这不是 JSON"))
        // 只有学号不算"有学籍信息"—— 卡片要的是班级/辅导员那类
        assertNull(SchoolProfileCache.decode("""{"studentId":"2026015842"}"""))
    }

    @Test
    fun `写入与读取`() {
        val db = db()
        val raw = """{"college":"数理与人工智能学院","major":"计算机类","adminClass":"计算机类26-6班","counselor":"邢淑汶"}"""
        assertTrue(runBlocking { SchoolProfileCache.save(db, raw, LocalDate.of(2026, 10, 18)) })
        val loaded = runBlocking { SchoolProfileCache.load(db) }
        assertEquals("计算机类26-6班", loaded?.adminClass)
        assertEquals("2026-10-18", runBlocking { SchoolProfileCache.updatedAt(db) })
    }

    @Test
    fun `取不到时不覆盖上一份`() {
        val db = db()
        runBlocking {
            SchoolProfileCache.save(db, """{"adminClass":"计算机类26-6班","counselor":"邢淑汶"}""", LocalDate.of(2026, 10, 18))
        }
        // 教务改版 / 未登录：JS 交出的是一份空对象
        assertFalse(runBlocking { SchoolProfileCache.save(db, "{}", LocalDate.of(2026, 10, 25)) })
        val loaded = runBlocking { SchoolProfileCache.load(db) }
        assertEquals("计算机类26-6班", loaded?.adminClass)
        // 时间也不能被"失败的一次"刷新成假的"刚更新过"
        assertEquals("2026-10-18", runBlocking { SchoolProfileCache.updatedAt(db) })
    }
}
