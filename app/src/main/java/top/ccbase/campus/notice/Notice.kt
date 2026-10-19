package top.ccbase.campus.notice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import top.ccbase.campus.net.Net

/**
 * 公告（2026-10-19 用户："给app加一个通知（或者叫公告）通道，我可以通过后台给所有用户
 * 发送公告（公告里要支持放链接，图片等）"）。
 *
 * 设计取舍：
 *  · **不做推送**。推送要过厂商通道、要用户开权限，校园助手这种自己分发的包根本拿不到；
 *    改成"进 App 拉一次，没看过的弹一次"，覆盖率和稳定性都更好。
 *  · 内容是**我们自己的服务器**上的一份 json，图片走同一个域（`/api/art/img?u=…` 也行，
 *    也可以直接放完整 URL），不依赖任何第三方。
 *  · id 由后台生成；本机只记"看过哪些 id"，所以同一条永远只弹一次。
 */
data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val link: String,
    val image: String,
    val ts: Long,
)

object NoticeRepo {

    private const val BASE = "https://study.ccbase.top"
    private const val NAME = "campus_notice"
    private const val K_SEEN = "seen_ids"

    fun fetch(): List<Notice> {
        val reply = Net.dial.call(
            "GET", "$BASE/api/art/notice",
            mapOf("Accept" to "application/json"), null,
        )
        if (reply.code != 200) return emptyList()
        val root = JSONObject(reply.text)
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Notice>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out += Notice(
                id = id,
                title = o.optString("title").ifBlank { "公告" },
                body = o.optString("body"),
                link = o.optString("link"),
                image = o.optString("image"),
                ts = o.optLong("ts"),
            )
        }
        return out
    }

    private fun seen(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet(K_SEEN, emptySet())?.toSet() ?: emptySet()

    /** 还没看过的最新一条；没有就 null */
    fun firstUnseen(ctx: Context): Notice? {
        val seen = seen(ctx)
        return runCatching { fetch().firstOrNull { it.id !in seen } }.getOrNull()
    }

    fun markSeen(ctx: Context, id: String) {
        val now = seen(ctx).toMutableSet()
        now += id
        // 只留最近 200 个 id：公告不会多到需要全留，但也不能无限涨
        val keep = now.toList().takeLast(200).toSet()
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(K_SEEN, keep).apply()
    }

    fun image(url: String): Bitmap? {
        if (url.isBlank()) return null
        val full = if (url.startsWith("http")) url else BASE + url
        return runCatching {
            val buf = java.io.ByteArrayOutputStream()
            Net.dial.stream(full) { chunk, n, _ -> buf.write(chunk, 0, n) }
            val b = buf.toByteArray()
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }.getOrNull()
    }
}
