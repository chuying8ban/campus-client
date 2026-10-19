package top.ccbase.campus.ui.me

import androidx.compose.runtime.mutableStateOf

/**
 * 「有一个素材正在下」这件事的全局快照（2026-10-19 用户：
 * "离开图库后如果有正在下载的图要在上面放一个弹窗提示正在下载以及下载进度，
 * 在图库页面里也要给下载进度"）。
 *
 * 为什么值是一个单例状态、而不是图库页自己的 `var`：下载协程本来就活在页面之外
 * （见 `ArtGalleryScreen` 里那个 `CoroutineScope`），页面退出后没有任何 UI 能读到它。
 * 现在谁都能读：图库页画格子上那圈进度，App 顶部画那条提示。
 */
object MediaJob {

    /** 正在下的那一项（图库里的 item.id）；null = 没有下载 */
    val id = mutableStateOf<String?>(null)

    /** 给用户看的名字（"动图 · 原神" 之类） */
    val label = mutableStateOf("")

    /** 0..100；-1 = 还不知道总量（服务器没给 Content-Length），显示"已下载 xMB" */
    val pct = mutableStateOf(-1)

    /** 已下载字节 */
    val bytes = mutableStateOf(0L)

    /** 总量；0 = 未知 */
    val total = mutableStateOf(0L)

    fun start(itemId: String, what: String, totalBytes: Long = 0L) {
        id.value = itemId
        label.value = what
        pct.value = -1
        bytes.value = 0L
        total.value = totalBytes
    }

    fun progress(now: Long, totalBytes: Long) {
        bytes.value = now
        total.value = totalBytes
        pct.value = if (totalBytes > 0) ((now * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
    }

    fun finish() {
        id.value = null
        pct.value = -1
        bytes.value = 0L
        total.value = 0L
        label.value = ""
    }

    fun humanBytes(n: Long): String = when {
        n >= 1024L * 1024 -> String.format("%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }
}
