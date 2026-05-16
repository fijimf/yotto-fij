package com.yotto.basketball.repository;

import com.yotto.basketball.entity.NonD1GameObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NonD1GameObservationRepository extends JpaRepository<NonD1GameObservation, Long> {

    Optional<NonD1GameObservation> findByEspnGameId(String espnGameId);

    long countBySeasonYear(Integer seasonYear);
}
