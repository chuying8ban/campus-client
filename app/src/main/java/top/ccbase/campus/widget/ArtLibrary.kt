package top.ccbase.campus.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import top.ccbase.campus.net.Net

/**
 * 「在线图库」—— 桌面小组件可以用的插画底图。
 *
 * 图**不在 APK 里**：随包发图会把包撑大、也没法加新的。这里只放一份清单（catalog.json），
 * 图在服务器上，用户搜到喜欢的再下载一张存本机（`WidgetThemes.saveUserImage`）。
 *
 * ## 图是哪来的
 * 这些图是**为这个 App 单独生成的**（不是从网上抓的）：
 * 网上能搜到的二次元图基本都是有版权的同人/商业作品，抓来放进一个发给别人的 App 里
 * 是侵权；生成的原图可以随包/随服务给所有人看。清单里仍然带 `author/license` 字段，
 * 以后要放别人的图（CC 协议），这两个字段直接填，App 端不用改。
 *
 * ## 搜索
 * 清单很小（几十条），搜索在**本机做**：按标题、标签、id 匹配，输入即筛，
 * 不用等网络往返。这也意味着断网时用不了图库，但已经下载过的图仍然生效。
 */
data class ArtItem(
    val id: String,
    val title: String,
    val tags: List<String>,
    val url: String,
    val thumbUrl: String,
    val author: String,
    val license: String,
    /** illustration = 插画；photo = 摄影 */
    val kind: String = "illustration",
    /** landscape = 横图（桌面小组件）；portrait = 竖图（App 页面背景，手机是竖屏） */
    val orient: String = "landscape",
    /**
     * 动态内容（mp4/webm）。用户 2026-10-18："搜索出来的内容如果是动态的要标注是动态"。
     * 图库里静态和动态**混在一条流里**，这个字段决定卡片上有没有「动」角标、以及点下去走播放而不是解码。
     */
    val animated: Boolean = false,
) {
    /** 两种用途分开列：小组件横向、App 背景竖向，混在一起搜反而更难找。 */
    fun ofKind(kind: String): Boolean = this.kind == kind
    fun ofOrient(orient: String): Boolean = this.orient == orient

    /** 搜索匹配：标题/标签/id 任一命中即算。空查询 = 全部。 */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (title.contains(q, ignoreCase = true)) return true
        if (id.contains(q, ignoreCase = true)) return true
        return tags.any { it.contains(q, ignoreCase = true) }
    }
}

/** 一次整库搜索的结果（还翻不翻得动看 hasMore；hint = 这个词没收录时服务端给的提醒） */
data class LivePage(
    val items: List<ArtItem>,
    val hasMore: Boolean,
    val hint: String? = null,
)

object ArtRepo {

    /** 图和清单都挂在这个站上（和更新清单同一个域名、同一条无 SNI 链路） */
    const val BASE = "https://study.ccbase.top"
    const val DIR = "/updates/art"

    private val thumbs = LruCache<String, Bitmap>(24)

    fun catalogUrl(): String = "$BASE$DIR/catalog.json"

    /**
     * 清单里的图地址有三种写法，都得认：
     *   `01_x.jpg`            → 服务器上那张随包之外的成品图（/updates/art/）
     *   `/api/art/img?u=...`  → 走本机代理去上游取，不落盘（清单扩容后绝大多数是这种）
     *   `https://...`         → 原样用
     */
    private fun absUrl(p: String): String = when {
        p.startsWith("http://") || p.startsWith("https://") -> p
        p.startsWith("/") -> BASE + p
        else -> "$BASE$DIR/$p"
    }

    /**
     * 解析清单。容错到"能出几张就出几张"：
     * 缺 url 的条目直接跳过，而不是整页报错 —— 服务器上少一个文件不该让图库整页变空白。
     */
    fun parseCatalog(json: String): List<ArtItem> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<ArtItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val file = o.optString("file")
            if (id.isBlank() || file.isBlank()) continue
            val thumb = o.optString("thumb").ifBlank { file }
            val tagsJson = o.optJSONArray("tags")
            val tags = buildList {
                if (tagsJson != null) {
                    for (j in 0 until tagsJson.length()) add(tagsJson.optString(j))
                }
            }
            out += ArtItem(
                id = id,
                title = o.optString("title").ifBlank { id },
                tags = tags,
                url = absUrl(file),
                thumbUrl = absUrl(thumb),
                author = o.optString("author").ifBlank { "校园助手" },
                license = o.optString("license").ifBlank { "原创生成" },
                kind = o.optString("kind").ifBlank { "illustration" },
                orient = o.optString("orient").ifBlank { "landscape" },
            )
        }
        return out
    }

    /**
     * 拉清单。失败抛异常，由 UI 显示"图库暂时读不出来"，不静默空列表。
     *
     * 清单现在有近千条、400 多 KB，每次打开都重下一遍太浪费（校园网也就那样）。
     * 所以本机留一份缓存、10 分钟内直接用；网络失败时优先退回旧缓存而不是给用户看空白。
     */
    fun fetchCatalog(ctx: android.content.Context, maxAgeMs: Long = 10 * 60 * 1000L): List<ArtItem> {
        val cache = java.io.File(ctx.cacheDir, "art_catalog.json")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < maxAgeMs) {
            runCatching { return parseCatalog(cache.readText()) }
        }
        return try {
            val reply = Net.dial.call("GET", catalogUrl(), mapOf("Accept" to "application/json"), null)
            if (reply.code != 200) throw IllegalStateException("图库清单 HTTP ${reply.code}")
            runCatching { cache.writeText(reply.text) }
            parseCatalog(reply.text)
        } catch (e: Exception) {
            // 有旧缓存就先给旧的（图库不该因为一次网络抖动整页空白）
            if (cache.isFile) runCatching { return parseCatalog(cache.readText()) }
            throw e
        }
    }

    /**
     * 整库搜索（不只看服务端预先同步的那 976 张）。
     *
     * 走的是自己服务器上的 `/api/art/search`：它在服务端查 safebooru（Danbooru 的 SFW 镜像，
     * 官方口径几百万张），把缩略图/大图以 `/api/art/img?u=...` 转发回来 ——
     * 这样手机只需要能连我们的域名（校园网、移动网都行），不必直连境外图床。
     */
    fun searchLive(q: String, page: Int, orient: String, limit: Int = 100): LivePage {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val url = "$BASE/api/art/search?q=$enc&page=$page&orient=$orient&limit=$limit"
        val reply = Net.dial.call("GET", url, mapOf("Accept" to "application/json"), null)
        if (reply.code != 200) throw IllegalStateException("搜索 HTTP ${reply.code}")
        val root = JSONObject(reply.text)
        val arr = root.optJSONArray("items") ?: return LivePage(emptyList(), false)
        val out = ArrayList<ArtItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val tagsJson = o.optJSONArray("tags")
            out += ArtItem(
                id = id,
                title = o.optString("title").ifBlank { "插画" },
                tags = buildList { if (tagsJson != null) for (j in 0 until tagsJson.length()) add(tagsJson.optString(j)) },
                url = BASE + "/api/art" + o.optString("full"),
                thumbUrl = BASE + "/api/art" + o.optString("thumb"),
                author = o.optString("author").ifBlank { "未知画师" },
                license = o.optString("license").ifBlank { "同人作品" },
                kind = if (o.optBoolean("animated", false)) "animated" else "illustration",
                orient = o.optString("orient").ifBlank { orient },
                animated = o.optBoolean("animated", false),
            )
        }
        return LivePage(
            items = out,
            hasMore = root.optBoolean("has_more", out.isNotEmpty()),
            hint = root.optString("hint").takeIf { it.isNotBlank() },
        )
    }

    /** 缩略图（内存缓存，12 张一屏够用）。 */
    fun thumb(item: ArtItem): Bitmap? {
        thumbs.get(item.id)?.let { return it }
        val bytes = downloadBytes(item.thumbUrl) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        thumbs.put(item.id, bmp)
        return bmp
    }

    /**
     * 原始字节（动图/视频用）。静态图仍走 [full] 解码，动态必须保字节 —— 解码成 Bitmap 就只剩一帧。
     */
    fun bytes(item: ArtItem): ByteArray? = downloadBytes(item.url)

    /** 整张图。调用方负责压缩并落盘（见 WidgetThemes.saveUserImage）。 */
    fun full(item: ArtItem): Bitmap? {
        val bytes = downloadBytes(item.url) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * 取一张图。先走 App 那条自己选路的链（校园网能通的那条），失败再退回普通
     * HttpURLConnection —— 图床和我们不是同一套握手指纹，只用一条路会在某些网络下
     * 整片缩略图空白（用户看到的"图库没有预览图"）。
     */
    private fun downloadBytes(url: String): ByteArray? {
        runCatching {
            val buf = java.io.ByteArrayOutputStream()
            if (Net.dial.stream(url) { chunk, n, _ -> buf.write(chunk, 0, n) } > 0) {
                return buf.toByteArray()
            }
        }
        return runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept", "image/*")
            try {
                if (c.responseCode !in 200..299) return null
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    fun clearThumbs() = thumbs.evictAll()
}
