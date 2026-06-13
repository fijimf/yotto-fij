package com.yotto.basketball.repository;

import com.yotto.basketball.entity.TeamGameStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeamGameStatsRepository extends JpaRepository<TeamGameStats, Long> {

    List<TeamGameStats> findByGameId(Long gameId);

    Optional<TeamGameStats> findByGameIdAndTeamId(Long gameId, Long teamId);

    boolean existsByGameId(Long gameId);

    @Query("SELECT t FROM TeamGameStats t JOIN FETCH t.game g WHERE g.season.id = :seasonId")
    List<TeamGameStats> findBySeasonId(@Param("seasonId") Long seasonId);

    /** Earliest game date among games whose box-score rows were scraped at or after the given instant. */
    @Query(value = "SELECT MIN(CAST(g.game_date AS date)) FROM team_game_stats t " +
                   "JOIN games g ON t.game_id = g.id " +
                   "WHERE g.season_id = :seasonId AND t.scrape_date >= :since",
           nativeQuery = true)
    LocalDate findMinGameDateWithStatsScrapedSince(@Param("seasonId") Long seasonId,
                                                   @Param("since") LocalDateTime since);
}
