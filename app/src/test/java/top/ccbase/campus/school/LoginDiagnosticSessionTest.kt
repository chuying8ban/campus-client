package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
class LoginDiagnosticSessionTest {
 @Test fun unknownCapabilitiesAndReceiptChainOnlyAfterConsent() {
  val s=LoginDiagnosticSession{0L};s.start();val id=s.sessionId
  val a=s.freeze("unknown",false,"unsupported_no_observer",null,emptyList())
  assertEquals(kotlinx.serialization.json.JsonNull,a["executed"])
  assertEquals(kotlinx.serialization.json.JsonNull,a["previousReceipt"])
  try{s.sent("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0");fail()}catch(_:IllegalStateException){}
  s.consent();s.sent("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0")
  val next=s.freeze("unknown",false,"unsupported_no_observer",false,emptyList())
  assertEquals(kotlinx.serialization.json.JsonPrimitive(id),next["sessionId"])
  assertEquals(kotlinx.serialization.json.JsonPrimitive("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0"),next["previousReceipt"])
  assertNull(s.approved());s.stop();s.start();assertNotEquals(id,s.sessionId)
  assertEquals(kotlinx.serialization.json.JsonNull,s.freeze("unknown",false,"unsupported_no_observer",null,emptyList())["previousReceipt"])
 }

 @Test fun v2WireKeepsFrozenTelemetryOnSameUuidRetry() {
  val session=LoginDiagnosticSession{12L};session.start();session.resource("http","script",403)
  val telemetry=session.freeze("130.0.1.2",true,"registered_document_start",true,listOf("TypeError","SECRET"))
  val result="\"{\\\"tables\\\":0,\\\"frames\\\":0,\\\"hasTreeModel\\\":false,\\\"ready\\\":false}\""
  val id="b4eb244c-fd76-43d7-9da7-cfdbb7b727d0"
  val a=SchoolDiagnosticSender.payload(id,"aust","https://example.invalid/login",result,emptyList(),telemetry)
  session.resource("http","other",500)
  val b=SchoolDiagnosticSender.payload(id,"aust","https://example.invalid/login",result,emptyList(),telemetry)
  assertEquals(a,b);assertTrue(a.contains("telemetry"));assertTrue(a.contains("403"));assertFalse(a.contains("SECRET"));assertTrue(a.length<16384)
 }
 @Test fun consentStopLateRetry() {
  val s=LoginDiagnosticSession{100L};assertFalse(s.active)
  s.start();val round=s.round;s.resource("http","script",403);val p=s.freeze("unknown",false,"unsupported_no_observer",false,emptyList())
  assertNull(s.approved());s.consent();assertEquals(p,s.approved());assertEquals(p,s.approved())
  s.stop();s.resource("http","script",500);assertNull(s.approved());assertFalse(s.accepts(round));s.start();assertNotEquals(round,s.round);assertEquals(0,s.errorCount())
 }
}
