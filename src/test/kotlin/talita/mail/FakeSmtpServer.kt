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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

/**
 * A mail server of the tests, only on the loopback, that speaks just enough SMTP to receive messages and to **refuse** the recipients that contain
 * [refusing]: what a real server does with an address that does not exist (a 550 on `RCPT TO`). It is how the tests see what the application does when one mail
 * of a list does not leave and the others do, with the real code of the mail library and not with a stub of it. It never forwards anything.
 *
 * [connectionCount] counts the connections that were opened (a list sent in one connection counts one), [accepted] holds the recipients of the messages that it took.
 *
 * [mode] is how it behaves (TALITA-16, criterion 21): normally it answers everything; a server that is **silent** after the greeting, or after the second message, accepts the
 * connection and then says nothing, as a mail server that hangs does. A silent server reads what it is sent and never answers, until the client closes the connection:
 * [openConnections] is how many connections are still open. A **slow** server answers everything, but each answer comes [delayMillis] late: no step is past the timeout of the
 * client, and the steps of a whole list add up.
 */
class FakeSmtpServer(
    private val refusing: String? = null,
    @Volatile var mode: Mode = Mode.NORMAL,
    @Volatile var delayMillis: Long = 0,
    /** The answer to the `RCPT TO` of a recipient that contains [refusing]: a `550` (it does not exist) by default, a `450` (try again later) for a temporary one. */
    private val refusal: String = "550 5.1.1 no such user",
) : AutoCloseable {

    enum class Mode {
        NORMAL,

        /** The greeting, and then nothing: no answer to `EHLO`, nor to `HELO`. */
        SILENT_AFTER_GREETING,

        /** Two messages go through, and the third gets no answer (it stops at `MAIL FROM`). */
        SILENT_AFTER_TWO_MESSAGES,

        /** Every answer, the greeting included, comes [delayMillis] late. */
        SLOW,

        /** The server refuses the sender of every message, for all the recipients: a `553` to `MAIL FROM`, as a provider does with an address that is not its own. */
        REFUSE_SENDER,

        /** The server takes the sender and the recipients and refuses the message after its text, a `554`: what it does with a content that it does not like. */
        REFUSE_CONTENT,

        /** The server refuses to start the message: a `554` to `DATA` itself. */
        REFUSE_DATA,

        /** A temporary refusal of the whole sending, a `452` to `MAIL FROM`: what a provider says when the limit of the day is over. */
        TEMPORARY_LIMIT,
    }

    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())

    private val open = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort

    val host: String get() = InetAddress.getLoopbackAddress().hostAddress

    /** How many connections the server has seen. */
    val connectionCount = AtomicInteger()

    /** How many times a client began a message (`MAIL FROM`): a list that stops at the first refusal has one. */
    val mailFromCount = AtomicInteger()

    /** How many connections are open now: the ones that the client has not closed yet. */
    val openConnections = AtomicInteger()

    private val lock = ReentrantLock()

    private val allClosed = lock.newCondition()

    /** Waits until every connection that the client opened is closed (the server notices it at once), for [seconds] at most; false if one is still open. No pause of a fixed time. */
    fun awaitClosed(seconds: Long): Boolean {
        var left = java.time.Duration.ofSeconds(seconds).toNanos()
        lock.withLock {
            while (openConnections.get() > 0) {
                if (left <= 0) return false
                left = allClosed.awaitNanos(left)
            }
            return true
        }
    }

    /** The recipient of every message that was accepted, in order. */
    val accepted = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "fake-smtp") {
            try {
                while (true) {
                    val socket = server.accept()
                    open += socket
                    connectionCount.incrementAndGet()
                    openConnections.incrementAndGet()
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
                if (mode == Mode.SLOW) Thread.sleep(delayMillis)
                out.write((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            say("220 fake ESMTP")
            if (mode == Mode.SILENT_AFTER_GREETING) return silence(reader)
            var recipient: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                val upper = line.uppercase()
                when {
                    upper.startsWith("EHLO") || upper.startsWith("HELO") -> say("250 fake")
                    upper.startsWith("MAIL FROM") -> {
                        mailFromCount.incrementAndGet()
                        when {
                            mode == Mode.SILENT_AFTER_TWO_MESSAGES && accepted.size >= 2 -> return silence(reader)
                            mode == Mode.REFUSE_SENDER -> say("553 5.7.1 sender not allowed")
                            mode == Mode.TEMPORARY_LIMIT -> say("452 4.2.2 sending limit reached")
                            else -> say("250 OK")
                        }
                    }
                    upper.startsWith("RCPT TO") -> {
                        val address = line.substringAfter('<').substringBefore('>')
                        if (refusing != null && address.contains(refusing)) {
                            say(refusal)
                        } else {
                            recipient = address
                            say("250 OK")
                        }
                    }
                    upper == "DATA" -> if (mode == Mode.REFUSE_DATA) {
                        say("554 5.6.0 no data today")
                    } else {
                        say("354 go on")
                        while (reader.readLine().let { it != null && it != "." }) {
                            // the body is not kept
                        }
                        if (mode == Mode.REFUSE_CONTENT) {
                            say("554 5.6.0 content refused")
                        } else {
                            recipient?.let { accepted += it }
                            say("250 OK")
                        }
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
            lock.withLock {
                openConnections.decrementAndGet()
                allClosed.signalAll()
            }
        }
    }

    /** Reads what the client sends and answers nothing, until the client closes the connection. */
    private fun silence(reader: BufferedReader) {
        while (reader.readLine() != null) {
            // no answer
        }
    }

    override fun close() {
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
    }
}
