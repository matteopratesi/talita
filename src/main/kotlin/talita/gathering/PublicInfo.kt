package talita.gathering

import org.springframework.stereotype.Component
import talita.account.PublicUrl
import talita.ui.Share
import java.time.LocalDate

/** Which page a person sees: with the registrations open, with them closed, or about a gathering that is over. */
enum class InfoVariant { OPEN, CLOSED, OVER }

/** A line of «Quanto costa»: the label and the amount as it is written («60 €», «25 € per ogni giorno», «Gratuita»). */
data class PublicFee(val label: String, val value: String)

/**
 * Everything the public page shows of a gathering, and nothing else (criterion 28): title, dates, place, fees and the two texts. The
 * template of the page gets this and never the gathering, so the custodians and the numbers of the registrations cannot get in by a
 * slip of the pen. A gathering that is over shows only the title and the dates (criterion 27).
 *
 * @property address the complete address of the page, only when the registrations are open (it is printed in place of the button)
 * @property registerHref where «Iscrivi i tuoi figli» leads; null when there is no such button
 */
data class PublicInfo(
    val variant: InfoVariant,
    val title: String,
    val dates: String,
    val days: String?,
    val place: String?,
    val fees: List<PublicFee>,
    val paragraphs: List<String>,
    val whatToBring: List<String>,
    val registerHref: String?,
    val address: String?,
)

/**
 * Builds [PublicInfo] and [Share] from a gathering, with the day of Rome for «over». The one place that decides what goes on the
 * public page; the preview of the teachers uses it too, so what they see is what a parent sees.
 */
@Component
class PublicInfos(private val gatherings: Gatherings, private val presenter: GatheringPresenter, private val publicUrl: PublicUrl) {

    fun variantOf(gathering: Gathering, today: LocalDate): InfoVariant = when {
        gathering.isOver(today) -> InfoVariant.OVER
        gathering.state == GatheringState.CLOSED -> InfoVariant.CLOSED
        // A draft is shown as it will be when it is opened (the preview of the teachers).
        else -> InfoVariant.OPEN
    }

    fun of(gathering: Gathering): PublicInfo {
        val today = gatherings.today()
        val variant = variantOf(gathering, today)
        val dates = GatheringFormat.range(gathering.startDate, gathering.endDate)
        if (variant == InfoVariant.OVER) {
            return PublicInfo(variant, gathering.title, dates, null, null, emptyList(), emptyList(), emptyList(), null, null)
        }
        val fees = gathering.fees
        return PublicInfo(
            variant = variant,
            title = gathering.title,
            dates = dates,
            days = GatheringFormat.days(gathering.days),
            place = gathering.place,
            fees = listOfNotNull(
                fees.fullCents?.let { PublicFee("Tutti i giorni", GatheringFormat.fee(it)) },
                fees.dailyCents?.let { PublicFee("Alcuni giorni", if (it == 0) GatheringFormat.fee(it) else GatheringFormat.fee(it) + " per ogni giorno") },
                fees.lunchOnlyCents?.let { PublicFee("Solo pranzo", if (it == 0) GatheringFormat.fee(it) else GatheringFormat.fee(it) + " per ogni pranzo") },
            ),
            paragraphs = presenter.paragraphs(gathering.infoText),
            whatToBring = presenter.lines(gathering.whatToBring),
            registerHref = if (variant == InfoVariant.OPEN) "/info/${gathering.publicCode}/iscrizione" else null,
            address = if (variant == InfoVariant.OPEN && gathering.state == GatheringState.OPEN) publicUrl.of("/info/${gathering.publicCode}") else null,
        )
    }

    /** `{title}` and a line `17–19 settembre 2027 · Casa per ritiri di esempio`; closed adds «iscrizioni chiuse», over says it is over and not where. */
    fun share(gathering: Gathering): Share {
        val today = gatherings.today()
        val dates = GatheringFormat.range(gathering.startDate, gathering.endDate)
        val description = when (variantOf(gathering, today)) {
            InfoVariant.OVER -> "$dates · Convivenza finita"
            InfoVariant.CLOSED -> listOfNotNull(dates, gathering.place, "iscrizioni chiuse").joinToString(" · ")
            InfoVariant.OPEN -> listOfNotNull(dates, gathering.place).joinToString(" · ")
        }
        return Share(gathering.title, description)
    }
}
