package talita.account

import org.jooq.DSLContext
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Makes and unmakes teachers for the tests, straight in the database: the set-up of a test, like an
 * invitation that was accepted yesterday. The way in is never a shortcut: the person still enters with a
 * link (see [MockSignIn] and `E2e.signIn`).
 */
object TestAccounts {

    /** The moment the teachers of the set-up were made teachers: long ago (the tests of the retention with a fixed clock make the one that matters by hand). */
    val LONG_AGO: OffsetDateTime = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    /** Makes [email] a teacher with this name, making the account first if it is not there. Returns the id of the account. */
    fun makeTeacher(dsl: DSLContext, email: String, first: String = "Elisa", last: String = "Conti"): Long {
        dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, LocalDate.now()).onConflict(ACCOUNT.EMAIL).doNothing().execute()
        val id = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!
        dsl.insertInto(TEACHER)
            .set(TEACHER.ACCOUNT_ID, id)
            .set(TEACHER.FIRST_NAME, first)
            .set(TEACHER.LAST_NAME, last)
            .set(TEACHER.CREATED_AT, LONG_AGO)
            .onConflict(TEACHER.ACCOUNT_ID).doUpdate()
            .set(TEACHER.FIRST_NAME, first)
            .set(TEACHER.LAST_NAME, last)
            .execute()
        return id
    }

    /** The teacher stops being one (the account stays). */
    fun removeTeacher(dsl: DSLContext, email: String) {
        dsl.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)))).execute()
    }

    fun isTeacher(dsl: DSLContext, email: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(TEACHER).join(ACCOUNT).on(ACCOUNT.ID.eq(TEACHER.ACCOUNT_ID)).where(ACCOUNT.EMAIL.eq(email)),
        )
}
