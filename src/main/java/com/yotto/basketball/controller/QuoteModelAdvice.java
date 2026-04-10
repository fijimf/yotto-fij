package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.service.QuoteService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class QuoteModelAdvice {

    private final QuoteService quoteService;

    public QuoteModelAdvice(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @ModelAttribute("randomQuote")
    public Quote randomQuote() {
        return quoteService.getRandomQuote().orElse(null);
    }

    @ModelAttribute("isAdminPage")
    public boolean isAdminPage(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/admin");
    }
}
