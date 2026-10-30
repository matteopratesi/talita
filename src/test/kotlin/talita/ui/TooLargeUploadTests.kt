package talita.ui

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a form with a file that is past the limit of the container is recognised and answered (TALITA-12, criterion 4), without a server: the addresses that have such a form, the
 * exception of the container, and the filter that turns it into a 303 to the page of the form, and nothing else. The real thing, with a real server, is in `MenuOversizeTests`.
 */
class TooLargeUploadTests {

    private val tooLarge = ServletException("the parameters", SizeLimitExceededException("the request was rejected because its size (26214735) exceeds the configured maximum (20971520)", 26_214_735, 20_971_520))

    private fun post(path: String): MockHttpServletRequest = MockHttpServletRequest("POST", path).apply { contentType = "multipart/form-data; boundary=x"; setContent(ByteArray(100)) }

    private fun run(request: MockHttpServletRequest, failure: Exception?): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        TooLargeUploadFilter().doFilter(request, response, FilterChain { _, _ -> if (failure != null) throw failure })
        return response
    }

    @Test
    fun `t12 c04 the form of the menu is the one address of a form with a file, and nothing that looks like it is`() {
        assertTrue(TooLargeUpload.isForm("/convivenza/12/menu"))
        assertTrue(TooLargeUpload.isForm("/convivenza/123456789012345678/menu"))
        for (other in listOf(
            "/convivenza/12/menu/file", "/convivenza/12/menu/togli", "/convivenza/12", "/convivenza/abc/menu", "/convivenza//menu", "/convivenza/12/../menu",
            "//example.org/convivenza/12/menu", "convivenza/12/menu", "/menu", "/profilo", "/", "", """/convivenza/12\\menu""",
        )) {
            assertFalse(TooLargeUpload.isForm(other), other)
        }
    }

    @Test
    fun `t12 c04 the exception of the container is recognised wherever it is in the chain of the causes, and no other is`() {
        assertTrue(TooLargeUpload.isTooLarge(tooLarge))
        assertTrue(TooLargeUpload.isTooLarge(SizeLimitExceededException("x", 2, 1)))
        assertTrue(TooLargeUpload.isTooLarge(FileSizeLimitExceededException("x", 2, 1)))
        assertTrue(TooLargeUpload.isTooLarge(IllegalStateException("outer", RuntimeException("middle", tooLarge))))
        assertFalse(TooLargeUpload.isTooLarge(IllegalStateException("something else")))
        assertFalse(TooLargeUpload.isTooLarge(ServletException("the content type is not right", java.io.IOException("a connection that was closed"))))
    }

    @Test
    fun `t12 c04 a form of the menu that is too big is answered with 303 to the same page, with the note in the session of whoever has one`() {
        val request = post("/convivenza/12/menu").also { it.session = org.springframework.mock.web.MockHttpSession() }

        val response = run(request, tooLarge)

        assertEquals(303, response.status)
        assertEquals("/convivenza/12/menu", response.getHeader("Location"))
        assertTrue(TooLargeUpload.take(request, "/convivenza/12/menu"), "the page finds the note")
        assertFalse(TooLargeUpload.take(request, "/convivenza/12/menu"), "once")
    }

    @Test
    fun `t12 c04 the note is for the page it was left for, and only once`() {
        val request = post("/convivenza/12/menu").also { it.session = org.springframework.mock.web.MockHttpSession() }
        run(request, tooLarge)

        assertFalse(TooLargeUpload.take(request, "/convivenza/13/menu"), "another gathering does not show it")
        assertFalse(TooLargeUpload.take(request, "/convivenza/12/menu"), "and it is gone: it was taken by the other page")
    }

    @Test
    fun `t12 c04 without a session nothing is left and none is made, the answer is the same`() {
        val request = post("/convivenza/12/menu")

        val response = run(request, tooLarge)

        assertEquals(303, response.status)
        assertEquals("/convivenza/12/menu", response.getHeader("Location"))
        assertNull(request.getSession(false), "no session is made for someone who has none")
    }

    @Test
    fun `t12 c04 any other failure, any other address and any other method go through untouched`() {
        // Another failure of the form of the menu: the person sees what they saw before.
        val other = IllegalStateException("something else")
        assertEquals(other, assertFailsWith<IllegalStateException> { run(post("/convivenza/12/menu"), other) })
        // The same exception on an address that has no form with a file: not ours.
        assertEquals(tooLarge, assertFailsWith<ServletException> { run(post("/profilo/nome"), tooLarge) })
        // A GET never has a body to refuse.
        assertEquals(tooLarge, assertFailsWith<ServletException> { run(MockHttpServletRequest("GET", "/convivenza/12/menu"), tooLarge) })
        // A request that goes well is not touched.
        assertEquals(200, run(post("/convivenza/12/menu"), null).status)
    }
}
