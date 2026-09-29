package top.ccbase.campus.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.net.PlanSubject

/**
 * 「AI 规划学习计划」表单 → 入参这一层（纯函数，不起界面）。
 *
 * 用户原话：
 *  - 「我希望尽量详细，让用户自由选择自己想要的计划」；
 *  - 「这个基础自评指代不清，应该给每个课程分开自评」。
 *
 * 这里钉的是**发出去的那份东西**：
 *  1. 没填的分组就是没填（null → 编码器整段省掉 → 老服务端连字段都看不到，不会 422）；
 *  2. 逐门课只带用户**真的评过**的课，没评的不参与 —— 不往里塞一串中间档当噪音；
 *  3. 课名只从本地课表来（去空、去重、截到服务端上限），绝不写死课程名；
 *  4. 排除项界面显示的字数 == 发出去的字数（让他看到 80 字实际只发 60 字，等于骗他）；
 *  5. 小时档位落在服务端 clamp 的 1~40 里（老界面只给 2/5/10 却写「上限 40」，自相矛盾）。
 */
class PlanFormLogicTest {

    @Test
    fun `没填的分组一律是 null_老服务端只看到老四项`() {
        val p = PlanLogic.prefs()

        assertNull("目标没填就不该发", p.goals)
        assertNull("时段没填就不该发", p.periods)
        assertNull("每周几天没填就不该发", p.daysPerWeek)
        assertNull("一门课都没评就不该发 subjects", p.subjects)
        assertNull(p.pace)
        assertNull(p.avoid)

        // 老四项照旧（服务端 prompt 与缓存键逐字依赖它们）
        assertEquals(emptyList<String>(), p.focus)
        assertEquals(5, p.hours)
        assertEquals(emptyList<String>(), p.style)
        assertEquals("入门", p.level)
    }

    @Test
    fun `填了的分组按白名单过滤后带上`() {
        val p = PlanLogic.prefs(
            focus = listOf("编程", "写代码"),        // 「写代码」不在界面上，也不许从别的地方混进来
            style = listOf("刷题"),
            hours = 12,
            goals = listOf("补弱", "中彩票"),
            periods = listOf("晚", "凌晨"),
            daysPerWeek = 4,
            pace = "冲刺式",
            avoid = "  周日   别排   ",
        )

        assertEquals(listOf("编程"), p.focus)
        assertEquals(listOf("刷题"), p.style)
        assertEquals(12, p.hours)
        assertEquals(listOf("补弱"), p.goals)
        assertEquals(listOf("晚"), p.periods)
        assertEquals(4, p.daysPerWeek)
        assertEquals("冲刺式", p.pace)
        assertEquals("周日 别排", p.avoid)
    }

    @Test
    fun `逐门课只带评过的_没评的不参与`() {
        val p = PlanLogic.prefs(scores = linkedMapOf("数学分析（I）" to 1, "通用英语" to 5))

        assertEquals(listOf(PlanSubject("数学分析（I）", 1), PlanSubject("通用英语", 5)), p.subjects)
    }

    @Test
    fun `课名只从课表来_去空去重_跟随课表顺序_截到服务端上限`() {
        val names = listOf("数学分析（I）", "  ", " 数学分析（I） ", "通用英语") + (1..15).map { "课$it" }
        val rows = PlanLogic.subjectNames(names)

        assertEquals("数学分析（I）", rows[0])
        assertEquals("通用英语", rows[1])
        assertEquals("同名课分了班也只该出现一次", rows.size, rows.distinct().size)
        assertEquals("超上限的课要给服务端截掉，不是发过去让它自己丢", PlanLogic.SUBJECT_LIMIT, rows.size)
    }

    @Test
    fun `课名超长按服务端口径截 20 字`() {
        val long = "这是一个特别特别长的课程名字用来测试截断行为到底对不对"
        val p = PlanLogic.prefs(scores = mapOf(long to 3))

        assertEquals(20, p.subjects!!.first().name.length)
        assertEquals(long.take(20), p.subjects!!.first().name)
    }

    @Test
    fun `小时档位都落在服务端区间内_超了夹到 40 而不是发过去让它自己夹`() {
        assertTrue(PlanLogic.HOURS.all { it in 1..40 })
        assertEquals(40, PlanLogic.clampHours(999))
        assertEquals(1, PlanLogic.clampHours(0))
        assertEquals(40, PlanLogic.prefs(hours = 400).hours)
    }

    @Test
    fun `排除项压掉多余空白并截到 60_界面显示的就是发出去的`() {
        assertEquals("周日 别排", PlanLogic.avoidText("  周日 \n 别排  "))

        val long = "啊".repeat(120)
        assertEquals(60, PlanLogic.avoidText(long).length)
        assertEquals(60, PlanLogic.prefs(avoid = long).avoid!!.length)

        assertNull("只敲了空格等于没填", PlanLogic.prefs(avoid = "   \n  ").avoid)
    }

    @Test
    fun `新分组的取值逐字对齐服务端白名单`() {
        // 服务端 ai_plan.py 的 GOALS / PERIODS / PACES。白名单外的值服务端直接丢，
        // 所以这里改一个字，用户选了都会静默失效 —— 必须钉死。
        assertEquals(listOf("补弱", "冲绩点", "备四级", "竞赛", "考研预习", "兴趣拓展"), PlanLogic.GOALS)
        assertEquals(listOf("早", "午", "晚"), PlanLogic.PERIODS)
        assertEquals(listOf("每天固定时长", "周末集中", "冲刺式"), PlanLogic.PACES)
        assertEquals("服务端 name[:20]", 20, PlanLogic.SUBJECT_NAME_LIMIT)
        assertEquals("服务端 MAX_SUBJECTS", 12, PlanLogic.SUBJECT_LIMIT)
        assertEquals("服务端 MAX_AVOID", 60, PlanLogic.AVOID_LIMIT)
    }

    // ---------------------------------------- 「加入清单」之后的可见反馈（纯函数层）

    @Test
    fun `加入按钮三种样子_还没加_正在加_已经加完`() {
        assertEquals(PlanLogic.APPLY, PlanLogic.applyLabel(step = "", appliedCount = 0))
        // 正在加的时候按钮上要写正在加，不能还是「加入任务清单」装没事
        assertEquals(
            PlanLogic.APPLYING,
            PlanLogic.applyLabel(step = PlanLogic.APPLYING, appliedCount = 0),
        )
        // 加完了还写着「加入任务清单」= 用户根本不知道到底成没成（这就是用户提的意见）
        assertEquals(
            "${PlanLogic.APPLY_DONE}（4 条）",
            PlanLogic.applyLabel(step = "", appliedCount = 4),
        )
        assertTrue(
            "已加入的按钮不该再是「${PlanLogic.APPLY}」",
            PlanLogic.applyLabel(step = "", appliedCount = 4) != PlanLogic.APPLY,
        )
    }

    @Test
    fun `逐条的已加入标记和按钮文案不是同一串_免得数错条数`() {
        // 按钮文案里若含 APPLIED_BADGE，界面上「已加入」的节点数会比实际条数多一个，
        // 测试就数不准了（这条钉住它）
        assertTrue(!PlanLogic.APPLY_DONE.contains(PlanLogic.APPLIED_BADGE))
    }

    // ------------------------------------------------------------------
    // 「你的选择 → 会怎样推荐」预览
    //
    // 用户 2026-09-20 的原话：「把 AI 学习规划里面的选项做的易懂一些，要让用户知道
    // 自己的选择对课程的推荐到底有什么影响」。
    // 这里钉的是**翻译得准不准**：说出来的每一句都要对得上服务端真正用到的偏好，
    // 而且一项都没填就什么都不说 —— 不摆一坨空话。
    // ------------------------------------------------------------------

    @Test
    fun `一项都没填就不显示预览`() {
        assertNull("全空 → 一句话都不该有", PlanLogic.effectSummary())
    }

    @Test
    fun `填了就要逐项翻译成对推荐的影响`() {
        val s = PlanLogic.effectSummary(
            focus = listOf("编程", "数学"),
            hours = 8,
            style = listOf("看视频"),
            goals = listOf("补弱", "冲绩点"),
            periods = listOf("早", "晚"),
            daysPerWeek = 3,
            scored = listOf("数学分析（I）" to 2, "通用英语" to 5),
            pace = "周末集中",
            avoid = "周日别排",
        )!!
        assertTrue("开头要说清这是什么：$s", s.startsWith("会这样推荐："))
        for (bit in listOf(
            "围绕「补弱、冲绩点」",
            "多推编程、数学",
            "每周约 8 小时的量",
            "只排在「早、晚」",
            "一周 3 天",
            "优先看视频的形式",
            "按「周末集中」摊",
            "数学分析（I）多推",
            "通用英语少推",
            "不出现「周日别排」",
        )) {
            assertTrue("少了「$bit」：$s", s.contains(bit))
        }
    }

    @Test
    fun `自评中间档不编方向`() {
        // 3 分是中间档，服务端也只是软侧重 —— 与其编一个方向，不如这块不显示
        assertNull(PlanLogic.effectSummary(scored = listOf("数学分析（I）" to 3)))
    }

    @Test
    fun `排除项前后的空格要去掉再显示`() {
        val s = PlanLogic.effectSummary(avoid = "  周日  ")!!
        assertTrue(s, s.contains("不出现「周日」"))
    }
}
