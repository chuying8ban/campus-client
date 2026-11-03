package top.ccbase.campus.ui.me

import androidx.compose.runtime.mutableStateMapOf

/**
 * 「现在有哪些素材正在下」的全局快照（2026-10-19 用户：
 * "离开图库后如果有正在下载的图要在上面放一个弹窗提示正在下载以及下载进度，
 * 在图库页面里也要给下载进度"）。
 *
 * 2026-11-03 用户："我希望可以同时下载多个素材" —— 以前这里是**单个** id/pct/bytes，
 * 一次只能下一件（下着的时候点别的格子直接被拦），第二件必须等第一件完。
 * 现在是一张按 item.id 索引的表：点几件就并行下几件，每格各自一圈进度，
 * 顶部那条提示改成汇总（"正在下载 2 个素材"）。
 *
 * 为什么值必须活在单例里：下载协程本来就活在页面之外（见 `ArtGalleryScreen`），
 * 页面退出后没有任何 UI 能读到它。现在谁都能读：图库格子上那圈进度、App 底部那条提示。
 */
object MediaJob {

    data class Job(
        /** 图库里的 item.id */
        val id: String,
        /** 给用户看的名字（"动图 · 原神" 之类） */
        val label: String,
        /** 0..100；-1 = 还不知道总量（服务器没给 Content-Length），显示"已下载 xMB" */
        val pct: Int = -1,
        /** 已下载字节 */
        val bytes: Long = 0L,
        /** 总量；0 = 未知 */
        val total: Long = 0L,
    )

    private val table = mutableStateMapOf<String, Job>()

    /** 正在下的全部（Compose 可观察，按开始顺序） */
    val active: List<Job> get() = table.values.toList()

    val count: Int get() = table.size

    fun isRunning(itemId: String): Boolean = table.containsKey(itemId)

    fun job(itemId: String): Job? = table[itemId]

    fun pct(itemId: String): Int = table[itemId]?.pct ?: -1

    fun bytes(itemId: String): Long = table[itemId]?.bytes ?: 0L

    fun start(itemId: String, what: String, totalBytes: Long = 0L) {
        table[itemId] = Job(id = itemId, label = what, total = totalBytes)
    }

    fun progress(itemId: String, now: Long, totalBytes: Long) {
        val cur = table[itemId] ?: return
        table[itemId] = cur.copy(
            bytes = now,
            total = totalBytes,
            pct = if (totalBytes > 0) ((now * 100) / totalBytes).toInt().coerceIn(0, 100) else -1,
        )
    }

    fun finish(itemId: String) {
        table.remove(itemId)
    }

    fun humanBytes(n: Long): String = when {
        n >= 1024L * 1024 -> String.format("%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }

    /** 汇总进度：全部都知道总量才算百分比，否则 -1（界面画不确定进度条）。 */
    fun aggregatePct(): Int {
        val all = active
        if (all.isEmpty() || all.any { it.total <= 0L }) return -1
        val totalBytes = all.sumOf { it.total }
        if (totalBytes <= 0L) return -1
        return ((all.sumOf { it.bytes } * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    fun aggregateBytes(): Long = active.sumOf { it.bytes }
}
