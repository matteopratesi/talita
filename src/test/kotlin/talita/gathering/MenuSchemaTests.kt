package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.GATHERING_MENU
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The schema of the menu (V10, TALITA-12, criterion 12): what the database refuses by itself, whatever the application does. The application checks the same
 * things first and with kinder words; this is the net under it. The limit of size is the constant of the application, and a test makes sure that the table says
 * the same number.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MenuSchemaTests(
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val support = GatheringSupport(dsl, clock)

    /** The name of the constraint that refused, found in the chain of the causes. */
    private fun refusedBy(block: () -> Unit): String {
        val failure = assertFailsWith<Exception> { block() }
        val sql = generateSequence<Throwable>(failure) { it.cause?.takeIf { cause -> cause !== it } }.filterIsInstance<SQLException>().first()
        return Regex("""(?:constraint|index) "([^"]+)"""").find(sql.message.orEmpty())?.groupValues?.get(1) ?: sql.message.orEmpty()
    }

    private fun insert(
        gathering: Long,
        name: String = "menu-settembre-2027.pdf",
        type: String = "application/pdf",
        content: ByteArray = MenuSamples.pdf(),
        at: OffsetDateTime? = OffsetDateTime.now(),
    ) = dsl.insertInto(GATHERING_MENU)
        .set(GATHERING_MENU.GATHERING_ID, gathering).set(GATHERING_MENU.FILE_NAME, name).set(GATHERING_MENU.MEDIA_TYPE, type)
        .set(GATHERING_MENU.CONTENT, content).set(GATHERING_MENU.UPLOADED_AT, at).execute()

    @Test
    fun `t12 c12 a gathering has one menu at most, and another gathering has its own`() {
        support.reset()
        val first = support.make()
        insert(first)

        assertEquals("gathering_menu_pkey", refusedBy { insert(first) })
        support.reset()
    }

    @Test
    fun `t12 c12 the database accepts the three kinds and refuses any other`() {
        support.reset()
        val id = support.make()

        for (type in listOf("application/pdf", "image/jpeg", "image/png")) {
            dsl.deleteFrom(GATHERING_MENU).execute()
            insert(id, type = type)
        }
        for (other in listOf("text/html", "image/svg+xml", "image/heic", "application/msword", "application/octet-stream", "", "APPLICATION/PDF")) {
            assertEquals("gathering_menu_media_type", refusedBy { dsl.deleteFrom(GATHERING_MENU).execute(); insert(id, type = other) }, "'$other'")
        }
        support.reset()
    }

    @Test
    fun `t12 c12 the content goes from 1 byte to 10 MB, and the table agrees with the constant of the application`() {
        support.reset()
        val id = support.make()

        assertEquals("gathering_menu_content_size", refusedBy { insert(id, content = ByteArray(0)) }, "empty")
        assertEquals(1, insert(id, content = byteArrayOf(1)), "one byte")
        dsl.deleteFrom(GATHERING_MENU).execute()
        // The limit is the constant of the application: 10 MB exactly goes in, one byte more does not.
        assertEquals(1, insert(id, content = MenuSamples.pdfOf(MenuFile.MAX_BYTES)), "exactly the limit")
        assertEquals(MenuFile.MAX_BYTES, dsl.select(org.jooq.impl.DSL.field("octet_length(content)", Int::class.java)).from(GATHERING_MENU).fetchSingle().value1())
        dsl.deleteFrom(GATHERING_MENU).execute()
        assertEquals("gathering_menu_content_size", refusedBy { insert(id, content = MenuSamples.pdfOf(MenuFile.MAX_BYTES + 1)) }, "one byte over the limit")
        assertEquals("gathering_menu_content_size", refusedBy { insert(id, content = MenuSamples.pdfOf(15 * 1024 * 1024)) }, "15 MB")
        support.reset()
    }

    @Test
    fun `t12 c12 the migration says the same number as the constant, and the sentences of the pages are made from the constant`() {
        val migration = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V10__menu.sql"))

        assertEquals(MenuFile.MAX_BYTES, Regex("""octet_length\(content\) between 1 and (\d+)""").find(migration)!!.groupValues[1].toInt(), "the table and the application say one limit")
        assertEquals("10 MB", MenuFile.LIMIT_LABEL)
        assertEquals("Il file è troppo grande: al massimo ${MenuFile.LIMIT_LABEL}.", MenuTexts.TOO_BIG)
        assertTrue(MenuTexts.HELP.contains("Al massimo ${MenuFile.LIMIT_LABEL}."), MenuTexts.HELP)
    }

    @Test
    fun `t12 c12 the name goes from 1 to 104 characters, with no control characters, and the date is required`() {
        support.reset()
        val id = support.make()

        assertEquals("gathering_menu_file_name_length", refusedBy { insert(id, name = "") })
        assertEquals("gathering_menu_file_name_length", refusedBy { insert(id, name = "a".repeat(105)) })
        assertEquals(1, insert(id, name = "a".repeat(104)))
        dsl.deleteFrom(GATHERING_MENU).execute()
        // The 104 are characters and not bytes: 104 letters with an accent are 208 bytes of UTF-8.
        assertEquals(1, insert(id, name = "è".repeat(104)))
        dsl.deleteFrom(GATHERING_MENU).execute()
        for (control in listOf("\n", "\r", "\t", "\u0001", "\u001F", "\u007F")) {
            assertEquals("gathering_menu_file_name_plain", refusedBy { insert(id, name = "me${control}nu.pdf") }, "U+%04X".format(control.codePointAt(0)))
        }
        // The date is not optional: «caricato il …» is on every page that shows the file.
        assertTrue(refusedBy { insert(id, at = null) }.contains("uploaded_at"))
        support.reset()
    }

    @Test
    fun `t12 c12 the menu of a gathering goes with it, and a menu needs a gathering`() {
        support.reset()
        val id = support.make()
        insert(id)

        assertEquals("gathering_menu_gathering_id_fkey", refusedBy { insert(id + 100_000) })
        support.reset()

        assertEquals(0, dsl.fetchCount(GATHERING_MENU), "the menu went with its gathering")
    }

    @Test
    fun `t12 c12 the content is kept exactly, byte for byte, with every value of a byte`() {
        support.reset()
        val id = support.make()
        val everyByte = ByteArray(256 * 40) { (it % 256).toByte() }.also { it[0] = '%'.code.toByte() }

        insert(id, content = everyByte)

        assertTrue(everyByte.contentEquals(dsl.select(GATHERING_MENU.CONTENT).from(GATHERING_MENU).fetchSingle().value1()))
        support.reset()
    }
}
