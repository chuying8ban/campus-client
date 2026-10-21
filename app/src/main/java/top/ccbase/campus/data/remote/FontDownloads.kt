package top.ccbase.campus.data.remote

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.ccbase.campus.domain.RemoteFont

/**
 * 字体下载的**进程级**任务表。
 *
 * 为什么不用 `rememberCoroutineScope()`：那个 scope 跟着页面走。用户点了下载、看一眼进度、
 * 觉得慢就退回上一页 —— 协程当场被取消，半包留在磁盘上，界面回到「未下载」，
 * 他会以为"点了没反应"然后反复点。这里挂在进程作用域上：**退出页面下载照跑**，
 * 回来时进度还在（状态就是这张表）。
 */
object FontDownloads {

    /** id → 0..1 */
    val progress = mutableStateMapOf<String, Float>()

    /** id → 失败原因（成功后清空） */
    val error = mutableStateMapOf<String, String>()

    /** 正在下的 id（同一款不重复下） */
    private val running = mutableSetOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isDownloading(id: String): Boolean = progress.containsKey(id)

    fun start(ctx: Context, rf: RemoteFont, onDone: () -> Unit = {}) {
        val app = ctx.applicationContext
        synchronized(running) {
            if (!running.add(rf.id)) return
        }
        error.remove(rf.id)
        progress[rf.id] = 0f
        scope.launch {
            val r = FontCatalog.install(app, rf) { p ->
                val total = if (p.total > 0) p.total.toFloat() else rf.bytes.toFloat().coerceAtLeast(1f)
                progress[rf.id] = (p.received / total).coerceIn(0f, 0.999f)
            }
            synchronized(running) { running.remove(rf.id) }
            progress.remove(rf.id)
            if (r.isSuccess) {
                error.remove(rf.id)
                top.ccbase.campus.data.local.RemoteFontStore.refresh(app)
            } else {
                error[rf.id] = r.exceptionOrNull()?.message ?: "下载失败，稍后再试"
            }
            onDone()
        }
    }
}
