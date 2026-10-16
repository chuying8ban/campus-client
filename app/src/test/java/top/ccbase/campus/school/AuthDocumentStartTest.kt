package top.ccbase.campus.school

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AuthDocumentStartTest {
 @Test fun cleanupPrecedesHandlerRemovalAndRegistration() {
  val source=File("src/main/java/top/ccbase/campus/school/AuthDocumentStart.kt").readText().substringAfter("fun install(view: WebView)").substringBefore("fun remove")
  assertTrue(source.indexOf("window.__campusAuthStop") < source.indexOf("handlers.remove"))
  assertTrue(source.indexOf("handlers.remove") < source.indexOf("states[view] = register"))
  assertTrue(source.contains("it.host in hosts"))
 }

 @Test fun `exact HTTPS origins exclude wildcards ports paths and credentials`() {
  assertEquals(setOf("https://auth.example.edu"), AuthDocumentStart.origins(setOf("auth.example.edu","*.example.edu","auth.example.edu:8443","user@auth.example.edu","auth.example.edu/path","auth.example.edu?token=x")))
 }
 @Test fun `support is checked before registration and unsupported never injects`() {
  val calls=mutableListOf<String>()
  val d=AuthDocumentStart(setOf("auth.example.edu"))
  assertEquals("registered_document_start",d.register({calls.add("feature");true},{script,origins->calls.add("register");assertEquals(setOf("https://auth.example.edu"),origins);assertTrue(script.contains("location.origin"))}))
  assertEquals(listOf("feature","register"),calls)
  assertEquals("unsupported_no_observer",d.register({false},{_,_->fail("late injection forbidden")}))
  assertEquals("registration_failed_no_observer",d.register({true},{_,_->throw IllegalStateException("secret")}))
 }
 @Test fun `registration precedes first navigation and popup transport with no late fallback`() {
  val root=File("src/main/java/top/ccbase/campus/school/AustLogin.kt").readText()
  assertTrue(root.indexOf("documentStart.install(v)")<root.indexOf("loadUrl(entry)"))
  assertFalse(root.contains("evaluateJavascript(AuthResourceDiagnostics.errorObserver"))
  val child=File("src/main/java/top/ccbase/campus/school/AuthBrowserWindows.kt").readText()
  assertTrue(child.indexOf("install(child)")<child.indexOf("transport.webView=child"))
  assertTrue(child.contains("beforeNavigation(view)"))
 }
 @Test fun `observer rejects sensitive reads and request mutation`() {
  val script=AuthDocumentStart.script(setOf("https://auth.example.edu"))
  for(word in listOf("cookie","input","innerHTML","innerText","textContent",".message",".stack",".reason","fetch","XMLHttpRequest","preventDefault","stopPropagation","console")) assertFalse(word,script.contains(word))
  assertTrue(script.contains("window!==window.top"))
  assertTrue(script.contains("UnhandledRejection"))
  assertFalse(AuthDocumentStart.observed("null"))
  assertTrue(AuthDocumentStart.observed("\"{\\\"observerStarted\\\":true}\""))
 }
}
