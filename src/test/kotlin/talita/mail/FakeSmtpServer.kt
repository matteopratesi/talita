package talita.mail

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A mail server of the tests, only on the loopback, that speaks just enough SMTP to receive messages and to **refuse** the recipients that contain
 * [refusing]: what a real server does with an address that does not exist (a 550 on `RCPT TO`). It is how the tests see what the application does when one mail
 * of a list does not leave and the others do, with the real code of the mail library and not with a stub of it. It never forwards anything.
 *
 * [connections] counts the connections that were opened (a list sent in one connection counts one), [accepted] holds the recipients of the messages that it took.
 */
class FakeSmtpServer(private val refusing: String? = null) : AutoCloseable {

    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())

    private val open = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort

    val host: String get() = InetAddress.getLoopbackAddress().hostAddress

    /** How many connections the server has seen. */
    val connectionCount = AtomicInteger()

    /** The recipient of every message that was accepted, in order. */
    val accepted = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "fake-smtp") {
            try {
                while (true) {
                    val socket = server.accept()
                    open += socket
                    connectionCount.incrementAndGet()
                    thread(isDaemon = true, name = "fake-smtp-connection") { serve(socket) }
                }
            } catch (_: IOException) {
                // closed: the test is over
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val out = socket.getOutputStream()
            fun say(line: String) {
                out.write((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            say("220 fake ESMTP")
            var recipient: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                val upper = line.uppercase()
                when {
                    upper.startsWith("EHLO") || upper.startsWith("HELO") -> say("250 fake")
                    upper.startsWith("MAIL FROM") -> say("250 OK")
                    upper.startsWith("RCPT TO") -> {
                        val address = line.substringAfter('<').substringBefore('>')
                        if (refusing != null && address.contains(refusing)) {
                            say("550 5.1.1 no such user")
                        } else {
                            recipient = address
                            say("250 OK")
                        }
                    }
                    upper == "DATA" -> {
                        say("354 go on")
                        while (reader.readLine().let { it != null && it != "." }) {
                            // the body is not kept
                        }
                        recipient?.let { accepted += it }
                        say("250 OK")
                    }
                    upper == "RSET" -> say("250 OK")
                    upper == "NOOP" -> say("250 OK")
                    upper == "QUIT" -> {
                        say("221 bye")
                        return
                    }
                    else -> say("502 not implemented")
                }
            }
        } catch (_: IOException) {
            // the client went away
        } finally {
            runCatching { socket.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
    }
}
