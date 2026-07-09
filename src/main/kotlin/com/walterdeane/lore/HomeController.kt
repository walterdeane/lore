package com.walterdeane.lore

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** Landing page linking out to domains, search, and chat. */
@Controller
class HomeController {

    @GetMapping("/")
    fun home() = "home"
}
