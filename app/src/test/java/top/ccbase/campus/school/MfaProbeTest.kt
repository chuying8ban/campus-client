package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
class MfaProbeTest {
 @Test fun unknown_is_not_absent(){val p=MfaProbe.decode("null");assertEquals(JsonNull,p["form"]);assertTrue(MfaProbe.preview(p).contains("未知"))}
 @Test fun fixed_stage_only(){assertEquals("reauth_check",MfaProbe.stage("https://authserver-443.webvpn.neau.edu.cn/authserver/reAuthCheck/reAuthCheck.do?ticket=x"));assertNull(MfaProbe.stage("https://evil.invalid/authserver/reAuthCheck/reAuthCheck.do"))}
 @Test fun frozen_probe_upload_and_stop(){
  val s=LoginDiagnosticSession(clock={0L});s.start();val round=s.round
  val p=MfaProbe.unknown();val t=s.freeze("unknown",true,"registered_document_start",true,emptyList(),probe=p)
  s.consent();assertEquals(t,s.approved())
  val payload=SchoolDiagnosticSender.payload(java.util.UUID.randomUUID().toString(),"neau","https://authserver-443.webvpn.neau.edu.cn/","null",emptyList(),t)
  assertEquals(p,Json.parseToJsonElement(payload).jsonObject["telemetry"]!!.jsonObject["probe"])
  s.stop();assertFalse(s.accepts(round));assertNull(s.approved())
 }
 @Test fun no_free_content(){listOf("innerHTML","textContent","input.value","document.cookie","XMLHttpRequest","console.").forEach{assertFalse(MfaProbe.script.contains(it))}}
 @Test fun extra_rejected(){try{MfaProbe.validate(buildJsonObject{put("cookie","x")});fail()}catch(_:IllegalArgumentException){}}
}
