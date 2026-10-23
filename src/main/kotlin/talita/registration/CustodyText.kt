package talita.registration

import org.springframework.core.io.ClassPathResource
import talita.gathering.GatheringFormat
import java.security.MessageDigest
import java.time.LocalDate

/** A child as the text of the custody names it. */
data class CustodyChild(val name: String, val birthDate: LocalDate)

/** What fills the model of the text: the address of whoever accepts, the children, the custodians of that moment and the gathering. */
data class CustodyData(
    val email: String,
    val children: List<CustodyChild>,
    /** The custodians, by surname and name, as the text names them. */
    val custodians: List<String>,
    val title: String,
    val place: String,
    val start: LocalDate,
    val end: LocalDate,
)

/**
 * The text of the custody, the heart of the manleva (criteria 6, 8, 35). **One model**, plain text in the code, with a **version**: the
 * file `registration/affidamento-1.txt` is `affidamento-1`, and a test fixes the fingerprint of every version, so that changing the text
 * without making a new version (`affidamento-2`) fails the build. What is kept with a registration is the **resulting text** and the
 * version, never the model.
 *
 * The model has placeholders in braces that the application fills: `{indirizzo}`, `{bambini}` (a name and a date of birth for each child),
 * `{affidatari}`, `{titolo}`, `{luogo}`, `{primo giorno}`, `{ultimo giorno}`; and two rules of agreement with the first form for one and the
 * second for more: `{b:suo|loro}` by the number of children and `{a:lo/la|li}` by the number of custodians. The sex of nobody is asked,
 * so the single form of a person is the two together. The last paragraph is the declaration about the other parent: the page shows it as
 * a box to tick, and it is part of the text that is kept.
 */
object CustodyText {

    const val VERSION = "affidamento-1"

    private const val MODEL_PATH = "registration/affidamento-1.txt"

    /** The model of the current version. */
    val model: String by lazy {
        ClassPathResource(MODEL_PATH).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }.replace("\r\n", "\n").trimEnd()
    }

    /** The text with its parts: [body] is what is read, [declaration] is the sentence of the box about the other parent, [full] is what is kept. */
    data class Rendered(val body: String, val declaration: String) {
        val full: String get() = body + "\n\n" + declaration
    }

    private val rule = Regex("""\{([ab]):([^{}|]*)\|([^{}|]*)}""")
    private val placeholder = Regex("""\{([^{}|:]+)}""")

    fun render(data: CustodyData, model: String = this.model): Rendered {
        val paragraphs = model.split("\n\n")
        val declaration = fill(paragraphs.last(), data)
        return Rendered(fill(paragraphs.dropLast(1).joinToString("\n\n"), data), declaration)
    }

    private fun fill(text: String, data: CustodyData): String {
        val ruled = rule.replace(text) { match ->
            val count = if (match.groupValues[1] == "b") data.children.size else data.custodians.size
            if (count == 1) match.groupValues[2] else match.groupValues[3]
        }
        return placeholder.replace(ruled) { match ->
            when (val name = match.groupValues[1]) {
                "indirizzo" -> data.email
                "bambini" -> list(data.children.map { "${it.name}, nato/a il ${GatheringFormat.date(it.birthDate)}" }, ";")
                "affidatari" -> list(data.custodians, ",")
                "titolo" -> data.title
                "luogo" -> data.place
                "primo giorno" -> GatheringFormat.date(data.start)
                "ultimo giorno" -> GatheringFormat.date(data.end)
                else -> error("the model of the custody has an unknown placeholder: {$name}")
            }
        }
    }

    /** `A`, `A e B`, `A, B e C` (with [separator] `;` when the items have commas of their own). */
    private fun list(items: List<String>, separator: String): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString("$separator ") + " e " + items.last()
    }

    /** The fingerprint of a text (SHA-256, in hexadecimal): of the file of a version, and of the text that a page shows. */
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
