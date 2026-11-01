package talita.mail

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A mail as the tests read it from Mailpit. */
data class ReceivedMail(
    val to: List<String>,
    val fromAddress: String,
    val fromName: String,
    val subject: String,
    val text: String,
    val html: String,
    /** Header names as Mailpit writes them (`X-Mailjet-Trackopen`), lowercased here. */
    val headers: Map<String, List<String>>,
) {
    /** The links in the text: every `http` word. */
    fun links(): List<String> = Regex("""https?://\S+""").findAll(text).map { it.value }.toList()
}

/**
 * Reads the mail that the application sent from the API of [MailpitContainer]. Every test uses its
 * own recipient addresses (see `Addresses`), so one Mailpit serves all of them.
 */
object Mailbox {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = JsonMapper()

    private fun get(path: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("http://${MailpitContainer.host}:${MailpitContainer.httpPort}$path"))
            .timeout(Duration.ofSeconds(10)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(response.statusCode() == 200) { "Mailpit answered ${response.statusCode()} to $path" }
        return json.readTree(response.body())
    }

    /** The mails sent to [recipient] up to now, oldest first. */
    fun sentTo(recipient: String): List<ReceivedMail> {
        val query = URLEncoder.encode("to:\"$recipient\"", StandardCharsets.UTF_8)
        val ids = items(get("/api/v1/search?query=$query&limit=100")["messages"]).map { it["ID"].asString() }
        return ids.map { read(it) }.reversed()
    }

    /** The last mail that arrives for [recipient] within [seconds]: waits for the first one. */
    fun waitForMail(recipient: String, seconds: Long = 10): ReceivedMail = waitForMails(recipient, 1, seconds).last()

    /**
     * The mails of [recipient] once there are at least [count] of them, within [seconds] (TALITA-16). The mail leaves before the answer of a request, but Mailpit is another
     * process and shows it a moment later: a test that counts the mails waits for them here and does not pause for a time.
     */
    fun waitForMails(recipient: String, count: Int, seconds: Long = 10): List<ReceivedMail> =
        waitFor(recipient, "$count mail" + (if (count == 1) "" else "s"), seconds) { it.size >= count }

    /**
     * The mails of [recipient] once [condition] holds for them (all the mails that [recipient] has, oldest first), looking again every 100 ms for [seconds] at most. [what] says what the
     * test waits for, for the message of the failure, which also says how many mails there were at the end.
     */
    fun waitFor(recipient: String, what: String, seconds: Long = 10, condition: (List<ReceivedMail>) -> Boolean): List<ReceivedMail> {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (true) {
            val mails = sentTo(recipient)
            if (condition(mails)) return mails
            check(System.nanoTime() < deadline) { "waiting for $what for $recipient: ${mails.size} mail(s) after $seconds seconds" }
            Thread.sleep(100)
        }
    }

    // JsonNode has a `map` of its own (it applies a function to the node), which hides the one of Kotlin.
    private fun items(node: JsonNode): List<JsonNode> = node.valueStream().toList()

    private fun read(id: String): ReceivedMail {
        val message = get("/api/v1/message/$id")
        val headers = get("/api/v1/message/$id/headers").properties().associate { (name, values) ->
            name.lowercase() to items(values).map { it.asString() }
        }
        return ReceivedMail(
            to = items(message["To"]).map { it["Address"].asString() },
            fromAddress = message["From"]["Address"].asString(),
            fromName = message["From"]["Name"].asString(),
            subject = message["Subject"].asString(),
            text = message["Text"].asString(),
            html = message["HTML"].asString(),
            headers = headers,
        )
    }
}
