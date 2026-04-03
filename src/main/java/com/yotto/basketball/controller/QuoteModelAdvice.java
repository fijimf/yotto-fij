package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.service.QuoteService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class QuoteModelAdvice {

    private final QuoteService quoteService;

    public QuoteModelAdvice(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @ModelAttribute("randomQuote")
    public Quote randomQuote() {
        return quoteService.getRandomQuote().orElse(null);
    }
}
