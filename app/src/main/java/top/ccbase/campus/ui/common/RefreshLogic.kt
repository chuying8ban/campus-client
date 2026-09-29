package top.ccbase.campus.ui.common

import top.ccbase.campus.net.StudentError

/**
 * 刷新状态的文案规则（纯逻辑，可 JVM 测试）。
 *
 * 为什么单独拆出来：刷新这类功能最容易变成"假成功"——
 * 转一圈圈、什么都不说、失败也不说为什么。用户看不到服务器上发生了什么，
 * 所以这里把三种状态（进行中 / 成功 / 失败）**必须**翻译成一句带数字或带原因的人话。
 */
object RefreshLogic {

    /** 拿不到具体原因时的兜底 —— 绝不能是空白，那等于没说。 */
    const val NO_REASON = StudentError.TEXT     // 拿不到具体原因时也只给学生同一句（以前是"没拿到具体原因…"）

    const val NEVER = "还没更新过"

    /** ISO 时间裁成"09-16 22:40"这种能读的形状。 */
    fun shortTime(iso: String?): String? {
        val s = iso?.trim().orEmpty()
        if (s.length < 16) return if (s.isEmpty()) null else s
        return s.take(16).replace("T", " ")
    }

    /** 常态文案：上次什么时候更新的。 */
    fun summary(at: String?): String {
        val t = shortTime(at)
        return if (t == null) NEVER else "上次更新 $t"
    }

    /** 成功文案：必须带真实数字，不许写"更新成功"就完事。 */
    fun doneText(courses: Int, tasks: Int, at: String?): String {
        val when_ = shortTime(at)
        val body = "已更新：$courses 门课 · $tasks 项任务"
        return if (when_ == null) body else "$body · $when_"
    }

    /** 失败文案：必须说卡在哪。 */
    fun failText(reason: String?): String {
        // 不再前缀"更新失败："：服务端给的就是成品文案（一句话或我们自己写的产品提示），
        // 再套一层前缀等于同一件事出现两种说法。
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) NO_REASON else r
    }
}
