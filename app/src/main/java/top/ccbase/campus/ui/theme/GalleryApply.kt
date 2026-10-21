package top.ccbase.campus.ui.theme

import android.content.Context
import android.util.Log
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetThemes

/**
 * 在线图库里"点一下就换主题"的这一条路。
 *
 * ## 用户口径（2026-10-21）
 *
 * 「我希望用户下载使用的主题都能够自动下载到本地主题库，而不是需要用户手动点击下载到主题库。」
 *
 * 所以这里把原来的两条路合一：
 *  旧：装一个「存到本地素材库」开关 —— 开着只入库、关着只套用（不留在库里）。
 *  新：**套用即入库**。选定哪张，那张就已经在本地素材库里、并标成"正在用"，
 *      下次在没网的地方、或者想换回这张，直接在素材库里点一下就行，不用再下一次。
 *
 * ## 为什么入库之后统一用 `mat:` spec
 *
 * 素材库里的东西有自己的落点和清单（[MaterialLibrary]），`mat:<slot>:<id>` 就是它的地址：
 *  · App 背景和桌面小组件都能解析（Theme.kt / WidgetFrames.kt 都认）；
 *  · 「正在用」这件事只有一条记录（`MaterialLibrary.selected`），不会出现
 *    "背景是这张、素材库却说没在用"的错位；
 *  · 不再像以前那样同时往 `UserMedia` 的单槽里塞一份 —— 那等于同一个视频存两份（动图 10MB）。
 */
object GalleryApply {

    data class Result(
        val ok: Boolean,
        /** 入库后的那一件（失败时为 null） */
        val item: MaterialLibrary.Item?,
        /** 失败原因，界面直接显示这句话 */
        val error: String? = null,
    )

    /**
     * 把在线图库里的一件套上去。
     *
     * @param target "background" = App 页面背景；其他值一律当桌面小组件
     * @param bytes  已经下载到内存的完整字节（图片是 JPEG 编码后的、动图是原始 mp4/gif）
     * @param mime   用来判类型/扩展名
     */
    fun apply(
        ctx: Context,
        target: String,
        name: String,
        bytes: ByteArray,
        mime: String,
    ): Result {
        if (bytes.isEmpty()) return Result(false, null, "一个字节都没下下来，换一张试试")
        val widget = target != "background"
        val slot = if (widget) MaterialLibrary.Slot.WIDGET else MaterialLibrary.Slot.APP

        // ① 先入库：这一步失败就没必要往下走（套用了却不留在库里＝又回到用户抱怨的那个状态）
        val item = MaterialLibrary.saveBytes(ctx, slot, bytes, name, mime)
            ?: return Result(false, null, "存到本地素材库失败（可能空间不足，或这段视频不完整）")

        // ② 标成"正在用"：素材库列表、外观页都靠这一个标记
        MaterialLibrary.select(ctx, slot, item.id)
        val spec = MaterialLibrary.spec(slot, item.id)

        // ③ 真正套上去
        if (!widget) {
            AppearanceStore.setBackground(ctx, spec)
            return Result(true, item)
        }
        val prev = WidgetThemes.spec(ctx)
        WidgetThemes.applyNow(ctx, spec)
        // 桌面是"先抽几帧再轮播"的：抽不出帧就等于设了也不显示，必须回滚并说清原因
        val frames = runCatching { top.ccbase.campus.widget.WidgetFrames.forSpec(ctx, spec, 900, 400) }
            .getOrNull()
        if (frames == null) {
            WidgetThemes.set(ctx, prev)
            runCatching { TodayWidgetProvider.refresh(ctx) }
            Log.w("GalleryApply", "抽不出帧，已回滚主题：$spec")
            // 素材本身是好的（能进库），只是桌面用不了 —— 别让用户以为白下了
            return Result(false, item, "这张桌面渲染不出来（可能是不支持的动图格式），已经留在素材库里")
        }
        runCatching { TodayWidgetProvider.refresh(ctx) }
        return Result(true, item)
    }
}
