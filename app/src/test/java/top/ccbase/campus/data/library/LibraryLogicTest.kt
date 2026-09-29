package top.ccbase.campus.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.net.Catalog
import top.ccbase.campus.net.CatalogCourse
import top.ccbase.campus.net.CatalogItem

/**
 * 学习库的规则都写在纯函数里，所以这一类用例不需要数据库、也不需要界面。
 *
 * 钉的是两条**在界面上看不出来、改坏了也不知道**的规矩：
 *   ① 同一门课永远只有一条任务（再挑是"加进那条"，不是"再来一条"）；
 *   ② 同一条链接不会加两遍（加两遍就是两张一模一样的卡片）。
 * 外加 id 分段：本机生成的行必须躲开服务端下发的段位，否则一次同步就把它们抹了。
 */
class LibraryLogicTest {

    private fun item(
        url: String,
        title: String = "函数与极限",
        kind: String = "video",
        source: String = "B站",
        course: String = "数学分析（I）",
    ) = CatalogItem(
        url = url, title = title, kind = kind, kindLabel = "视频",
        source = source, course = course, note = "视频 · 来源 B站",
    )

    private fun pick(courseId: Int?, url: String, course: String = "数学分析（I）") =
        Library.pickOf(item(url = url, course = course), courseId)

    @Test
    fun 同一门课只建一条任务_再挑是加进那条() {
        val (one, seq1) = Library.merge(emptyList(), listOf(pick(7, "https://a/1")), 0)
        val (two, _) = Library.merge(one, listOf(pick(7, "https://a/2")), seq1)

        val rows = Library.rows(two)
        assertEquals("同一门课应该只有一条任务", 1, rows.tasks.size)
        assertEquals(2, rows.steps.size)
        assertEquals(2, rows.resources.size)
        assertEquals("步骤得挂在那条任务上", rows.tasks[0].id, rows.steps[0].task_id)
        assertEquals(rows.tasks[0].id, rows.resources[0].task_id)
        assertEquals("标题要跟着条数走", Library.taskTitle("数学分析（I）", 2), rows.tasks[0].title)
    }

    @Test
    fun 同一条链接加两遍只留一条() {
        val (one, seq1) = Library.merge(emptyList(), listOf(pick(7, "https://a/1")), 0)

        // 第一次已经加入的链接，第二次又点了一次（目录里它还在）→ 不该多一条
        val (again, seq2) = Library.merge(one, listOf(pick(7, "https://a/1")), seq1)

        assertEquals(1, again.size)
        assertEquals("没有新东西时游标不该动", seq1, seq2)
        assertEquals("重复调用不会换 id（否则重放会插出两份）", one[0].stepId, again[0].stepId)
    }

    @Test
    fun 同一门课两次挑不换id_认不出的归自学桶() {
        val (a, s1) = Library.merge(emptyList(), listOf(pick(7, "https://a/1")), 0)
        val (b, _) = Library.merge(a, listOf(pick(7, "https://a/2"), pick(null, "https://b/1", course = "")), s1)

        val rows = Library.rows(b)
        assertEquals("一门课 + 自学桶 = 两条任务", 2, rows.tasks.size)
        assertEquals("一共三条链接：两条进那门课，一条进自学桶", 3, rows.steps.size)
        // 课程 id 为空的进自学桶
        val self = rows.tasks.first { it.course_id == null }
        assertEquals(Library.taskIdFor(null), self.id)
        // 第 3 条（自学桶那条）的 url 是 b/1
        assertTrue(rows.resources.any { it.url == "https://b/1" && it.task_id == self.id })
    }

    @Test
    fun 课程名全角半角括号都认得出() {
        val courses = listOf(
            Course(id = 3, name = "程序设计基础（B）", teacher = null),
            Course(id = 9, name = "数学分析"),
        )
        // 目录里的名字来自别的同学的课表，括号全角/半角、后缀有无都可能差一点；
        // 逐字相等会大面积认不出，于是所有资料都掉进自学桶（界面上看不出来，只是"按课程"没意义了）
        assertEquals(3, Library.matchCourse(courses, "程序设计基础（B）"))
        assertEquals("目录里写半角，也要认出同一门", 3, Library.matchCourse(courses, "程序设计基础(B)"))
        assertEquals("目录里多带了后缀，本机课名没有，也要认出", 9, Library.matchCourse(courses, "数学分析（I）"))
        assertEquals("自学那一桶不该硬塞给某门课", null, Library.matchCourse(courses, Library.SELF_KEY))
        assertEquals("认不出就是 null，不要瞎认", null, Library.matchCourse(courses, "马克思主义基本原理"))
    }

    @Test
    fun 段位要躲开服务端下发的段() {
        val (one, _) = Library.merge(emptyList(), listOf(pick(7, "https://a/1")), 0)
        val rows = Library.rows(one)

        val t = rows.tasks[0].id
        assertTrue("任务 id 必须在 3_000_000 段内，got=$t", t in 3_000_000 until 4_000_000)
        assertTrue("步骤 id 在 4_000_000 段，got=${rows.steps[0].id}", rows.steps[0].id in 4_000_000 until 5_000_000)
        assertTrue("资料 id 在 5_000_000 段，got=${rows.resources[0].id}", rows.resources[0].id in 5_000_000 until 6_000_000)
        assertTrue(Library.isLibraryTask(t))
        assertTrue("服务端下发的行不该被认成学习库任务", !Library.isLibraryTask(1201))
    }

    @Test
    fun 重放的时候打勾不能丢() {
        val (picks, _) = Library.merge(emptyList(), listOf(pick(7, "https://a/1"), pick(7, "https://a/2")), 0)
        val stepId = picks[0].stepId

        val rows = Library.rows(picks, done = mapOf(stepId to "2026-09-19"))

        assertEquals("打过勾的步骤要原样带回来（putSteps 是 REPLACE，不带就等于清空）",
            "2026-09-19", rows.steps.first { it.id == stepId }.done_day)
        assertNull(rows.steps.first { it.id != stepId }.done_day)
    }

    @Test
    fun 学习库的资料不带别人的why() {
        val (picks, _) = Library.merge(emptyList(), listOf(pick(7, "https://a/1")), 0)
        val rows = Library.rows(picks)

        // 目录是别人的收藏汇总来的。"为什么推给他"带个人语境（隐私红线），
        // 详情页因此只该显示一条空 why（界面会跳过不画），不能出现别人的原话。
        assertNull(rows.resources[0].why)
        assertEquals("https://a/1", rows.resources[0].url)
        assertEquals("B站", rows.resources[0].source)
        assertEquals(Library.PHASE, rows.tasks[0].phase)
    }

    @Test
    fun 目录条目字段搬家不丢东西_步骤类型按目录分类映射() {
        val p = pick(7, "https://a/1")
        assertEquals("函数与极限", p.title)
        assertEquals("视频 · 来源 B站", p.note)
        assertEquals(Library.stepKind("video"), "watch")
        assertEquals(Library.stepKind("course"), "watch")
        assertEquals(Library.stepKind("practice"), "practice")
        assertEquals(Library.stepKind("doc"), "read")
        assertNotNull("未知类型也得给个兜底，不能是空", Library.stepKind("whatever"))
    }

    // ------------------------------------------ 「按课程」那一列（课程列表 + 课程详情）
    //
    // 用户原话：「我希望用户可以在学习库中查看每个课程的详细内容」。
    // 这里的规则错了，界面上不会报错 —— 只会"某门课的资料不见了"或"条数对不上"，
    // 所以分组/认领/计数都必须在纯函数上钉住。

    /** 目录夹具（courses/kinds 由服务端汇总，这里按 items 自己算一份，形状与真接口一致）。 */
    private fun cat(items: List<CatalogItem>) = Catalog(
        items = items,
        courses = items.groupBy { it.course }.map { CatalogCourse(it.key, it.value.size) },
        kinds = emptyList(),
        total = items.size,
        generatedAt = "2026-09-19T16:00:00+08:00",
    )

    @Test
    fun 学习库里的课带公开信息_别人在学的课也看得到() {
        // 用户要的：「所有用户的课程都要出现在同一个学习库中」+「能直接看到每个课程的详细信息」。
        // 别人在学、我课表里没有的课：本机没有 Course 行，但服务端给的公开信息必须在。
        val c = Catalog(
            items = listOf(item("https://ai/1", course = "人工智能基础")),
            courses = listOf(
                CatalogCourse("人工智能基础", 1, learners = 1, teacher = "艾尔西丁", credits = 2.0),
            ),
            kinds = emptyList(), total = 1, generatedAt = "2026-09-20T12:00:00+08:00",
        )

        val row = Library.courseRows(c, emptyList()).body.single()

        assertEquals("人工智能基础", row.name)
        assertEquals("没有本机课程行", null, row.course)
        assertEquals("别人在学的课也要能点进去看信息", 1, row.shared?.learners)
        assertEquals("艾尔西丁", row.shared?.teacher)
        assertEquals(2.0, row.shared?.credits)
    }

    @Test
    fun 认得出的课也带上学习库的公开信息() {
        val courses = listOf(Course(id = 2, name = "数学分析（I）", teacher = "张三"))
        val c = Catalog(
            items = listOf(item("https://a/1", course = "数学分析（I）")),
            courses = listOf(CatalogCourse("数学分析（I）", 1, learners = 5, teacher = "张三", credits = 3.0)),
            kinds = emptyList(), total = 1, generatedAt = "2026-09-20T12:00:00+08:00",
        )

        val row = Library.courseRows(c, courses).body.single()

        assertEquals("几个人在学要给出来（讲师/学分认本机课程行）", 5, row.shared?.learners)
        assertEquals(2, row.course?.id)
    }

    @Test
    fun 老服务端不给公开信息也不炸() {
        // 老服务端只回 name/count：字段全有默认值 → 0/空串，界面显示"这门课还没有更多信息"
        val c = cat(listOf(item("https://a/1", course = "数学分析（I）")))

        val row = Library.courseRows(c, emptyList()).body.single()

        assertEquals(0, row.shared?.learners)
        assertEquals("", row.shared?.teacher)
        assertEquals(0.0, row.shared?.credits)
    }

    @Test
    fun 课表里的每门课都列出来_有资料的排前面_自学桶最后() {
        val courses = listOf(
            Course(id = 1, name = "军事理论导论", teacher = "李四"),      // 全班还没攒到资料
            Course(id = 2, name = "数学分析（I）", teacher = "张三"),
            Course(id = 3, name = "通用英语", teacher = "王五"),
        )
        val c = cat(
            listOf(
                item("https://a/1", course = "数学分析（I）"),
                item("https://a/2", course = "数学分析（I）"),
                item("https://e/1", course = "通用英语"),
                item("https://s/1", course = Library.SELF_KEY),
                item("https://b/1", course = "马克思主义基本原理"),   // 我课表里没有的课
            ),
        )

        val l = Library.courseRows(c, courses)

        assertEquals(
            "有资料的课按条数降序，认不出的课（别的班的）也不能丢",
            listOf("数学分析（I）", "通用英语", "马克思主义基本原理"),
            l.body.map { it.name },
        )
        assertEquals("没资料的课也要列出来 —— 用户要的是每个课程都能进去看", listOf("军事理论导论"), l.empty.map { it.name })
        assertEquals("自学桶永远最后", Library.SELF_KEY, l.self?.name)
        assertEquals(1, l.self?.count)
        assertEquals("认得出课程的条目要带上本机课程行（详情页靠它显示教师/学分/上课时间）", 2, l.body.first().course?.id)
        assertEquals("别的班的课在本机认不出，就没有课程信息可显示", null, l.body.last().course)
    }

    @Test
    fun 同名优先_一门目录课只许被一门本机课认领() {
        // 两门本机课都能"宽松"匹配到同一门目录课。两趟认领（先整名相等）是必要的：
        // 一趟到底的话 `数学分析` 会先把 `数学分析（I）` 认领走，真正同名那门反而"暂无资料"。
        val courses = listOf(Course(id = 1, name = "数学分析"), Course(id = 2, name = "数学分析（I）"))
        val l = Library.courseRows(cat(listOf(item("https://a/1", course = "数学分析（I）"))), courses)

        assertEquals("认领它的是整名相等的那门课", 2, l.body.single().course?.id)
        assertEquals("另一门课该显示'暂无资料'，而不是把同一批资料再算一遍", listOf("数学分析"), l.empty.map { it.name })
    }

    // ---------------------------------------------------------- 搜索（本地过滤）
    // 搜索规则拿纯函数钉住：中文课程名上的全角/半角括号、大小写、空格是天天出事的细节，
    // 在界面上看只会表现为"搜不出来"，查起来只能靠猜。

    @Test
    fun 搜索命中面含标题_来源_课程_链接_且全角半角括号通吃() {
        val a = item("https://a/1", title = "函数与极限", course = "数学分析（I）")
        val b = item("https://b/1", title = "指针入门", course = "C程序设计基础（B）")
        val all = listOf(a, b)

        assertEquals("标题命中", listOf(a), Library.searchItems(all, "极限"))
        assertEquals("来源命中", all, Library.searchItems(all, "B站"))
        assertEquals("大小写不敏感", listOf(b), Library.searchItems(all, "c程序设计"))
        assertEquals("全角「（B）」和半角「(B)」要能互搜", listOf(b), Library.searchItems(all, "C程序设计基础(B)"))
        assertEquals("链接也命中", listOf(a), Library.searchItems(all, "a/1"))
        assertEquals("空词（含只有空格）不过滤", all, Library.searchItems(all, "   "))
        assertEquals("搜不到就是空，不许把不相关的塞回来", emptyList<CatalogItem>(), Library.searchItems(all, "量子力学"))
    }

    @Test
    fun 课程行按课名和教师搜_没命中的块不渲染() {
        val courses = listOf(
            Course(id = 1, name = "军事理论导论", teacher = "李四"),
            Course(id = 2, name = "数学分析（I）", teacher = "张三"),
        )
        val l = Library.courseRows(
            cat(listOf(item("https://a/1", course = "数学分析（I）"), item("https://s/1", course = Library.SELF_KEY))),
            courses,
        )

        val byName = Library.searchCourseList(l, "数学")
        assertEquals("只留命中的课程", listOf("数学分析（I）"), byName.body.map { it.name })
        assertTrue("没命中的课程块要空掉，不留空标题", byName.empty.isEmpty())
        assertNull("自学桶不命中就整个不渲染", byName.self)

        val byTeacher = Library.searchCourseList(l, "李四")
        assertEquals("教师也搜得到 —— 全班还没资料的课照样能被搜出来", listOf("军事理论导论"), byTeacher.empty.map { it.name })
        assertTrue(byTeacher.body.isEmpty())
        assertNull(byTeacher.self)

        val all = Library.searchCourseList(l, "")
        assertEquals("空词不过滤：三块都还在", 3, all.body.size + all.empty.size + (if (all.self != null) 1 else 0))
    }

    @Test
    fun 列表上的条数和点进去数出来的条数必须一致() {
        val courses = listOf(Course(id = 2, name = "数学分析（I）"))     // 全角括号的本机课名
        val c = cat(listOf(item("https://a/1", course = "数学分析（I）"), item("https://a/2", course = "数学分析（I）")))
        val row = Library.courseRows(c, courses).body.single()

        assertEquals("两个数都从同一份 items 算出来，不许一个走汇总一个走明细", row.count, Library.itemsOf(c, row).size)
        assertTrue(
            "取资料要按目录里的课名 —— 拿本机那个全角括号的名字去比，一条都取不到",
            Library.itemsOf(c, row).any { it.url == "https://a/1" },
        )
    }

    @Test
    fun 自学桶的资料也取得到() {
        val c = cat(listOf(item("https://s/1", course = ""), item("https://s/2", course = Library.SELF_KEY)))
        val self = Library.courseRows(c, emptyList()).self!!

        assertEquals("课程名为空 = 自学桶", 2, self.count)
        assertEquals(2, Library.itemsOf(c, self).size)
    }

    @Test
    fun 资料按类型分组_顺序固定_空组不占位置_一条都不丢() {
        val items = listOf(
            item("u1", kind = "practice"),
            item("u2", kind = "video"),
            item("u3", kind = "weird"),
            item("u4", kind = "doc"),
            item("u5", kind = "video"),
        )
        val g = Library.kindGroups(items)

        assertEquals("顺序固定（同一份数据两次渲染必须一样）", listOf("视频", "文档", "练习", "资料"), g.map { it.label })
        assertEquals(listOf(2, 1, 1, 1), g.map { it.items.size })
        assertEquals("空组不占位置（没有慕课就别画一行「慕课 0」）", 4, g.size)
        assertEquals("未知类型不许把英文原值端到界面上", "资料", Library.kindLabelOf("weird"))
        assertEquals("分组不许丢条目", items.size, g.sumOf { it.items.size })
    }

    @Test
    fun 已加入的链接能一眼认出来() {
        // 界面靠这个集合给条目打「已加入」标记 —— 认错了的后果是用户重复挑同一条资料，
        // 勾一遍、点"加入"、被去重吃掉，才发现"这些都已在你的清单里了"（白折腾一遍）
        val picks = listOf(pick(7, "https://a/1"), pick(null, " https://s/1 "))

        assertEquals(setOf("https://a/1", "https://s/1"), Library.pickedUrls(picks))
        assertTrue("一条都没挑时不该冒出标记", Library.pickedUrls(emptyList()).isEmpty())
    }
}
