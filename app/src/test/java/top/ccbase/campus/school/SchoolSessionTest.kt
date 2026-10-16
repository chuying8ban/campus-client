package top.ccbase.campus.school
import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SchoolSessionTest {
 private val ctx:Context=ApplicationProvider.getApplicationContext()
 @Before fun reset(){SchoolSession.forget(ctx)}
 @After fun clean(){SchoolSession.forget(ctx)}
 @Test fun `不同学校记住开关隔离且兼容旧键`() {
  ctx.getSharedPreferences("school_session_consent",Context.MODE_PRIVATE).edit().clear().putBoolean("remember",true).commit()
  val other=SchoolRegistry.cupk.copy(id="synthetic",origin="https://synthetic.example.org")
  Assert.assertTrue(SchoolSession.remembered(ctx))
  Assert.assertFalse(SchoolSession.remembered(ctx,other))
  SchoolSession.setRemembered(ctx,true,other)
  SchoolSession.forget(ctx,other)
  Assert.assertTrue(SchoolSession.remembered(ctx))
  Assert.assertFalse(SchoolSession.remembered(ctx,other))
  SchoolSession.forget(ctx)
  Assert.assertFalse(SchoolSession.remembered(ctx))
 }
 @Test fun `默认不记住且开启不删除学校会话`() {
  Assert.assertFalse(SchoolSession.remembered(ctx))
  CookieManager.getInstance().setCookie("https://eams.cupk.edu.cn/student/","SESSION=dummy; Path=/student/")
  SchoolSession.setRemembered(ctx,true)
  Assert.assertTrue(SchoolSession.remembered(ctx))
  SchoolSession.flush()
  Assert.assertTrue(CookieManager.getInstance().getCookie("https://eams.cupk.edu.cn/student/").contains("SESSION=dummy"))
 }
 @Test fun `忘记登录清学校路径但不影响其他网站`() {
  val cm=CookieManager.getInstance()
  cm.setCookie("https://eams.cupk.edu.cn/student/","SESSION=dummy; Path=/student/")
  cm.setCookie("https://example.org/","test=kept; Path=/")
  SchoolSession.setRemembered(ctx,true)
  SchoolSession.forget(ctx)
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
  Assert.assertFalse(SchoolSession.remembered(ctx))
  // RoboCookieManager discards expired Set-Cookie before removing existing cookies.
  // Verify the real scoped expiration payload instead of relying on that broken shadow.
  val removals=mutableListOf<Pair<String,String>>()
  SchoolCookie.clear(expire={u,v->removals.add(u to v)})
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
  Assert.assertTrue(removals.any{it.first.endsWith("/student/") && it.second.startsWith("SESSION=;") && it.second.contains("Max-Age=0")})
  Assert.assertTrue(removals.all{it.first.startsWith("https://eams.cupk.edu.cn")})
  Assert.assertTrue(cm.getCookie("https://example.org/").contains("test=kept"))
 }
}
