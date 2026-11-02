// ThemePacks.kt — 精选主题（服务器下发的一整套搭配）
//
// 一套「主题」= 明暗 + 强调色 + 页面背景 + 背景浓淡（可含文字色）。
// 用户在「我的 → App 主题 → 精选主题」里点一张卡，整套一次换掉 —— 不用自己
// 从色环上试、再去背景里挑图。
//
// 为什么要从服务器下发：主题要能换、能加、能下架，不能绑死在一个版本的 APK 里。
// 清单在 https://dl.ccbase.top/themes/index.json（和更新包、图库、字体同一台快机器）。
//
// 格式（一条）：
//   { "id":"starry", "name":"星夜", "tag":"深色 · 静谧", "desc":"一句话",
//     "mode":"dark|light|system",          // 缺省 system
//     "accent":"diy:329:1.0",              // default / preset:<id> / diy:<hue>:<tint>
//     "alpha":0.58,                        // 背景浓淡 0..1，缺省 0.5
//     "tint":"auto",                       // 文字色，见 TextTint
//     "bg":{"type":"image|anim|solid","url":"…","cover":"…",
//           "kind":"aurora","tintHex":"#A06BFF"} }
// 容错原则：字段缺了就用缺省，整条不可用就跳过这一条 —— 服务器上多一条坏数据，
// 不该让「精选主题」整块变空白（图库清单当年就是这么栽的）。
package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import top.ccbase.campus.net.Net
import top.ccbase.campus.widget.ArtItem
import top.ccbase.campus.widget.ArtRepo
import java.io.File

data class ThemePack(
    val id: String,
    val name: String,
    val tag: String,
    val desc: String,
    /** dark / light / system */
    val mode: String,
    /** 强调色规格：default / preset:<id> / diy:<hue>:<tint> */
    val accent: String,
    val alpha: Float,
    val tint: String,
    /** image / anim / solid */
    val bgType: String,
    val bgUrl: String,
    val coverUrl: String,
    val animKind: String = "",
    val animTint: String = "",
)

object ThemePacks {

    private const val TAG = "ThemePacks"
    const val BASE = "https://dl.ccbase.top"
    const val DIR = "/themes"
    private const val CACHE = "theme_packs.json"
    private const val PREF = "theme_packs"
    private const val K_APPLIED = "applied"

    fun indexUrl(): String = "$BASE$DIR/index.json"

    /** 解析清单。坏一条跳一条，坏到没有就返回空表。 */
    fun parse(json: String): List<ThemePack> {
        val arr = runCatching { JSONObject(json).optJSONArray("items") }.getOrNull() ?: return emptyList()
        val out = ArrayList<ThemePack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val bg = o.optJSONObject("bg")
            val type = bg?.optString("type")?.trim().orEmpty()
            val url = bg?.optString("url")?.trim().orEmpty()
            // 图片主题没地址＝套上去是空白，直接跳过这条
            if (type == "image" && url.isBlank()) continue
            if (type == "anim" && bg?.optString("kind").isNullOrBlank()) continue
            out += ThemePack(
                id = id,
                name = name,
                tag = o.optString("tag").trim(),
                desc = o.optString("desc").trim(),
                mode = o.optString("mode").trim().ifBlank { AppearanceStore.SYSTEM }
                    .let { if (it == AppearanceStore.DARK || it == AppearanceStore.LIGHT ||
                        it == AppearanceStore.SYSTEM) it else AppearanceStore.SYSTEM },
                accent = sanitizeAccent(o.optString("accent")),
                alpha = o.optDouble("alpha", 0.5).toFloat().coerceIn(0.1f, 1f),
                tint = o.optString("tint").trim().ifBlank { TextTint.AUTO },
                bgType = if (type == "image" || type == "anim") type else "solid",
                bgUrl = url,
                coverUrl = bg?.optString("cover")?.trim().orEmpty().ifBlank { url },
                animKind = bg?.optString("kind")?.trim().orEmpty(),
                animTint = bg?.optString("tintHex")?.trim().orEmpty(),
            )
        }
        return out
    }

    /**
     * 强调色规格只收三种写法。清单来自网络，`accent` 会被原样塞进偏好再解析成颜色，
     * 不设白名单的话等于让服务器决定本机能存什么 —— 这里挡一道。
     */
    fun sanitizeAccent(raw: String): String {
        val v = raw.trim()
        if (v == "default") return v
        if (v.startsWith("preset:")) {
            val id = v.removePrefix("preset:")
            return if (id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) v else "default"
        }
        if (v.startsWith("diy:")) {
            val p = v.removePrefix("diy:").split(":")
            val hue = p.getOrNull(0)?.toFloatOrNull()
            val tint = p.getOrNull(1)?.toFloatOrNull() ?: 1f
            return if (hue != null) "diy:${hue.toInt()}:$tint" else "default"
        }
        return "default"
    }

    /**
     * 取清单：10 分钟内用缓存；网络失败时退回旧缓存（离线也能看到上次那排主题）。
     * 与图库清单同一套路 —— 校园网会抖，抖一次不该整块变空。
     */
    fun fetch(ctx: Context, maxAgeMs: Long = 10 * 60 * 1000L): List<ThemePack> {
        val cache = File(ctx.cacheDir, CACHE)
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < maxAgeMs) {
            runCatching { return parse(cache.readText()) }
        }
        return try {
            val reply = Net.dial.call("GET", indexUrl(), mapOf("Accept" to "application/json"), null)
            if (reply.code != 200) throw IllegalStateException("主题清单 HTTP ${reply.code}")
            val packs = parse(reply.text)
            if (packs.isNotEmpty()) runCatching { cache.writeText(reply.text) }
            packs
        } catch (e: Exception) {
            if (cache.isFile) runCatching { return parse(cache.readText()) }
            throw e
        }
    }

    /** 封面缩略图（走图库同一套下载 + 内存缓存，不另起一套）。 */
    fun cover(pack: ThemePack): Bitmap? {
        if (pack.coverUrl.isBlank()) return null
        return runCatching { ArtRepo.thumb(artOf(pack, pack.coverUrl)) }.getOrNull()
    }

    private fun artOf(pack: ThemePack, url: String) = ArtItem(
        id = "theme:${pack.id}:${url.hashCode()}",
        title = pack.name,
        tags = emptyList(),
        url = url,
        thumbUrl = url,
        author = "校园助手",
        license = "原创",
        kind = "illustration",
        orient = "portrait",
    )

    data class Result(val ok: Boolean, val error: String? = null)

    /**
     * 整套套上去。顺序有讲究：
     *  ① 明暗、强调色先落（纯内存/偏好，不会失败）；
     *  ② 背景最后落，因为它可能是下载 —— 失败了也不该把颜色改了一半；
     *  ③ **浓淡必须在 setBackground 之后**：换一类背景会把浓淡重置成 50%
     *    （Theme.kt setBackground 里的既有行为），先设就被覆盖掉了。
     */
    fun apply(ctx: Context, pack: ThemePack): Result {
        AppearanceStore.setTheme(ctx, pack.mode)
        AppearanceStore.setAccent(ctx, sanitizeAccent(pack.accent))

        when (pack.bgType) {
            "image" -> {
                val bytes = ArtRepo.bytes(artOf(pack, pack.bgUrl)) { _, _ -> }
                if (bytes == null) {
                    val why = ArtRepo.lastError ?: "背景没下下来，检查网络再点一次"
                    Log.w(TAG, "背景下载失败 ${pack.id}: $why")
                    return Result(false, why)
                }
                val r = GalleryApply.apply(ctx, "background", pack.name, bytes, "image/jpeg")
                if (!r.ok) return Result(false, r.error ?: "这套主题的背景没套上")
            }
            "anim" -> {
                val kind = DynKind.of(pack.animKind)
                if (kind == null) return Result(false, "这套主题的动效这个版本还不支持")
                val argb = runCatching {
                    val h = pack.animTint.removePrefix("#")
                    if (h.length == 6) (0xFF000000.toInt() or h.toLong(16).toInt()) else h.toLong(16).toInt()
                }.getOrNull() ?: 0xFFA06BFF.toInt()
                AppearanceStore.setBackground(ctx, animSpec(kind, androidx.compose.ui.graphics.Color(argb)))
            }
            else -> AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID)
        }

        AppearanceStore.setBgAlpha(ctx, pack.alpha)
        TextTint.set(ctx, pack.tint)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(K_APPLIED, pack.id).apply()
        return Result(true)
    }

    /** 上一次用精选主题套的是哪一套（用于卡片上的「正在用」角标）。 */
    fun appliedId(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_APPLIED, null)

    /** 用户自己又去动过明暗/背景/颜色之后，角标就不该再显示 —— 由调用方比对当前状态决定。 */
    fun clearApplied(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(K_APPLIED).apply()
    }
}
