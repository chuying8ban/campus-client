package top.ccbase.campus.school

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.JsonNull

class LoginDiagnosticDefaultTest {
 @Test fun actualBuildDefaultMatchesPackage() {
  assertEquals(top.ccbase.campus.BuildConfig.APPLICATION_ID.endsWith(".privacytest"),top.ccbase.campus.BuildConfig.SCHOOL_TEST)
  assertEquals(top.ccbase.campus.BuildConfig.SCHOOL_TEST,LoginDiagnosticSession(defaultEnabled=top.ccbase.campus.BuildConfig.SCHOOL_TEST,clock={0L}).active)
 }
 @Test fun pageDefaultsAndStopSurviveRefreshUntilReopen() {
  val testPage=LoginDiagnosticSession(defaultEnabled=true,clock={0L})
  assertTrue(testPage.active)
  val round=testPage.round
  testPage.stop()
  testPage.navigate("finished") // refresh callbacks never start a new round
  testPage.resource("http","script",403)
  assertFalse(testPage.active);assertFalse(testPage.accepts(round));assertEquals(0,testPage.errorCount())
  assertTrue(LoginDiagnosticSession(defaultEnabled=true,clock={0L}).active)
  assertFalse(LoginDiagnosticSession(defaultEnabled=false,clock={0L}).active)
 }
 @Test fun exitClearsConsentReceiptAndComparison() {
  val s=LoginDiagnosticSession(defaultEnabled=true,clock={0L})
  s.freeze("unknown",false,"unsupported_no_observer",null,emptyList());s.consent()
  s.sent("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0");s.comparison();s.stop()
  assertNull(s.approved());s.start()
  assertEquals(JsonNull,s.freeze("unknown",false,"unsupported_no_observer",null,emptyList())["previousReceipt"])
  assertTrue(s.comparison().contains("尚无同手机对照"))
 }
 @Test fun productionWiringRegistersBeforeFirstLoadAndPopupTransport() {
  val base=File("src/main/java/top/ccbase/campus/school")
  val login=File(base,"AustLogin.kt").readText()
  assertTrue(login.contains("LoginDiagnosticSession(defaultEnabled=top.ccbase.campus.BuildConfig.SCHOOL_TEST)"))
  assertTrue(login.contains("AuthDocumentStart(hosts).apply{enabled=session.active}"))
  assertTrue(login.indexOf("AuthBrowserWindows(this") < login.indexOf("loadUrl(entry)"))
  val windows=File(base,"AuthBrowserWindows.kt").readText()
  assertTrue(windows.indexOf("install(child)") < windows.indexOf("transport.webView=child"))
  assertTrue(login.contains("onDispose{session.stop();documentStart.stop();events.clear()"))
  val pageStarted=login.substringAfter("override fun onPageStarted").substringBefore("override fun onPageFinished")
  assertFalse(pageStarted.contains("session.start()"))
  assertTrue(login.contains("session.consent();val sendTelemetry=session.approved()"))
 }
}
