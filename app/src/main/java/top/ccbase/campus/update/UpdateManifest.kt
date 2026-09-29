package top.ccbase.campus.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务端 `/updates/manifest.json` 的契约。
 *
 * 服务的是一群**旁加载**（应用商店之外）装的 App，所以这里每一个字段都要能自校验：
 * 版本号用来比大小，`sha256` 用来确认下下来的字节没被动过，`url` 允许写成相对路径
 * （跟 manifest 同域，换域名时不用改两个地方）。
 *
 * 全部字段都有默认值 —— 服务端加字段/少字段都不该让 App 崩，这是上次
 * `/grab/status` 契约对不上（布尔 0/1 解析炸整包响应）换来的教训。
 */
@Serializable
data class UpdateManifest(
    /** 必须比 App 自己的 versionCode 大才算有新版；Android 也用它判断能不能覆盖安装 */
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    /** APK 地址：绝对 `https://…` 或相对（相对 manifest 所在域解析） */
    val url: String = "",
    /** 小写十六进制，长度 64；对不上就整包丢弃 */
    val sha256: String = "",
    /** 字节数，可选；给了就一并核对（防截断） */
    val size: Long = 0,
    /** 更新说明，一行一条 */
    val notes: List<String> = emptyList(),
    /** true = 不给「以后再说」按钮 */
    val force: Boolean = false,
    /** 低于这个 versionCode 强制更新（例如老版本接口不兼容了） */
    @SerialName("min_version_code") val minVersionCode: Int = 0,
)
