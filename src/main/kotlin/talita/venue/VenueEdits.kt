package talita.venue

import org.springframework.stereotype.Component
import talita.gathering.GatheringChecks
import talita.gathering.PublicNames
import talita.gathering.TextCheck
import talita.checkin.VenueFormRef
import talita.room.BedsCheck
import talita.room.NameCheck
import talita.room.RemoveResult
import talita.room.Room
import talita.room.RoomChecks
import talita.room.Rooms
import talita.room.SaveResult

/** What came of writing a venue through [VenueEdits]. */
sealed interface VenueEdit {
    /** Saved. [gatherings] is how many gatherings use it: if there are some, what the parents read has changed with it. */
    data class Saved(val venue: Venue, val gatherings: Int) : VenueEdit

    /** The checks said no, or the name is the one of another venue (then [name] has [VenueChecks.NAME_TAKEN]): what was written, and the error of each field. */
    data class Invalid(val name: TextCheck, val place: TextCheck) : VenueEdit {
        val errors: Int get() = listOfNotNull(name.error, place.error).size
    }

    /** No such venue: another teacher took it away a moment before. */
    data object NotFound : VenueEdit
}

/**
 * **The one place that writes a venue** (TALITA-21, criteria 13 and 14): the checks of the name and the place (`VenueChecks`), the question about the names of people (`PublicNames`) and the write ([Venues]), with
 * the same words for everyone. The pages of the teachers (`VenueController`) and the APIs of configuration (`talita.api`) both call it, and each turns the result into a page or into JSON: so a form and a
 * request of the APIs with the same values have the same outcome, the same sentences and the same database, and the locks and the transactions are the ones of [Venues].
 */
@Component
class VenueEdits(private val venues: Venues, private val names: PublicNames) {

    private fun checked(rawName: String?, rawPlace: String?): Pair<TextCheck, TextCheck> =
        GatheringChecks.withNames(names, VenueChecks.name(rawName), VenueChecks.place(rawPlace)).let { it[0] to it[1] }

    fun create(rawName: String?, rawPlace: String?): VenueEdit {
        val (name, place) = checked(rawName, rawPlace)
        if (name.error != null || place.error != null) return VenueEdit.Invalid(name, place)
        return when (val saved = venues.create(name.value, place.value)) {
            is VenueSave.Saved -> VenueEdit.Saved(saved.venue, saved.gatherings)
            VenueSave.NameTaken -> VenueEdit.Invalid(name.copy(error = VenueChecks.NAME_TAKEN), place)
            VenueSave.NotFound -> VenueEdit.NotFound
        }
    }

    fun update(id: Long, rawName: String?, rawPlace: String?): VenueEdit {
        val (name, place) = checked(rawName, rawPlace)
        if (name.error != null || place.error != null) return VenueEdit.Invalid(name, place)
        return when (val saved = venues.update(id, name.value, place.value)) {
            is VenueSave.Saved -> VenueEdit.Saved(saved.venue, saved.gatherings)
            VenueSave.NameTaken -> VenueEdit.Invalid(name.copy(error = VenueChecks.NAME_TAKEN), place)
            VenueSave.NotFound -> VenueEdit.NotFound
        }
    }

    /** A venue that a gathering uses is not taken away ([VenueRemoval.InUse]): the count is made under the lock of the row. */
    fun remove(id: Long): VenueRemoval = venues.remove(id)
}

/** What came of writing a room through [VenueRoomEdits]. */
sealed interface RoomEdit {
    data class Saved(val room: Room) : RoomEdit

    /** The checks said no, or the name is the one of another room of the venue (then [name] has [RoomChecks.NAME_TAKEN]): what was written, and the error of each field. */
    data class Invalid(val name: NameCheck, val beds: BedsCheck) : RoomEdit {
        val errors: Int get() = listOfNotNull(name.error, beds.error).size
    }

    /** No such venue, or no such room in it. */
    data object NotFound : RoomEdit
}

/** The one place that writes a room of a venue (TALITA-21): the checks of `RoomChecks` and the write of [Rooms], for the pages and for the APIs. */
@Component
class VenueRoomEdits(private val rooms: Rooms, private val names: PublicNames) {

    private fun saved(name: NameCheck, beds: BedsCheck, result: SaveResult): RoomEdit = when (result) {
        is SaveResult.Saved -> RoomEdit.Saved(result.room)
        SaveResult.NameTaken -> RoomEdit.Invalid(name.copy(error = RoomChecks.NAME_TAKEN), beds)
        SaveResult.NotFound -> RoomEdit.NotFound
    }

    /** The name of a room as the checks of the room say it, and then the question about the names of people: a room is named after the house, never after a person that the application knows (TALITA-21). */
    private fun checkedName(raw: String?): NameCheck {
        val name = RoomChecks.name(raw.orEmpty())
        if (name.error != null || name.value.isEmpty()) return name
        return if (names.contain(listOf(name.value)).single()) name.copy(error = NAME_IS_A_PERSON) else name
    }

    fun create(venueId: Long, rawName: String?, rawBeds: String?): RoomEdit {
        val name = checkedName(rawName)
        val beds = RoomChecks.beds(rawBeds.orEmpty())
        if (name.error != null || beds.error != null) return RoomEdit.Invalid(name, beds)
        return saved(name, beds, rooms.create(venueId, name.value, beds.beds!!))
    }

    fun update(venueId: Long, roomId: Long, rawName: String?, rawBeds: String?): RoomEdit {
        val name = checkedName(rawName)
        val beds = RoomChecks.beds(rawBeds.orEmpty())
        if (name.error != null || beds.error != null) return RoomEdit.Invalid(name, beds)
        return saved(name, beds, rooms.update(venueId, roomId, name.value, beds.beds!!))
    }

    /** Takes the room away. [refusePeople] is for the APIs: a room where somebody sleeps is not taken away ([RemoveResult.HasPeople]), because the confirmation is a dialog of the app. */
    fun remove(venueId: Long, roomId: Long, refusePeople: Boolean = false): RemoveResult = rooms.remove(venueId, roomId, refusePeople)

    companion object {
        const val NAME_IS_A_PERSON = "Scrivi il nome della camera nella casa, non il nome di una persona."
    }
}

/** What came of writing a form of a venue through [VenueFormEdits]. */
sealed interface FormEdit {
    data class Saved(val form: VenueFormRef) : FormEdit

    /** The name is wrong, or is the one of another form of the venue (then [name] has [VenueFormChecks.NAME_TAKEN]). */
    data class Invalid(val name: TextCheck) : FormEdit

    /** No such venue, or no such form in it. */
    data object NotFound : FormEdit
}

/** The one place that writes a form of a venue (TALITA-21): the check of the name, with the question about the names of people, and the write of [VenueForms]. */
@Component
class VenueFormEdits(private val forms: VenueForms, private val names: PublicNames) {

    private fun checked(rawName: String?): TextCheck = GatheringChecks.withNames(names, VenueFormChecks.name(rawName)).single()

    private fun saved(name: TextCheck, result: FormSave): FormEdit = when (result) {
        is FormSave.Saved -> FormEdit.Saved(result.form)
        FormSave.NameTaken -> FormEdit.Invalid(name.copy(error = VenueFormChecks.NAME_TAKEN))
        FormSave.NotFound -> FormEdit.NotFound
    }

    fun create(venueId: Long, rawName: String?, choices: FormColumns): FormEdit {
        val name = checked(rawName)
        if (!name.valid) return FormEdit.Invalid(name)
        return saved(name, forms.create(venueId, name.value, choices))
    }

    fun update(venueId: Long, formId: Long, rawName: String?, choices: FormColumns): FormEdit {
        val name = checked(rawName)
        if (!name.valid) return FormEdit.Invalid(name)
        return saved(name, forms.update(venueId, formId, name.value, choices))
    }

    fun remove(venueId: Long, formId: Long): FormRemoval = forms.remove(venueId, formId)
}
