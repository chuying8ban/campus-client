package top.ccbase.campus.school

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import top.ccbase.campus.net.EamsClient

/** Only stores consent. School cookies remain in Android WebView's private store. */
object SchoolSession {
 private fun prefs(ctx: Context) = ctx.getSharedPreferences("school_session_consent", Context.MODE_PRIVATE)
 fun remembered(ctx: Context, profile: SchoolProfile = SchoolRegistry.cupk): Boolean = prefs(ctx).getBoolean("remember_${profile.id}", if(profile.id==SchoolRegistry.cupk.id) prefs(ctx).getBoolean("remember",false) else false)
 fun setRemembered(ctx: Context, enabled: Boolean, profile: SchoolProfile = SchoolRegistry.cupk) {
  prefs(ctx).edit().putBoolean("remember_${profile.id}", enabled).commit()
  if (!enabled) forget(ctx,profile)
 }
 fun forget(ctx: Context, profile: SchoolProfile = SchoolRegistry.cupk) {
  val edit=prefs(ctx).edit().putBoolean("remember_${profile.id}", false)
  if(profile.id==SchoolRegistry.cupk.id) edit.remove("remember")
  edit.commit()
  SchoolCookie.clear(profile.host)
  WebStorage.getInstance().deleteOrigin(profile.origin)
 }
 fun flush() { CookieManager.getInstance().flush() }
}
