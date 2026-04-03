package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Quote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface QuoteRepository extends JpaRepository<Quote, Long> {

    @Query(value = "SELECT * FROM quotes WHERE active = true ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
    Optional<Quote> findRandom();
}
