package top.ccbase.campus.crash

import android.app.Application
import android.os.Build
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 崩溃上报（2026-10-19 用户："为什么我一打开图库就闪退"）。
 *
 * 自签名包没有厂商崩溃通道，用户只会说"闪退"，开发这边看不到堆栈就只能猜 ——
 * 这个文件存在的唯一目的就是**别再猜**：崩之前把堆栈 POST 到自己的服务器
 * （`/api/art/crash`，落在 `/var/lib/artapi/crashes.log`），下次 ssh 上去直接读。
 *
 * 三条纪律：
 *  - 只发崩溃信息，不发任何用户数据（没有账号、没有内容、没有设备标识）；
 *  - 超时压到 2.5 秒，绝不因为上报卡住用户；
 *  - 上完报**照常把异常交给系统**（`prev`），不吞崩溃、不假装没事。
 */
object CrashReporter {

    private const val ENDPOINT = "https://study.ccbase.top/api/art/crash"

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching { send(app, thread.name, err) }
            prev?.uncaughtException(thread, err)
        }
    }

    private fun send(app: Application, threadName: String, err: Throwable) {
        val sw = StringWriter()
        err.printStackTrace(PrintWriter(sw))
        val body = JSONObject().apply {
            put("app", "campus")
            put("version", runCatching { top.ccbase.campus.BuildConfig.VERSION_NAME }.getOrDefault("?"))
            put("code", runCatching { top.ccbase.campus.BuildConfig.VERSION_CODE }.getOrDefault(0))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("thread", threadName)
            put("stack", sw.toString().take(12_000))
        }
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 2500
            c.readTimeout = 2500
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
