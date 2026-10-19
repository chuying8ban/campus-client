package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import top.ccbase.campus.R

/**
 * 字体：**App 与小组件分开**（2026-10-19 用户："再新增一个更改字体功能（app和小部件要分开）"）。
 *
 * 两条边界（这是这个功能最容易出错的地方）：
 *  ① 两个 pref 各存各的（`app_font` / `widget_font`），改一个绝不动另一个；
 *  ② 生效路径完全不同 —— App 走 Compose 的 [Typography]，小组件走 **layout XML 的 android:fontFamily**
 *     （RemoteViews 没有 setTypeface 这种远程方法，字体在充气时就得定下来）。
 *
 * 只用系统自带的通用族：不打包第三方字体（授权问题、体积问题），
 * 而且中文字形会跟着系统字体走，不会出现"英文好看中文缺字"。
 */
enum class FontOpt(
    val id: String,
    val label: String,
    val compose: FontFamily,
    /** layout XML 里 `android:fontFamily` 的值 —— 必须是系统通用族名 */
    val system: String,
) {
    SANS("sans", "默认", FontFamily.SansSerif, "sans-serif"),
    MONO("mono", "等宽", FontFamily.Monospace, "monospace"),
    SERIF("serif", "衬线", FontFamily.Serif, "serif"),
    ;

    companion object {
        fun of(id: String?): FontOpt = entries.firstOrNull { it.id == id } ?: SANS
    }
}

private const val K_APP = "app_font"
private const val K_WIDGET = "widget_font"

/** 两套字体存储的共同形状（UI 复用同一个选择器，但**存储分开**）。 */
interface FontStore {
    fun read(ctx: Context): FontOpt
    fun set(ctx: Context, opt: FontOpt)
}

/** App 的字体（只影响 App 内的 Compose 文字）。 */
object AppFont : FontStore {

    val state = mutableStateOf(FontOpt.SANS)
    @Volatile private var bound = false

    fun bind(ctx: Context) {
        if (bound) return
        bound = true
        state.value = FontOpt.of(prefs(ctx).getString(K_APP, null))
    }

    override fun read(ctx: Context): FontOpt = FontOpt.of(prefs(ctx).getString(K_APP, null))

    override fun set(ctx: Context, opt: FontOpt) {
        prefs(ctx).edit().putString(K_APP, opt.id).apply()
        state.value = opt
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("campus_appearance", Context.MODE_PRIVATE)
}

/** 小组件的字体（只影响桌面那张卡）。 */
object WidgetFont : FontStore {

    val state = mutableStateOf(FontOpt.SANS)
    @Volatile private var bound = false

    fun bind(ctx: Context) {
        if (bound) return
        bound = true
        state.value = FontOpt.of(prefs(ctx).getString(K_WIDGET, null))
    }

    override fun read(ctx: Context): FontOpt = FontOpt.of(prefs(ctx).getString(K_WIDGET, null))

    override fun set(ctx: Context, opt: FontOpt) {
        prefs(ctx).edit().putString(K_WIDGET, opt.id).apply()
        state.value = opt
    }

    /**
     * 小组件该用哪份布局。
     *
     * 为什么是"复制三份 XML"而不是运行时改字体：`RemoteViews` 只允许白名单方法，
     * `setTypeface` 不在里面（要传 Typeface 对象，跨进程传不了）。
     * 字体只能在**桌面充气我们的布局**那一刻由 `android:fontFamily` 决定，
     * 所以一份字体 = 一份布局。三份内容的唯一差异就是这一行属性（有测试守着）。
     */
    fun layoutFor(ctx: Context): Int = when (read(ctx)) {
        FontOpt.SANS -> R.layout.widget_today
        FontOpt.MONO -> R.layout.widget_today_mono
        FontOpt.SERIF -> R.layout.widget_today_serif
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("campus_appearance", Context.MODE_PRIVATE)
}

/**
 * 字体选择器（App / 小组件各摆一个，各自写各自的 pref）。
 *
 * 这里刻意**不共用状态**：`widget = true` 只碰 `widget_font`，`false` 只碰 `app_font`。
 * 用户 2026-10-19 的原话："app 和小部件要分开"。
 */
@Composable
fun FontPicker(
    widget: Boolean,
    onPicked: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val store = if (widget) WidgetFont else AppFont
    var sel by remember(widget) { mutableStateOf(store.read(ctx)) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (widget) "小组件字体" else "App 字体",
                fontSize = 11.sp, color = C.txt3,
            )
            Spacer(Modifier.weight(1f))
            Text("只改这一边", fontSize = 9.5.sp, color = C.txt3)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FontOpt.entries.forEach { opt ->
                val on = opt.id == sel.id
                Text(
                    opt.label,
                    fontSize = 11.sp,
                    fontFamily = opt.compose,
                    color = if (on) C.violet else C.txt2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) C.violet.copy(alpha = 0.16f) else C.card)
                        .border(
                            1.dp,
                            if (on) C.violet.copy(alpha = 0.5f) else C.line,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            store.set(ctx, opt)
                            sel = opt
                            onPicked()
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
