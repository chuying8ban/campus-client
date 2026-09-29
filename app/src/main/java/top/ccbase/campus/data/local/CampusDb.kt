package top.ccbase.campus.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * 手机本地库。
 *
 * ✅ **已处理（2026-09-17）**：破坏性重建现在**只在还没有任何迁移时**生效（也就是 v1 期间，
 * 本地数据全是 assets 种子，重建无损失）。往 `MIGRATIONS` 里补上第一条迁移后，这句自动失效，
 * 不需要谁记得来"摘"它。另外 `MigrationGuardTest` 守两条线：
 * ① 表结构 `version` 涨了，`MIGRATIONS` 里必须有到该版本的迁移，否则**测试直接红**；
 * ② 真的用生产 builder 建库并读写一次（不能只有内存库被测过）。
 */
@Database(
    entities = [
        Course::class,
        Slot::class,
        SelfStudy::class,
        Task::class,
        StudyStep::class,
        Resource::class,
        ChecklistItem::class,
        Milestone::class,
        TaskDone::class,
        Session::class,
        Checkin::class,
        Meta::class,
        DeletedTask::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class CampusDb : RoomDatabase() {

    abstract fun dao(): CampusDao

    companion object {
        private const val NAME = "campus.db"

        /**
         * 正式迁移，按版本顺序放在这里。
         *
         * **规矩：`@Database(version = …)` 一涨，这里就必须补一条到新版本的迁移。**
         * 走破坏性重建的代价是把用户手机里自己攒的东西（连续打卡、专注记录、学习进度）
         * 无声清空 —— 所以下限逻辑改成："有迁移就不许破坏性重建"，并由
         * `MigrationGuardTest` 把版本号与迁移列表绑死（漏写迁移 → 测试红，发不出去）。
         */
        /**
         * v1 → v2：课表时段加「精确周次」列。
         *
         * 只能 ADD COLUMN，老行该列是 NULL —— 读的地方一律"空就退回 week_from~week_to"，
         * 所以不需要回填，用户本地的打卡/专注记录一个字都不动。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slots ADD COLUMN weeks TEXT")
            }
        }

        /**
         * v2 → v3：加「从我的清单里删掉」的墓碑表。
         *
         * 只新增一张表，老数据（打卡、专注记录、任务进度）一行都不碰 —— 这是风险最低的一类迁移：
         * 没有回填、没有改名、没有默认值，读不到墓碑就等于没删过任何东西。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deleted_tasks " +
                        "(task_id INTEGER NOT NULL PRIMARY KEY, at TEXT NOT NULL)",
                )
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile
        private var inst: CampusDb? = null

        /**
         * **仅测试用**：把进程级缓存关掉并清空。
         *
         * 单例缓存是绑在**第一次那个 Context** 上的。Robolectric 每个用例给的是新
         * Context/新数据目录，所以测试里碰过 `get()` 之后必须还原，否则后面同沙箱的
         * 用例会拿到"绑在旧目录上的实例"，表现成数据莫名读不到。
         */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() {
            runCatching { inst?.close() }
            inst = null
        }

        fun get(ctx: Context): CampusDb =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(ctx.applicationContext, CampusDb::class.java, NAME)
                    .addMigrations(*MIGRATIONS)
                    .apply {
                        // 只在"一条迁移都还没有"时允许破坏性重建（v1 阶段：本地数据都是种子，重建无损失）。
                        // 补上第一条迁移后这句自动不再生效，不依赖任何人记得回来摘它。
                        if (MIGRATIONS.isEmpty()) fallbackToDestructiveMigration()
                    }
                    .build()
                    .also { inst = it }
            }
    }
}
