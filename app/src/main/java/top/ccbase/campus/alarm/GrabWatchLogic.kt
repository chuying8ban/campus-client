package top.ccbase.campus.alarm

import top.ccbase.campus.net.GrabLog

/**
 * 「抢课提醒」要不要弹、弹什么 —— **纯逻辑**，能在 JVM 上直接测。
 *
 * 为什么值得单独一层：提醒这件事错了比不做更烦人。
 *   - 第一次运行如果不记账，用户一开开关就会被**历史上所有提醒**轰一遍（"翻旧账"）；
 *   - 用 id 去重而不是用时间：服务端日志的 id 是自增主键，可靠；
 *   - 一次最多弹几条，多了就合并成一条摘要 —— 半夜连弹十条会被直接关掉通知权限。
 */
object GrabWatchLogic {

    /** 只有这个级别会弹手机通知。info（"推送 飞书=✅"这类）是给作者看的运行日志。 */
    val ALERT_LEVELS = setOf("alert")

    /** 一次最多单独弹 3 条，其余合并成一句"还有 N 条" */
    const val MAX_PER_RUN = 3

    fun newAlerts(logs: List<GrabLog>, lastSeenId: Int): List<GrabLog> =
        logs.filter { it.id > lastSeenId && it.level in ALERT_LEVELS }
            .sortedBy { it.id }

    fun maxId(logs: List<GrabLog>): Int = logs.maxOfOrNull { it.id } ?: 0

    /**
     * 第一次运行该记到哪个 id。
     * 返回当前最大 id = **只记账、不弹通知**，历史提醒不该重新轰一遍。
     */
    fun initialSeenId(logs: List<GrabLog>): Int = maxId(logs)

    /** 服务端 msg 里那条给作者看的尾巴（"| 👉 快去选课页确认"）不该出现在通知里 */
    fun notifyBody(msg: String): String =
        msg.split("|")[0].trim().ifBlank { "监控有新的余位" }

    fun notifyTitle(): String = "抢课提醒"

    /** 超过上限时的合并文案 */
    fun overflowBody(extra: Int): String = "还有 $extra 条新提醒，打开「抢课」页看"

    /** 让用户在通知里一眼看出"这台手机自己弹的"，不用猜是谁推的 */
    fun channelName(): String = "抢课提醒（手机直接弹）"
}
