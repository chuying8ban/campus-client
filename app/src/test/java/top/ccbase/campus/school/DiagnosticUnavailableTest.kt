package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
class DiagnosticUnavailableTest {
 @Test fun unavailableIsNotZeroAndRejectsFreeTelemetry() {
  val s=LoginDiagnosticSession{0L};s.start()
  val t=s.freeze("unknown",false,"unsupported_no_observer",false,emptyList())
  val p=Json.parseToJsonElement(SchoolDiagnosticSender.payload("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0","aust","https://example.invalid/","null",emptyList(),t)).jsonObject
  assertEquals("unavailable",p["structure"]!!.jsonPrimitive.content)
  assertEquals(JsonNull,p["tables"])
  try {SchoolDiagnosticSender.payload("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0","aust","https://example.invalid/","null",emptyList(),buildJsonObject{put("cookie","SECRET")});fail()}catch(_:IllegalArgumentException){}
 }
}
