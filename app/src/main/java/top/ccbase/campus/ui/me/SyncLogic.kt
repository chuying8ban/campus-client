package top.ccbase.campus.ui.me

import top.ccbase.campus.net.StudentError

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.ccbase.campus.net.TimetableSyncRes

/**
 * 一条失败该怎么讲给用户听。
 *
 * `needsRelogin` 决定**要不要给重登入口**（401 = 令牌没了，用户能做的只有重新登录）；
 * `retryable` 决定**要不要说「可以再点一次」**（网络/忙 = 值得重试；403/404 重试也没用）。
 *
 * 为什么把这件事单独拎出来（而不是在各个界面里写 when）：
 * 「上传被 401 拒绝」这个 bug 报上来时，界面上只有一句"上传失败"——
 * 用户不知道是自己密码错了、网络断了、还是登录过期了，只能回来问。
 * 这个判断只有一处实现，就能先红后绿钉住。
 */
data class SyncFail(val text: String, val needsRelogin: Boolean, val retryable: Boolean)

/**
 * 「刷新课表」两条路（手机自己抓 / 让服务器重读）共用的**纯逻辑**。
 *
 * 全是纯函数：不碰网络、不碰 Android API，所以能用最普通的 JUnit 钉死，
 * 不依赖 Robolectric 渲染（渲染层看不到的就是"文案对不对、该不该重试"）。
 */
object SyncLogic {

    /** 单步超时：教务读课表本身就要十几秒，45 秒足够，再久就是出问题了 */
    const val STEP_TIMEOUT_MS = 45_000L

    /**
     * 看门狗：到点无条件把界面解绑。
     *
     * 为什么不能只靠 `withTimeout`：教务与网络那一整段是**阻塞式** socket IO，
     * 协程的取消要等阻塞调用自己返回才生效 —— 真机上就出现过程序卡在
     * 「正在登录教务…」不动（用户截图）。看门狗是"最后一道防线"，
     * 保证**界面**永远会给出结果，而不是无限转圈。
     */
    const val WATCHDOG_MS = 120_000L

    /** 401 的标准说法（服务端原话就是这一句） */
    const val RELOGIN = StudentError.SESSION   // 唯一出处：net/StudentError（与服务端 errors.SESSION_EXPIRED_TEXT 同串）

    /** 服务端早就部署了这条路由 = 支持重读；404 = 老服务端 */
    const val RESYNC_YES = "yes"
    const val RESYNC_NO = "no"

    // ------------------------------------------------------------ 失败怎么说

    fun fail(code: Int, serverMsg: String): SyncFail {
        val msg = serverMsg.trim()
        return when (code) {
            // 令牌没了/过期：用户唯一能做的事是重新登录 —— 必须给入口，别让他反复点
            401 -> SyncFail(
                text = if (msg.isBlank() || msg == RELOGIN) RELOGIN else "$RELOGIN（服务端：$msg）",
                needsRelogin = true,
                retryable = false,
            )
            // 403/404 的原话里可能带"服务端没部署/哪个接口"这类内部状态 —— 学生只需要一句话
            403 -> SyncFail(msg.ifBlank { StudentError.TEXT }, false, false)
            // 老服务端：这条路由还不存在。**不能说成网络故障**，那会把用户引到错的方向
            404, 405 -> SyncFail(StudentError.TEXT, false, false)
            429 -> SyncFail(msg.ifBlank { "服务器正忙，稍后再试" }, false, true)
            // 网络层统一把异常折成 code=0（见 CampusApi.call），原因在 msg 里
            0 -> SyncFail(msg.ifBlank { StudentError.TEXT }, false, true)
            else -> SyncFail(msg.ifBlank { StudentError.http(code, "课表同步") }, false, true)
        }
    }

    // ------------------------------------------------------------ 抓到了什么

    /** 超时怎么说：必须点明"停在哪一步"，否则用户只能看到转圈消失 */
    fun timeoutText(step: String): String =
        "停在「${step.ifBlank { "这一步" }}」超过 ${STEP_TIMEOUT_MS / 1000} 秒，已经停下了" +
            "（多半是网络或教务太慢，不是没反应）。可以再点一次。"

    /** 手机读到的课表规模：给用户看"到底读到了什么"，而不是只有一句"成功" */
    data class Stats(val activities: Int, val courses: Int)

    fun stats(acts: JsonArray?): Stats {
        val a = acts ?: return Stats(0, 0)
        val names = a.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // 教务的 id/名字有时是字符串有时是数字，形状不对就跳过这一条（不抛）
            runCatching { (o["courseName"] ?: o["lessonName"])?.jsonPrimitive?.contentOrNull }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()
        return Stats(a.size, names.size)
    }

    /** 抓完之后的回话：**读到了几条、几门课、本机现在是什么样**，一条都不能省 */
    fun uploadOkText(s: Stats, counts: Map<String, Int>): String =
        "已经交上去了：手机读到 ${s.activities} 条上课活动 · ${s.courses} 门课；" +
            "本机现在是 ${counts["courses"] ?: 0} 门课 · ${counts["tasks"] ?: 0} 项任务"

    fun syncedText(counts: Map<String, Int>): String =
        "已更新：${counts["courses"] ?: 0} 门课 · ${counts["tasks"] ?: 0} 项任务"

    // ------------------------------------------------- 「立即更新课表」的副标题

    /**
     * 这个副标题**必须是真的**。
     *
     * 老文案写的是「从教务系统重新读一次，几秒钟」，而那个按钮只把服务端缓存再拉一遍，
     * 根本不读教务 —— 用户信了这句话，课表没变也以为刷过了。
     * 现在按服务端能力说实话（能力由 `/plan` 回来有没有课表状态字段判定）：
     *   yes  → 服务器真的会去重读教务；
     *   no   → 服务器不支持，那就是"从服务器同步"；
     *   还不知道 → 说最保守的那句"重新同步"，不承诺读教务。
     */
    fun syncSubtitle(resync: String?): String = when (resync) {
        RESYNC_YES -> "让服务器重读一次你的教务课表，几秒钟"
        RESYNC_NO -> "从服务器同步（这台服务器还不能重读教务）"
        else -> "重新同步你的课表，几秒钟"
    }

    /** 服务端重读的结果怎么说 —— 三种真结果不能混成一句"成功" */
    fun serverSyncText(res: TimetableSyncRes): String = when {
        res.ok && res.changed > 0 -> "服务器已重读教务：课表有更新（换了 ${res.changed} 行）"
        res.ok -> "服务器已重读教务：课表没有变化"
        res.noCreds.isNotEmpty() -> "服务器上没存你的教务密码，读不了教务"
        else -> "服务器这次没能重读教务"
    }

    /** 服务端不支持重读时，退回「从服务器同步」的那句话 —— 说清楚做了什么、没做什么 */
    fun fallbackText(counts: Map<String, Int>): String =
        "这台服务器还不支持重读教务课表，这次只做了「从服务器同步」：" +
            "${counts["courses"] ?: 0} 门课 · ${counts["tasks"] ?: 0} 项任务"
}
