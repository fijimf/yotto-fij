package com.yotto.basketball.service;

import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class QuoteService {

    private final QuoteRepository quoteRepository;

    public QuoteService(QuoteRepository quoteRepository) {
        this.quoteRepository = quoteRepository;
    }

    public Optional<Quote> getRandomQuote() {
        return quoteRepository.findRandom();
    }

    public List<Quote> findAll() {
        return quoteRepository.findAll();
    }

    public Optional<Quote> findById(Long id) {
        return quoteRepository.findById(id);
    }

    public Quote save(Quote quote) {
        return quoteRepository.save(quote);
    }

    public void deleteById(Long id) {
        quoteRepository.deleteById(id);
    }
}
