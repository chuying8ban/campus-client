package top.ccbase.campus.data.seed

import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Content
import top.ccbase.campus.data.local.Meta

/**
 * 把 seed.json 灌进本地库。
 *
 * 三条纪律：
 * ① **幂等**：靠 `meta.seed_version` 判断，版本没变就一个字节都不写
 *    （每次启动都重灌会让启动变慢，还会把用户改过的内容盖掉）。
 * ② **绝不碰用户记录**：task_done / checkins / sessions 是用户真实产出，
 *    重灌种子只覆盖"课程/任务/资源"这些**模板性质**的表。用户记录一旦丢，
 *    连续天数一夜归零 —— 这是这个 App 最不可接受的数据事故。
 * ③ **依赖 ID 稳定**：种子里的主键就是网页版的真实 ID，且用 REPLACE 写入，
 *    所以重复导入同一份种子结果一致（不会产生重复行）。
 */
object SeedImporter {

    const val KEY = "seed_version"

    data class Result(val imported: Boolean, val version: Int, val counts: Map<String, Int>)

    suspend fun import(db: CampusDb, seed: Seed, force: Boolean = false): Result {
        val dao = db.dao()
        // 远端计划优先：这位同学的计划是从服务器拉下来的他自己的课表，
        // 内置种子是作者本人的。若种子把它覆盖掉，就是最典型的串数据（而且极难查）。
        if (!force && dao.metaGet("plan_source") == "remote") {
            return Result(false, -1, emptyMap())
        }
        val have = dao.metaGet(KEY)?.toIntOrNull()
        if (!force && have != null && have >= seed.version) {
            return Result(false, seed.version, emptyMap())
        }

        val c = Content.replace(db, seed, extraMeta = listOf(Meta(KEY, seed.version.toString())))

        return Result(
            imported = true,
            version = seed.version,
            counts = c.counts,
        )
    }
}
