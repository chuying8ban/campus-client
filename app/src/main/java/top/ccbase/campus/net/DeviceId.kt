package top.ccbase.campus.net

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 这台设备的身份 —— 服务端拿它当**作者身份的第二把钥匙**。
 *
 * 用户 2026-09-20：「我希望只有通过我现在用的这个设备登录我的账号的用户才能看到后台」。
 * 账号密码对了、但设备不在服务端的受信清单里 → 一样不是作者（后台、抢课都 403）。
 *
 * 为什么用 ANDROID_ID：
 * ① 不需要任何权限；
 * ② 同一台设备 + 同一个签名下稳定 —— **重装 App 不会变**（由签名和用户推导出来），
 *    只有刷机 / 换手机才会变。这点很重要：用自生成的 UUID 存本地的话，重装一次
 *    就把自己锁在后台外面了。
 *
 * 它不是密钥：真正的凭证是登录令牌，设备号只回答"是不是那一台"。
 * 所以在服务端那边，**不存在"第一次来就自动信任"** —— 必须由作者这边人工登记。
 */
object DeviceId {

    /** 空串 = 还没初始化过（单测里常常如此）。服务端那头等于"没报设备号" = 不是作者。 */
    var value: String = ""
        private set

    /**
     * 机型（人看得懂的那种，例如 `"Xiaomi 17 Pro pudding"`）。
     *
     * 为什么要连机型一起报：设备号是十六进制 ANDROID_ID，在服务端看就是一串随机字符，
     * **没法和"我只有一台手机，是小米 17 Pro"对上**。作者登记受信设备时得按机型认准 ——
     * 否则"他在别人手机上也登过自己的账号"这种情况就可能批错人。
     * 只取厂商 / 型号 / 代号，不含任何个人信息。
     */
    var model: String = ""
        private set

    fun init(ctx: Context) {
        if (value.isBlank()) {
            value = runCatching {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty().trim()
        }
        if (model.isBlank()) {
            model = runCatching {
                val sb = StringBuilder()
                for (p in listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
                    .map { it.orEmpty().trim() }.filter { it.isNotEmpty() }) {
                    if (sb.contains(p, ignoreCase = true)) continue    // "Xiaomi Xiaomi 17 Pro" 这种别写两遍
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(p)
                }
                sb.toString().take(80)
            }.getOrNull().orEmpty().trim()
        }
    }

    /** 仅供单测：把设备号/机型设成指定值（生产里只有 `init(ctx)` 一条路）。 */
    internal fun setForTest(v: String, m: String = "") {
        value = v.trim()
        model = m.trim()
    }
}
