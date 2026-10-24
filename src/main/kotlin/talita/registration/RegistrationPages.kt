package talita.registration

import org.springframework.stereotype.Component
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.retention.RetentionTexts
import talita.ui.Alert
import talita.ClockConfiguration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One way to pick a kind of day for a day: the value that is sent and the words on the segment. */
data class KindOption(val code: String, val label: String)

/** A day of the gathering with the kinds of day that the fees allow (the segments under «Alcuni giorni»). */
data class DayRow(val key: String, val label: String, val kinds: List<KindOption>)

/**
 * What a gathering offers as «Quando viene?» (criterion 4), with the sentences of the design: it is the same for every child of a form, because
 * it comes from the fees. A fee that is not set gives no segment, and with the full fee alone there is nothing to choose.
 */
data class OfferView(
    val oneDay: Boolean,
    val choice: Boolean,
    val allDaysLabel: String,
    val allDaysHelp: String,
    val someDays: Boolean,
    val someDaysHelp: String,
    val daysHelp: String,
    val dayRows: List<DayRow>,
    val lunchOnly: Boolean,
    val lunchHelp: String,
) {
    /**
     * The names of the fields that a request for the quota carries (`hx-params`): the presence and the days of each block in [indexes], the fields in
     * [more] and the CSRF code; and, only when there are two blocks or more, the name and the surname of each one, because the card «Quota» of the first
     * step has a line for every child with their name (design A 1/2, 4). htmx adds to a POST every field of the form around the element, so without this
     * list the date of birth, the phone and the notes on health of the children, and the name of a single child, would travel at every touch (criterion 5).
     */
    fun quotaFields(indexes: List<Int>, more: List<String> = emptyList()): String =
        (
            indexes.flatMap { i ->
                listOf("children[$i].presence") + dayRows.map { "children[$i].day.${it.key}" } + if (indexes.size > 1) listOf("children[$i].firstName", "children[$i].lastName") else emptyList()
            } + more + CSRF_FIELD
        ).joinToString(",")

    companion object {
        /** The name of the field of the CSRF code of every form (`components/csrf.jte` writes `CsrfToken.getParameterName()`, which is this one). */
        const val CSRF_FIELD = "_csrf"
    }
}

/** A block of a child for a form: what the person typed, the error under each field, the quota that is due now and what the block does. */
data class ChildView(
    val index: Int,
    val number: Int,
    val knownId: Long?,
    val firstName: String,
    val lastName: String,
    val birthDay: String,
    val birthMonth: String,
    val birthYear: String,
    val presence: String,
    val days: Map<String, String>,
    val status: String,
    val allergies: String,
    val intolerances: String,
    val medications: String,
    val healthConsent: Boolean,
    val errors: Map<String, String>,
    val taken: Boolean,
    val quota: String?,
    /** The block of the notes on health is open: there is something written or an error in it. */
    val healthOpen: Boolean,
    val healthWritten: Boolean,
    val canRemove: Boolean,
    /** The first field of this block takes the focus when the block comes in (a new one) or stays (the one before a removed one). */
    val focus: Boolean,
) {
    /** The name that the pages use for the block: the name that was written, or «Bambino 2». */
    val label: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Bambino $number" }
}

/** A child of the account that can be added again, with the words of the row of «I tuoi figli». */
data class KnownView(val id: Long, val name: String, val initials: String, val birth: String)

/** The card «Quota» of two or more children: a line for each child that has a quota now, and the total. */
data class TotalView(val lines: List<TotalLine>, val total: String)

data class TotalLine(val name: String, val amount: String)

/** The first step, and the piece of it that every action of the blocks replaces (`#figli`). */
data class FirstStepPage(
    val code: String,
    val title: String,
    val dates: String,
    val phone: String,
    val phoneError: String?,
    val children: List<ChildView>,
    val known: List<KnownView>,
    val canAdd: Boolean,
    val offer: OfferView,
    val total: TotalView?,
    val alert: Alert?,
    val formAction: String,
    val childrenAction: String,
    val quotaAction: String,
    val cancelHref: String,
    /** The page has a phone that comes already written («Iscrivi un altro figlio»): the help says it is the same for all of them. */
    val phoneKnown: Boolean = false,
) {
    /** What a request for the quota carries: the days of every block, because the total adds them up. */
    val quotaFields: String get() = offer.quotaFields(children.map { it.index })
}

/** What the second step says of a child: the words of the recap. */
data class RecapChild(val name: String, val status: String, val statusTone: String, val birth: String, val presence: String, val health: String, val quota: String)

/**
 * The short notice of step 2: who the controller is and how to reach them (`talita.privacy.*`), how long the data are kept ([time]) and the rights ([rights]): the two
 * last come from `RetentionTexts`, that is from the periods of the policy, and nowhere else (TALITA-7, criterion 32).
 */
data class ShortNotice(val controller: String, val contact: String, val time: String, val rights: String)

/** The second step: recap, short notice, the text of the custody, the three boxes. [hidden] carries the data of the first step (a second step saves nothing). */
data class SecondStepPage(
    val code: String,
    val title: String,
    val dates: String,
    val children: List<RecapChild>,
    val phone: String,
    val total: String,
    val notice: ShortNotice,
    val custodyBody: String,
    val declaration: String,
    val hash: String,
    val hidden: List<Pair<String, String>>,
    val plural: Boolean,
    val accept: Boolean,
    val other: Boolean,
    val religion: Boolean,
    val errors: Map<String, String>,
    val alert: Alert?,
    val confirmAction: String,
    val backAction: String,
)

/** The page of a registration that can no longer be made: the registrations are closed or the gathering is over. */
data class ClosedPage(val over: Boolean, val infoHref: String)

/**
 * Builds what the pages of the first and of the second step show, from the gathering and from what the person wrote. The sentences are those of
 * the design (A 1/2 and A 2/2 of TALITA-5); the quota of a block comes from [Quota] and from nowhere else.
 */
@Component
class RegistrationPresenter(private val privacy: PrivacySettings, private val texts: RetentionTexts) {

    fun offer(gathering: GatheringInfo): Offer = Offer(gathering.fees, gathering.days)

    fun offerView(offer: Offer): OfferView {
        val fees = offer.fees
        val money = { cents: Int -> RegistrationFormat.money(cents) }
        val full = fees.fullCents?.let(money).orEmpty()
        val kinds = offer.kinds.map { if (it == DayKind.FULL) KindOption("full", "Giornata") else KindOption("lunch", "Solo pranzo") }
        val someHelp = listOfNotNull(
            fees.dailyCents?.let { "${money(it)} per ogni giornata" }.takeIf { DayKind.FULL in offer.kinds },
            fees.lunchOnlyCents?.let { "${money(it)} per ogni solo pranzo" }.takeIf { DayKind.LUNCH in offer.kinds },
        ).joinToString(", ") + "."
        val names = offer.kinds.joinToString(" o ") { if (it == DayKind.FULL) "«Giornata»" else "«Solo pranzo»" }
        return OfferView(
            oneDay = offer.oneDay,
            choice = offer.choice,
            allDaysLabel = if (offer.oneDay) "Tutto il giorno" else "Tutti i giorni",
            allDaysHelp = GatheringFormat.days(offer.days) + " · " + full,
            someDays = offer.someDays,
            someDaysHelp = someHelp,
            daysHelp = "Per ogni giorno scegli $names. Se non viene, lascia «Non viene».",
            dayRows = offer.days.map { DayRow(Quota.key(it), RegistrationFormat.dayLabel(it), kinds) },
            lunchOnly = offer.lunchOnlyOneDay,
            lunchHelp = fees.lunchOnlyCents?.let(money).orEmpty(),
        )
    }

    /** The quota that is due for a block as it stands now, or null while there is no valid choice of days. */
    fun quota(offer: Offer, input: ChildInput): Int? = Quota.parse(offer, input.presence) { key -> input.days[key].orEmpty() }.first?.let { Quota.due(offer, it) }

    fun childView(index: Int, count: Int, input: ChildInput, check: ChildCheck?, offer: Offer, focus: Boolean = false, withHealth: Boolean = true): ChildView {
        val errors = check?.errors.orEmpty()
        val written = withHealth && listOf(input.allergies, input.intolerances, input.medications).any { it.isNotBlank() }
        return ChildView(
            index = index,
            number = index + 1,
            knownId = input.knownId,
            firstName = input.firstName,
            lastName = input.lastName,
            birthDay = input.birthDay,
            birthMonth = input.birthMonth,
            birthYear = input.birthYear,
            presence = input.presence,
            days = input.days,
            status = input.status,
            allergies = input.allergies,
            intolerances = input.intolerances,
            medications = input.medications,
            healthConsent = input.healthConsent,
            errors = errors,
            taken = check?.taken ?: false,
            quota = quota(offer, input)?.let(RegistrationFormat::money),
            healthOpen = written || listOf("allergies", "intolerances", "medications", "consent").any { it in errors },
            healthWritten = written,
            canRemove = count > 1,
            focus = focus,
        )
    }

    /** The card «Quota» when there are two blocks or more with a quota. */
    fun total(offer: Offer, children: List<ChildInput>): TotalView? {
        if (children.size < 2) return null
        val lines = children.mapIndexedNotNull { index, input ->
            quota(offer, input)?.let { index to it }
        }
        if (lines.isEmpty()) return null
        return TotalView(
            lines.map { (index, cents) ->
                TotalLine(listOf(children[index].firstName, children[index].lastName).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Bambino ${index + 1}" }, RegistrationFormat.money(cents))
            },
            RegistrationFormat.money(lines.sumOf { it.second }),
        )
    }

    /** The first step: [check] has the errors of a form that was sent, null for a form not sent yet. [focus] is the block that takes the focus. */
    fun firstStep(
        code: String,
        gathering: GatheringInfo,
        form: FormInput,
        check: FormCheck?,
        known: List<KnownChild>,
        alert: Alert?,
        focus: Int? = null,
        phoneKnown: Boolean = false,
        formAction: String = "/info/$code/iscrizione",
    ): FirstStepPage {
        val offer = offer(gathering)
        val shown = known.filter { child -> form.children.none { it.knownId == child.id } }
        return FirstStepPage(
            code = code,
            title = gathering.title,
            dates = GatheringFormat.range(gathering.start, gathering.end),
            phone = form.phone,
            phoneError = check?.phoneError,
            children = form.children.mapIndexed { index, input -> childView(index, form.children.size, input, check?.children?.getOrNull(index), offer, focus == index) },
            known = shown.map { KnownView(it.id, it.name, RegistrationFormat.initials(it.firstName, it.lastName), RegistrationFormat.birth(it.birthDate)) },
            canAdd = form.children.size < FormParser.MAX_CHILDREN,
            offer = offerView(offer),
            total = total(offer, form.children),
            alert = alert,
            formAction = formAction,
            childrenAction = "$formAction/figli",
            quotaAction = "$formAction/quota",
            cancelHref = "/info/$code",
            phoneKnown = phoneKnown,
        )
    }

    /** The second step for a form that is good ([check]); [accept], [other] and [religion] are the boxes as they were, [errors] the ones that are wrong. */
    fun secondStep(
        code: String,
        gathering: GatheringInfo,
        check: FormCheck,
        text: CustodyText.Rendered,
        accept: Boolean,
        other: Boolean,
        religion: Boolean,
        errors: Map<String, String>,
        alert: Alert?,
    ): SecondStepPage {
        val recap = check.children.map { block ->
            RecapChild(
                name = block.name,
                status = block.status!!.label,
                statusTone = if (block.status == Status.CONFIRMED) "positive" else "warning",
                birth = RegistrationFormat.birth(block.birthDate!!),
                presence = RegistrationFormat.presence(block.presence!!, gathering.days),
                health = if (block.notes != null) "Scritte, con il tuo consenso" else "Nessuna",
                quota = RegistrationFormat.money(block.dueCents!!),
            )
        }
        return SecondStepPage(
            code = code,
            title = gathering.title,
            dates = GatheringFormat.range(gathering.start, gathering.end),
            children = recap,
            phone = check.phone,
            total = RegistrationFormat.money(check.children.sumOf { it.dueCents!! }),
            notice = ShortNotice(privacy.controllerName, privacy.contact, texts.briefTime(), texts.briefRights),
            custodyBody = text.body,
            declaration = text.declaration,
            hash = CustodyText.hash(text.full),
            hidden = check.input.fields(),
            plural = check.children.size > 1,
            accept = accept,
            other = other,
            religion = religion,
            errors = errors,
            alert = alert,
            confirmAction = "/info/$code/iscrizione/conferma",
            backAction = "/info/$code/iscrizione/indietro",
        )
    }
}

/**
 * How the registrations say what they say, the same in every page of the parent and of the teachers (design guide, 6): the amounts, the moments,
 * the days of presence, the ages. Dates and amounts come from [GatheringFormat]; nothing is written by hand in a template.
 */
object RegistrationFormat {

    private val locale: Locale = Locale.ITALIAN
    private val time = DateTimeFormatter.ofPattern("d MMMM uuuu 'alle' HH:mm", locale).withZone(ClockConfiguration.ZONE)
    private val dayOfMonth = DateTimeFormatter.ofPattern("d", locale)
    private val dayAndMonth = DateTimeFormatter.ofPattern("d MMMM", locale)

    /** `60 €`, with a space that does not break before the euro sign, so that an amount is never split between two lines; `Gratuita` for zero. */
    fun money(cents: Int): String = GatheringFormat.fee(cents).replace(' ', ' ')

    /** `12 giugno 2027 alle 21:30`, the hour of Rome. */
    fun moment(at: OffsetDateTime): String = time.format(at)

    private val dayOnly = DateTimeFormatter.ofPattern("d MMMM uuuu", locale).withZone(ClockConfiguration.ZONE)

    /** `12 giugno 2027`: the day of a moment, in Rome. */
    fun day(at: OffsetDateTime): String = dayOnly.format(at)

    /** The first letter in capitals: `venerdì` becomes `Venerdì`. */
    fun capital(text: String): String = text.replaceFirstChar { it.uppercase() }

    /** `Venerdì 17`: how a day is named in the choice of the days of a form. */
    fun dayLabel(day: LocalDate): String = capital(GatheringFormat.weekday(day)) + " " + dayOfMonth.format(day)

    /** `4 aprile 2016`. */
    fun birth(date: LocalDate): String = GatheringFormat.date(date)

    /** The age in whole years on [on] (the first day of the gathering: the age that the child will have when they arrive). */
    fun age(birth: LocalDate, on: LocalDate): Int = Period.between(birth, on).years

    fun ageText(birth: LocalDate, on: LocalDate): String = age(birth, on).let { if (it == 1) "1 anno" else "$it anni" }

    /** `sabato 18` for days of one month, `sabato 18 settembre` when they are not all in the same one. */
    private fun short(days: List<LocalDate>): List<String> {
        val sameMonth = days.all { it.month == days.first().month && it.year == days.first().year }
        return days.map { GatheringFormat.weekday(it) + " " + (if (sameMonth) dayOfMonth else dayAndMonth).format(it) }
    }

    private fun list(items: List<String>): String = if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " e " + items.last()

    /**
     * When the child comes, as the pages write it: `Tutti i giorni (17–19 settembre 2027)` (`Tutto il giorno (18 settembre 2027)` for a gathering of
     * one day), or `Giornata: sabato 18. Solo pranzo: domenica 19.` with the days of each kind in order.
     */
    fun presence(presence: Presence, gatheringDays: List<LocalDate>): String {
        if (presence.allDays) {
            val range = GatheringFormat.range(gatheringDays.first(), gatheringDays.last())
            return (if (gatheringDays.size == 1) "Tutto il giorno" else "Tutti i giorni") + " ($range)"
        }
        return DayKind.entries.mapNotNull { kind ->
            val days = presence.days.filterValues { it == kind }.keys.sorted()
            if (days.isEmpty()) null else (if (kind == DayKind.FULL) "Giornata" else "Solo pranzo") + ": " + list(short(days)) + "."
        }.joinToString(" ")
    }

    /** The same for a row of a list: `Tutti i giorni`, `Sab 18, Dom 19`, `Sab 18 · pranzo Dom 19`. */
    fun presenceShort(presence: Presence): String {
        if (presence.allDays) return "Tutti i giorni"
        fun abbreviated(days: Collection<LocalDate>) = days.sorted().joinToString(", ") { capital(GatheringFormat.weekday(it).take(3)) + " " + dayOfMonth.format(it) }
        val full = presence.days.filterValues { it == DayKind.FULL }.keys
        val lunch = presence.days.filterValues { it == DayKind.LUNCH }.keys
        return listOfNotNull(abbreviated(full).takeIf { full.isNotEmpty() }, ("pranzo " + abbreviated(lunch)).takeIf { lunch.isNotEmpty() }).joinToString(" · ")
    }

    /** The two initials in the circle of a row. */
    fun initials(first: String, last: String): String = (first.take(1) + last.take(1)).uppercase()

    /** The digits of a phone with the sign, for a `tel:` link. */
    fun telephone(phone: String): String = phone.filter { it.isDigit() || it == '+' }
}
