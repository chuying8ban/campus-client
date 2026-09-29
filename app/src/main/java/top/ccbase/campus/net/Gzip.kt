package top.ccbase.campus.net

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * 解 gzip 响应体。
 *
 * **必须在字节层做，不能在 String 层做** —— 压缩后的字节当成 UTF-8 读一遍就已经毁了
 * （跟"安装包不能用 `call` 读成 String"是同一个道理）。所以两个传输层各自在
 * 读到字节之后、转成字符串之前调用它。
 *
 * 两个传输层共用这一份实现：写两遍的话，迟早有一边先腐烂（改了一边忘了另一边），
 * 而表现是"某些网络下接口解析失败"这种最难查的病。
 */
internal fun gunzip(b: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(minOf(b.size * 4, 1 shl 20))
    GZIPInputStream(b.inputStream()).use { gz ->
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = gz.read(buf)
            if (n <= 0) break
            total += n
            // 上限不是防服务器（那是我们自己的），是防"解压炸弹"把内存交出去。
            if (total > MAX_UNZIPPED) {
                throw IllegalStateException(
                    "响应解压后超过 ${MAX_UNZIPPED / 1024 / 1024}MB，已拒绝")
            }
            out.write(buf, 0, n)
        }
    }
    return out.toByteArray()
}

internal const val MAX_UNZIPPED = 8 * 1024 * 1024

/** 响应头里声明了 gzip 吗（大小写无所谓，nginx 给的是小写） */
internal fun isGzip(encoding: String?): Boolean =
    encoding?.contains("gzip", ignoreCase = true) == true
