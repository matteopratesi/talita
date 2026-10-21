package talita.ui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.resource.ResourceUrlProvider

/**
 * URLs of the static files for the templates. JTE does not go through Spring's
 * `ResourceUrlEncodingFilter`, so the templates ask this helper for the versioned URL.
 */
@Component
class Assets(private val resourceUrls: ResourceUrlProvider) {

    private val log = LoggerFactory.getLogger(Assets::class.java)

    /**
     * URL of a static file with the content fingerprint: `/css/app.css` becomes
     * `/css/app-<hash>.css`. When the content changes, so does the URL, which is what allows
     * the very long cache.
     */
    fun url(path: String): String {
        val versioned = resourceUrls.getForLookupPath(path)
        if (versioned == null) {
            log.warn("Static resource not found, using the plain URL: {}", path)
            return path
        }
        return versioned
    }
}
