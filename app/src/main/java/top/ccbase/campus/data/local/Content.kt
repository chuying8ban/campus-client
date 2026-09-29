package top.ccbase.campus.data.local

import top.ccbase.campus.data.seed.Seed

/**
 * 内容表（课程/时段/任务/步骤/资源/里程碑/清单/自习）的**整体替换**。
 *
 * 内置种子导入和远端计划同步都走这里，只此一份规则：
 *   ① 清掉不在新内容里的行 —— 否则切换账号时两个人的课会混在一张课表上
 *   ② 用户记录（task_done / checkins / sessions）**一个字都不动**
 *
 * 被清掉的行若还挂着用户记录（比如某任务已完成过），那些记录会变成"悬空引用"。
 * 这是**安全**的：读不到对应任务就忽略；而只要该行以同一 id 回来，进度会自动复活。
 * 反过来——为了保住悬空引用而留着旧内容——才是错的：那会让两个人的数据混在一起。
 */
object Content {

    data class Counts(val counts: Map<String, Int>)

    /** 主键为空的哨兵：`NOT IN (-1)` 会删掉全表，正好对应"新内容是空的"这种情况 */
    private fun keep(ids: List<Int>): List<Int> = ids.ifEmpty { listOf(-1) }

    suspend fun replace(db: CampusDb, plan: Seed, extraMeta: List<Meta> = emptyList()): Counts {
        val dao = db.dao()

        // ---- 先按父行过滤子行：没有父行的子行**必须丢掉**，不能插 ----
        //
        // 真事故（2026-09-17，同学点「立即更新课表」闪退）：
        //   SQLiteConstraintException: FOREIGN KEY constraint failed (code 787)
        // 服务端重跑建档时只删了 6 张表，漏了 study_steps / resources，
        // 于是它们的 task_id 指向已被删掉的任务；本地按新任务整表重插 → 外键炸 →
        // 异常一路抛出 → 进程崩。
        //
        // 这里不能假设服务端干净（这次就是），也**不能**改用"忽略外键"：
        // 骨架都断了的数据留着只会更难查。过滤掉、把数量如实报上去，最坏是少几行。
        val courseIds = plan.courses.map { it.id }.toSet()
        val taskIds = plan.tasks.map { it.id }.toSet()
        val slots = plan.slots.filter { it.course_id in courseIds }
        val steps = plan.study_steps.filter { it.task_id in taskIds }
        val resources = plan.resources.filter { it.task_id == null || it.task_id in taskIds }
        val droppedRows = (plan.slots.size - slots.size) + (plan.study_steps.size - steps.size) +
            (plan.resources.size - resources.size)

        dao.putCourses(plan.courses)
        dao.pruneCourses(keep(plan.courses.map { it.id }))

        // 时段没有用户进度，整表重插最干净
        dao.clearSlots()
        dao.putSlots(slots)

        dao.putSelfStudy(plan.selfstudy)
        dao.pruneSelfStudy(keep(plan.selfstudy.map { it.id }))

        dao.putTasks(plan.tasks)
        dao.pruneTasks(keep(plan.tasks.map { it.id }))

        dao.putSteps(steps)
        dao.pruneSteps(keep(steps.map { it.id }))

        dao.putResources(resources)
        dao.pruneResources(keep(resources.map { it.id }))

        dao.putMilestones(plan.milestones)
        dao.pruneMilestones(keep(plan.milestones.map { it.id }))

        dao.putChecklist(plan.checklist)
        dao.pruneChecklist(keep(plan.checklist.map { it.id }))

        val meta = plan.meta.map { (k, v) -> Meta(k, v) } + extraMeta
        if (meta.isNotEmpty()) dao.putMeta(meta)

        if (droppedRows > 0) {
            android.util.Log.w(
                "Content",
                "服务端计划里有 ${droppedRows} 行引用了不存在的父行（课表/学习步骤/资源），已丢弃；" +
                    "这几行插进去会外键失败把 App 搞崩",
            )
        }

        return Counts(
            mapOf(
                "courses" to plan.courses.size,
                "slots" to slots.size,
                "selfstudy" to plan.selfstudy.size,
                "tasks" to plan.tasks.size,
                "steps" to steps.size,
                "resources" to resources.size,
                "checklist" to plan.checklist.size,
                "milestones" to plan.milestones.size,
            )
        )
    }
}
