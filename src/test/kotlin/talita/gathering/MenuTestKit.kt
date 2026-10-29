package talita.gathering

import org.jooq.DSLContext
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.multipart
import talita.Addresses
import talita.account.MockBrowser
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING_MENU
import talita.teacher.Person
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** The menu as it is kept, for a test to look at: everything the table has, content included. */
internal class StoredMenu(val name: String, val mediaType: String, val content: ByteArray, val uploadedAt: OffsetDateTime)

/**
 * The tools of the tests of the menu (TALITA-12): two teachers (Marta Bianchi, who has entered, and Paolo Verdi, a second pair of hands), a parent, a gathering, the
 * upload as a browser sends it (a `multipart/form-data` form with the code against forgery that the page carries) and a look at the table. Every name here is
 * synthetic, and every file is built in memory (see [MenuSamples]).
 */
internal class MenuTestKit(private val mockMvc: MockMvc, tokens: OneTimeTokenService, private val dsl: DSLContext, clock: Clock) {

    val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)

    /** Two teachers, who have both entered, and one gathering (in the state asked for, a month ahead by default) with Marta as custodian. */
    inner class World(status: GatheringStatus = GatheringStatus.draft, start: LocalDate = kit.gatherings.today().plusDays(30), title: String = "Convivenza di settembre 2027") {
        private val martaPerson = Person(Addresses.unique("menu-marta"), "Marta", "Bianchi")
        private val paoloPerson = Person(Addresses.unique("menu-paolo"), "Paolo", "Verdi")
        private val started = kit.start(martaPerson, paoloPerson)

        /** Marta Bianchi, inside. */
        val marta: MockBrowser = started.first
        val martaId: Long = started.second

        /** Paolo Verdi, inside from another browser: the second teacher. */
        val paolo: MockBrowser = MockBrowser(mockMvc).also { it.confirm(kit.link(paoloPerson.email)) }

        val id: Long = kit.gatherings.make(title = title, start = start, status = status, custodians = listOf(martaId))

        val path: String = "/convivenza/$id/menu"
    }

    /** The code against forgery of the form at [formPath], as the browser reads it from the page (the page also gives the browser its session). */
    fun csrf(browser: MockBrowser, formPath: String): String =
        checkNotNull(MockBrowser.csrfIn(browser.get(formPath).response.contentAsString)) { "$formPath has no form with a code" }

    /**
     * What a browser does when someone chooses a file and taps «Carica il menù»: it asks for the page of the form (the code against forgery and the session) and sends
     * the form as `multipart/form-data`. [file] null is the form sent with nothing chosen (the browser still sends an empty part). Returns the answer of the post.
     */
    fun upload(browser: MockBrowser, gathering: Long, file: MockMultipartFile?, formPath: String = "/convivenza/$gathering/menu"): MvcResult =
        postFile(browser, gathering, file, csrf(browser, formPath))

    /** The post of the form with a code that the caller has already. */
    fun postFile(browser: MockBrowser, gathering: Long, file: MockMultipartFile?, csrf: String): MvcResult =
        mockMvc.multipart("/convivenza/$gathering/menu") {
            browser.cookie?.let { session = it }
            file(file ?: MockMultipartFile("file", "", "application/octet-stream", ByteArray(0)))
            param("_csrf", csrf)
        }.andReturn()

    /** A file as a browser sends it: the field is `file`, with the name and the type that the browser says (neither is believed). */
    fun file(name: String, content: ByteArray, type: String = "application/octet-stream"): MockMultipartFile = MockMultipartFile("file", name, type, content)

    fun stored(gathering: Long): StoredMenu? =
        dsl.select(GATHERING_MENU.FILE_NAME, GATHERING_MENU.MEDIA_TYPE, GATHERING_MENU.CONTENT, GATHERING_MENU.UPLOADED_AT).from(GATHERING_MENU)
            .where(GATHERING_MENU.GATHERING_ID.eq(gathering)).fetchOne { StoredMenu(it[GATHERING_MENU.FILE_NAME]!!, it[GATHERING_MENU.MEDIA_TYPE]!!, it[GATHERING_MENU.CONTENT]!!, it[GATHERING_MENU.UPLOADED_AT]!!) }

    fun count(): Int = dsl.fetchCount(GATHERING_MENU)

    /** A menu written straight in the table, as one that was uploaded some time ago. */
    fun put(gathering: Long, name: String = "menu-settembre-2027.pdf", type: String = "application/pdf", content: ByteArray = MenuSamples.pdf(), at: OffsetDateTime = OffsetDateTime.now()) {
        dsl.deleteFrom(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gathering)).execute()
        dsl.insertInto(GATHERING_MENU)
            .set(GATHERING_MENU.GATHERING_ID, gathering).set(GATHERING_MENU.FILE_NAME, name).set(GATHERING_MENU.MEDIA_TYPE, type)
            .set(GATHERING_MENU.CONTENT, content).set(GATHERING_MENU.UPLOADED_AT, at).execute()
    }
}

/** The text of a card or a page between two markers, for a sentence to be looked for inside one part of the page and not anywhere. */
internal fun section(html: String, id: String): String =
    Regex("""<section[^>]*aria-labelledby="$id"[^>]*>([\s\S]*?)</section>""").find(html)?.groupValues?.get(1) ?: ""

/** The links of a part of a page: the text (with the markup out) of each, and where it leads. */
internal fun links(html: String): List<Pair<String, String>> =
    Regex("""<a [^>]*?href="([^"]*)"[^>]*>([\s\S]*?)</a>""").findAll(html).map { it.groupValues[2].replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim() to it.groupValues[1] }.toList()
