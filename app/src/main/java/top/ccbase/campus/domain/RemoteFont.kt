package top.ccbase.campus.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一款**从云端下载**的字体。
 *
 * 为什么不像随包字体那样进 `res/font`：用户 2026-10-21 的口径是
 * 「装新版本时不连带着下字体，用户想要使用的时候再下载」——
 * 几十款中文字体塞进 APK 会让安装包从 29MB 涨到上百 MB，而绝大多数人只用其中一款。
 *
 * 三件必须一致的事实（少一件就会出现"下完了用不了"）：文件大小 [bytes]、sha256 [sha256]、
 * 本地文件名 [file]。校验不通过的包绝不允许出现在字体库里。
 */
@Serializable
data class RemoteFont(
    val id: String,
    /** 界面上显示的名字，如「阿里普惠体」 */
    val name: String,
    /** 分组（黑体 / 宋体 / 楷体 / 手写 / 圆体 / 创意），字体库里按这个分节 */
    val group: String,
    /** 云端文件名，如 `alibaba.ttf`（与 [id] 同名，扩展名区分 ttf/otf） */
    val file: String,
    val bytes: Long,
    val sha256: String,
    /** 样张图（云端）文件名；没有样张时卡片退化成名字 + 分组 */
    val preview: String? = null,
    /** 版权/授权来源一句话，字体库里要能看到（这些字体都是可免费商用的开源/免费字体） */
    @SerialName("license") val license: String = "",
) {
    /** 本地落盘用的文件名 —— 固定用 id 命名，升级字体版本时先删旧文件 */
    val localName: String get() = id + file.substringAfterLast('.', "ttf").let { ".$it" }
}

/** 云端字体清单（`https://…/fonts/index.json`）。 */
@Serializable
data class FontIndex(
    val version: Int = 1,
    val fonts: List<RemoteFont> = emptyList(),
)
