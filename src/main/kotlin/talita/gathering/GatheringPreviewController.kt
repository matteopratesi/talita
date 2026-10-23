package talita.gathering

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The preview of the information page, in the shell of the teachers (`/convivenza/{id}/anteprima`: only for them): the page as a parent would see it
 * now, with a notice on top. A draft is shown as it will be when the registrations open. It is the same component as the public page, fed by the same
 * [PublicInfos]; the button «Iscrivi i tuoi figli» does nothing here and never leads to the registration.
 */
@Controller
class GatheringPreviewController(
    private val shells: ShellFactory,
    private val gatherings: Gatherings,
    private val infos: PublicInfos,
) {

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/anteprima")
    fun preview(@PathVariable id: Long, response: HttpServletResponse): ModelAndView {
        val gathering = gatherings.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
        val info = infos.of(gathering)
        val notice = when (infos.variantOf(gathering, gatherings.today())) {
            InfoVariant.OVER -> "Così vede la pagina chi apre il link adesso: la convivenza è finita."
            InfoVariant.CLOSED -> "Così vede la pagina chi apre il link adesso."
            InfoVariant.OPEN ->
                if (gathering.state == GatheringState.DRAFT) {
                    "Così apparirà la pagina quando aprirai le iscrizioni. Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione."
                } else {
                    "Così vede la pagina chi apre il link adesso. Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione."
                }
        }
        val page = PreviewPage(gathering.id, notice, info, printable = gathering.state != GatheringState.DRAFT)
        return ModelAndView(
            "gathering/preview",
            mapOf("shell" to shells.shell(page = "Anteprima", current = Section.CONVIVENZA, noindex = true), "page" to page),
        )
    }
}

/** The preview: the notice on top, the information as the page shows it, and whether the sheet can be printed (not for a draft: its address is not true yet). */
data class PreviewPage(val id: Long, val notice: String, val info: PublicInfo, val printable: Boolean)
