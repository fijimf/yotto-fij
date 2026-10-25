package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Quote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;

import java.util.Optional;

public interface QuoteRepository extends JpaRepository<Quote, Long> {

    @NativeQuery("SELECT id, quote_text, attribution, active FROM quotes WHERE active = true ORDER BY RANDOM() LIMIT 1")
    Optional<Quote> findRandom();
}
