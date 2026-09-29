package top.ccbase.campus.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「提建议」相关的返回体（服务端 `POST /api/v2/feedback`、`GET /api/v2/feedback/mine`）。
 *
 * 字段全部给了默认值：服务端少给一个字段，旧 App 也不会炸（`ignoreUnknownKeys` 管反方向）。
 */
@Serializable
data class FeedbackAck(
    val ok: Boolean = false,
    /** 服务端回的一句人话（如「收到了，谢谢 —— 我会看」）。 */
    val note: String = "",
)

@Serializable
data class FeedbackItem(
    val id: Int = 0,
    val text: String = "",
    @SerialName("created_at") val createdAt: String = "",
    /** new / read / done */
    val status: String = "",
    /** 已收到 / 已看过 / 已处理 —— 服务端翻好，客户端别自己映射 */
    @SerialName("status_cn") val statusCn: String = "",
    /** 作者的定向回复（2026-09-21 用户：「我也可以定向回复」）。没回复时是空串。 */
    val reply: String = "",
    @SerialName("replied_at") val repliedAt: String = "",
    /** reply 非空 —— 服务端算好，省得各端各写一遍判空 */
    val replied: Boolean = false,
)

@Serializable
data class MyFeedbackRes(
    val items: List<FeedbackItem> = emptyList(),
)
