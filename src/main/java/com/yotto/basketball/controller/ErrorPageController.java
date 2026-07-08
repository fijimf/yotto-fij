package com.yotto.basketball.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** Target for Spring Security's access-denied forward. */
@Controller
public class ErrorPageController {

    // Any method: access-denied on a POST (e.g. a CSRF failure) is forwarded
    // as a POST, and must still render the page rather than 405
    @RequestMapping("/error/403")
    public String forbidden() {
        return "error/403";
    }
}
