package top.ccbase.campus.school

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 通用课表表格导入 —— 「教务接口不灵时的最后一手」。
 *
 * 为什么要它：内置 WebView 走 `print-data` JSON 是最省事的一条，但它依赖两件事
 * （会话有效 + 教务接口没改）。学校一改版、一换皮肤、会话一失效，学生手上就只剩
 * 一张**能在浏览器里看见的课表**。这条路径就是吃那张课表：
 *
 *   浏览器里全选复制 → 粘进来   （纯文本表格）
 *   或者 View Source / 保存网页 → 粘 HTML （带 <table>）
 *
 * 算法借自 WakeUp 的通用导入思路：不认学校的 class 名和 DOM 结构，只做三件事——
 *   ① 把 `<td rowspan colspan>` 还原成**行 × 列矩阵**（真实网格）；
 *   ② 用表头里的「星期一……星期日」定位课程落在哪一天，用首列「第N节」定位第几节；
 *   ③ 对单元格文字做**字段抠取**（周次 / 地点 / 教师 / 课程名），完全不靠皮肤。
 *
 * 纯 Kotlin、无 Android 依赖，所以能在 JVM 单测里直接喂样本跑（见 ScheduleGridImportTest）。
 */
object ScheduleGridImport {

    /** 一门课的一段（同课不同天/不同节算多个 Course）。 */
    data class Course(
        val courseName: String,
        val weekday: Int,
        val startUnit: Int,
        val endUnit: Int,
        val weeks: List<Int>,
        val room: String? = null,
        val teacher: String? = null,
    ) {
        /** 同一段课（rowspan 展开会重复出现），落库前按这个去重。 */
        internal fun key() = listOf(courseName, weekday, startUnit, endUnit, weeks, room, teacher)
    }

    data class Result(
        val courses: List<Course>,
        /** 认得出是单元格、但抠不出课程名的那些格子 —— 不猜，报出来。 */
        val warnings: List<String>,
        /** 数据来源：html / text。 */
        val source: String,
    ) {
        val isEmpty: Boolean get() = courses.isEmpty()

        fun summary(): String {
            if (courses.isEmpty()) return "没认出任何课程"
            val names = courses.map { it.courseName }.distinct()
            val rooms = courses.mapNotNull { it.room }.distinct()
            return "${names.size} 门课 · ${courses.size} 段 · ${rooms.size} 个地点" +
                if (warnings.isNotEmpty()) " · ${warnings.size} 个格子没认出" else ""
        }

        /** 转成 App 内部统一吃的 activities 数组（字段与教务 print-data 对齐）。 */
        fun activitiesJson(): String = buildJsonArray {
            for (c in courses) {
                add(
                    buildJsonObject {
                        put("courseName", c.courseName)
                        put("weekday", c.weekday)
                        put("startUnit", c.startUnit)
                        put("endUnit", c.endUnit)
                        put("weekIndexes", JsonArray(c.weeks.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        put("weeksStr", weeksToText(c.weeks))
                        c.room?.let { put("room", it) }
                        c.teacher?.let { put("teachers", it) }
                    },
                )
            }
        }.toString()
    }

    // ─────────────────────────── 入口 ───────────────────────────

    /**
     * @param input HTML 片段、整页源码，或从浏览器复制出来的表格文字，都行。
     * @param maxWeek 周次上限（超过的当噪声丢掉）。
     */
    fun parse(input: String, maxWeek: Int = 30, defaultWeeks: IntRange = 1..16): Result {
        val raw = normalize(input)
        if (raw.isBlank()) return Result(emptyList(), listOf("内容是空的"), "text")
        return if (looksLikeHtml(raw)) parseHtml(raw, maxWeek, defaultWeeks)
        else parseText(raw, maxWeek, defaultWeeks)
    }

    private fun looksLikeHtml(s: String): Boolean =
        s.contains("<table", ignoreCase = true) || s.contains("<td", ignoreCase = true) ||
            s.contains("<tr", ignoreCase = true)

    // ─────────────────────────── HTML 路径 ───────────────────────────

    private fun parseHtml(html: String, maxWeek: Int, defaultWeeks: IntRange): Result {
        val tables = htmlTables(html)
        if (tables.isEmpty()) return Result(emptyList(), listOf("这段 HTML 里没有表格"), "html")
        // 多张表时挑「最像课表」的那张：能认出星期表头 + 课程格最多的
        var best: Pair<Int, Result>? = null
        tables.forEachIndexed { idx, grid ->
            val r = readGrid(grid, maxWeek, defaultWeeks, "html")
            val score = r.courses.size * 10 + (if (r.warnings.isEmpty()) 1 else 0) - idx
            if (best == null || score > best!!.first) best = score to r
        }
        return best!!.second
    }

    private data class RawCell(val text: String, val rowspan: Int, val colspan: Int)

    /** 把 HTML 里所有 `<table>` 抠成「行 → 原始单元格」。只处理第一层表格，嵌套的当纯文本。 */
    private fun htmlTables(html: String): List<List<List<RawCell>>> {
        val out = mutableListOf<List<List<RawCell>>>()
        var depth = 0
        var rows = mutableListOf<MutableList<RawCell>>()
        var row: MutableList<RawCell>? = null
        var cellBuf: StringBuilder? = null
        var cellSpan = 1 to 1
        var last = 0

        fun closeCell() {
            val buf = cellBuf ?: return
            row?.add(RawCell(buf.toString(), cellSpan.first, cellSpan.second))
            cellBuf = null
            cellSpan = 1 to 1
        }
        fun closeRow() {
            closeCell()
            row?.let { if (it.isNotEmpty()) rows.add(it) }
            row = null
        }

        for (m in TAG.findAll(html)) {
            val between = html.substring(last, m.range.first)
            last = m.range.last + 1
            if (depth >= 1) cellBuf?.append(between)
            val closing = m.groupValues[1].isNotEmpty()
            when (m.groupValues[2].lowercase()) {
                "table" -> {
                    if (!closing) {
                        depth++
                        if (depth == 1) { rows = mutableListOf(); row = null; cellBuf = null }
                    } else if (depth > 0) {
                        depth--
                        if (depth == 0) { closeRow(); if (rows.isNotEmpty()) out.add(rows) }
                    }
                }
                "tr" -> if (depth == 1) { if (!closing) closeRow(); else closeRow() }
                "td", "th" -> if (depth == 1) {
                    if (!closing) {
                        closeCell()
                        if (row == null) row = mutableListOf()
                        cellBuf = StringBuilder()
                        cellSpan = spanOf(m.groupValues[3])
                    } else closeCell()
                }
            }
        }
        return out
    }

    private val TAG = Regex("<\\s*(/?)\\s*(table|tr|td|th)\\b([^>]*)>", RegexOption.IGNORE_CASE)

    private fun spanOf(attrs: String): Pair<Int, Int> {
        fun pick(name: String): Int {
            val m = Regex("$name\\s*=\\s*[\"']?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(attrs) ?: return 1
            return m.groupValues[1].toIntOrNull()?.coerceIn(1, 20) ?: 1
        }
        return pick("rowspan") to pick("colspan")
    }

    // ─────────────────────────── 网格还原 ───────────────────────────

    /** rowspan/colspan → 稠密矩阵。跨行跨列的那格在覆盖到的每个位置都出现同一段文字。 */
    private fun expand(rows: List<List<RawCell>>): List<List<String>> {
        val grid = mutableListOf<MutableList<String>>()
        fun ensure(r: Int) { while (grid.size <= r) grid.add(mutableListOf()) }

        rows.forEachIndexed { ri, cells ->
            ensure(ri)
            var ci = 0
            for (cell in cells) {
                while (grid[ri].getOrNull(ci)?.isNotEmpty() == true) ci++
                for (dr in 0 until cell.rowspan) {
                    ensure(ri + dr)
                    for (dc in 0 until cell.colspan) {
                        val r = ri + dr
                        val c = ci + dc
                        while (grid[r].size <= c) grid[r].add("")
                        if (grid[r][c].isEmpty()) grid[r][c] = cell.text
                    }
                }
                ci += cell.colspan
            }
        }
        return grid.map { it.toList() }
    }

    private fun readGrid(raw: List<List<RawCell>>, maxWeek: Int, defaultWeeks: IntRange, source: String): Result {
        var grid = expand(raw)
        if (grid.isEmpty()) return Result(emptyList(), emptyList(), source)

        // 有的学校把星期放行首（转置表），先看第一列能不能认出 ≥3 个星期
        val colHits = grid.count { weekdayOf(it.firstOrNull().orEmpty()) != null }
        val rowHits = grid.firstOrNull()?.count { weekdayOf(it) != null } ?: 0
        if (colHits >= 3 && rowHits < 3) grid = transpose(grid)

        val headerIdx = grid.indexOfFirst { row -> row.count { weekdayOf(it) != null } >= 3 }
        if (headerIdx < 0) return Result(emptyList(), listOf("没找到「星期一…星期日」这样的表头"), source)

        val header = grid[headerIdx]
        val colWeekday = mutableMapOf<Int, Int>()
        header.forEachIndexed { ci, text -> weekdayOf(text)?.let { colWeekday[ci] = it } }

        val courses = mutableListOf<Course>()
        val warnings = mutableListOf<String>()
        var lastSpan: Pair<Int, Int>? = null
        for (ri in headerIdx + 1 until grid.size) {
            val row = grid[ri]
            // 「上午/下午/中午」这种整行铺满的分隔行（colspan 跨全表 → expand 后每列都是同一段文字）
            // 不是课，跳过；它也不该动 lastSpan。
            if (isSeparatorRow(row)) continue
            val (start, end) = resolveSpan(row.firstOrNull().orEmpty(), lastSpan).also { lastSpan = it }
            for ((ci, wd) in colWeekday) {
                val text = row.getOrNull(ci).orEmpty()
                if (text.isBlank()) continue
                val got = extractBlock(text, Hint(wd, start, end), maxWeek, defaultWeeks)
                if (got.isEmpty()) {
                    if (text.trim().length > 1) warnings += "第${start}节 ${weekdayName(wd)} 有一格没认出：「${text.trim().take(24)}」"
                } else courses += got
            }
        }
        return Result(courses.distinctBy { it.key() }, warnings, source)
    }

    /**
     * 一行对应哪两节。
     *
     * 三种情况都要认：
     *  ① 首列写了「第3-4节」→ 就是它；
     *  ② 首列**空**（浏览器复制带 rowspan 的表格时，跨行格只在第一行出现）→ 沿用上一行的
     *     节次跨度，不能自作主张 +1，否则一整门课的节次全错位；
     *  ③ 首列既没数字也不是空（比如「上午」）→ 按上一行往下顺延一节。
     */
    private fun resolveSpan(firstCell: String, lastSpan: Pair<Int, Int>?): Pair<Int, Int> {
        periodOf(firstCell)?.let { return it }
        if (firstCell.isBlank() && lastSpan != null) return lastSpan
        val next = ((lastSpan?.second ?: 0) + 1).coerceAtMost(20)
        return next to next
    }

    /**
     * 是不是「上午 / 下午 / 中午」这类整行分隔。
     *
     * 判定依据：这一行所有非空格子**都是同一段文字**，且这段文字是时段词（或很短、且铺了 ≥3 列）。
     * 不能只按长度判断 —— 有的学校真的把同一门课放在多天同一行（很少见但存在），
     * 那边文字会超过 4 个字。
     */
    private fun isSeparatorRow(row: List<String>): Boolean {
        val filled = row.filter { it.isNotBlank() }.map { it.replace(Regex("\\s+"), "") }
        if (filled.isEmpty()) return false
        if (filled.distinct().size != 1) return false
        val v = filled.first()
        return SEPARATOR_RE.matches(v) || (v.length <= 4 && filled.size >= 3)
    }

    private val SEPARATOR_RE = Regex("上午|下午|中午|晚上|早晨|课间|休息|午休|备注")

    private fun transpose(grid: List<List<String>>): List<List<String>> {
        val w = grid.maxOf { it.size }
        return (0 until w).map { c -> grid.map { it.getOrNull(c).orEmpty() } }
    }

    // ─────────────────────────── 纯文本路径 ───────────────────────────

    private fun parseText(text: String, maxWeek: Int, defaultWeeks: IntRange): Result {
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result(emptyList(), listOf("内容是空的"), "text")

        // 表格形态：有制表符，或首行里就有 2 个以上「星期X」
        val tabular = lines.count { it.contains('\t') } >= 2 ||
            (lines.first().split(Regex("\\t|\\s{2,}")).count { weekdayOf(it) != null } >= 2)

        return if (tabular) parseTextGrid(lines, maxWeek, defaultWeeks) else parseTextList(lines, maxWeek)
    }

    private fun parseTextGrid(lines: List<String>, maxWeek: Int, defaultWeeks: IntRange): Result {
        val rows = lines.map { splitCells(it) }
        val headerIdx = rows.indexOfFirst { r -> r.count { weekdayOf(it) != null } >= 2 }
        if (headerIdx < 0) return parseTextList(lines, maxWeek)
        val colWeekday = mutableMapOf<Int, Int>()
        rows[headerIdx].forEachIndexed { ci, t -> weekdayOf(t)?.let { colWeekday[ci] = it } }

        val courses = mutableListOf<Course>()
        val warnings = mutableListOf<String>()
        var lastSpan: Pair<Int, Int>? = null
        for (ri in headerIdx + 1 until rows.size) {
            val row = rows[ri]
            if (isSeparatorRow(row)) continue
            val (start, end) = resolveSpan(row.firstOrNull().orEmpty(), lastSpan).also { lastSpan = it }
            for ((ci, wd) in colWeekday) {
                val cell = row.getOrNull(ci).orEmpty()
                if (cell.isBlank()) continue
                val got = extractBlock(cell, Hint(wd, start, end), maxWeek, defaultWeeks)
                if (got.isEmpty()) warnings += "第${start}节 ${weekdayName(wd)}：「${cell.take(24)}」"
                else courses += got
            }
        }
        return Result(courses.distinctBy { it.key() }, warnings, "text")
    }

    /** 一行一门课（教务/Excel 复制出来最常见的样子）。 */
    private fun parseTextList(lines: List<String>, maxWeek: Int): Result {
        val courses = mutableListOf<Course>()
        val warnings = mutableListOf<String>()
        lines.forEach { line ->
            if (headerLike(line)) return@forEach
            val got = extractBlock(line, Hint(null, null, null), maxWeek)
            if (got.isEmpty()) {
                if (line.length >= 4) warnings += "这行没认出：「${line.take(28)}」"
            } else courses += got
        }
        return Result(courses, warnings, "text")
    }

    private fun headerLike(line: String): Boolean {
        val t = line.replace(Regex("\\s+"), "")
        return t in setOf("课程名称", "课程名", "课表", "星期", "节次") ||
            (t.contains("星期") && t.contains("节次"))
    }

    private fun splitCells(line: String): List<String> =
        line.split('\t').takeIf { it.size > 1 }
            ?: line.split(Regex("\\s{2,}")).takeIf { it.size > 1 }
            ?: listOf(line)

    // ─────────────────────────── 单元格 → 课程 ───────────────────────────

    private data class Hint(val weekday: Int?, val startUnit: Int?, val endUnit: Int?)

    private fun extractBlock(rawText: String, hint: Hint, maxWeek: Int, defaultWeeks: IntRange = 1..16): List<Course> {
        val text = rawText.replace('\u00A0', ' ').trim()
        if (text.isEmpty()) return emptyList()

        // 一格塞多门课：按空行切；没有空行就按「第几个周次段」切
        val blocks = splitBlocks(text)

        return blocks.mapNotNull { block -> extractOne(block, hint, maxWeek, defaultWeeks) }
    }

    private fun splitBlocks(text: String): List<String> {
        val byBlank = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (byBlank.size > 1) return byBlank
        // 没空行：一行一课且每行都有周次 → 按行切
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1 && lines.count { WEEK_RE.containsMatchIn(it) } >= 2 &&
            lines.all { WEEK_RE.containsMatchIn(it) || it.length > 3 }
        ) {
            // 只有每行都像独立课程才切（避免把「课程名/教师/地点」三行切成三门课）
            val eachHasWeeks = lines.all { WEEK_RE.containsMatchIn(it) }
            if (eachHasWeeks) return lines
        }
        return listOf(text)
    }

    private fun extractOne(block: String, hint: Hint, maxWeek: Int, defaultWeeks: IntRange): Course? {
        var rest = block

        // 周次
        val weeksMatch = WEEK_RE.find(rest)
        var weeksSpec = weeksMatch?.value.orEmpty()
        if (weeksMatch != null) {
            rest = rest.removeRange(weeksMatch.range)
            // 紧跟在后面（同格内）的「除8」属于这条周次，不是别的东西
            EXCLUDE_RE.find(rest)?.let { ex ->
                weeksSpec += ex.value
                rest = rest.removeRange(ex.range)
            }
        }
        val weeks = if (weeksSpec.isBlank()) emptyList() else parseWeeks(weeksSpec, maxWeek)

        // 单元格自己写了星期/节次就覆盖表头（list 形态靠这个）
        val wdInCell = weekdayOf(rest)
        val spanInCell = periodInText(rest)

        val weekday = wdInCell ?: hint.weekday ?: return null
        val start = spanInCell?.first ?: hint.startUnit ?: return null
        val end = spanInCell?.second ?: hint.endUnit ?: start
        if (weekday !in 1..7 || start < 1 || end < start) return null

        // 星期/节次已经从文字里取过了，抠课程名前先擦掉，免得"周一 第1-2节"混进课名
        rest = rest
            .replace(Regex("(?:星期|周|礼拜)[一二三四五六日天1-7]"), " ")
            .replace(Regex("第?\\d{1,2}\\s*[-~]\\s*\\d{1,2}\\s*节"), " ")
            .replace(Regex("第\\d{1,2}节"), " ")

        // 教师：先看「教师：张三」这种显式标签
        var teacher = TEACHER_RE.find(rest)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (teacher != null) rest = rest.replace(TEACHER_RE.find(rest)!!.value, " ")

        // 地点：先看显式标签，再看「xx楼xxx」这种形态
        var room = ROOM_LABEL_RE.find(rest)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (room != null) rest = rest.replace(ROOM_LABEL_RE.find(rest)!!.value, " ")
        else {
            val r = ROOM_SHAPE_RE.find(rest) ?: ROOM_CODE_RE.find(rest)
            if (r != null) { room = r.value.trim(); rest = rest.replace(r.value, " ") }
        }

        if (teacher == null) {
            // 教务表格里名字多半裸写（第二行一个「张三」），没有标签可认。
            // 必须在**地点抠掉之后**做：行尾 token 有时是「C3-105」。
            val lines = rest.lines().map { it.trim() }.filter { it.isNotEmpty() }
            var cand = lines.drop(1).firstOrNull { BARE_NAME_RE.matches(it) && it !in NAME_STOP }
            if (cand == null && lines.size == 1) {
                // 整行空格分隔：「马克思主义基本原理 张三 1-16周 逸夫楼201」——行尾 2~4 个汉字是老师
                val tokens = lines[0].split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.size >= 2) {
                    val last = tokens.last()
                    if (BARE_NAME_RE.matches(last) && last !in NAME_STOP) cand = last
                }
            }
            if (cand != null) { teacher = cand; rest = rest.replace(cand, " ") }
        }

        // 课程名：剩下的第一行像样的文字
        val name = rest.lines()
            .map { it.replace(Regex("[\\s:：]+$"), "").trim() }
            .map { it.replace(Regex("^(课程名称|课程名|课程)\\s*[:：]\\s*"), "") }
            .firstOrNull { it.length >= 2 && !NOISE_RE.matches(it) }
            ?.let { cleanName(it) }
            ?.takeIf { it.length >= 2 }
            ?: return null

        // 表格里的格子可以不写周次（整学期由表头隐含）；一行一课的清单必须写，否则当噪声
        if (weeks.isEmpty() && hint.weekday == null) return null
        val finalWeeks = if (weeks.isEmpty()) defaultWeeks.toList() else weeks
        return Course(name, weekday, start, end, finalWeeks, room, teacher)
    }

    private fun cleanName(s: String): String = s
        .replace(Regex("[（(][^）)]*学分[^）)]*[）)]"), "")
        .replace(Regex("[（(]\\s*\\d+(\\.\\d+)?\\s*学分\\s*[）)]"), "")
        .replace(Regex("\\s*\\[.*?]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** 「C3-105 / A101 / 教三105 / J1-101」这类没有「楼」字的教室编号。 */
    private val ROOM_CODE_RE = Regex("(?:[A-Za-z]{1,3}\\d{1,2}|教[一二三四五六七八九十])\\s?[-—]?\\d{2,4}")

    private val BARE_NAME_RE = Regex("^[\\u4e00-\\u9fa5]{2,4}$")
    private val NAME_STOP = setOf(
        "实验", "理论", "实践", "上机", "必修", "选修", "通识", "公选", "课程",
        "上午", "下午", "中午", "晚上", "备注", "教室", "地点", "校区", "学院",
    )

    private val NOISE_RE = Regex("^[\\d\\s第节周星期一二三四五六日天\\-—~、,，:：()（）/|.]+$")

    private val TEACHER_RE = Regex("(?:任课教师|授课教师|教师|老师)\\s*[:：]?\\s*([^\\s,，;；/|()（）]{2,12})")
    private val ROOM_LABEL_RE = Regex("(?:上课地点|教室名称|地点|教室|场地)\\s*[:：]?\\s*([^\\s,，;；|]{1,20})")
    private val ROOM_SHAPE_RE =
        Regex("[\\u4e00-\\u9fa5A-Za-z]{1,10}(?:楼|馆|室|中心|场|校区)[A-Za-z]?\\d{0,5}(?:-[\\dA-Za-z]{1,5})?")

    /**
     * 周次抠取的核心正则：数字段（可带范围/逗号）后面跟「周」。
     * 单独 `第1-2节` 不会被吃进来（它后面是「节」不是「周」）。
     */
    /**
     * 周次抠取的核心正则。
     *
     * 两条约束是踩过坑加的，别删：
     *  ① `(?<!\\d)` —— 数字前面不能紧挨数字，否则「逸夫楼201 \\t 周一」里的 `01\\t周`
     *     会被当成「第1周」，地点直接被啃掉一截；
     *  ② 数字与「周」之间**不允许空白** —— 同上，跨过制表符就会串到下一列。
     * `第1-2节` 不会命中（后面是「节」不是「周」）。
     */
    private val WEEK_RE = Regex(
        "(?<!\\d)(?:\\d{1,2}\\s*[-~至]\\s*\\d{1,2}|\\d{1,2})" +
            "(?:\\s*[,，、;；]\\s*(?:\\d{1,2}\\s*[-~至]\\s*\\d{1,2}|\\d{1,2}))*" +
            "(?:单|双|全)?周(?:[（(][^）)]*[）)])?",
    )

    /** 「1-16周除8」：排除项跟在周次后面，单独一段。 */
    private val EXCLUDE_RE = Regex("\\s*除\\s*([\\d,，、\\-~]+)")

    /**
     * 周次字符串 → 具体周列表。
     *
     * 认这些写法（教务/各校导出里都见过）：
     *   `1-16周` `1-5,7-11单周` `1-16周(双)` `1-10周除5` `2,5,8周` `1~16周` `全周`
     * 单/双是**整段过滤**（先展开范围再筛），`除X` 是排除。
     */
    fun parseWeeks(raw: String, maxWeek: Int = 30): List<Int> {
        var s = raw
            .replace('（', '(').replace('）', ')')
            .replace('～', '~').replace('至', '-').replace('—', '-').replace('–', '-')
            .replace(Regex("\\s+"), "")
            .replace("周", "")
        // 「全周」「全」＝整学期每周
        val allWeeks = s.contains('全')
        var odd = false
        var even = false
        if (s.contains('单')) { odd = true; s = s.replace("单", "") }
        if (s.contains('双')) { even = true; s = s.replace("双", "") }
        s = s.replace("全", "")

        // 排除项：除8 / 除8-10 / 除8,10
        val excluded = mutableSetOf<Int>()
        Regex("除([0-9,，、\\-~]+)").findAll(s).forEach { m ->
            expandNums(m.groupValues[1]).forEach { excluded += it }
            s = s.replace(m.value, "")
        }
        s = s.replace(Regex("[()\\[\\]]"), "")

        val weeks = expandNums(s).filter { it in 1..maxWeek }
            .filter { if (odd) it % 2 == 1 else true }
            .filter { if (even) it % 2 == 0 else true }
            .filter { it !in excluded }
            .distinct().sorted()
        if (weeks.isEmpty() && allWeeks) return (1..maxWeek).toList()
        return weeks
    }

    private fun expandNums(spec: String): List<Int> {
        val out = mutableListOf<Int>()
        spec.split(',', '，', '、', ';', '；').forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            val range = Regex("^(\\d{1,2})[-~](\\d{1,2})$").find(p)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                if (a <= b && b - a <= 40) out += a..b
            } else {
                p.toIntOrNull()?.let { out += it }
            }
        }
        return out
    }

    // ─────────────────────────── 星期 / 节次 ───────────────────────────

    private val CN_NUM = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
        '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7,
    )

    /** 「星期一 / 周一 / 礼拜三 / Mon」→ 1..7。找不到返回 null。 */
    fun weekdayOf(text: String): Int? {
        val t = text.replace(Regex("\\s+"), "")
        Regex("(?:星期|周|礼拜)([一二三四五六日天1-7])").find(t)?.let { m ->
            CN_NUM[m.groupValues[1].firstOrNull() ?: '一']?.let { return it }
        }
        Regex("(?:mon|tue|wed|thu|fri|sat|sun)", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            return listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                .indexOf(m.value.lowercase()) + 1
        }
        // 表头里只有「一 二 三 四 五 六 日」这种极简写法
        if (t.length == 1) CN_NUM[t[0]]?.let { return it }
        return null
    }

    private fun cnInt(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val idx = "一二三四五六七八九十".indexOf(s.firstOrNull() ?: return null)
        return if (idx >= 0) idx + 1 else null
    }

    private fun weekdayName(w: Int): String = "星期" + "一二三四五六日"[(w - 1).coerceIn(0, 6)]

    /** 首列 → 节次区间。「第1-2节」/「1-2」/「3」/「第一大节」都认。 */
    private fun periodOf(text: String): Pair<Int, Int>? {
        val t = text.replace(Regex("\\s+"), "")
        if (t.isEmpty()) return null
        // 「第一大节 / 第3大节」→ 每大节按 2 小节算（1→1-2、3→5-6）；「第三节」→ 3
        Regex("第([一二三四五六七八九十]|\\d{1,2})大节").find(t)?.let { m ->
            val n = cnInt(m.groupValues[1]) ?: return@let
            if (n in 1..10) return (2 * n - 1) to (2 * n)
        }
        Regex("第([一二三四五六七八九十]|\\d{1,2})节").find(t)?.let { m ->
            val n = cnInt(m.groupValues[1]) ?: return@let
            if (n in 1..20) return n to n
        }
        Regex("(\\d{1,2})[-~](\\d{1,2})").find(t)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if (a in 1..20 && b in a..20) return a to b
        }
        val nums = Regex("\\d{1,2}").findAll(t).map { it.value.toInt() }.filter { it in 1..20 }.toList()
        if (nums.isEmpty()) return null
        return nums[0] to nums[0]
    }

    private fun periodInText(text: String): Pair<Int, Int>? {
        Regex("第?(\\d{1,2})[-~](\\d{1,2})节").find(text)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if (a in 1..20 && b in a..20) return a to b
        }
        Regex("第(\\d{1,2})节").find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            if (a in 1..20) return a to a
        }
        return null
    }

    // ─────────────────────────── 杂项 ───────────────────────────

    private fun weeksToText(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < weeks.size) {
            var j = i
            while (j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(if (j > i) "${weeks[i]}-${weeks[j]}" else "${weeks[i]}")
            i = j + 1
        }
        sb.append("周")
        return sb.toString()
    }

    /** 统一换行、去 BOM、`<br>`/块级标签转换行、实体解码、去标签。 */
    private fun normalize(input: String): String {
        var s = input.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        s = s.replace(Regex("(?i)<\\s*br\\s*/?\\s*>"), "\n")
        // 块级标签：**开标签删掉、闭标签换行**。
        //  - 开标签不删 → 格子里留着 "<div>高等数学A(1)"
        //  - 开闭都换行 → 两个 div 之间多出空行 → 被当成「一格塞多门课」拆开，课名/教师/地点各成一门课
        s = s.replace(Regex("(?i)<\\s*(div|p|li|h[1-6])\\s*[^>]*>"), "")
        s = s.replace(Regex("(?i)</\\s*(div|p|li|h[1-6])\\s*>"), "\n")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        // 单元格里剩下的行内标签直接去掉（保留文字）
        if (looksLikeHtml(s)) s = s.replace(Regex("(?i)</?(font|span|b|i|u|em|strong|a|small|sup|sub)\\b[^>]*>"), "")
        return s.trim()
    }
}
