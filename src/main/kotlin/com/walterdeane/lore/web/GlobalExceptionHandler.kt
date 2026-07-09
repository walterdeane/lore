package com.walterdeane.lore.web

import com.walterdeane.lore.exception.UnsupportedDocumentTypeException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.resource.NoResourceFoundException
import jakarta.servlet.http.HttpServletRequest

/**
 * App-wide fallback: turns exceptions that would otherwise bubble up as Spring's raw Whitelabel
 * error page into a redirect back to the referring page with a flash error message instead.
 * [handleUnexpected] is the last resort for anything not specifically handled above it — Spring
 * picks the most specific matching handler, so it only applies when nothing else matches.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException, request: HttpServletRequest, redirectAttributes: RedirectAttributes): String {
        redirectAttributes.addFlashAttribute("error", ex.message ?: "Not found")
        return "redirect:" + (request.getHeader("Referer") ?: "/")
    }

    @ExceptionHandler(UnsupportedDocumentTypeException::class)
    fun handleUnsupportedDocumentType(ex: UnsupportedDocumentTypeException, request: HttpServletRequest, redirectAttributes: RedirectAttributes): String {
        redirectAttributes.addFlashAttribute("error", ex.message ?: "Unsupported document type")
        return "redirect:" + (request.getHeader("Referer") ?: "/")
    }

    /**
     * Plain 404s for missing static assets (favicon, or Tomcat's `;jsessionid=` URL-rewrite fallback
     * hitting `/`) are routine, not application errors — [handleUnexpected] would otherwise catch
     * these too and turn an ordinary 404 into an ERROR-level log and a "something went wrong" redirect.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    /** Catches anything not specifically handled above; the real cause goes to the log, only a generic message to the user. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: HttpServletRequest, redirectAttributes: RedirectAttributes): String {
        log.error("unhandled exception for {} {}", request.method, request.requestURI, ex)
        redirectAttributes.addFlashAttribute("error", "Something went wrong. Please try again.")
        return "redirect:" + (request.getHeader("Referer") ?: "/")
    }
}
