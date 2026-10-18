package top.ccbase.campus.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.R

/**
 * 小组件布局真充一次。
 *
 * 为什么值得：RemoteViews 的布局如果有属性不合法（拼错的 attr、不支持的 View），
 * 在打包阶段不会报错，只会在**用户桌面上变成一块白板**——那种失败没人看得见。
 * 这里把它真 inflate 一遍，把"四个 id 都在、都是 TextView"钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetLayoutTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `布局能充气且三个行位都存在`() {
        val view = LayoutInflater.from(ctx).inflate(R.layout.widget_today, null)
        assertNotNull("根容器缺 id 就点不开 App", view.findViewById<View>(R.id.widget_root))
        listOf(R.id.widget_date, R.id.widget_head, R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_foot)
            .forEach { id ->
                val v = view.findViewById<View>(id)
                assertNotNull("布局里少了 id=$id", v)
                assertTrue("id=$id 应该是 Text 控件", v is TextView)
            }
    }
}
