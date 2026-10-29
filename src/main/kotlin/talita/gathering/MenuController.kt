package talita.gathering

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.SUCCESS
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory
import talita.ui.TooLargeUpload

/**
 * The menu of the gathering for the teachers (TALITA-12): `/convivenza/{id}/menu` is the page to upload it or replace it (`GET`) and the upload (`POST`,
 * `multipart/form-data`), `/convivenza/{id}/menu/file` the download, `/convivenza/{id}/menu/togli` the page of confirmation that stands in for the dialog without
 * JavaScript (`GET`) and the removal (`POST`). All under `/convivenza` (and what is below it), so `TeacherPaths` answers 403 to a parent before a request gets here.
 *
 * - **The upload** is a page, not a piece of a page: the form is not boosted by htmx (its limit of 20 seconds would cut a slow upload from a phone). It answers
 *   303 to the detail with «Menù caricato.», or 422 with the same page and the error under the field. In every error the menu that was there stays. The kind is
 *   recognised from the first bytes ([MenuFile.typeOf]), the size is checked first (10 MB, [MenuFile.MAX_BYTES]), and nothing of the file is ever read beyond that.
 * - **A file past the limit of the container** (20 MB) never gets here: `AccessHandler` sends the person back to the page, which says it under the field.
 * - **The download** is always `attachment`, with `nosniff` and a Content-Security-Policy of its own that allows nothing and puts the file in a sandbox: the file
 *   is never opened on the address of the app, whatever it holds.
 * - **The log** (INFO and over) says the id of the gathering, the kind and the size, never the name or the content.
 */
@Controller
class MenuController(
    private val shells: ShellFactory,
    private val gatherings: Gatherings,
    private val menus: GatheringMenus,
    private val presenter: MenuPresenter,
) {

    private val log = LoggerFactory.getLogger(MenuController::class.java)

    // ---- The page: upload or replace ---------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/menu")
    fun page(@PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val gathering = found(id)
        // A file that was past the limit of the container comes back here (AccessHandler): the error is the one of a file that is too big for the application.
        val error = if (TooLargeUpload.take(request, "/convivenza/$id/menu")) MenuTexts.TOO_BIG else null
        return formPage(gathering, error, HttpStatus.OK)
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/menu")
    fun upload(@PathVariable id: Long, @RequestParam(name = "file", required = false) file: MultipartFile?, request: HttpServletRequest): ModelAndView {
        val gathering = found(id)
        val size = file?.size ?: 0
        if (file == null || size == 0L) return refused(gathering, MenuTexts.CHOOSE_A_FILE, "no file", 0)
        // The size first: a file that is too big is too big whatever it is, and it is not read.
        if (size > MenuFile.MAX_BYTES) return refused(gathering, MenuTexts.TOO_BIG, "too big", size)
        val content = file.inputStream.use { it.readNBytes(MenuFile.MAX_BYTES + 1) }
        if (content.size > MenuFile.MAX_BYTES) return refused(gathering, MenuTexts.TOO_BIG, "too big", content.size.toLong())
        val type = MenuFile.typeOf(content.copyOf(minOf(content.size, MenuFile.HEAD_BYTES)))
            ?: return refused(gathering, MenuTexts.NOT_A_MENU, "not a PDF, a JPEG or a PNG", content.size.toLong())

        menus.save(id, MenuFile.cleanName(file.originalFilename, type), type, content)
        log.info("Menu saved: gathering {}, {}, {} bytes.", id, type.mediaType, content.size)
        Flash.put(request, Alert(SUCCESS, MenuTexts.UPLOADED))
        return seeOther("/convivenza/$id")
    }

    /** 422 with the page and the error under the field; the menu that was there is still there. The log has the reason and the size, no name. */
    private fun refused(gathering: Gathering, error: String, reason: String, size: Long): ModelAndView {
        log.info("Menu refused: gathering {}, {}, {} bytes.", gathering.id, reason, size)
        return formPage(gathering, error, HttpStatus.UNPROCESSABLE_ENTITY)
    }

    private fun formPage(gathering: Gathering, error: String?, status: HttpStatus): ModelAndView =
        ModelAndView("gathering/menu", mapOf("shell" to shells.shell(page = "Menù", current = Section.CONVIVENZA), "page" to presenter.page(gathering, error)))
            .also { it.status = status }

    // ---- The download -----------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/menu/file")
    fun file(@PathVariable id: Long): ResponseEntity<ByteArray> {
        found(id)
        val menu = menus.content(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        log.info("Menu downloaded: gathering {}, {}, {} bytes.", id, menu.type.mediaType, menu.bytes.size)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(menu.type.mediaType))
            .contentLength(menu.bytes.size.toLong())
            .header(HttpHeaders.CONTENT_DISPOSITION, MenuFile.contentDisposition(menu.fileName))
            .header("X-Content-Type-Options", "nosniff")
            // Instead of the policy of every page (Spring Security adds its own only when the response has none): nothing is allowed, and a browser that opened the
            // file anyway would put it in a sandbox. Cache-Control is left to Spring Security: no-store, like every page behind the access.
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .body(menu.bytes)
    }

    // ---- Taking it away ---------------------------------------------------------------------------------------------------------

    /** The page of confirmation that stands in for the dialog without JavaScript: same words. It reads nothing of the menu, so it is the same whether the menu is there or not. */
    @GetMapping("/convivenza/{id:[0-9]{1,18}}/menu/togli")
    fun confirmRemoval(@PathVariable id: Long): ModelAndView {
        found(id)
        val model = presenter.removeDialog(id)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/menu/togli")
    fun remove(@PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        found(id)
        val removed = menus.remove(id)
        if (removed != null) log.info("Menu removed: gathering {}, {}, {} bytes.", id, removed.type.mediaType, removed.sizeBytes)
        // Taken away already by another teacher a moment ago (a double tap, two teachers): the same confirmation, never an error.
        Flash.put(request, Alert(SUCCESS, MenuTexts.REMOVED))
        return seeOther("/convivenza/$id")
    }

    // ---- Support ----------------------------------------------------------------------------------------------------------------

    /** The gathering, or the page «Pagina non trovata» (an id that does not exist). */
    private fun found(id: Long): Gathering = gatherings.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
