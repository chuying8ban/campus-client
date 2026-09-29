package top.ccbase.campus.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.seed.Seed

/**
 * 用**线上真实报文**跑解析 + 落库。
 *
 * 夹具 `live_plan_sample_2.json` 是 2026-09-17 从生产库导出的某位同学的真实规划
 * （服务端 /api/v2/plan 的同一个构造函数生成的）。同学反馈点「立即更新课表」闪退，
 * 而 api.plan() 的解析失败只会返回错误文案，所以嫌疑在落库这一步。
 *
 * 教训同 `dto-contract-and-dirty-values.md`：只用自己造的漂亮数据测，测不出真实形状。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePlanApplyTest {

    private lateinit var db: CampusDb

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `线上真实规划能解析`() {
        val text = read()
        val seed = json.decodeFromString<Seed>(text)
        println("[真实报文] courses=${seed.courses.size} slots=${seed.slots.size} tasks=${seed.tasks.size}")
        println("[真实报文] resources=${seed.resources?.size} steps=${seed.study_steps.size} " +
            "milestones=${seed.milestones?.size} checklist=${seed.checklist?.size} selfstudy=${seed.selfstudy?.size}")
    }

    @Test
    fun `线上真实规划能落库`() = runBlocking {
        val seed = json.decodeFromString<Seed>(read())
        val res = PlanApplier.apply(db, seed, at = "2026-09-17T21:00:00")
        println("[落库结果] $res")
        println("[落库校验] courses=${db.dao().courses().first().size} slots=${db.dao().slots().first().size} " +
            "steps=${db.dao().allSteps().first().size}")
    }

    private fun read(): String =
        javaClass.classLoader!!.getResource("live_plan_sample_2.json")!!.readText()
}
