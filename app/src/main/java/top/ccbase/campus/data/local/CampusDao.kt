package top.ccbase.campus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * P0.4 的 DAO —— 只放 P1 首页/课表/任务确实要用的查询。
 *
 * 刻意**不**把网页版所有查询一次性搬过来：DAO 越早写满，越容易写成一份
 * "猜出来的接口"，等真接页面时发现参数不对又得改。P1 做到哪页、加哪个查询。
 */
@Dao
interface CampusDao {

    // ------------------------------------------------------------ 课表

    @Query("SELECT * FROM courses ORDER BY sort, id")
    fun courses(): Flow<List<Course>>

    /** 一次性快照（导入时用来复用同名课程的既有 id，避免生成新 id 丢用户进度）。 */
    @Query("SELECT * FROM courses")
    suspend fun allCoursesOnce(): List<Course>

    /** 课程详情要用：这门课的全部时间段 */
    @Query("SELECT * FROM slots WHERE course_id = :courseId ORDER BY weekday, p_start, sort, id")
    fun slotsOfCourse(courseId: Int): Flow<List<Slot>>

    /** 课程详情要用：和这门课绑定的学习任务 */
    @Query("SELECT * FROM tasks WHERE course_id = :courseId ORDER BY phase_order, sort, id")
    fun tasksOfCourse(courseId: Int): Flow<List<Task>>

    @Query("SELECT * FROM slots WHERE weekday = :weekday ORDER BY p_start, sort, id")
    fun slotsOfDay(weekday: Int): Flow<List<Slot>>

    @Query("SELECT * FROM slots ORDER BY weekday, p_start, sort, id")
    fun slots(): Flow<List<Slot>>

    @Query("SELECT * FROM selfstudy WHERE weekday = :weekday ORDER BY start")
    fun selfStudyOfDay(weekday: Int): Flow<List<SelfStudy>>

    @Query("SELECT * FROM selfstudy ORDER BY weekday, start")
    fun selfStudy(): Flow<List<SelfStudy>>

    // ------------------------------------------------------------ 任务与学习内容

    @Query("SELECT * FROM tasks ORDER BY phase_order, sort, id")
    fun tasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE active = 1 ORDER BY phase_order, sort, id")
    fun activeTasks(): Flow<List<Task>>

    // ------------------------------------------------ 从我的清单里删除（墓碑，不真删行）
    //
    // 过滤写在 SQL 里而不是 Kotlin 里，为的是让**所有**读取处口径一致：任务页、今日页、
    // 看板都读 visibleTasks()，漏掉一处就会"删了但今日页还在催我"。detail 页例外
    // （它要先能按 id 找到任务才好渲染），所以那边仍用 tasks()。

    /** 清单：已打墓碑的不出现 */
    @Query("SELECT * FROM tasks WHERE id NOT IN (SELECT task_id FROM deleted_tasks) ORDER BY phase_order, sort, id")
    fun visibleTasks(): Flow<List<Task>>

    /** 已删掉的那些任务本体 —— 恢复列表要显示标题，光有 id 没法给用户看 */
    @Query("SELECT * FROM tasks WHERE id IN (SELECT task_id FROM deleted_tasks) ORDER BY id")
    fun deletedTasks(): Flow<List<Task>>

    @Query("SELECT task_id FROM deleted_tasks ORDER BY at DESC")
    fun deletedTaskIds(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markTaskDeleted(row: DeletedTask)

    /** 恢复一条（只删墓碑，任务行本来就没动过） */
    @Query("DELETE FROM deleted_tasks WHERE task_id = :taskId")
    suspend fun undeleteTask(taskId: Int)

    /**
     * 批量撤墓碑。
     *
     * 用途只有一个：用户**重新把同一门课的资料加回学习库**（[top.ccbase.campus.data.library.Library.add]）
     * —— 那是他明确要这条任务回来，可任务 id 是"段首 + 课程 id"固定的，不清墓碑就永远看不见。
     */
    @Query("DELETE FROM deleted_tasks WHERE task_id IN (:taskIds)")
    suspend fun undeleteTasks(taskIds: List<Int>)

    @Query("DELETE FROM deleted_tasks")
    suspend fun undeleteAllTasks()

    @Query("SELECT * FROM study_steps WHERE task_id = :taskId ORDER BY seq")
    fun stepsOfTask(taskId: Int): Flow<List<StudyStep>>

    /** 全部步骤：任务页要一次性算每个任务的"3/8 步"进度 */
    @Query("SELECT * FROM study_steps ORDER BY task_id, seq")
    fun allSteps(): Flow<List<StudyStep>>

    /** 步骤可以单独打勾（done_day 存那一天）—— 与网页版同一个字段 */
    @Query("UPDATE study_steps SET done_day = :day WHERE id = :stepId")
    suspend fun markStep(stepId: Int, day: String)

    @Query("UPDATE study_steps SET done_day = NULL WHERE id = :stepId")
    suspend fun unmarkStep(stepId: Int)

    @Query("SELECT * FROM resources WHERE task_id = :taskId ORDER BY sort, id")
    fun resourcesOfTask(taskId: Int): Flow<List<Resource>>

    @Query("SELECT * FROM resources ORDER BY task_id, sort, id")
    fun resources(): Flow<List<Resource>>

    /**
     * 只属于"还活着的任务"的资料。
     *
     * 为什么必须有这个：删任务只打墓碑（服务端同步会把真删的行写回来），
     * 而「学习」那半、今日、看板都直接查 `resources()` —— 结果任务清空了，
     * 它的资料还在页面上挂着（用户 2026-09-19 截图指出：「已删除 65 项」+ 学习里 443 条还在）。
     * 规矩：凡是按任务展示的东西，都要跟着"可见任务"过滤。
     */
    @Query(
        "SELECT * FROM resources WHERE task_id NOT IN (SELECT task_id FROM deleted_tasks) " +
            "ORDER BY task_id, sort, id"
    )
    fun visibleResources(): Flow<List<Resource>>

    @Query("SELECT * FROM milestones ORDER BY sort, id")
    fun milestones(): Flow<List<Milestone>>

    @Query("SELECT * FROM checklist ORDER BY sort, id")
    fun checklist(): Flow<List<ChecklistItem>>

    // ------------------------------------------------------------ 记录

    @Query("SELECT * FROM task_done WHERE day = :day ORDER BY at")
    fun doneOn(day: String): Flow<List<TaskDone>>

    /** 一次取回近段完成记录 —— 「今日」逻辑要用它算"今天做过/本周做过"（Flow 不适合传入纯函数） */
    @Query("SELECT * FROM task_done WHERE day >= :fromDay ORDER BY day DESC, at DESC")
    suspend fun doneSince(fromDay: String): List<TaskDone>

    @Query("SELECT COUNT(*) FROM task_done WHERE task_id = :taskId AND day = :day")
    suspend fun isDone(taskId: Int, day: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markDone(row: TaskDone)

    /**
     * 「重置学习任务」用：所有打勾一次性归零。
     *
     * 为什么敢整表清：重置在写之前会把现状整份存成备份（`ResetPlan.Snapshot`），
     * 界面写着"恢复上次重置"，误点能回来。
     */
    @Query("DELETE FROM task_done")
    suspend fun clearDone()

    @Query("DELETE FROM task_done WHERE task_id = :taskId AND day = :day")
    suspend fun unmarkDone(taskId: Int, day: String)

    @Query("SELECT * FROM checkins WHERE day = :day")
    suspend fun checkin(day: String): Checkin?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckin(row: Checkin)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(row: Session)

    /**
     * 连续天数的"活跃天"定义 —— **必须与网页版 db.streak() 逐字一致**：
     *   有专注记录（sessions.minutes > 0）  或  有任务完成（task_done）
     *
     * ⚠️ 这里**不含 checkins**。网页版的 streak() 从来没读过 checkins，
     * 早期版本的注释写成"同口径"是错的 —— 错误的注释比没注释更糟，
     * 它会让人相信一个不成立的等价关系。
     * （网页版的"专注"功能后来删了，所以 sessions 对新用户恒为空，
     *   实际效果是"只有 task_done 算活跃"；但口径仍按原样保留，
     *   否则以后一旦有人重新启用专注，两边又会对不上。）
     */
    @Query(
        """
        SELECT day FROM (
            SELECT day FROM sessions WHERE minutes > 0
            UNION
            SELECT day FROM task_done
        ) ORDER BY day DESC LIMIT :limit
        """
    )
    suspend fun recentActiveDays(limit: Int): List<String>

    // ------------------------------------------------------------ meta

    @Query("SELECT v FROM meta WHERE k = :key")
    suspend fun metaGet(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun metaPut(row: Meta)

    // ------------------------------------------------------------ 种子导入用

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCourses(rows: List<Course>)

    // ------- 撤掉某个模块包时按 id 段批量删除（id 段由 Modules.baseFor 稳定派生）
    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteTasks(ids: List<Int>)

    @Query("DELETE FROM study_steps WHERE id IN (:ids)")
    suspend fun deleteSteps(ids: List<Int>)

    @Query("DELETE FROM resources WHERE id IN (:ids)")
    suspend fun deleteResources(ids: List<Int>)

    // ---------------------------------------------- 内容整体替换时清理多余行
    // 规则：谁写内容（内置种子 / 远端计划）都整体替换 → 多余的清掉。
    // 注意这些行被删后，用户记录（task_done 等）会变成"悬空引用"——
    // 这是**安全**的：读不到就忽略，任务若以同一 id 回来，进度还会复活。
    // 反过来（为了保留悬空引用而留着旧课程）才是错的：切换账号时两个人的课会混在一起。
    @Query("DELETE FROM courses WHERE id NOT IN (:keep)")
    suspend fun pruneCourses(keep: List<Int>)

    @Query("DELETE FROM tasks WHERE id NOT IN (:keep)")
    suspend fun pruneTasks(keep: List<Int>)

    @Query("DELETE FROM study_steps WHERE id NOT IN (:keep)")
    suspend fun pruneSteps(keep: List<Int>)

    @Query("DELETE FROM resources WHERE id NOT IN (:keep)")
    suspend fun pruneResources(keep: List<Int>)

    @Query("DELETE FROM milestones WHERE id NOT IN (:keep)")
    suspend fun pruneMilestones(keep: List<Int>)

    @Query("DELETE FROM checklist WHERE id NOT IN (:keep)")
    suspend fun pruneChecklist(keep: List<Int>)

    @Query("DELETE FROM selfstudy WHERE id NOT IN (:keep)")
    suspend fun pruneSelfStudy(keep: List<Int>)

    /** 时段整表清空（同步远端计划时用）。slots 没有用户进度，删了重插最干净。 */
    @Query("DELETE FROM slots")
    suspend fun clearSlots()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSlots(rows: List<Slot>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSelfStudy(rows: List<SelfStudy>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTasks(rows: List<Task>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSteps(rows: List<StudyStep>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putResources(rows: List<Resource>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putChecklist(rows: List<ChecklistItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMilestones(rows: List<Milestone>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(rows: List<Meta>)

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun courseCount(): Int
}
