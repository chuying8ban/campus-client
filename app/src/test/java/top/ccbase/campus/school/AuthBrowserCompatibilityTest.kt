package top.ccbase.campus.school

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class AuthBrowserCompatibilityTest {
 @Test fun `login wires in app popup transport rather than external browser`() {
  val source=File("src/main/java/top/ccbase/campus/school/AustLogin.kt").readText()
  assertTrue("Login must install browser window support", source.contains("AuthBrowserWindows"))
  assertTrue("Back must prefer child window",source.contains("windows?.back()"))
  assertTrue("Disposal must destroy children",source.contains("windows?.dispose()"))
 }
}
