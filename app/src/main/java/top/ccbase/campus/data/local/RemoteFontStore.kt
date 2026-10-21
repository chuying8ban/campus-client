package top.ccbase.campus.data.local

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * 下载字体的本地仓库（`filesDir/fonts/`）。
 *
 * ## 为什么放 filesDir 而不是 cacheDir
 * 用户原话：「只要下载一次用户就可以一直直接使用这个字体，哪怕更新新版本已下的字体也不能丢失」。
 * - `cacheDir` 会被系统在低存储时**直接清空**（还没提示），等于用户白下；
 * - APK 覆盖安装（升级）**不动** `filesDir`，所以放这儿就天然满足"跨版本不丢"；
 * - 卸载 App 会一起没 —— 这是合理的：字体是这款 App 的资产，不是公共资源。
 *
 * 判定「已下载」不看数据库、也不看 prefs，**只看文件在不在且大小对**：
 * 用户换机迁移/清数据之后 prefs 可能对不上，以磁盘为准永远不会出现"界面说已下载、其实点不动"。
 */
object RemoteFontStore {

    const val DIR = "fonts"

    /** 已下载的 id 集合（Compose 状态，扫描后写一次） */
    val installed = mutableStateOf<Set<String>>(emptySet())

    /** 从磁盘扫一遍。文件名 = `<id>.<ext>`，所以 id 就是文件名去掉扩展名 */
    fun refresh(ctx: Context) {
        val dir = dir(ctx) ?: return
        installed.value = if (!dir.isDirectory) emptySet() else {
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.length() > 0 && it.name.substringAfterLast('.', "") in SET_OF_EXT }
                .map { it.name.substringBeforeLast('.') }
                .toSet()
        }
    }

    fun isInstalled(ctx: Context, id: String): Boolean = fileOf(ctx, id)?.exists() == true

    /** 某款字体的本地文件；不存在返回 null */
    fun fileOf(ctx: Context, id: String): File? {
        val dir = dir(ctx) ?: return null
        if (!dir.isDirectory) return null
        return dir.listFiles().orEmpty().firstOrNull {
            it.isFile && it.name.substringBeforeLast('.') == id && it.name.substringAfterLast('.', "") in SET_OF_EXT
        }
    }

    /**
     * 载入 Typeface。**失败返回 null 而不是抛**：字体文件可能因存储损坏读不出来，
     * 那时界面要能回落成默认字体并提示，而不是把整个 App 崩掉。
     */
    fun typeface(ctx: Context, id: String): Typeface? = faces[id] ?: runCatching {
        fileOf(ctx, id)?.let { Typeface.createFromFile(it) }
    }.getOrNull()?.also { faces[id] = it }

    /** 删掉某款下载的字体。删之前上层必须把选中项切回系统字体，否则会留下"选中一款已不存在的字体" */
    fun delete(ctx: Context, id: String): Boolean {
        val f = fileOf(ctx, id) ?: return false
        faces.remove(id)
        val ok = f.delete()
        refresh(ctx)
        return ok
    }

    /** 装机后第一次调用前先扫一遍，避免界面把已下载的当成未下载 */
    @Volatile private var scanned = false

    fun ensureScanned(ctx: Context) {
        if (scanned) return
        scanned = true
        refresh(ctx)
    }

    private val faces = mutableMapOf<String, Typeface>()

    private fun dir(ctx: Context): File? = File(ctx.applicationContext.filesDir, DIR)

    /** 给下载器用的落点目录（会创建） */
    fun dirForWrite(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, DIR)
        d.mkdirs()
        return d
    }

    /** 字体族：Compose 只认 FontFamily，文件字体走 `FontFamily(Typeface)` */
    fun family(ctx: Context, id: String): FontFamily? =
        typeface(ctx, id)?.let { FontFamily(it) }

    private val SET_OF_EXT = setOf("ttf", "otf")

    /** 测试钩子：换机/清数据之后重新扫 */
    fun resetForTest() {
        faces.clear()
        installed.value = emptySet()
        scanned = false
    }

    /** 供清单变化时清掉"文件在但清单里没有"的孤儿（用户手工塞文件/云端下架） */
    fun orphans(ctx: Context, known: Set<String>): List<String> =
        installed.value.filter { it !in known }

}
