package talita.registration

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.gathering.PublicNames

/**
 * The names that the application knows, for the texts of the gathering that anyone with the link can read (TALITA-16, criteria 12-16): the first and last name of every child
 * (`child`, whatever gathering it was registered at, also from the paper form) and of every teacher (`teacher`). The use that this adds to a name is protective: it keeps the name
 * of a minor, or of a teacher, out of a title, a place, some information or a list of things to bring (`docs/02-modello-dati.md`).
 *
 * **One query** for all the texts that a form sends, and the answer is only yes or no for each of them, in the same order: the names are compared inside the database and never leave
 * it. Nothing is saved, nothing is kept between two requests, and nothing, neither the texts nor a name, is written in the log (the query has no logging of its own and the
 * application logs no statement, only the numbers of a job).
 *
 * How two names are compared, the same on both sides (`norm`): lowercase and without accents (`unaccent(lower(…))`, as the duplicates of TALITA-5 are found: «Nicolo» is «Nicolò»);
 * the curly and the straight apostrophes are the same («D’Amico» is «D'Amico»); a tab or a non-breaking space is a space, and the spaces in a row are one; and every other character
 * that is not a letter, a digit or an apostrophe (a comma, a full stop, a hyphen, a line break) becomes a separator `|` between two spaces. So a name is found only as **whole words**
 * (the text is read with a space before and after it), and the first name and the last name must be **next to each other with nothing but spaces between them**, in either order:
 * «Luca Rossi», «rossi luca» and «LUCA ROSSÌ» are found; «Lucarossi», «Luca, Rossi», «Luca e Rossi», «Luca» and «Rossi» alone are not. A first name of two words («Anna Maria»)
 * is found only whole.
 */
@Component
class KnownNames(private val dsl: DSLContext) : PublicNames {

    override fun contain(texts: List<String>): List<Boolean> {
        if (texts.isEmpty()) return emptyList()
        val values = texts.indices.joinToString(", ") { "(${it + 1}, cast(? as text))" }
        val rows = dsl.fetch(
            """
            with texts(ord, txt) as (
                select ord, ' ' || ${norm("raw")} || ' ' from (values $values) as asked(ord, raw)
            ),
            people(forwards, backwards) as (
                select ' ' || ${norm("first_name")} || ' ' || ${norm("last_name")} || ' ', ' ' || ${norm("last_name")} || ' ' || ${norm("first_name")} || ' ' from child
                union
                select ' ' || ${norm("first_name")} || ' ' || ${norm("last_name")} || ' ', ' ' || ${norm("last_name")} || ' ' || ${norm("first_name")} || ' ' from teacher
            )
            select texts.ord, exists (
                select 1 from people where position(people.forwards in texts.txt) > 0 or position(people.backwards in texts.txt) > 0
            ) as found
            from texts order by texts.ord
            """.trimIndent(),
            *texts.toTypedArray(),
        )
        return rows.map { it.get("found", Boolean::class.javaObjectType) == true }
    }

    private companion object {

        /**
         * The SQL that makes [column] comparable (see the class). The five apostrophes and quotes become `'`, the tab and the non-breaking space become spaces; then anything that is
         * not a letter, a digit, an underscore, a space or an apostrophe is a separator, and the spaces in a row are one.
         */
        fun norm(column: String): String =
            "btrim(regexp_replace(regexp_replace(translate(unaccent(lower($column)), '’‘ʼ´' || chr(96) || chr(9) || chr(160), repeat(chr(39), 5) || '  '), '[^\\w '']', ' | ', 'g'), ' +', ' ', 'g'))"
    }
}
