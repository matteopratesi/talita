package talita.registration

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.gathering.PublicNames
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The check of the names of the texts that anyone with the link can read (TALITA-16, criteria 12-14, 16): the query of [KnownNames] on a Postgres that is real, with
 * `unaccent`, for the children (of this gathering or of a past one, from the paper form too) and for the teachers. It answers yes or no for each text, in the order of the
 * texts, and a name that is a first name alone, a surname alone or the two apart does not count. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PublicNamesTests(
    @Autowired private val names: PublicNames,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    /** No gathering, no child and no teacher: the people of each test are the ones that it makes. */
    @BeforeEach
    fun nobody() {
        GatheringSupport(dsl, clock).reset()
        dsl.deleteFrom(TEACHER_INVITATION).execute()
        dsl.deleteFrom(TEACHER).execute()
    }

    private fun child(first: String, last: String): Long =
        dsl.insertInto(CHILD).set(CHILD.FIRST_NAME, first).set(CHILD.LAST_NAME, last).set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).returningResult(CHILD.ID).fetchSingle().value1()!!

    private fun teacher(first: String, last: String) {
        TestAccounts.makeTeacher(dsl, Addresses.unique("t16-maestro"), first, last)
    }

    private fun contains(text: String): Boolean = names.contain(listOf(text)).single()

    @Test
    fun `t16 c12 the name and the surname of a child are found in both orders, without capitals or accents, and in the middle of a sentence`() {
        child("Luca", "Rossi")
        child("Nicolò", "Bianchi")

        for (text in listOf("Convivenza con Luca Rossi", "rossi luca", "LUCA ROSSÌ", "Luca Rossi", "  luca   rossi  ", "Luca Rossi.", "(Luca Rossi)", "gioco: Rossi Luca, a squadre", "Luca\tRossi", "Luca Rossi")) {
            assertTrue(contains(text), "«$text» has the name of a child")
        }
        // The accent is not counted on either side.
        for (text in listOf("Nicolo Bianchi", "NICOLÒ BIANCHI", "bianchi nicolò", "Bianchi Nicolo")) assertTrue(contains(text), "«$text» is Nicolò Bianchi")
    }

    @Test
    fun `t16 c12 the name and the surname of a teacher are found like those of a child`() {
        teacher("Marta", "Bianchi")

        assertTrue(contains("Per informazioni chiedete a Marta Bianchi"))
        assertTrue(contains("bianchi marta"))
        assertTrue(contains("MARTA BIANCHÌ"))
        assertFalse(contains("Per informazioni chiedete a Marta"))
    }

    @Test
    fun `t16 c13 a name alone, a surname alone, the two parts apart or glued together pass`() {
        child("Luca", "Rossi")

        for (text in listOf("Lucarossi", "Luca, Rossi", "Luca e Rossi", "Luca", "Rossi", "Luca-Rossi", "Luca. Rossi", "Luca\nRossi", "Luca Rossini", "Lucas Rossi", "il papà di Luca porta le torte")) {
            assertFalse(contains(text), "«$text» does not have the name and the surname together")
        }
    }

    @Test
    fun `t16 c13 the parts of a name count as whole words next to each other, with only spaces between them`() {
        child("Sole", "Mare")
        child("Anna Maria", "Rossi")
        child("Marco", "Polo")

        assertFalse(contains("crema da sole, mare permettendo"), "the comma separates")
        assertTrue(contains("crema da Sole Mare"))
        assertFalse(contains("Anna Rossi"), "the first name is Anna Maria")
        assertTrue(contains("Anna Maria Rossi"))
        assertTrue(contains("Rossi Anna Maria"))
        assertFalse(contains("Marco Polonia"), "whole words: Polonia is not Polo")
        // The honest false positive of the edge cases: a child called like a game blocks that text, and the error says to write it another way.
        assertTrue(contains("il gioco di Marco Polo"))
    }

    @Test
    fun `t16 c13 the apostrophes, straight and curly, are the same on both sides`() {
        child("Luca", "D’Amico")
        child("Sara", "D'Angelo")

        for (text in listOf("Luca D'Amico", "Luca D’Amico", "d’amico luca", "Sara D’Angelo", "Sara D'Angelo")) assertTrue(contains(text), text)
        assertFalse(contains("Luca DAmico"))
    }

    @Test
    fun `t16 c13 punctuation and symbols in the name of the registration do not break the comparison`() {
        child("Anne-Marie", "Rossi (Bianchi)")
        child("J.R.", "Smith")

        assertTrue(contains("Anne-Marie Rossi (Bianchi)"))
        assertTrue(contains("anne-marie rossi (bianchi)"))
        assertTrue(contains("la squadra di J.R. Smith"))
        assertFalse(contains("Anne Marie Rossi Bianchi"), "the hyphen and the brackets are in the name, the text has none")
    }

    @Test
    fun `t16 c14 with no child and no teacher nothing is found, and the same for a child that was deleted`() {
        assertFalse(contains("Luca Rossi"))
        val luca = child("Luca", "Rossi")
        assertTrue(contains("Luca Rossi"))

        dsl.deleteFrom(CHILD).where(CHILD.ID.eq(luca)).execute()

        assertFalse(contains("Luca Rossi"), "a child that is not there any more does not count")
    }

    @Test
    fun `t16 c14 the child of a past gathering and the one of the paper form with no account count, whatever the gathering of the text`() {
        // A child with no account and no registration at all: what the query reads is the table of the children, as it is when the draft of a new gathering is written.
        child("Luca", "Rossi")

        assertTrue(contains("Convivenza di Luca Rossi"))
    }

    @Test
    fun `t16 c16 one question for all the texts answers yes or no for each of them, in the order of the texts, and nothing else`() {
        child("Luca", "Rossi")
        teacher("Marta", "Bianchi")

        val answers = names.contain(listOf("Convivenza di settembre 2027", "Luca Rossi", "", "chiedi a Bianchi Marta", "Rossi"))

        assertEquals(listOf(false, true, false, true, false), answers)
        assertEquals(emptyList(), names.contain(emptyList()))
        assertEquals(listOf(true), names.contain(listOf("Luca Rossi")))
        assertEquals(listOf(false, false), names.contain(listOf("", " ")))
    }

    @Test
    fun `t16 c16 a text with the characters of a regular expression or of a pattern of SQL is only text`() {
        child("Luca", "Rossi")

        val strange = listOf("Luca Rossi\\", "%Luca Rossi%", "(Luca|Rossi)", "[Luca Rossi]", "Luca.*Rossi", "Luca _ Rossi", "' or 1=1 --", "\${Luca Rossi}")

        assertEquals(listOf(true, true, false, true, false, false, false, true), names.contain(strange))
    }
}
