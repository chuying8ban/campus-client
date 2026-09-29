package top.ccbase.campus.data.seed

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.ccbase.campus.data.local.ChecklistItem
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Milestone
import top.ccbase.campus.data.local.Resource
import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.local.StudyStep
import top.ccbase.campus.data.local.Task

/**
 * App 内置的种子数据（打进 assets，不联网也能用）。
 *
 * 两个文件分工不同，别混：
 *  - `seed.json`           —— 作者本人的实例数据（课表 11 门 / 自习 / 39 任务 / 68 步骤 / 165 资源）。
 *                            自用版直接用它。由 `~/study-app/seed_export.py` 从 study.db 导出。
 *  - `plan_templates.json` —— 课程模板 + 模块包。同学版登录后按自己的课表匹配它生成计划。
 *                            由 `~/study-app/plan_templates.py` 生成。
 *
 * 字段名与 Room 实体逐字一致（同一个类既是实体又是序列化目标），
 * 避免再抄一份 DTO —— 抄一份就多一处会漂移的地方。
 */
@Serializable
data class Seed(
    val version: Int = 1,
    val exported_from: String? = null,
    val note: String? = null,
    val meta: Map<String, String?> = emptyMap(),
    val courses: List<Course> = emptyList(),
    val slots: List<Slot> = emptyList(),
    val selfstudy: List<SelfStudy> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val study_steps: List<StudyStep> = emptyList(),
    val resources: List<Resource> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
)

object SeedLoader {

    val json: Json = Json {
        ignoreUnknownKeys = true   // 导出加了新字段时旧 App 不会崩
        isLenient = true
        explicitNulls = false
    }

    fun load(ctx: Context, asset: String = "seed.json"): Seed {
        val text = ctx.assets.open(asset).use { it.readBytes().decodeToString() }
        return json.decodeFromString(text)
    }
}
