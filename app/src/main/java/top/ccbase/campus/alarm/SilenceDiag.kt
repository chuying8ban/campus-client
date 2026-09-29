package top.ccbase.campus.alarm

/**
 * 自动静音的「自诊断」。
 *
 * 为什么需要它：静音失败的原因（缺权限 / 闹钟没排上 / 被系统杀掉）在真机上
 * 的表现就是"什么都没发生" —— 用户看不到、也没法描述。
 * 原先代码把原因写进 `Log.w`，在户外等于没有记录。
 *
 * 这里把状态做成「可读、可落盘的数据」：
 *   ① 体检项（缺什么、缺了会怎样） ② 已排闹钟 ③ 每次实际触发的记录
 *
 * 本文件只放「纯逻辑」：不碰 Android API，方便在 JVM 上真跑测试。
 */
data class DiagItem(
    val name: String,
    val ok: Boolean,
    /** 缺失时的后果说明 —— 只写"没授权"等于没说 */
    val detail: String = "",
    /** 界面上的按钮文案（空=没有可去的地方） */
    val action: String = "",
    /**
     * 「照着做」的步骤：要开的「开关原名」 + 手机上的「逐级菜单路径」 + 到了那页看什么。
     *
     * 为什么要有它：只说"缺少勿扰访问权限"，用户在小米的设置里翻五分钟也找不到 ——
     * 各版本菜单名还不一样。所以路径要按「小米/HyperOS 优先」写，再给一条直达的办法。
     */
    val steps: List<String> = emptyList(),
)

/** 厂商后台限制的一条：只有路径，没有状态（系统查不到，所以不敢写 ✓/✗）。 */
data class GuideItem(val title: String, val steps: List<String>)

data class Attempt(
    val at: String,          // 本地时间 "2026-09-17 08:44"
    val what: String,        // "课前静音" / "下课恢复"
    val ok: Boolean,
    val reason: String = "", // 失败原因，越具体越好
)

/**
 * 体检快照：Android 侧只负责把真实值读进来，判定逻辑全是纯函数（可在 JVM 上真跑）。
 */
data class DiagSnapshot(
    val writeSettings: Boolean,      // Settings.System.canWrite：静音本身必需
    val dndAccess: Boolean,          // 勿扰访问：改铃声模式
    val exactAlarm: Boolean,         // 精确闹钟（API31+）：到点会不会响
    val batteryWhitelist: Boolean,   // 电池白名单：小米/HyperOS 会不会杀
    val notificationsOn: Boolean,    // 通知被关会连带影响前台服务存活
    // 系统是否允许本 App 安装应用（装更新要用）。默认 true 只是让老的构造点不用改，
    // 真机上一律由 DiagLog.snapshot() 现读真值 —— 默认值不能被当成结论用。
    val installAllowed: Boolean = true,
)

object SilenceDiag {

    /** 一句话结论：静音到底会不会生效，不会的话卡在哪一项。 */
    fun verdict(items: List<DiagItem>): String {
        if (items.isEmpty()) return "还没体检"
        val bad = items.filter { !it.ok }
        if (bad.isEmpty()) return "静音具备生效条件"
        return "静音不会生效，缺：" + bad.joinToString("、") { it.name }
    }

    /** 落盘的一行：用制表符分隔，既能人读也能按行切。 */
    fun format(a: Attempt): String {
        val reason = a.reason.replace('\n', ' ').replace('\t', ' ').trim()
        return listOf(a.at, a.what, if (a.ok) "成功" else "失败", reason).joinToString("\t")
    }

    fun parse(line: String): Attempt? {
        val p = line.split('\t')
        if (p.size < 3 || p[0].isBlank()) return null
        return Attempt(
            at = p[0],
            what = p[1],
            ok = p[2] == "成功",
            reason = if (p.size > 3) p[3] else "",
        )
    }

    /**
     * 失败原因的人话解释。
     *
     * 这是整个自诊断的重点：把"什么都没发生"变成一句能照着修的话。
     */
    fun explain(kind: String): String = when (kind) {
        "no_write_settings" -> "缺少「修改系统设置」权限 —— 系统不允许 App 改铃声/静音"
        "no_dnd_access" -> "缺少「勿扰访问」权限 —— 无法调节铃声模式"
        "no_notification_permission" -> "缺少「通知」权限 —— 提醒弹不出来（Android 13+ 要手动允许）"
        "no_exact_alarm" -> "缺少「闹钟与提醒」权限 —— 闹钟到点不会响"
        "no_alarm_set" -> "这节课的闹钟没有排上（可能被系统清理）"
        "exception" -> "系统拒绝了静音调用（多见于厂商 ROM 的限制）"
        else -> kind
    }

    /** 体检项。每一项都要说清"缺了会怎样" —— 只说"未授权"等于没说。 */
    fun items(s: DiagSnapshot): List<DiagItem> = listOf(
        DiagItem("修改系统设置", s.writeSettings,
            if (s.writeSettings) "已授权" else explain("no_write_settings"), "去授权",
            steps = if (s.writeSettings) emptyList() else listOf(
                "要开的开关：「修改系统设置」（有的版本写作「允许修改系统设置」）",
                "路径：设置 → 应用设置 → 应用管理 → 校园 → 其他权限 → 修改系统设置：允许",
                "点上面「去授权」会直接跳到系统那一页",
                "不开的后果：系统不许 App 改铃声 —— 静音和恢复都会失败",
            )),
        DiagItem("勿扰访问", s.dndAccess,
            if (s.dndAccess) "已授权" else explain("no_dnd_access"), "去授权",
            steps = if (s.dndAccess) emptyList() else listOf(
                "要开的开关：列表里「校园」右边的开关",
                "这页各品牌叫法不一样：小米/HyperOS 叫「模式访问权限」，原生安卓叫「勿扰访问权限」，还有的叫「通知访问权限」——名字对不上不是你走错了",
                "最快的走法：点上面「去授权」，能直接落到「校园」那一项；落进一条长列表就往『下』翻，找「校园」",
                "手动走法：设置 → 通知与控制中心 → 勿扰 → 拉到底（小米上就是「模式访问权限」）",
                "不开的后果：Android 会「静默丢掉」改铃声的调用 —— 上课时手机照样响，而且不报错",
            )),
        DiagItem("闹钟与提醒", s.exactAlarm,
            if (s.exactAlarm) "已授权" else explain("no_exact_alarm"), "去授权",
            steps = if (s.exactAlarm) emptyList() else listOf(
                "要开的开关：「闹钟和提醒」",
                "路径：设置 → 应用设置 → 应用管理 → 校园 → 闹钟和提醒：允许（新版本可能收在「其他权限」里）",
                "点上面「去授权」会直接跳到系统那一页",
                "不开的后果：闹钟到点会被系统推迟甚至不响 —— 这一项影响最大",
            )),
        DiagItem("电池白名单", s.batteryWhitelist,
            if (s.batteryWhitelist) "已加入" else "没加入白名单 —— 小米/HyperOS 可能杀掉后台，闹钟不响", "去设置",
            steps = if (s.batteryWhitelist) emptyList() else listOf(
                "要开的开关：「省电策略 → 无限制」，同时把「自启动」打开",
                "小米/HyperOS 原生入口（最管用）：设置 → 应用设置 → 应用管理 → 校园 → 省电策略：无限制；同页往下 → 自启动：允许",
                "点「去设置」走的是系统那一页：顶部筛选切到「所有应用」→ 找「校园」→ 选「不优化」",
                "再补一手：最近任务里把「校园」下拉加锁（出现锁图标），系统清后台时不会连它一起清",
                "不开的后果：后台被清掉后，到点的闹钟不触发 —— 记录里会写「这节课的闹钟没有排上」",
            )),
        DiagItem("通知", s.notificationsOn,
            if (s.notificationsOn) "正常" else "通知被关闭 —— 连带影响后台存活", "去设置",
            steps = if (s.notificationsOn) emptyList() else listOf(
                "要开的开关：「允许通知」（顺手把「锁屏通知」也打开）",
                "路径：设置 → 应用设置 → 应用管理 → 校园 → 通知：允许",
                "点上面「去设置」会直接跳到系统那一页",
                "不开的后果：提醒看不到，后台存活也更容易被清",
            )),
    )

    /** 已排闹钟的可读清单（传入的是已排好的条目文本，避免和排程结构耦合）。 */
    fun describe(list: List<String>, limit: Int = 14): List<String> =
        if (list.size <= limit) list else list.take(limit) + "…共 ${list.size} 条"

    /**
     * 「安装更新」这一项。
     *
     * 单独拎出来是因为它**不属于静音**：混进 items() 会让结论说出
     * 「静音不会生效，缺：安装更新」这种牛头不对马嘴的话。
     */
    fun installItem(s: DiagSnapshot): DiagItem = DiagItem(
        "安装更新", s.installAllowed,
        if (s.installAllowed) "已授权" else "没授权 —— 点了更新也会卡在最后一步",
        "去授权",
        steps = if (s.installAllowed) emptyList() else listOf(
            "要开的开关：「允许来自此来源的应用」/「安装未知应用」",
            "路径：设置 → 应用设置 → 应用管理 → 校园 → 安装未知应用：允许",
            "点「去授权」会直接跳到系统那一页",
            "不开的后果：新版本下载完了装不上（1.30 那版就是这么卡住的）",
        ),
    )

    /** 授权页用的完整清单：静音相关 5 项 + 安装更新。 */
    fun permissionItems(s: DiagSnapshot): List<DiagItem> = items(s) + installItem(s)

    /**
     * 厂商后台限制怎么开。
     *
     * 为什么只给路径不给状态：Android 没有公开 API 能查「自启动」「省电策略」
     * （小米自己的 RemoteController 是私有接口，不同版本还不一样）。
     * 与其瞎猜一个 ✓ 骗用户，不如老实说「系统查不到，自己看一眼」。
     */
    fun vendorGuides(): List<GuideItem> = listOf(
        GuideItem("自启动", listOf(
            "路径：设置 → 应用设置 → 应用管理 → 校园 → 自启动：允许",
            "不开的后果：手机重启后闹钟全丢，上课静音不会触发",
        )),
        GuideItem("省电策略 → 无限制（别用「智能省电」）", listOf(
            "路径：同上页 → 省电策略 → 选「无限制」",
            "不开的后果：息屏一段时间后进程被清，到点的闹钟被推迟",
        )),
        GuideItem("最近任务里加锁", listOf(
            "做法：多任务界面把「校园」卡片向下拉，出现锁图标",
            "作用：系统清后台时不会连它一起清（这条最容易漏）",
        )),
    )
}
