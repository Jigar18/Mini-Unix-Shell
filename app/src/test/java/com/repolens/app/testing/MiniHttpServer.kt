package com.repolens.app.testing

import com.repolens.app.core.RepoDownloader
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 for hermetic download tests (the Android unit-test
 * classpath has no `com.sun.net.httpserver`). One thread per connection, `Connection: close`.
 */
class MiniHttpServer : AutoCloseable {
    fun interface Handler { fun handle(out: OutputStream) }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, Handler>()
    val requests = CopyOnWriteArrayList<String>()
    val base = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread({
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (e: Exception) { break }
                Thread({ serve(s) }, "mini-http-conn").apply { isDaemon = true }.start()
            }
        }, "mini-http").apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        s.use {
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break }
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            requests.add(path)
            val out = s.getOutputStream()
            val h = routes[path]
            try {
                if (h == null) respond(out, 404, "text/plain", "not found".toByteArray()) else h.handle(out)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun route(path: String, handler: Handler) { routes[path] = handler }

    fun bytes(path: String, body: ByteArray, status: Int = 200, type: String = "application/octet-stream") =
        route(path) { respond(it, status, type, body) }

    fun status(path: String, status: Int) = bytes(path, "status $status".toByteArray(), status, "text/plain")

    fun redirect(path: String, to: String, status: Int = 302) = route(path) {
        it.write("HTTP/1.1 $status Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    /** Announces [announced] bytes but sends only [body] and closes: a dropped connection. */
    fun truncated(path: String, body: ByteArray, announced: Long) = route(path) {
        it.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: $announced\r\nConnection: close\r\n\r\n".toByteArray())
        it.write(body)
    }

    /** Sends [chunks] of [chunk] bytes with [delayMs] between them (to cancel mid-download). */
    fun slow(path: String, chunk: Int, chunks: Int, delayMs: Long) = route(path) {
        it.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${chunk.toLong() * chunks}\r\nConnection: close\r\n\r\n".toByteArray())
        val buf = ByteArray(chunk)
        repeat(chunks) { _ -> it.write(buf); it.flush(); Thread.sleep(delayMs) }
    }

    /** What GitHub serves for a public repository: the API's default branch and the codeload zip. */
    fun githubRepo(owner: String, repo: String, zip: ByteArray, branch: String = "main") {
        bytes("/repos/$owner/$repo", """{"full_name":"$owner/$repo","default_branch":"$branch"}""".toByteArray(), type = "application/json")
        bytes("/$owner/$repo/zip/refs/heads/$branch", zip, type = "application/zip")
    }

    fun downloader(dir: File) = RepoDownloader(dir, apiBase = base, codeloadBase = base, webBase = base)

    override fun close() { socket.close() }

    companion object {
        fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }

        fun stratosZip(): ByteArray =
            MiniHttpServer::class.java.getResourceAsStream("/fixtures/Stratos-main.zip")!!.use { it.readBytes() }
    }
}
