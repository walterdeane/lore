package com.walterdeane.lore.web

import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import jakarta.servlet.http.HttpServletRequest

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException, request: HttpServletRequest, redirectAttributes: RedirectAttributes): String {
        redirectAttributes.addFlashAttribute("error", ex.message ?: "Not found")
        return "redirect:" + (request.getHeader("Referer") ?: "/")
    }
}
