package top.ccbase.campus.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「第一次进入，正在给你生成规划」的状态。
 *
 * 立场：**进度只信服务端**。客户端自己推算百分比看着"更流畅"，但一旦和服务端真实进度
 * 不一致，同学看到的就是"卡在 60% 不动"—— 那比转圈更让人以为坏了。
 *
 * 步骤文案（登录教务系统/读取你的已选课程/…）也全部来自服务端，App 不硬编码：
 * 服务端加一步、改一步，App 不用跟着发版。
 */
@Serializable
data class OnboardStep(
    val k: String = "",
    val label: String = "",
    /** wait / doing / done / fail */
    val state: String = "wait",
    val at: String = "",
)

@Serializable
data class OnboardState(
    /** none / pending / running / done / failed */
    val state: String = "none",
    val step: String = "",
    @SerialName("step_label") val stepLabel: String = "",
    val steps: List<OnboardStep> = emptyList(),
    val error: String = "",
    val percent: Int = 0,
    val tries: Int = 0,
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("ended_at") val endedAt: String = "",
)
