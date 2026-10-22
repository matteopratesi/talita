package talita.account

import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Who is making the request: the one way to know it.
 *
 * A controller method asks for it as a parameter, of type [CurrentAccount] (the account of the
 * request: id, address, and what the database says about the person now) or `CurrentAccount?` where
 * the page is public and the person may be nobody. The pages of the shell (header, navigation) get it
 * through [talita.ui.ShellFactory], which reads it from here: a controller does not pass it.
 * Nothing else reads the session or the security context for this.
 */
@Component
class CurrentAccounts {

    /** The account of the request, or null if nobody is inside. */
    fun current(): CurrentAccount? = (SecurityContextHolder.getContext().authentication as? AccountAuthentication)?.account
}

/** Gives a controller the [CurrentAccount] as a parameter. */
private class CurrentAccountResolver(private val accounts: CurrentAccounts) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == CurrentAccount::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? = accounts.current()
        ?: if (parameter.isOptional) null else throw AuthenticationCredentialsNotFoundException("nobody is inside")
}

@Configuration(proxyBeanMethods = false)
class CurrentAccountConfiguration(private val accounts: CurrentAccounts) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += CurrentAccountResolver(accounts)
    }
}
