package top.ccbase.campus.data.plan

import android.content.Context
import java.io.File

/**
 * 重置备份落在哪：`filesDir/reset-backup/last.json`。
 *
 * 为什么用 filesDir 而不是 cacheDir：cacheDir 会在"空间紧张"时被系统清掉 ——
 * 被清掉的那天很可能正好是用户想恢复的那天。filesDir 只有卸载才会没。
 *
 * 为什么只留最新一份：留多份的话，用户面对"恢复哪一个"根本无从选。
 * 恢复一次就删掉它（恢复完再"恢复"就是把同一份又盖一遍，只会更乱）。
 */
object ResetStore {
    private const val DIR = "reset-backup"
    const val FILE = "last.json"

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), FILE)

    /** 先写 `.tmp` 再改名：写到一半被杀（进程被杀/存储满）不会留下半份坏 JSON。 */
    fun save(ctx: Context, snap: ResetPlan.Snapshot) {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(ResetPlan.encode(snap))
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    fun load(ctx: Context): ResetPlan.Snapshot? = runCatching {
        val f = file(ctx)
        if (f.exists()) ResetPlan.decode(f.readText()) else null
    }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
