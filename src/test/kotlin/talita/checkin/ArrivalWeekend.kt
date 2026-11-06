package talita.checkin

import java.time.LocalDate

/**
 * The gathering of the examples of TALITA-19: three days in a row, Friday, Saturday and Sunday, and three children: Luca Rossi (every day), Sara Neri (Friday whole day and Saturday
 * lunch only) and Nicolò Bianchi (Friday and Sunday). The days of the week are those of the day the test runs: [friday] is the first day and the others follow it. Today is the day
 * [first] days after Friday: 0 is Friday (the other two days are after today), 1 is Saturday, 2 is Sunday. The teachers are Marta Bianchi and Paolo Verdi, as in every set-up of the
 * check-in. Names and dates are synthetic.
 */
internal class ArrivalWeekend(private val kit: CheckinTestKit, first: Long = 0) {
    val setup = kit.start(first = -first, last = 2 - first)
    val friday: LocalDate = setup.today.minusDays(first)
    val saturday: LocalDate = friday.plusDays(1)
    val sunday: LocalDate = friday.plusDays(2)
    val days = listOf(friday, saturday, sunday)
    val luca = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
    val sara = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", days = mapOf(friday to "full", saturday to "lunch"))
    val nicolo = kit.rows.register(setup.id, kit.account(), "Nicolò", "Bianchi", days = mapOf(friday to "full", sunday to "full"))
}
