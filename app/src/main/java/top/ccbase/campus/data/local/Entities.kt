package top.ccbase.campus.data.local

import androidx.room.Entity
import kotlinx.serialization.Serializable
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地库实体 —— **字段名与网页版 SQLite 的列名逐字一致**，故意不用 camelCase。
 *
 * 为什么：这张库的数据是从服务器/网页版那份 `study.db` 导过来的。列名一一对应，
 * 导入、导出、两边对账都不用做名称映射，直接少掉一整类"某个字段悄悄没对上"的错。
 * 代价只是 Kotlin 命名风格不地道 —— 这个代价我认为值。
 *
 * 表清单：现有 study.db 有 13 张表，这里建 12 张。
 * **`_hb` 不建** —— 那是服务器用的心跳/健康检查表（0 行），跟手机端无关。
 */

// ---------------------------------------------------------------- 课表

@Entity(tableName = "courses")
@Serializable
data class Course(
    @PrimaryKey val id: Int,
    val name: String,
    val short: String? = null,
    val credits: Double = 0.0,
    val teacher: String? = null,
    val category: String? = null,
    val week_from: Int? = null,
    val week_to: Int? = null,
    /** 1 = 重点盯防（决定 GPA 的课） */
    val is_focus: Int = 0,
    /** 目标分（用宽容解析：服务端写成 "" 也不能让整份计划解析失败，见 LenientInt） */
    @Serializable(with = LenientInt::class)
    val target: Int? = null,
    val note: String? = null,
    val sort: Int = 0,
)

@Entity(
    tableName = "slots",
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
@Serializable
data class Slot(
    @PrimaryKey val id: Int,
    /** 1 = 周一 … 7 = 周日（跟 Python isoweekday 对齐） */
    val weekday: Int,
    val course_id: Int? = null,
    val p_start: Int? = null,
    val p_end: Int? = null,
    val time_text: String? = null,
    val room: String? = null,
    val week_from: Int? = null,
    val week_to: Int? = null,
    /**
     * 精确周次（逗号分隔的周号，如 `"6,7,9,12,13,14,15,16"`）。
     *
     * 为什么区间不够：教务的周次有单双周和散周 —— 同一门 程序设计基础（B） 的周五 1-2 节，
     * 一段是「3,4,5,8,10,11 周在 I区212」，另一段是「6,7,9,12~16 周在 II区308机房」。
     * 只比区间会告诉同学"第 6 周在 I区212" —— 他走过去才发现当天在机房。
     * 有值就只认它；为空（老数据/内置种子）退回 week_from~week_to 区间。
     */
    val weeks: String? = null,
    val sort: Int = 0,
)

/** 早晚自习（周日至周四晚 20:30–22:05 / 周一至周五早 8:45–9:15） */
@Entity(tableName = "selfstudy", indices = [Index(value = ["weekday", "kind"], unique = true)])
@Serializable
data class SelfStudy(
    @PrimaryKey val id: Int,
    val weekday: Int,
    /** 早自习 / 晚自习 */
    val kind: String,
    val start: String,
    val end: String,
    val place: String? = null,
)

// ---------------------------------------------------------------- 任务与学习内容

@Entity(tableName = "tasks")
@Serializable
data class Task(
    @PrimaryKey val id: Int,
    val phase: String,
    val phase_order: Int = 0,
    val title: String,
    val detail: String? = null,
    val course_id: Int? = null,
    /** 正课 / 自学 / 课外 / 手续 */
    val track: String? = null,
    /** 1 高 2 中 3 低 */
    val priority: Int = 2,
    /** daily/weekly/per-class/once */
    val cadence: String? = null,
    /** 建议执行日 1~7 */
    val weekday: Int? = null,
    /** 验收物（用户原话：没有产出物的学习等于没学） */
    val deliverable: String? = null,
    /** 1 = 当前阶段激活 */
    val active: Int = 0,
    val sort: Int = 0,
)

@Entity(
    tableName = "study_steps",
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["task_id"])],
)
@Serializable
data class StudyStep(
    @PrimaryKey val id: Int,
    val task_id: Int,
    val seq: Int,
    val text: String,
    val minutes: Int = 0,
    /** watch/read/practice/produce */
    val kind: String? = null,
    /** NULL = 还没做 */
    val done_day: String? = null,
)

@Entity(
    tableName = "resources",
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["task_id"])],
)
@Serializable
data class Resource(
    @PrimaryKey val id: Int,
    val task_id: Int,
    /** video / doc / course / practice */
    val kind: String? = null,
    val title: String,
    val url: String,
    val source: String? = null,
    /** 为什么推荐、先看哪部分 */
    val why: String? = null,
    /** 入库时实测的 HTTP 状态码 */
    val http: Int? = null,
    /** 1 = 可内嵌播放/阅读（原生版一律走外链跳转，这个字段留作参考） */
    val embed: Int = 0,
    val sort: Int = 0,
)

@Entity(tableName = "checklist")
@Serializable
data class ChecklistItem(
    @PrimaryKey val id: Int,
    val item: String,
    val sort: Int = 0,
)

@Entity(tableName = "milestones")
@Serializable
data class Milestone(
    @PrimaryKey val id: Int,
    val when_text: String? = null,
    val term: String? = null,
    val academic: String? = null,
    val extra: String? = null,
    val checkpoint: String? = null,
    val sort: Int = 0,
)

// ---------------------------------------------------------------- 记录（用户产生的数据）

@Entity(tableName = "task_done", indices = [Index(value = ["day"]), Index(value = ["task_id", "day"])])
@Serializable
data class TaskDone(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val task_id: Int? = null,
    /** YYYY-MM-DD */
    val day: String,
    val at: String,
    val note: String? = null,
)

@Entity(tableName = "sessions", indices = [Index(value = ["day"])])
@Serializable
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val task_id: Int? = null,
    val course_id: Int? = null,
    val started_at: String,
    val ended_at: String? = null,
    val minutes: Int = 0,
    val day: String,
    val note: String? = null,
)

@Entity(tableName = "checkins")
@Serializable
data class Checkin(
    @PrimaryKey val day: String,
    val note: String? = null,
    val updated_at: String? = null,
)

/** 键值表：学期起始日 `semester_start` 等（"当前第几周"就靠它算） */
@Entity(tableName = "meta")
@Serializable
data class Meta(
    @PrimaryKey val k: String,
    val v: String? = null,
)

/**
 * 「从我的清单里删掉」的任务 —— 只记 id，**不删 `tasks` 里的那一行**。
 *
 * 为什么不真删：任务是从服务端同步下来的（`Content.kt` 里 `putTasks()` upsert +
 * `pruneTasks(keep = 服务端 id)`）。真删了行，下一次同步就把它写回来 —— 用户看到的是
 * 「我明明删了它又回来了」，而且**只在联网之后**出现，本地怎么点都试不出来。
 * 所以删除 = 打墓碑 + 读的时候过滤（`CampusDao.visibleTasks()`），同步那套逻辑一个字不改。
 *
 * 墓碑只在本机：服务端不知道你删了，别的设备照旧看得到它 —— 这是对的，
 * 「删除」的语义是"我不想在我这儿看到它"，不是"把这条学习任务毁掉"。
 * 卸载重装（本地库重建）即等于全部恢复，所以它是可逆的、代价最小的那一种删除。
 */
@Entity(tableName = "deleted_tasks")
@Serializable
data class DeletedTask(
    @PrimaryKey val task_id: Int,
    /** 删除时间（本地时间串）。留着是为了"最近删的排前面"和排查，UI 现在只按 id 排 */
    val at: String,
)
