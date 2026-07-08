package com.yotto.basketball.controller;

import com.yotto.basketball.security.RateLimitExceededException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * HTML error handling for the account controllers — must outrank the JSON
 * GlobalExceptionHandler, which would otherwise render these as REST errors.
 */
@ControllerAdvice(assignableTypes = {AuthController.class, AccountController.class,
        AdminUserController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HtmlErrorAdvice {

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public String rateLimited() {
        return "error/429";
    }
}
