@import org.springframework.security.web.csrf.CsrfToken
@param CsrfToken csrf
<%-- The CSRF code of a form. It is read only here, in the forms: Spring Security loads it (and starts the session) when someone asks for its value. --%>
<input type="hidden" name="${csrf.getParameterName()}" value="${csrf.getToken()}">
