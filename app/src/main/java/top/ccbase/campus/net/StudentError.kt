package top.ccbase.campus.net

import android.util.Log

/**
 * 学生面报错文案 —— **App 里只有这一处**（对应服务端的 `errors.py`）。
 *
 * 为什么要单独一个对象
 * --------------------
 * 同一条提示会出现在四个地方：服务端响应体、nginx 错误页、App 文案、桌面宠物/其它前端。
 * 手抄四份必然分叉（这个项目在服务端已经分叉过一次）。谁要改口径，只改这里的常量。
 *
 * 纪律（2026-09-24 定的）
 * ----------------------
 * 面向学生的报错**不许出现**：异常类名、HTTP 状态码、接口路径、字段名、堆栈、模块名。
 * 这些信息全部只进 logcat（见 [tech]）—— 排查靠日志，不靠用户截图。
 *
 * 两句的分工（@writer 定稿）
 * -------------------------
 *  - [TEXT]  A 串，与服务端 `errors.STUDENT_ERROR_TEXT`、nginx 错误页**逐字相同**。
 *  - [HOW]   B 串，只在「整屏错误页 / 登录失败页」跟着 A 出现；toast 里**不放**
 *            —— 吐司塞不下两步导航，而且导航路径会随 UI 改，写死在服务端等于每次改 UI 都要后端发版。
 */
object StudentError {

    /** A 串：与 `errors.py` 的 `STUDENT_ERROR_TEXT` **必须逐字相同**。 */
    const val TEXT = "遇到问题请联系学生会"

    /** B 串：只在整屏错误页/登录失败页出（指向 App 内已有的入口，名字要对得上）。 */
    const val HOW = "可在「我的 → 关于 → 给 App 提建议」里告诉我们"

    /**
     * 未登录 / 会话过期。
     *
     * 与服务端 `errors.SESSION_EXPIRED_TEXT` 同一句：**服务端照这句来对齐 App**
     * （@writer 定稿），所以这句是学生能自己解决的状态，不并入 [TEXT]。
     */
    const val SESSION = "登录已过期，请重新登录"

    private const val TAG = "StudentError"

    /**
     * 记日志**必须容错**。
     *
     * 这个项目的「纯逻辑」用例是**普通 JUnit**（不挂 Robolectric，见 `SyncLogicTest` 的抬头），
     * 那种环境下 `android.util.Log` 没有被实现，直接调用会抛
     * `RuntimeException: Method w in android.util.Log not mocked` —— 于是"记一条日志"
     * 变成了"整条业务失败"。日志不该有这种权力（2026-09-24 真踩到：SyncLogicTest 红在这）。
     */
    private fun log(msg: String, e: Throwable? = null) {
        runCatching { if (e == null) Log.w(TAG, msg) else Log.w(TAG, msg, e) }
    }

    /** 整屏错误页用：A + 换行 + B。toast 别用这个。 */
    fun screen(): String = "$TEXT\n$HOW"

    /**
     * 整屏错误页的显示口径：**恰好是那句通用报错**时才补上 [HOW]（告诉学生去哪儿说），
     * 服务端给的产品提示（密码不对/登录过期…）原样显示 —— 它们本身已经说清了下一步。
     */
    fun screenText(msg: String): String = if (msg.trim() == TEXT) screen() else msg

    /**
     * 把一次技术性失败翻成学生看得懂的那一句，**同时把原文写进 logcat**。
     *
     * 以前每个 catch 点各自拼 `"XX失败：${e.javaClass.simpleName}"` / `"…${parseReason(e)}"`，
     * 于是异常类名、字段名、接口路径跟着文案一起上了屏（用户截图里就是一坨内部细节）。
     * 现在所有这类地方都过这一个口子：**出去一句话，进来一条日志**。
     */
    fun tech(e: Throwable? = null, where: String = ""): String {
        if (e != null || where.isNotEmpty()) {
            log("技术性失败${if (where.isEmpty()) "" else "（$where）"}", e)
        }
        return TEXT
    }

    /** 服务端返回了空白 detail（老服务端/网关截断）：同样只给学生一句话，状态码进日志。 */
    fun http(code: Int, where: String = ""): String {
        log("HTTP $code${if (where.isEmpty()) "" else "（$where）"}")
        return TEXT
    }
}
