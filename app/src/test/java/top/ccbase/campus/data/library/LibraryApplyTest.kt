package top.ccbase.campus.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Content
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.DeletedTask
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.net.CatalogItem

/**
 * 学习库落库这一段（任务 / 步骤 / 资料）。
 *
 * 为什么必须真起一个库：这里要证的是**和服务端同步的相互作用** ——
 * 「服务端整体替换会把本机生成的行清掉」和「重放能救回来」这两件事
 * 都只在真表（含外键/墓碑）上才成立，纯函数测不出来。
 *
 * 用内存库、**不在收尾里 close**：界面上的 LaunchedEffect 还在收 Flow，
 * 关库会抛 `connection pool has been closed` 并漏到后面跑的类里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LibraryApplyTest {

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
        // 学习库任务挂着 course_id，而它是外键（指向 courses）。
        // 真机上课程一定在库里（用户自己的课表），这里要把前提摆上。
        runBlocking { db.dao().putCourses(listOf(Course(id = 7, name = "数学分析（I）"))) }
    }

    private fun item(url: String, course: String = "数学分析（I）", kind: String = "video") = CatalogItem(
        url = url, title = "标题 $url", kind = kind, kindLabel = "视频",
        source = "B站", course = course, note = "视频 · 来源 B站",
    )

    private fun pick(courseId: Int?, url: String, course: String = "数学分析（I）") =
        Library.pickOf(item(url, course), courseId)

    private fun tasks() = runBlocking { db.dao().visibleTasks().first() }
    private fun steps() = runBlocking { db.dao().allSteps().first() }
    private fun resources() = runBlocking { db.dao().visibleResources().first() }

    @Test
    fun 加入之后学习页读得到这条任务和它的资料() {
        val n = runBlocking { Library.add(db, listOf(pick(7, "https://a/1"), pick(7, "https://a/2"))) }

        assertEquals(2, n)
        val t = tasks().single()
        assertEquals(Library.taskIdFor(7), t.id)
        assertEquals(7, t.course_id)
        assertEquals(2, steps().count { it.task_id == t.id })
        assertEquals(
            listOf("https://a/1", "https://a/2"),
            resources().filter { it.task_id == t.id }.map { it.url },
        )
    }

    @Test
    fun 第二次挑是加进同一条任务不是再来一条() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        runBlocking { Library.add(db, listOf(pick(7, "https://a/2"))) }

        assertEquals(1, tasks().size)
        assertEquals(2, steps().size)
        assertEquals(2, resources().size)
    }

    @Test
    fun 一条都没新增就如实返回0() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        val n = runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }

        // 界面靠这个数字说话：明明一条没加却报"已加入 1 条"，就是在骗用户
        assertEquals(0, n)
        assertEquals(1, steps().size)
    }

    @Test
    fun 服务端整体替换清掉了本机行_重放把它救回来() {
        runBlocking {
            Library.add(db, listOf(pick(7, "https://a/1"), pick(null, "https://b/1", course = "")))
        }
        assertEquals(2, tasks().size)

        // 服务端每次落地计划都会整体替换内容表：不在它那份计划里的行走 prune，本机生成的两段被清掉
        runBlocking { Content.replace(db, Seed()) }
        assertEquals("前提：整体替换确实清掉了本机生成的任务", 0, tasks().size)
        assertEquals(0, steps().size)

        // 挑选记录存在 meta 里（replace 只往 meta 里加，不清空），所以重放是可靠的
        runBlocking { Library.replay(db) }

        assertEquals("挑选记录还在，重放该把两条任务都救回来", 2, tasks().size)
        assertEquals(2, steps().size)
        assertEquals(2, resources().size)
        assertTrue("课没了也不许炸（降级成自学桶）", tasks().any { it.course_id == null })
    }

    @Test
    fun 重放不清掉已经打过的勾() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        val stepId = steps().single().id
        runBlocking { db.dao().markStep(stepId, "2026-09-19") }
        assertEquals("2026-09-19", steps().single().done_day)

        runBlocking { Library.replay(db) }

        // putSteps 是 REPLACE：重放时不把 done_day 带回去，用户"只是改了个课表"就丢进度
        assertEquals("2026-09-19", steps().single().done_day)
    }

    @Test
    fun 用户把这条任务删了_重放不许把它复活() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        val tid = tasks().single().id
        runBlocking { db.dao().markTaskDeleted(DeletedTask(task_id = tid, at = "2026-09-19")) }

        runBlocking { Library.replay(db) }

        // 墓碑是"删了它又回来了"的正解：行可以在（服务端同步也会写回来），但读的时候必须过滤掉
        assertTrue("删掉的学习库任务不该出现在可见列表里", tasks().none { it.id == tid })
        assertTrue("它的资料也该跟着隐起来", resources().none { it.task_id == tid })
    }

    @Test
    fun 删掉学习库任务之后再加同一条_必须重新看得见() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        val tid = Library.taskIdFor(7)
        runBlocking { db.dao().markTaskDeleted(DeletedTask(task_id = tid, at = "2026-09-19")) }
        assertTrue("前提：删掉之后这条任务看不见了", tasks().none { it.id == tid })

        // 用户又去学习库把同一门课的资料加回来（他明确想要它）
        val n = runBlocking { Library.add(db, listOf(pick(7, "https://a/2"))) }

        assertTrue("加了却没有新增（被去重吃掉了？）", n > 0)
        assertTrue(
            "重新加入 = 用户明确要它回来，墓碑必须撤掉；不然他加完回去看\"学习\"页什么都没有，" +
                "只会以为 App 坏了（任务行其实一直在库里，只是被墓碑挡着）",
            tasks().any { it.id == tid },
        )
        assertTrue("资料也要跟着回来", resources().any { it.task_id == tid && it.url == "https://a/2" })
    }

    // ------------------------------------------------------------ 选错了能去掉（移出）
    // 用户原话里的场景：学习库里挑了一堆，加完才发现在「学习」页里多余了 ——
    // 得能就地移出，而且**移出要真的生效**：下次同步重放不许把它写回来。

    @Test
    fun 移出一条_行和挑选记录一起清掉_重放不许复活() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"), pick(7, "https://a/2"))) }
        assertEquals(2, steps().size)

        val n = runBlocking { Library.unpick(db, setOf("https://a/1")) }

        assertEquals("移出条数要照实报（界面靠它说话）", 1, n)
        assertEquals("步骤行必须真删", listOf("https://a/2"), resources().map { it.url })
        assertEquals(1, steps().size)
        assertEquals("任务还在（这门课还剩一条），标题的条数要跟着变", Library.taskTitle("数学分析（I）", 1), tasks().single().title)

        // 关键：挑选记录也清掉了 —— 否则下次同步重放（整体替换后的重放）又把它写回来
        assertEquals(listOf("https://a/2"), runBlocking { Library.picks(db) }.map { it.url })
        runBlocking { Library.replay(db) }
        assertEquals("重放之后被移出的那条不许复活", listOf("https://a/2"), resources().map { it.url })
    }

    @Test
    fun 把一门课的资料全移出_任务连墓碑一起清掉_以后还能加回来() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        val tid = Library.taskIdFor(7)
        runBlocking { db.dao().markTaskDeleted(DeletedTask(task_id = tid, at = "2026-09-19")) }

        val n = runBlocking { Library.unpick(db, setOf("https://a/1")) }

        assertEquals(1, n)
        assertTrue("任务行、步骤、资料一个都不许留", tasks().none { it.id == tid })
        assertTrue(steps().isEmpty())
        assertTrue(resources().isEmpty())
        assertEquals("挑选记录清空", 0, runBlocking { Library.picks(db) }.size)

        // 以后想把这条加回来：必须能加、也**看得见**（墓碑没清掉的话就是"加了却找不到"）
        val back = runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }
        assertEquals(1, back)
        assertTrue("加回来之后必须重新出现在可见列表里", tasks().any { it.id == tid })
    }

    @Test
    fun 移出一条本来就不在清单里的_如实返回0() {
        runBlocking { Library.add(db, listOf(pick(7, "https://a/1"))) }

        val n = runBlocking { Library.unpick(db, setOf("https://never-picked")) }

        assertEquals("不该谎报\"已移出\"", 0, n)
        assertEquals("别的条一条都不许动", 1, resources().size)
    }
}
