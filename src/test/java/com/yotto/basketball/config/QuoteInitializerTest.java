package com.yotto.basketball.config;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.repository.QuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteInitializerTest extends BaseIntegrationTest {

    @Autowired QuoteInitializer quoteInitializer;
    @Autowired QuoteRepository quoteRepository;

    @Test
    void run_seedsAllQuotesFromFileWhenTableIsEmpty() throws Exception {
        long expected = countNonBlankLinesIn("init-quotes.txt");
        assertThat(expected).isGreaterThan(0);

        quoteInitializer.run(null);

        assertThat(quoteRepository.count()).isEqualTo(expected);
    }

    private static long countNonBlankLinesIn(String classpathResource) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(classpathResource).getInputStream(), StandardCharsets.UTF_8));
             Stream<String> lines = reader.lines()) {
            return lines.map(String::trim).filter(s -> !s.isEmpty()).count();
        }
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
