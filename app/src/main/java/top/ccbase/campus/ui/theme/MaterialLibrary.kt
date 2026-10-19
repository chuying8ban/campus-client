package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * 本地素材库：**App 主题**和**桌面小组件**各一本，互不干扰。
 *
 * ## 为什么要有它（2026-10-20 用户）
 * 「加一个支持将素材下载到本地的功能，做两个专门的本地素材库（app主题和桌面小控件要分开），
 *  用户也可以在本地素材库里上传自己的文件」。
 *
 * 在这之前只有"一份当前素材"（`UserMedia` 的 `theme_media_<slot>.<ext>`）：
 * 图库里点一张就立刻顶掉上一张，想换回去只能重新下、重新找。素材库把它变成"收藏夹"：
 * 下载 == 收进库里，用哪个点哪个，删哪个不影响别的。
 *
 * ## 落盘
 * ```
 * filesDir/materials/<slot>/<id>.<ext>       素材本体
 * filesDir/materials/<slot>/<id>.thumb.jpg   缩略图（列表用，生成后复用）
 * filesDir/materials/<slot>/index.json       索引：名字/类型/来源/时间
 * ```
 * 索引与文件同目录：拷走整个目录就是一本完整的库，不需要额外数据库。
 */
object MaterialLibrary {

    enum class Slot(val key: String, val label: String) {
        APP("app", "App 主题"),
        WIDGET("widget", "桌面小组件"),
    }

    enum class Kind(val id: String, val label: String) {
        IMAGE("image", "图片"),
        GIF("gif", "动图"),
        VIDEO("video", "视频"),
    }

    data class Item(
        val id: String,
        val name: String,
        val kind: Kind,
        val ext: String,
        val bytes: Long,
        val addedAt: Long,
        /** "download" = 从在线图库下载来的；"user" = 用户自己传的 */
        val source: String,
    ) {
        val isVideoOrGif: Boolean get() = kind != Kind.IMAGE
    }

    private const val TAG = "MaterialLibrary"
    private const val MAX_BYTES = 200L * 1024 * 1024

    private fun dir(ctx: Context, slot: Slot): File =
        File(ctx.filesDir, "materials/${slot.key}").apply { mkdirs() }

    private fun indexFile(ctx: Context, slot: Slot) = File(dir(ctx, slot), "index.json")

    // ── 索引读写 ───────────────────────────────────────────────────────────

    fun list(ctx: Context, slot: Slot): List<Item> {
        val f = indexFile(ctx, slot)
        if (!f.isFile) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val item = Item(
                    id = o.getString("id"),
                    name = o.optString("name", "素材"),
                    kind = Kind.entries.firstOrNull { it.id == o.optString("kind") } ?: Kind.IMAGE,
                    ext = o.optString("ext", "bin"),
                    bytes = o.optLong("bytes"),
                    addedAt = o.optLong("addedAt"),
                    source = o.optString("source", "user"),
                )
                // 文件被人从"清理"里删掉了就别在列表里显示空壳
                if (file(ctx, slot, item.id) != null) out += item
            }
        }
        return out.sortedByDescending { it.addedAt }
    }

    private fun write(ctx: Context, slot: Slot, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(
                JSONObject()
                    .put("id", i.id)
                    .put("name", i.name)
                    .put("kind", i.kind.id)
                    .put("ext", i.ext)
                    .put("bytes", i.bytes)
                    .put("addedAt", i.addedAt)
                    .put("source", i.source),
            )
        }
        val f = indexFile(ctx, slot)
        val tmp = File(f.parentFile, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    fun file(ctx: Context, slot: Slot, id: String): File? =
        dir(ctx, slot).listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("$id.") && !it.name.endsWith(".thumb.jpg") && !it.name.endsWith(".json")
        }?.takeIf { it.length() > 0L }

    fun thumb(ctx: Context, slot: Slot, id: String): File? =
        File(dir(ctx, slot), "$id.thumb.jpg").takeIf { it.isFile && it.length() > 0 }

    /** 生成缩略图（列表用）。视频/gif 取第一帧；失败就返回 null，界面出占位。 */
    fun ensureThumb(ctx: Context, slot: Slot, item: Item, maxSide: Int = 480): File? {
        thumb(ctx, slot, item.id)?.let { return it }
        val src = file(ctx, slot, item.id) ?: return null
        val bmp = runCatching {
            when (item.kind) {
                Kind.IMAGE, Kind.GIF -> decodeSampled(src, maxSide)
                Kind.VIDEO -> MediaMetadataRetriever().use { r ->
                    r.setDataSource(src.path)
                    r.getFrameAtTime(0)
                }
            }
        }.getOrNull() ?: return null
        val out = File(dir(ctx, slot), "${item.id}.thumb.jpg")
        val ok = runCatching {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        }.isSuccess
        bmp.recycle()
        return if (ok) out else null
    }

    private fun decodeSampled(f: File, maxSide: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, probe)
        if (probe.outWidth <= 0) return null
        var sample = 1
        while (max(probe.outWidth, probe.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ── 增删 ───────────────────────────────────────────────────────────────

    /** 从手机里选的文件（SAF uri）导入。 */
    fun import(ctx: Context, slot: Slot, uri: Uri): Item? = runCatching {
        val cr = ctx.contentResolver
        val mime = (cr.getType(uri) ?: "").lowercase()
        val guess = (uri.lastPathSegment ?: "").substringAfterLast('/')
        val ext = extOf(mime, guess) ?: return null
        val input = cr.openInputStream(uri) ?: return null
        input.use { copyIn(ctx, slot, it, ext, displayName(uri, mime), "user") }
    }.getOrNull()

    /** 从在线图库下好的字节直接入库（图片/动图/视频都走这里）。 */
    fun saveBytes(ctx: Context, slot: Slot, bytes: ByteArray, name: String, mime: String = ""): Item? =
        runCatching {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val ext = sniffExt(bytes) ?: extOf(mime, name) ?: return null
            copyIn(ctx, slot, bytes.inputStream(), ext, name, "download")
        }.getOrNull()

    private fun copyIn(
        ctx: Context,
        slot: Slot,
        input: java.io.InputStream,
        ext: String,
        name: String,
        source: String,
    ): Item? {
        val id = "m" + System.currentTimeMillis().toString(36) + (100..999).random()
        val dir = dir(ctx, slot)
        val out = File(dir, "$id.$ext")
        var total = 0L
        var tooBig = false
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) { tooBig = true; break }
                o.write(buf, 0, n)
            }
        }
        if (tooBig || out.length() == 0L) {
            out.delete()
            return null
        }
        // 落盘之后立刻验一遍：截断的视频有大小、放不出来，收进素材库等于给自己埋雷
        val kind = kindOf(out, ext)
        if (kind != Kind.IMAGE && !UserMedia.playable(out)) {
            out.delete()
            return null
        }
        if (kind == Kind.IMAGE && runCatching { decodeSampled(out, 64) }.getOrNull() == null) {
            out.delete()
            return null
        }
        val item = Item(
            id = id,
            name = name.ifBlank { "素材" }.take(60),
            kind = kind,
            ext = ext,
            bytes = out.length(),
            addedAt = System.currentTimeMillis(),
            source = source,
        )
        write(ctx, slot, list(ctx, slot) + item)
        ensureThumb(ctx, slot, item)
        return item
    }

    fun delete(ctx: Context, slot: Slot, id: String): Boolean {
        val remaining = list(ctx, slot).filterNot { it.id == id }
        val ok = runCatching {
            file(ctx, slot, id)?.delete()
            thumb(ctx, slot, id)?.delete()
        }.isSuccess
        write(ctx, slot, remaining)
        return ok
    }

    private fun extOf(mime: String, name: String): String? {
        val n = name.lowercase()
        return when {
            mime == "image/gif" || n.endsWith(".gif") -> "gif"
            mime.contains("webp") || n.endsWith(".webp") -> "webp"
            mime.contains("png") || n.endsWith(".png") -> "png"
            mime.contains("jpeg") || mime.contains("jpg") || n.endsWith(".jpg") || n.endsWith(".jpeg") -> "jpg"
            mime.contains("bmp") || n.endsWith(".bmp") -> "bmp"
            mime.contains("mp4") || n.endsWith(".mp4") -> "mp4"
            mime.contains("webm") || n.endsWith(".webm") -> "webm"
            else -> null
        }
    }

    /** 按文件头判类型 —— SAF 给的 mime 经常是 application/octet-stream，不可信。 */
    private fun sniffExt(b: ByteArray): String? {
        if (b.size < 16) return null
        fun ascii(off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b[0] == 0x89.toByte() && ascii(1, 3) == "PNG" -> "png"
            ascii(0, 3) == "GIF" -> "gif"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
            ascii(4, 4) == "ftyp" -> "mp4"
            b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() -> "webm"
            else -> null
        }
    }

    private fun kindOf(f: File, ext: String): Kind = when (ext.lowercase()) {
        "gif" -> Kind.GIF
        "mp4", "webm", "mkv" -> Kind.VIDEO
        else -> Kind.IMAGE
    }

    private fun displayName(uri: Uri, mime: String): String {
        val raw = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: ""
        val base = raw.substringBeforeLast('.').ifBlank { "我的素材" }
        return base.take(40) + if (mime.startsWith("video")) "（视频）" else ""
    }

    // ── 选中态 ─────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("campus_materials", Context.MODE_PRIVATE)

    /** 当前这套库里"正在用"的那件（可能已被删除 → 返回 null）。 */
    fun selected(ctx: Context, slot: Slot): Item? {
        val id = prefs(ctx).getString("sel_${slot.key}", null) ?: return null
        return list(ctx, slot).firstOrNull { it.id == id }
    }

    fun select(ctx: Context, slot: Slot, id: String?) {
        prefs(ctx).edit().apply {
            if (id == null) remove("sel_${slot.key}") else putString("sel_${slot.key}", id)
        }.apply()
    }

    /** 素材 spec：`mat:<slot>:<id>`。桌面 / App 两边都靠它定位到库里那一件。 */
    fun spec(slot: Slot, id: String): String = "mat:${slot.key}:$id"

    fun parseSpec(spec: String): Pair<Slot, String>? {
        if (!spec.startsWith("mat:")) return null
        val parts = spec.split(':')
        if (parts.size != 3) return null
        val slot = Slot.entries.firstOrNull { it.key == parts[1] } ?: return null
        if (parts[2].isBlank()) return null
        return slot to parts[2]
    }

    fun resolve(ctx: Context, spec: String): UserMedia.Media? {
        val (slot, id) = parseSpec(spec) ?: return null
        val item = list(ctx, slot).firstOrNull { it.id == id } ?: return null
        val f = file(ctx, slot, id) ?: return null
        val kind = when (item.kind) {
            Kind.VIDEO -> UserMedia.Kind.VIDEO
            Kind.GIF -> UserMedia.Kind.GIF
            else -> return null
        }
        return UserMedia.Media(kind, f)
    }

    /** 库里所有条目一共占多少字节（界面上要如实显示）。 */
    fun totalBytes(ctx: Context, slot: Slot): Long = list(ctx, slot).sumOf { it.bytes }
}
