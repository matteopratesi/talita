package talita.checkin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rules of the check-in that need no database (criteria 10 and 11): what is still to be paid, said in the four sentences of the design, and what a person
 * may write as the amount of a payment. Synthetic amounts.
 */
class CheckinRulesTests {

    // ---- The balance (criterion 10) ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t6 c10 the balance is what is due less what was paid, and the four sentences are still to pay, paid, paid more and free`() {
        // Luca, every day, 60 euros: nothing paid yet, then 35, then 60, then 70.
        assertEquals(Balance.ToPay(6000), Balance.of(due = 6000, paid = 0, withdrawn = false))
        assertEquals(Balance.ToPay(2500), Balance.of(due = 6000, paid = 3500, withdrawn = false))
        // 35 and 25 euros are the 60 that are due.
        assertEquals(Balance.Paid, Balance.of(due = 6000, paid = 3500 + 2500, withdrawn = false))
        assertEquals(Balance.Over(1000), Balance.of(due = 6000, paid = 7000, withdrawn = false))
        // Due and paid at zero: free of charge. Free and yet paid: the payment is in excess.
        assertEquals(Balance.Free, Balance.of(due = 0, paid = 0, withdrawn = false))
        assertEquals(Balance.Over(500), Balance.of(due = 0, paid = 500, withdrawn = false))
    }

    @Test
    fun `t6 c10 a withdrawn registration owes nothing and the payments stay, and what cannot be worked out is said and not guessed`() {
        assertEquals(Balance.Over(6000), Balance.of(due = 6000, paid = 6000, withdrawn = true), "paid 60 euros more than the nothing that is due")
        assertEquals(Balance.NothingDue, Balance.of(due = 6000, paid = 0, withdrawn = true))
        assertEquals(Balance.Undefined, Balance.of(due = null, paid = 0, withdrawn = false))
        assertEquals(Balance.Undefined, Balance.of(due = null, paid = 2500, withdrawn = false))
    }

    @Test
    fun `t6 c02 only a balance that is still to pay is a debt to collect`() {
        assertEquals(2500, Balance.of(6000, 3500, false).owed)
        assertEquals(0, Balance.Paid.owed)
        assertEquals(0, Balance.Over(100).owed)
        assertEquals(0, Balance.Free.owed)
        assertEquals(0, Balance.NothingDue.owed)
        assertEquals(0, Balance.Undefined.owed)
    }

    // ---- The amount of a payment (criterion 11) ---------------------------------------------------------------------------------------------

    private fun cents(typed: String): Int? = PaymentAmount.check(typed).cents

    private fun error(typed: String): String? = PaymentAmount.check(typed).error

    @Test
    fun `t6 c11 an amount is from one cent to 1000 euros, with a comma or a dot, at most two decimals, and the spaces do not count`() {
        assertEquals(2500, cents("25"))
        assertEquals(1250, cents("12,50"))
        assertEquals(1250, cents("12.50"))
        assertEquals(1250, cents("12,5"))
        assertEquals(1, cents("0,01"))
        assertEquals(100_000, cents("1000"))
        assertEquals(100_000, cents("1000,00"))
        assertEquals(2500, cents("  25  "))
        assertEquals(1250, cents("12, 50"))
        assertEquals(100_000, cents("1 000"))
        assertEquals(1250, cents("12,50"))
        assertNull(error("25"))
    }

    @Test
    fun `t6 c11 what is not an amount gets an error under the field with an example, and the amount is kept as it was typed`() {
        // Empty: asks for the amount.
        assertEquals(PaymentAmount.EMPTY, error(""))
        assertEquals(PaymentAmount.EMPTY, error("   "))
        // The format: letters, a third decimal, a thousands separator («1.000» is a third decimal), a minus, two separators, a euro sign.
        for (typed in listOf("venti", "12,505", "1.000", "-5", "12,,50", "12,50 €", "1,5,0", "25.", ",50", "0x10", "٢٥")) {
            assertEquals(PaymentAmount.FORMAT, error(typed), typed)
        }
        // Zero and below: «più di zero».
        assertEquals(PaymentAmount.ZERO, error("0"))
        assertEquals(PaymentAmount.ZERO, error("0,00"))
        // More than a thousand euros.
        assertEquals(PaymentAmount.TOO_MUCH, error("1000,01"))
        assertEquals(PaymentAmount.TOO_MUCH, error("2500"))
        assertEquals(PaymentAmount.TOO_MUCH, error("9999"))
        // The words are those of the design.
        assertEquals("Scrivi un importo in euro con al massimo due decimali, per esempio 25 o 12,50.", PaymentAmount.FORMAT)
        assertEquals("L’importo deve essere più di zero.", PaymentAmount.ZERO)
        assertEquals("Un pagamento può essere al massimo 1.000 €. Se è di più, registralo in due volte.", PaymentAmount.TOO_MUCH)
        assertEquals("Scrivi quanto hai ricevuto, per esempio 25 o 12,50.", PaymentAmount.EMPTY)
        // What was typed comes back without the spaces, and never longer than what a field needs.
        assertEquals("12,505", PaymentAmount.check("12, 505").typed)
        assertEquals(30, PaymentAmount.check("1".repeat(500)).typed.length)
    }
}
