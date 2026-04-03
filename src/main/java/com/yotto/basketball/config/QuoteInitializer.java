package com.yotto.basketball.config;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QuoteInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuoteInitializer.class);
    // Matches a single-quoted value: '...' where '' is an escaped single quote inside
    private static final Pattern VALUE_PATTERN = Pattern.compile("'((?:[^']|'')*)'");

    private final QuoteRepository quoteRepository;

    public QuoteInitializer(QuoteRepository quoteRepository) {
        this.quoteRepository = quoteRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (quoteRepository.count() > 0) {
            return;
        }

        ClassPathResource resource = new ClassPathResource("init-quotes.txt");
        List<Quote> quotes = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Matcher matcher = VALUE_PATTERN.matcher(line);
                String quoteText = null;
                String attribution = null;
                int matchCount = 0;

                while (matcher.find() && matchCount < 2) {
                    String value = matcher.group(1).replace("''", "'");
                    if (matchCount == 0) quoteText = value;
                    else attribution = value;
                    matchCount++;
                }

                if (quoteText != null && attribution != null) {
                    Quote quote = new Quote();
                    quote.setQuoteText(quoteText);
                    quote.setAttribution(attribution);
                    quote.setActive(true);
                    quotes.add(quote);
                }
            }
        }

        quoteRepository.saveAll(quotes);
        log.info("Seeded {} quotes from init-quotes.txt", quotes.size());
    }
}
