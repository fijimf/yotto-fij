package com.yotto.basketball.repository;

import com.yotto.basketball.entity.BettingOdds;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BettingOddsRepository extends JpaRepository<BettingOdds, Long> {

    Optional<BettingOdds> findByGameId(Long gameId);

    @Query("SELECT bo FROM BettingOdds bo WHERE bo.game.season.id = :seasonId")
    List<BettingOdds> findBySeasonId(@Param("seasonId") Long seasonId);

    List<BettingOdds> findBySource(String source);
}
