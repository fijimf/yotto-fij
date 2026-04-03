package com.yotto.basketball.config;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.repository.QuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteInitializerTest extends BaseIntegrationTest {

    @Autowired QuoteInitializer quoteInitializer;
    @Autowired QuoteRepository quoteRepository;

    @BeforeEach
    void setUp() {
        quoteRepository.deleteAll();
    }

    @Test
    void run_seedsAllQuotesFromFileWhenTableIsEmpty() throws Exception {
        quoteInitializer.run(null);

        assertThat(quoteRepository.count()).isEqualTo(114);
    }

    @Test
    void run_doesNotReseedWhenTableAlreadyHasRows() throws Exception {
        quoteInitializer.run(null);
        long countAfterFirst = quoteRepository.count();

        quoteInitializer.run(null);

        assertThat(quoteRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    void run_seedsKnownQuoteText() throws Exception {
        quoteInitializer.run(null);

        assertThat(quoteRepository.findAll())
                .anyMatch(q -> q.getQuoteText().contains("Can I kick it?"));
    }

    @Test
    void run_parsesQuoteWithEscapedSingleQuoteInText() throws Exception {
        quoteInitializer.run(null);

        // "I'm gonna tell you how it's gonna be." — has '' in source
        assertThat(quoteRepository.findAll())
                .anyMatch(q -> q.getQuoteText().contains("I'm gonna tell you"));
    }
}
