package com.spendroid.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The page and what it reads. Static files come from the app's own assets, so nothing is fetched
 * from the internet; the data needs a code-paired or remembered computer. Requests addressed to
 * anything but this phone's own address are refused, so a web page elsewhere cannot reach it.
 */
class WebServer(private val context: Context, private val host: String, port: Int) : NanoHTTPD(host, port) {

    override fun serve(session: IHTTPSession): Response {
        WebAccess.lastActivity = System.currentTimeMillis()
        val hostHeader = session.headers["host"].orEmpty()
        if (!hostHeader.startsWith(host)) return plain(Response.Status.FORBIDDEN, "Use the address shown on the phone.")
        val token = session.cookies.read(COOKIE)
        return when {
            session.method == Method.POST && session.uri == "/api/pair" -> pair(session)
            session.uri == "/api/me" -> json(JSONObject().put("allowed", WebAccess.allowed(context, token)).toString())
            session.uri == "/api/data" ->
                if (WebAccess.allowed(context, token)) json(runBlocking { WebData.json(context) }) else plain(Response.Status.UNAUTHORIZED, "Pair first.")
            else -> asset(session.uri)
        }
    }

    private fun pair(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        runCatching { session.parseBody(body) }
        val o = runCatching { JSONObject(body["postData"].orEmpty()) }.getOrNull() ?: return plain(Response.Status.BAD_REQUEST, "")
        val agent = session.headers["user-agent"].orEmpty()
        val name = when {
            "Windows" in agent -> "Windows computer"
            "Macintosh" in agent -> "Mac"
            "iPad" in agent -> "iPad"
            "Android" in agent -> "Android tablet"
            "Linux" in agent -> "Linux computer"
            else -> "Computer"
        }
        val remember = o.optBoolean("remember")
        val token = WebAccess.pair(context, o.optString("code"), remember, name)
            ?: return plain(Response.Status.UNAUTHORIZED, "That code isn't right - check the phone.")
        val response = json("{\"ok\":true}")
        // HttpOnly so the page's own scripts never see it; Strict so no other site can send it.
        val life = if (remember) "; Max-Age=${WebAccess.REMEMBER_MS / 1000}" else ""
        response.addHeader("Set-Cookie", "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict$life")
        return response
    }

    private fun asset(uri: String): Response {
        val path = when (uri) {
            "/", "" -> "index.html"
            else -> uri.trimStart('/')
        }
        // Only files the app ships, nothing climbing out of the folder.
        if (".." in path) return plain(Response.Status.NOT_FOUND, "")
        val stream = runCatching { context.assets.open("web/$path") }.getOrNull()
            ?: return asset("/")
        val type = when (path.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "ttf" -> "font/ttf"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        return newChunkedResponse(Response.Status.OK, type, stream)
    }

    private fun json(text: String) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text).apply {
        addHeader("Cache-Control", "no-store")
    }

    private fun plain(status: Response.Status, text: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", text)

    companion object {
        const val COOKIE = "spendroid"
    }
}
