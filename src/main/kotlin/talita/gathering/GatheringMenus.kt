package talita.gathering

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Component
import talita.jooq.tables.references.GATHERING_MENU
import java.time.Clock
import java.time.OffsetDateTime

/** What the pages say about the menu file of a gathering: its name, kind, size and moment of the upload, **never the content**. */
data class MenuSummary(val fileName: String, val type: MenuType, val sizeBytes: Int, val uploadedAt: OffsetDateTime)

/** The menu file with its content, for the download. A class and not a `data class`: the content is an array, and two files are not compared. */
class MenuContent(val fileName: String, val type: MenuType, val bytes: ByteArray)

/**
 * The menu of a gathering (TALITA-12): at most one file for each, kept in the database (`gathering_menu`, V10). Four things, each one a statement: read the
 * summary (without the content, so the detail and Arrivi never read it), read the content (the download only), save (insert, or replace what is there) and
 * remove. None of them is a transaction of several steps, so a failure in the middle of an upload leaves the menu that was there.
 *
 * It holds no personal data and does not look inside the file; the pages decide who sees it (only the teachers).
 */
@Component
class GatheringMenus(private val dsl: DSLContext, private val clock: Clock) {

    /** The size of the content, worked out by the database: the content itself is not read. */
    private val size: Field<Int> = DSL.field("octet_length({0})", SQLDataType.INTEGER, GATHERING_MENU.CONTENT)

    /** The menu of [gatheringId] without its content, or null if it has none. */
    fun summary(gatheringId: Long): MenuSummary? =
        dsl.select(GATHERING_MENU.FILE_NAME, GATHERING_MENU.MEDIA_TYPE, size, GATHERING_MENU.UPLOADED_AT)
            .from(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gatheringId))
            .fetchOne { MenuSummary(it[GATHERING_MENU.FILE_NAME]!!, MenuType.ofMediaType(it[GATHERING_MENU.MEDIA_TYPE]!!), it[size]!!, it[GATHERING_MENU.UPLOADED_AT]!!) }

    /** The menu of [gatheringId] with its content, or null if it has none. */
    fun content(gatheringId: Long): MenuContent? =
        dsl.select(GATHERING_MENU.FILE_NAME, GATHERING_MENU.MEDIA_TYPE, GATHERING_MENU.CONTENT)
            .from(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gatheringId))
            .fetchOne { MenuContent(it[GATHERING_MENU.FILE_NAME]!!, MenuType.ofMediaType(it[GATHERING_MENU.MEDIA_TYPE]!!), it[GATHERING_MENU.CONTENT]!!) }

    /**
     * Saves [content] as the menu of [gatheringId]: the first one, or in place of the one that is there (the menu stays one for each gathering). One statement, so
     * two teachers who upload at the same moment leave one row, with the whole of one of the two files, and neither gets an error. The moment is the one of the clock.
     */
    fun save(gatheringId: Long, fileName: String, type: MenuType, content: ByteArray) {
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(GATHERING_MENU)
            .set(GATHERING_MENU.GATHERING_ID, gatheringId)
            .set(GATHERING_MENU.FILE_NAME, fileName)
            .set(GATHERING_MENU.MEDIA_TYPE, type.mediaType)
            .set(GATHERING_MENU.CONTENT, content)
            .set(GATHERING_MENU.UPLOADED_AT, now)
            .onConflict(GATHERING_MENU.GATHERING_ID).doUpdate()
            .set(GATHERING_MENU.FILE_NAME, fileName)
            .set(GATHERING_MENU.MEDIA_TYPE, type.mediaType)
            .set(GATHERING_MENU.CONTENT, content)
            .set(GATHERING_MENU.UPLOADED_AT, now)
            .execute()
    }

    /**
     * Deletes the menu of [gatheringId] for good. Returns what was deleted (its summary, for the log), or null if there was nothing: someone else had already taken it
     * away, and that is not an error.
     */
    fun remove(gatheringId: Long): MenuSummary? =
        dsl.deleteFrom(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gatheringId))
            .returningResult(GATHERING_MENU.FILE_NAME, GATHERING_MENU.MEDIA_TYPE, size, GATHERING_MENU.UPLOADED_AT)
            .fetchOne { MenuSummary(it[GATHERING_MENU.FILE_NAME]!!, MenuType.ofMediaType(it[GATHERING_MENU.MEDIA_TYPE]!!), it[size]!!, it[GATHERING_MENU.UPLOADED_AT]!!) }
}
