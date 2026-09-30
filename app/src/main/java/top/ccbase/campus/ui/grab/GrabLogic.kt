package top.ccbase.campus.ui.grab

import top.ccbase.campus.net.GrabLesson
import top.ccbase.campus.net.GrabLog
import top.ccbase.campus.net.GrabStatus
import top.ccbase.campus.net.GrabTarget

/**
 * 监控页的纯逻辑层 —— 不碰 Compose，可以拿 JVM 直接测。
 *
 * 为什么单独抽出来：这一页最容易出的错不是"渲染错了"，而是**话说错了**：
 *   - 把"监控已停止"说成"正在监控" → 用户以为有人在盯
 *   - 把"咱们自己库里的设置"和"教务系统登录状态"混成一句
 *   - 列表空了不说为什么空 → 用户以为坏了（其实是筛选条件太窄）
 *   - 有冲突的课说成"能抢" → 真去选也得退，这是最贵的一种错
 * 这些都是字符串问题，正是最该被测试钉住的那类东西。
 */
object GrabLogic {

    /** 筛选档位：显示名 → 服务端 flt 参数。顺序就是界面上的顺序。 */
    val FILTERS: List<Pair<String, String>> = listOf(
        "能抢且不冲突" to "ok",
        "有余位" to "seats",
        "不冲突" to "noconf",
        "计算机类" to "cs",
        "全部" to "all",
    )

    // ------------------------------------------------------------ 状态

    /**
     * 顶部那一行状态。**必须把"我们的监控"和"教务系统登录"分开说** ——
     * 合成一句就会出现"监控中但还没登录成功"这种自相矛盾的展示。
     *
     * 还要区分「开关开着」和「真的在盯东西」：目标被全部移除后服务端开关
     * 仍然是 1（cfg 没人去改），只看 monitor_on 会给出"监控中"这种假话。
     */
    fun statusLine(s: GrabStatus?): String {
        if (s == null) return "正在读取监控状态…"
        val monitor = when {
            !s.configured -> "未配置"
            s.monitor_on && s.targets.isNotEmpty() -> "监控中 ${s.targets.size} 门"
            s.monitor_on -> "没有在盯任何课"
            else -> "监控已停止"
        }
        val login = if (s.logged_in) "教务已登录" else "教务未登录"
        val when_ = s.last_check.takeIf { it.isNotBlank() }?.let { " · 上次检查 $it" }.orEmpty()
        return "$monitor · $login$when_"
    }

    /**
     * 服务端监控开关本身的状态（和"真的在盯"分开说）。
     * App 侧**没有**改这个开关的接口 —— 所以这里只报事实，不给假按钮。
     */
    fun monitorSwitchText(s: GrabStatus?): String = when {
        s == null -> "读取中"
        !s.configured -> "未配置"
        s.monitor_on -> "开"
        else -> "关"
    }

    /** 状态点颜色档位：绿=在监控、琥珀=停了、红=有错、灰=没配 */
    fun statusLevel(s: GrabStatus?): Int = when {
        s == null -> 0
        !s.configured -> 0
        s.last_error.isNotBlank() && s.monitor_on -> 3
        s.monitor_on -> 1
        else -> 2
    }

    /**
     * 第二行：轮询间隔 / 目标数 —— 都是服务端真有的字段。
     *
     * 这里**不再重复"上次检查"**：它已经在标题行里了，两行并排出现同一个时间戳
     * 只是视觉噪音（真机截图里就是这样叠着的）。
     */
    fun metaLine(s: GrabStatus?): String {
        if (s == null) return ""
        val bits = mutableListOf<String>()
        bits += if (s.interval > 0) "每 ${s.interval} 秒轮一次" else "轮询间隔未知"
        bits += "目标 ${s.targets.size} 门"
        return bits.joinToString(" · ")
    }

    /** 统计一行：真正"现在就能拿"的是 ok_clean，不是 total。 */
    fun statsLine(s: GrabStatus?): String {
        val c = s?.catalog ?: return ""
        if (c.total == 0) return "清单还没建（在网页版点一次「重建清单」）"
        return "${c.total} 门开放 · ${c.free} 有余位 · ${c.ok_clean} 能抢且不冲突"
    }

    /** 清单更新时间 —— 清单是缓存，不说时间用户会把旧数据当现在 */
    fun catalogFreshness(s: GrabStatus?): String {
        val u = s?.catalog?.updated.orEmpty()
        return if (u.isBlank()) "" else "清单更新于 $u"
    }

    /** 列表空的时候，说清"为什么空" —— 空列表不说话最像坏掉。 */
    fun emptyHint(flt: String, q: String): String = when {
        q.isNotBlank() -> "没有匹配「$q」的课，换个词试试"
        flt == "ok" -> "当前没有「有余位 + 不冲突 + 预检放行」的课 —— 换「有余位」看看"
        flt == "seats" -> "没有有余位的课了，这轮可能已经抢完"
        flt == "noconf" -> "清单里没有不冲突的课"
        flt == "cs" -> "没有计算机类相关的课"
        else -> "清单是空的（在网页版点一次「重建清单」）"
    }

    /** 一条可选课：余量说人话，别只给数字。 */
    fun seatsText(l: GrabLesson): String = when {
        l.mine == 1 -> "我已选"
        l.free <= 0 -> "已满"
        else -> "余 ${l.free}"
    }

    /** 余量带分母：25/110 比"余 85"更能看出这课有多热 */
    fun seatsDetail(l: GrabLesson): String =
        if (l.limit_cnt > 0) "${l.used}/${l.limit_cnt}" else "${l.used} 人"

    /** 一条可选课的副标题：教师 · 教室 · 学分 · 状态标记 */
    fun lessonSub(l: GrabLesson): String {
        val bits = mutableListOf<String>()
        if (l.teacher.isNotBlank()) bits += l.teacher
        if (l.credits > 0) bits += "${trimNum(l.credits)} 学分"
        if (l.prop.isNotBlank()) bits += l.prop
        if (l.cn > 0) bits += "撞课表 ${l.cn} 节"
        if (l.probe_ok == 0) bits += "预检不放行"
        if (l.probe_ok == null && l.mine == 0) bits += "未预检"
        return bits.joinToString(" · ")
    }

    /**
     * 服务端把「周次 星期 节次 教室」全塞在 place 一列里，`;` 分隔多个时段。
     * 直接整段显示会挤成一坨 —— 拆成一行一个时段，用户才扫得动。
     */
    fun timeSlots(place: String): List<String> =
        place.split(';', '\n')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }

    /** 上课班级（服务端叫 cls）—— 抢课时得看清是给哪个班开的 */
    fun classText(l: GrabLesson): String =
        if (l.cls.isBlank()) "" else "上课班级：${l.cls}"

    /** 学校预检不放行的原话 —— 别让用户猜"为什么不能选" */
    fun probeText(l: GrabLesson): String? {
        if (l.probe_ok != 0) return null
        val why = l.probe_reason?.takeIf { it.isNotBlank() } ?: "学校预检未通过"
        return "预检不放行：$why"
    }

    /** 3.0 → "3"，2.5 → "2.5"。学分显示不要拖一个没意义的 .0 */
    fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /**
     * 加入监控后给用户的一句反馈。**冲突必须说** ——
     * 抢到了也得退的课，不能等抢完才发现。
     */
    fun addedText(course: String, clashText: String): String =
        if (clashText.isBlank()) "已加入监控：$course"
        else "已加入监控：$course（⚠ $clashText）"

    /** 监控列表里那一行 */
    fun targetLine(t: GrabTarget): String {
        val who = listOf(t.teacher, "限 ${t.limit_cnt} 人").filter { it.isNotBlank() && it != "限 0 人" }
        val base = who.joinToString(" · ")
        return if (t.clash_text.isBlank()) base else "$base · ⚠ ${t.clash_text}"
    }

    /** 一条监控目标现在是什么状态 —— 用户最想知道的是"选上没选上" */
    fun targetStateText(t: GrabTarget): String = when {
        t.grabbed == 1 -> "已选上"
        t.enabled == 0 -> "已暂停"
        else -> "盯守中"
    }

    /** 0=普通 1=提醒（琥珀） 2=成功（绿） 3=硬错（红） */
    fun targetStateLevel(t: GrabTarget): Int = when {
        t.grabbed == 1 -> 2
        t.enabled == 0 -> 1
        else -> 0
    }

    /**
     * 监控目标的余量：status 的 targets 里**没有** free，只能拿清单里的同一门课来对。
     * 对不上就说"未知"，不要编一个数字出来。
     */
    fun targetSeat(t: GrabTarget, lessons: List<GrabLesson>): String? {
        val l = lessons.firstOrNull { it.lesson_id == t.lesson_id } ?: return null
        return seatsText(l)
    }

    // ------------------------------------------------------------ 日志

    /** "2026-09-17 01:02:53" → "01:02:53"；只有日期就原样返回 */
    fun logTime(ts: String): String {
        val t = ts.trim()
        if (t.isEmpty()) return ""
        return t.substringAfter(' ').takeIf { it.isNotBlank() } ?: t
    }

    /** 日志级别 → 颜色档位：error=3 红、alert=2 琥珀、warn=1、info=0 */
    fun logLevel(level: String): Int = when (level.trim().lowercase()) {
        "error", "err", "fatal" -> 3
        "alert" -> 2
        "warn", "warning" -> 1
        else -> 0
    }

    /** 级别标签：让用户一眼分得清"提醒"和"报错" */
    fun logLabel(level: String): String = when (level.trim().lowercase()) {
        "error", "err", "fatal" -> "错误"
        "alert" -> "提醒"
        "warn", "warning" -> "注意"
        else -> "记录"
    }

    /** 日志摘要：多少条、最新一条什么时候 —— 折叠着也要能看出"刚有动作" */
    fun logsSummary(logs: List<GrabLog>): String {
        if (logs.isEmpty()) return "还没有动作记录"
        val last = logTime(logs.first().ts)
        return if (last.isBlank()) "${logs.size} 条记录" else "${logs.size} 条 · 最新 $last"
    }

    /**
     * 冲突明细说人话：和哪门课撞了、什么时候、在哪。
     * 与网页版 `_fmt_clash` 同一个口径（服务端把课名/时间/教室都已经算好下发）。
     */
    fun clashText(l: GrabLesson, maxn: Int = 2): String {
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val parts = l.clash.take(maxn).map { c ->
            val wd = week[((c.weekday - 1) % 7).coerceAtLeast(0)]
            val at = c.room?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
            "${c.course.ifBlank { "?" }} $wd ${c.time_text}$at".trim()
        }
        var s = parts.joinToString("、")
        if (l.clash.size > maxn) s += " 等 ${l.clash.size} 门"
        return s
    }

    /**
     * 页顶说明（**用户定的原话，一字不改**）。
     *
     * 这一页只负责"盯"，不负责"抢"：把想要的课记下来，盯着有没有余位；
     * 有余位只在手机上提醒，抢不抢、什么时候抢由用户自己去教务系统操作，App 不代选。
     * 界面上默认只露 [DISCLAIMER_HEAD] 一行（不挡操作），点「展开」才看全文。
     */
    const val DISCLAIMER_HEAD: String = "只盯余量，不替抢"

    const val DISCLAIMER_BODY: String =
        "这个页面只做两件事：把你想要的课记下来，盯着它有没有余位。\n" +
            "有余位就在手机上提醒你 —— 抢不抢、什么时候抢，你自己去教务系统操作。\n" +
            "App 不会代你提交选课。"

    // ------------------------------------------------------------ 反馈与错误

    /**
     * 网页版地址 —— **跟着这个包连的服务器走**，不写死域名。
     *
     * 写死过的教训：域名一换，这里就指到旧站去了；
     * 而网页版和接口本来就是**同一个服务**（同一个 FastAPI 应用既发网页也发 /api/v2），
     * 所以直接取构建时的 `API_BASE`。
     *
     * 现在 App 自己就能开/关监控（走 `POST /api/v2/grab/config`）。
     * 网页版剩下的是 App 还没有的那几件事：**重建课程清单**、改教务账号密码、
     * 通知渠道配置 —— 所以这里只做"兜底入口"，不再用在监控开关上。
     */
    val WEB_URL: String get() = top.ccbase.campus.BuildConfig.API_BASE

    /**
     * 「手机直接提醒」的状态文字。
     *
     * 分三种状态说清：关着 / 开着但通知权限被系统挡了 / 开着。
     * 第三种带上最近一次检查结果 —— 用户在外地看不到界面，这一行就是他的"心跳"。
     */
    fun watchStateText(on: Boolean, notifOk: Boolean, last: String?): String = when {
        // 关着的时候不能只写一个"关"：要说清"关"的后果 —— 这台手机不会弹提醒。
        !on -> "关 · 不会弹提醒"
        !notifOk -> "已开 · 但通知权限没给"
        else -> last?.takeIf { it.isNotBlank() }?.let { "已开 · $it" } ?: "已开"
    }

    /**
     * 手机直接提醒的说明。必须和"服务端推送"分开说：
     * 更要紧的是**服务端监控没开时弹不出来东西** —— 不说清的话，
     * 用户会以为开着这个就等于有人在替他盯着。
     */
    fun watchHint(monitorOn: Boolean): String = if (monitorOn)
        "每 10 分钟由这台手机自己查一次，有新余位直接在通知栏弹出 —— 不经过飞书/QQ 机器人，谁登录谁收。"
    else
        "每 10 分钟由这台手机自己查一次并弹通知。但服务端监控开关现在是关的 —— 先开启监控，弹出来的才不是空的。"

    /** 页面上的一条反馈：说清是成功还是失败 */
    data class Notice(val text: String, val level: Int = 0) {
        // level: 0 中性 / 1 提醒 / 2 成功 / 3 失败
        val isError: Boolean get() = level >= 3
        val isOk: Boolean get() = level == 2
    }

    /**
     * 错误分类 —— **401/429/0 是三件完全不同的事**，混成一句"网络异常"会让人白查半天。
     *   401 登录过期（要重新登录） / 429 太频繁（等一下再来）
     *   0   网络不通（看看网络）   / 403 没权限 / 503 服务端没部署
     *   其他则是服务端错误，必须把具体码带出来。
     */
    fun errorText(code: Int, message: String): String {
        val m = message.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return when (code) {
            401 -> "登录已过期，请重新登录$m"
            403 -> "这个功能只对作者开放：当前账号没有监控权限"
            429 -> "操作太频繁了，等一会儿再试$m"
            503 -> "服务端监控模块没部署$m"
            0 -> "网络不通$m"
            -1 -> "服务端返回的内容看不懂$m"
            else -> "服务端错误 $code$m"
        }
    }

}
