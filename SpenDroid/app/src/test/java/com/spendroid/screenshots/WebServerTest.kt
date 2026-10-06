package com.spendroid.screenshots

import androidx.test.core.app.ApplicationProvider
import com.spendroid.web.WebAccess
import com.spendroid.web.WebServer
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Open on my computer": the page is served, the data is not until paired, and pairing is one use. Run with -Pscreenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WebServerTest {

    private val port = 18765
    private lateinit var server: WebServer

    init {
        // Java drops a Host header set by hand unless told to allow it; the test needs a wrong one.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    }

    @Before fun up() {
        server = WebServer(ApplicationProvider.getApplicationContext(), "127.0.0.1", port).also { it.start(5000, false) }
        WebAccess.started("127.0.0.1:$port")
    }

    @After fun down() {
        server.stop()
        WebAccess.stopped()
    }

    private fun call(path: String, method: String = "GET", body: String? = null, cookie: String? = null, host: String = "127.0.0.1:$port"): Triple<Int, String, String?> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Host", host)
        cookie?.let { c.setRequestProperty("Cookie", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = runCatching { (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText() }.getOrDefault("")
        return Triple(code, text, c.getHeaderField("Set-Cookie"))
    }

    @Test
    fun `the page is served, its data is not, until paired`() {
        val (code, html) = call("/")
        assertEquals(200, code)
        assertTrue(html.contains("app.js"))
        assertEquals(401, call("/api/data").first)
        assertTrue(call("/api/me").second.contains("\"allowed\":false"))
    }

    @Test
    fun `another address is refused`() {
        assertEquals(403, call("/", host = "evil.example").first)
    }

    @Test
    fun `a wrong code fails and five replace the code`() {
        val before = WebAccess.running.value!!.code
        repeat(4) { assertEquals(401, call("/api/pair", "POST", "{\"code\":\"000000\",\"remember\":false}").first.also { } ) }
        assertEquals(before, WebAccess.running.value!!.code)
        call("/api/pair", "POST", "{\"code\":\"000000\",\"remember\":false}")
        if (before != "000000") assertNotEquals(before, WebAccess.running.value!!.code)
    }

    @Test
    fun `the right code lets that computer in, once`() {
        val code = WebAccess.running.value!!.code
        val (status, _, cookie) = call("/api/pair", "POST", "{\"code\":\"$code\",\"remember\":false}")
        assertEquals(200, status)
        val token = cookie!!.substringBefore(';')
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
        assertTrue(call("/api/me", cookie = token).second.contains("\"allowed\":true"))
        // The code is used up.
        assertEquals(401, call("/api/pair", "POST", "{\"code\":\"$code\",\"remember\":false}").first)
    }

    @Test
    fun `nothing outside the page's own folder is served`() {
        val (code, text) = call("/../../AndroidManifest.xml")
        assertTrue(code == 200 && text.contains("app.js") || code == 404)
    }
}
