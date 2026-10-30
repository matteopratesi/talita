package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.account.TestAccounts
import talita.gathering.GatheringFormat
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.TEACHER
import talita.registration.Notes
import talita.retention.RetentionPolicy
import talita.retention.RetentionTexts
import java.time.LocalDate
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the teachers and everybody see of the retention, in a real browser, on a phone and on a desktop, with axe (criteria 23, 24, 29-32, 34): the card «Conservazione dei
 * dati» (before and after the dates), the notes that were deleted, the registrations that were deleted (an empty state that says when and why), the profile of a teacher
 * with the dialog of the account and with what blocks, and the complete notice. No page scrolls sideways at 320 px. Names, dates and numbers are synthetic.
 */
@E2eApplication
class RetentionE2eTest {

    private val policy = RetentionPolicy.STANDARD

    /** The person of the test stays a teacher («Elisa Conti»): the gathering is made with Marta Bianchi and Paolo Verdi as custodians. */
    private fun E2e.gathering(): OpenGathering {
        val gathering = openGathering()
        TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        return gathering
    }

    /** The gathering ended [daysAgo] days ago and is closed: the notes and the registrations are past their dates or not, as the number says. */
    private fun E2e.endedDaysAgo(daysAgo: Long): OpenGathering {
        val gathering = gathering()
        val today = today()
        dsl().update(GATHERING).set(GATHERING.START_DATE, today.minusDays(daysAgo + 2)).set(GATHERING.END_DATE, today.minusDays(daysAgo)).set(GATHERING.STATUS, GatheringStatus.closed)
            .where(GATHERING.ID.eq(gathering.id)).execute()
        return gathering
    }

    private fun E2e.end(gathering: OpenGathering): LocalDate = dsl().select(GATHERING.END_DATE).from(GATHERING).where(GATHERING.ID.eq(gathering.id)).fetchSingle(GATHERING.END_DATE)!!

    private fun E2e.notesDate(gathering: OpenGathering): String = GatheringFormat.date(policy.notesDeletionDate(end(gathering)))

    private fun E2e.registrationsDate(gathering: OpenGathering): String = GatheringFormat.date(policy.registrationsDeletionDate(end(gathering)))

    private fun E2e.parent(): Long = someParent()

    private fun com.microsoft.playwright.Page.fits320(profileIsPhone: Boolean) {
        assertTrue(noHorizontalScroll())
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "no sideways scrolling at 320 px")
        setViewportSize(if (profileIsPhone) 360 else 1280, 800)
    }

    // ---- «Conservazione dei dati» (criterion 29) ------------------------------------------------------------------------------------------

    @E2eTest
    fun `c29 the detail of the gathering has the card of the retention, with the dates and the reasons and no button`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()

        e2e.open("/convivenza/${gathering.id}")

        val card = page.locator("section[aria-labelledby=conservazione]")
        assertThat(card.locator("h2")).hasText("Conservazione dei dati")
        assertThat(card).containsText("Talità cancella da sola i dati di questa convivenza, ogni notte. Qui vedi quando.")
        assertThat(card).containsText("Si cancellano il ${e2e.notesDate(gathering)}")
        assertThat(card).containsText("Un mese dopo la fine della convivenza, perché servono se durante l’evento succede qualcosa.")
        assertThat(card).containsText("Si cancellano il ${e2e.registrationsDate(gathering)}")
        assertThat(card).containsText("Un anno dopo la fine della convivenza: servono come prova dell’affidamento. Vanno via anche le presenze, i pagamenti e il registro delle letture delle segnalazioni.")
        assertThat(card).containsText("Da distruggere a partire dal ${e2e.registrationsDate(gathering)}")
        assertThat(card).containsText("Le date seguono la fine della convivenza: se la cambi, cambiano anche queste.")
        assertThat(card.locator("a, button, form")).hasCount(0)
        assertThat(page.locator("main h2:visible").last()).hasText("Conservazione dei dati")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "detail with the card of the retention (${e2e.profile.label})")
    }

    @E2eTest
    fun `c29 after the dates the card says Cancellate, the notes first and the registrations a year later`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.endedDaysAgo(40)

        e2e.open("/convivenza/${gathering.id}")

        val card = page.locator("section[aria-labelledby=conservazione]")
        assertThat(card).containsText("Cancellate il ${e2e.notesDate(gathering)}")
        assertThat(card).containsText("Si cancellano il ${e2e.registrationsDate(gathering)}")
        assertThat(card).not().containsText("Le date seguono la fine della convivenza")
        assertAccessible(page, "card of the retention after the notes (${e2e.profile.label})")

        val older = e2e.endedDaysAgo(400)
        e2e.open("/convivenza/${older.id}")
        val past = page.locator("section[aria-labelledby=conservazione]")
        assertThat(past).containsText("Cancellate il ${e2e.registrationsDate(older)}")
        assertThat(page.locator("#iscrizioni").locator("..")).containsText("Cancellate il ${e2e.registrationsDate(older)}")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "card of the retention after the registrations (${e2e.profile.label})")
    }

    @E2eTest
    fun `c10 c29 a draft has no card of the retention`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering(status = GatheringStatus.draft)
        TestAccounts.makeTeacher(e2e.dsl(), checkNotNull(e2e.signedInAs), "Elisa", "Conti")

        e2e.open("/convivenza/${gathering.id}")

        assertThat(page.locator("section[aria-labelledby=conservazione]")).hasCount(0)
        assertThat(page.locator("main")).not().containsText("Conservazione dei dati")
    }

    // ---- The notes that were deleted (criterion 30) -------------------------------------------------------------------------------------------

    @E2eTest
    fun `c30 the detail and the card of Arrivi say that the notes were deleted, with no button, and nothing is written in the register of the readings`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.endedDaysAgo(40)
        val id = e2e.rows().register(gathering.id, e2e.parent(), "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val readings = e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS)
        val main = "Segnalazioni cancellate il ${e2e.notesDate(gathering)}, un mese dopo la fine."
        val second = "Talità le cancella da sola, per tutte le iscrizioni. Il registro delle letture resta fino al ${e2e.registrationsDate(gathering)}."

        e2e.open("/iscrizioni/$id")

        val card = page.locator("#segnalazioni")
        assertThat(card).containsText(main)
        assertThat(card).containsText(second)
        assertThat(card.locator("button, form, a")).hasCount(0)
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertThat(page.locator("#letture")).isVisible()
        assertThat(page.locator("#letture")).containsText("Nessuno le ha lette.")
        assertThat(page.locator("#letture")).not().containsText("ancora")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "detail with the notes deleted (${e2e.profile.label})")

        e2e.open("/arrivi/$id")
        val arrivals = page.locator("#segnalazioni")
        assertThat(arrivals).containsText(main)
        assertThat(arrivals).containsText(second)
        assertThat(arrivals.locator("button, form")).hasCount(0)
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "card of Arrivi with the notes deleted (${e2e.profile.label})")
        assertEquals(readings, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS), "no reading was written")
    }

    // ---- The registrations that were deleted (criterion 31) -------------------------------------------------------------------------------------

    @E2eTest
    fun `c31 Iscrizioni and Arrivi and the prints say when and why the registrations were deleted, and lead to the gathering`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.endedDaysAgo(400)
        val date = e2e.registrationsDate(gathering)

        e2e.open("/iscrizioni?convivenza=${gathering.id}")
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.locator("main h2:visible")).hasText("Iscrizioni cancellate")
        assertThat(page.locator("main")).containsText("Le iscrizioni di questa convivenza, con le manleve, sono state cancellate il $date, un anno dopo la fine: servivano come prova dell’affidamento.")
        assertThat(page.locator("main")).containsText("Restano le date e le informazioni della convivenza, senza nomi.")
        assertThat(page.locator("main").linkNamed("Inserisci un’iscrizione")).hasCount(0)
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "registrations deleted (${e2e.profile.label})")

        for (path in listOf("/arrivi", "/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            e2e.open(path)
            assertThat(page.locator("h1")).hasText("Arrivi")
            assertThat(page.locator("main h2:visible")).hasText("Arrivi cancellati")
            assertThat(page.locator("main")).containsText("Gli arrivi, le presenze e i pagamenti di questa convivenza sono stati cancellati il $date, un anno dopo la fine: servivano come registro e come prova dell’affidamento.")
            assertThat(page.locator("main").buttonNamed("Stampa")).hasCount(0)
        }
        e2e.open("/arrivi")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "arrivals deleted (${e2e.profile.label})")

        e2e.touch(page.locator("main").linkNamed("Vai alla convivenza"))
        assertThat(page).hasURL(e2e.url("/convivenza/${gathering.id}"))
        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2027")
    }

    // ---- The profile of a teacher (criteria 23, 24) ---------------------------------------------------------------------------------------------

    @E2eTest
    fun `c23 the profile of a teacher has the data, and the dialog of the account says that the address stays for the readings and what was marked`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.endedDaysAgo(3)
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        val id = e2e.rows().register(gathering.id, e2e.parent(), "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        e2e.dsl().insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, id).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, me).set(HEALTH_NOTE_ACCESS.ACCESSED_AT, OffsetDateTime.now()).execute()
        e2e.dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, id).set(CHECKIN.DAY, e2e.end(gathering)).set(CHECKIN.RECORDED_AT, OffsetDateTime.now()).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, me).execute()

        e2e.open("/profilo")

        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Il tuo accesso", "Il tuo nome", "I tuoi dati"))
        assertThat(page.locator("main")).containsText("Qui puoi cancellare il tuo account.")
        assertThat(page.locator("main")).containsText("Cancelliamo il tuo indirizzo email e il tuo nome. Non sarai più tra i maestri")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "profile of a teacher (${e2e.profile.label})")

        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Il tuo accesso a Talità.")
        assertThat(dialog).containsText("Il tuo nome e cognome dall’elenco dei maestri: non gestirai più la convivenza né vedrai i bambini.")
        assertThat(dialog).containsText("Il tuo indirizzo email, senza nome: resta fino al ${e2e.registrationsDate(gathering)}. Nel registro delle letture c’è scritto che hai letto le segnalazioni di alcuni bambini")
        assertThat(dialog).containsText("Le presenze, i pagamenti e le iscrizioni dal modulo cartaceo che hai segnato: restano fino al ${e2e.registrationsDate(gathering)}, senza il tuo nome.")
        assertThat(dialog).containsText("Si leggerà «un maestro che non è più tra i maestri».")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertAccessible(page, "dialog of the account of a teacher (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Annulla"))
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @E2eTest
    fun `c24 a teacher who is a custodian of a gathering that is not finished is told to be taken away from the custodians first, with the link`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.dsl().insertInto(GATHERING_CUSTODIAN).set(GATHERING_CUSTODIAN.GATHERING_ID, gathering.id).set(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, me).execute()

        e2e.open("/profilo")

        assertThat(page.locator("main")).containsText("Per cancellare il tuo account, prima:")
        assertThat(page.locator("main")).containsText("togliti dagli affidatari della «Convivenza di settembre 2027».")
        assertThat(page.locator("main").linkNamed("Cancella il mio account")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Togliti dagli affidatari")).hasAttribute("href", "/convivenza/${gathering.id}/affidatari")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "profile of a teacher with a block (${e2e.profile.label})")

        e2e.touch(page.locator("main").linkNamed("Togliti dagli affidatari"))
        assertThat(page).hasURL(e2e.url("/convivenza/${gathering.id}/affidatari"))
    }

    // ---- The complete notice (criterion 32) -------------------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c32 the complete notice has the card with the times, without a term that is left to decide, and the anchor of the profile leads to it`(e2e: E2e) {
        val page = e2e.page

        e2e.open("/informativa#per-quanto-tempo")

        assertThat(page.locator("h1")).hasText("Informativa sulla privacy")
        val card = page.locator("#per-quanto-tempo")
        assertThat(card.locator("h2")).hasText("Per quanto tempo")
        assertThat(card).containsText("Talità cancella i dati da sola, ogni notte, quando il loro tempo è scaduto.")
        assertThat(card).containsText("Un mese dopo la fine della convivenza. Le puoi togliere prima, quando vuoi.")
        assertThat(card).containsText("purché siano passati 30 giorni da quando l’hai creato")
        assertThat(card).containsText("Un invito non accettato si cancella 30 giorni dopo la scadenza.")
        assertThat(card).containsText("Al massimo 30 giorni, cifrate. Un dato cancellato sparisce anche dalle copie entro questo tempo.")
        assertThat(page.locator("main")).not().containsText("Da fissare con TALITA-7")
        assertThat(page.locator("#diritti")).hasText("I tuoi diritti")
        assertThat(page.locator("main")).containsText("Dal «Profilo» cancelli i dati dei tuoi figli o il tuo account")
        assertFalse(page.locator("script:not([src])").count() > 0, "no inline script")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "complete notice (${e2e.profile.label})")
    }

    // ---- Added by the QA of TALITA-7 ---------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `c29 before the end the dates of the card follow the end of the gathering, when it changes`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.gathering()
        val card = page.locator("section[aria-labelledby=conservazione]")
        val notesBefore = e2e.notesDate(gathering)
        val registrationsBefore = e2e.registrationsDate(gathering)
        e2e.open("/convivenza/${gathering.id}")
        assertThat(card).containsText("Si cancellano il $notesBefore")
        assertThat(card).containsText("Si cancellano il $registrationsBefore")

        // The gathering lasts two days more: the dates follow it (the card says it).
        e2e.dsl().update(GATHERING).set(GATHERING.END_DATE, e2e.end(gathering).plusDays(2)).where(GATHERING.ID.eq(gathering.id)).execute()
        val notesAfter = e2e.notesDate(gathering)
        val registrationsAfter = e2e.registrationsDate(gathering)
        assertNotEquals(notesBefore, notesAfter)
        e2e.open("/convivenza/${gathering.id}")
        assertThat(card).containsText("Si cancellano il $notesAfter")
        assertThat(card).containsText("Si cancellano il $registrationsAfter")
        assertThat(card).not().containsText("Si cancellano il $notesBefore")
        assertThat(card).not().containsText("Si cancellano il $registrationsBefore")
    }

    @E2eTest(teacher = false)
    fun `c20 the card says until when the registration is kept, and from its date the registration is not there any more, before the job has run`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.rows().register(gathering.id, me, "Luca", "Rossi")
        val today = e2e.today()
        fun endsOn(end: LocalDate) {
            e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, end.minusDays(2)).set(GATHERING.END_DATE, end).set(GATHERING.STATUS, GatheringStatus.closed)
                .where(GATHERING.ID.eq(gathering.id)).execute()
        }

        // A few days before the date.
        endsOn(today.minusYears(1).plusDays(3))
        assertFalse(policy.registrationsExpired(e2e.end(gathering), today))
        e2e.open("/iscrizioni")
        assertThat(page.locator("article").first()).containsText("Conservata fino al ${e2e.registrationsDate(gathering)} come prova dell’affidamento")
        assertThat(page.locator("main")).not().containsText("Nessuna iscrizione")

        // A few days after: the job has not run (the tests of the application have it off), and the page does not show the registration all the same.
        endsOn(today.minusYears(1).minusDays(3))
        assertTrue(policy.registrationsExpired(e2e.end(gathering), today))
        e2e.open("/iscrizioni")
        assertThat(page.locator("main")).containsText("Nessuna iscrizione")
        assertThat(page.locator("main")).not().containsText("Luca")
        assertThat(page.locator("main")).not().containsText("Conservata fino al")
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "registrations of the parent after the date (${e2e.profile.label})")
    }

    @E2eTest
    fun `c24 the only teacher is told to invite another one before deleting the account, and a request by hand changes nothing and says the same`(e2e: E2e) {
        val page = e2e.page
        e2e.gathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.dsl().deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.ne(me)).execute()
        val requests = e2e.dsl().fetchCount(RETENTION_LOG)

        e2e.open("/profilo")

        assertThat(page.locator("main")).containsText("Per cancellare il tuo account, prima:")
        assertThat(page.locator("main")).containsText("invita un altro maestro: ora sei l’unico.")
        assertThat(page.locator("main").linkNamed("Vai a Maestri")).hasAttribute("href", "/maestri")
        assertThat(page.locator("main").linkNamed("Cancella il mio account")).hasCount(0)
        assertThat(page.locator("dialog[id^=cancella]")).hasCount(0)
        page.fits320(e2e.profile.isPhone)
        assertAccessible(page, "profile of the only teacher (${e2e.profile.label})")

        // By hand: the same, with the notice of refusal, and the account is as it was.
        val csrf = page.locator("input[name=_csrf]").first().inputValue()
        val post = page.request().post(e2e.url("/profilo/account/cancella"), RequestOptions.create().setForm(FormData.create().set("_csrf", csrf)).setMaxRedirects(0))
        assertEquals(409, post.status())
        assertTrue(post.text().contains("Non è stato cancellato niente. Le cose da fare prima sono scritte qui sotto."))
        assertTrue(post.text().contains("invita un altro maestro: ora sei l’unico."))
        assertTrue(e2e.dsl().fetchExists(ACCOUNT, ACCOUNT.ID.eq(me)))
        assertTrue(e2e.dsl().fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(me)))
        assertEquals(requests, e2e.dsl().fetchCount(RETENTION_LOG), "nothing in the register")

        e2e.touch(page.locator("main").linkNamed("Vai a Maestri"))
        assertThat(page).hasURL(e2e.url("/maestri"))
    }

    @E2eTest
    fun `c25 c26 a teacher who deletes the account is no longer a teacher, and the same address comes back to an empty account with no section of the teachers`(e2e: E2e) {
        val page = e2e.page
        e2e.gathering()
        val email = checkNotNull(e2e.signedInAs)
        val me = e2e.accountId(email)
        // A link asked before the deletion and not used: it is still good afterwards, and opens a new account.
        val link = e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue
        val teachers = e2e.dsl().fetchCount(TEACHER)

        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Il tuo nome e cognome dall’elenco dei maestri: non gestirai più la convivenza né vedrai i bambini.")
        assertThat(dialog).containsText("Non resta niente.")
        e2e.touch(dialog.buttonNamed("Cancella il mio account"))

        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("[role=status]")).containsText("Account cancellato.")
        assertFalse(e2e.dsl().fetchExists(ACCOUNT, ACCOUNT.ID.eq(me)), "the account is gone")
        assertFalse(e2e.dsl().fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(me)), "and so is the teacher")
        assertEquals(teachers - 1, e2e.dsl().fetchCount(TEACHER), "the other teachers are still there")

        // The same address comes back: an account that is new and empty, a parent, with nothing of the teachers.
        page.navigate(e2e.url("/accedi/conferma?token=$link"))
        page.waitForSettledContent()
        e2e.touch(page.buttonNamed("Entra"))
        page.waitForURL(e2e.url("/"))
        assertThat(page.mainNavigation().locator("a")).hasText(arrayOf("Home", "Iscrizioni"))
        assertTrue(e2e.accountId(email) != me, "a new account")
        assertFalse(TestAccounts.isTeacher(e2e.dsl(), email), "not a teacher")
        assertEquals(403, e2e.open("/convivenza").status())
        assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
        assertEquals(403, e2e.open("/arrivi").status())
        e2e.open("/profilo")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Il tuo accesso", "I tuoi dati"))
        assertThat(page.locator("main")).not().containsText("Il tuo nome")
    }

    @E2eTest(teacher = false)
    fun `c32 step 2 of the registration has the short notice, with the times and the rights of the policy`(e2e: E2e) {
        val page = e2e.page
        val code = e2e.openGathering().code
        e2e.open("/info/$code/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.fillBlock(0, "Luca", "Rossi", "4", "4", "2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        assertThat(page.locator("h1")).hasText("Controlla e accetta")

        val texts = RetentionTexts(policy)
        assertThat(page.locator("main")).containsText(texts.briefTime())
        assertThat(page.locator("main")).containsText(texts.briefRights)
        assertThat(page.locator("main")).containsText("30 giorni")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "step 2 with the short notice (${e2e.profile.label})")
    }
}
