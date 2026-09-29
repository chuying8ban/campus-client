package top.ccbase.campus.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/**
 * 打开学习资源外链。
 *
 * 用 Chrome Custom Tabs 而不是 WebView：
 * ① App 内滑出，不跳出应用；
 * ② **B 站的登录态、完整播放器、弹幕都在** —— 内嵌 WebView 里看 B 站是残废的；
 * ③ 省一大块工作量，而且不会因为某个站禁内嵌而白屏。
 *
 * 三处必须防的坑（都会让用户看到"点了没反应"甚至闪退）：
 * ① 手机可能根本没装支持 Custom Tabs 的浏览器 → 退回系统 ACTION_VIEW；
 * ② 连系统浏览器都没有（极罕见）→ 给一句提示，不能崩；
 * ③ 只放行 http/https —— 资源表里未来若混入 `intent://`、`file://` 之类，
 *    直接启动会变成一个可被利用的跳板，这里从源头掐掉。
 */
object Links {

    fun open(ctx: Context, url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return
        // 大小写不敏感：`HTTPS://…` 在 RFC 里是合法写法，真出现时用户不该看到"不是网页"
        val low = u.lowercase()
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            Toast.makeText(ctx, "这个链接不是网页，暂不支持打开", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(u)

        // ① 先试 Custom Tabs
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
                .launchUrl(ctx, uri)
            return
        } catch (_: Exception) {
            // 落到 ②
        }

        // ② 退回系统浏览器
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) {
            // 落到 ③
        }

        Toast.makeText(ctx, "这台设备上没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }

    /**
     * 从链接里取一个**能给人看**的域名：
     * `https://www.bilibili.com/video/BV1x?p=1` → `bilibili.com`
     *
     * 用途：资料行上把目的地露出来。共享库里的链接是**别人收录的**，点之前就该看得见要去哪；
     * 点开之后浏览器地址栏还会再显示一次（Custom Tabs 有地址栏），两层都有。
     * 纯字符串处理（不走 android.net.Uri），所以能直接写用例。
     */
    fun hostOf(url: String?): String {
        val u = url?.trim().orEmpty()
        val at = u.indexOf("://")
        val rest = if (at >= 0) u.substring(at + 3) else u
        return rest.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
            .lowercase()
    }
}
