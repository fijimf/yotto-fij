package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteServiceTest extends BaseIntegrationTest {

    @Autowired QuoteService quoteService;
    @Autowired QuoteRepository quoteRepository;

    @BeforeEach
    void setUp() {
        quoteRepository.deleteAll();
    }

    @Test
    void getRandomQuote_returnsEmptyWhenNoActiveQuotes() {
        assertThat(quoteService.getRandomQuote()).isEmpty();
    }

    @Test
    void getRandomQuote_returnsQuoteWhenOneExists() {
        Quote q = new Quote();
        q.setQuoteText("Anger is an energy.");
        q.setAttribution("\"Rise\" - Public Image Ltd.");
        q.setActive(true);
        quoteRepository.save(q);

        Optional<Quote> result = quoteService.getRandomQuote();

        assertThat(result).isPresent();
        assertThat(result.get().getQuoteText()).isEqualTo("Anger is an energy.");
    }

    @Test
    void getRandomQuote_doesNotReturnInactiveQuote() {
        Quote q = new Quote();
        q.setQuoteText("Inactive quote");
        q.setAttribution("Nobody");
        q.setActive(false);
        quoteRepository.save(q);

        assertThat(quoteService.getRandomQuote()).isEmpty();
    }

    @Test
    void save_persistsNewQuote() {
        Quote q = new Quote();
        q.setQuoteText("We need the funk.");
        q.setAttribution("\"Give Up the Funk\" - Parliament");
        q.setActive(true);

        Quote saved = quoteService.save(q);

        assertThat(saved.getId()).isNotNull();
        assertThat(quoteRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void deleteById_removesQuote() {
        Quote q = new Quote();
        q.setQuoteText("Space travel's in my blood.");
        q.setAttribution("\"Another Girl, Another Planet\" - The Only Ones");
        q.setActive(true);
        Quote saved = quoteRepository.save(q);

        quoteService.deleteById(saved.getId());

        assertThat(quoteRepository.findById(saved.getId())).isEmpty();
    }
}
