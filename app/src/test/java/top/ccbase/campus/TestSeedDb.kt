package top.ccbase.campus

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader

/**
 * 用例要「库里有任务」时用它，**别用 `app().db`**。
 *
 * 为什么：App 启动时只灌学校通用数据（节次 / 自习时段），个人任务一律等**用户自己的**计划
 * 同步下来 —— 所以 `app().db` 里没有任务是**刻意的**（不拿作者本人的数据冒充别人的计划，
 * 用户原话：宁可没数据也不要造假数据）。
 *
 * 随包的 `seed.json` 里那份完整数据还在，只是不再被 App 自动导入；用例要验界面就自己起个
 * 内存库、把它灌进去。**用完不要 close**：界面上的 LaunchedEffect 还在收 Flow，
 * 关库会抛 `connection pool has been closed` 并漏到后面跑的类里。进程结束自然会回收。
 */
object TestSeedDb {

    fun seeded(ctx: Context): CampusDb {
        val db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { SeedImporter.import(db, SeedLoader.load(ctx)) }
        // 标成"服务器按你课表发下来的计划" —— 界面只认这种来路（见 rememberTasksSections 的说明）
        runBlocking {
            db.dao().putMeta(listOf(Meta(PlanApplier.K_SOURCE, PlanApplier.REMOTE)))
        }
        return db
    }
}
