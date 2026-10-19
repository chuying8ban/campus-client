package top.ccbase.campus.ui.me

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 提建议附的图：从相册 Uri 读出来 → 缩到最长边 1280 → JPEG q80 → base64。
 *
 * 为什么要压：服务端接的是 base64 字符串，原图动辄 5MB（base64 之后 6.7MB），
 * 手机流量和我们库都不划算；而建议里的图只是"说明问题"，1280 长边看截图足够。
 * 压完通常 80~250KB，落在服务端 700K 字符的上限以内。
 */
object ImgB64 {

    private const val MAX_SIDE = 1280
    private const val QUALITY = 80

    fun decode(ctx: Context, uri: Uri): Bitmap? = runCatching {
        // 先只读尺寸，再按尺寸采样解码 —— 一张 4000px 的截图直接 decode 会瞬时吃 60MB
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val longest = max(opts.outWidth, opts.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_SIDE * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: return null
        val scale = MAX_SIDE.toFloat() / max(src.width, src.height)
        if (scale >= 1f) src else Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1), true,
        ).also { if (it !== src) src.recycle() }
    }.getOrNull()

    fun toBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun bytes(b64: String): ByteArray? = runCatching {
        Base64.decode(b64, Base64.DEFAULT)
    }.getOrNull()
}
