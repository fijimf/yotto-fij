package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {

    long countByStatus(Game.GameStatus status);

    long countBySeasonId(Long seasonId);

    long countBySeasonIdAndStatus(Long seasonId, Game.GameStatus status);

    long countBySeasonIdAndTournamentType(Long seasonId, Game.TournamentType tournamentType);

    @Query("SELECT COUNT(DISTINCT g.scrapeDate) FROM Game g WHERE g.season.id = :seasonId AND g.scrapeDate IS NOT NULL")
    long countDistinctScrapeDateBySeasonId(@Param("seasonId") Long seasonId);

    @Query("SELECT COUNT(g) FROM Game g WHERE g.season.id = :seasonId AND g.status = 'IN_PROGRESS' AND g.gameDate < :cutoff")
    long countStaleInProgress(@Param("seasonId") Long seasonId, @Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT COUNT(DISTINCT g.id) FROM Game g WHERE g.season.id = :seasonId AND EXISTS (SELECT 1 FROM TeamGameStats t WHERE t.game = g)")
    long countGamesWithStats(@Param("seasonId") Long seasonId);

    @Query("SELECT COUNT(g) FROM Game g WHERE g.season.id = :seasonId AND g.bettingOdds IS NOT NULL")
    long countGamesWithOdds(@Param("seasonId") Long seasonId);

    @Query("SELECT COUNT(g) FROM Game g WHERE g.season.id = :seasonId AND g.status = 'FINAL' AND g.bettingOdds IS NULL")
    long countFinalGamesMissingOdds(@Param("seasonId") Long seasonId);

    @Query("SELECT COUNT(g) FROM Game g WHERE g.season.id = :seasonId AND g.status = 'FINAL' " +
           "AND NOT EXISTS (SELECT 1 FROM TeamGameStats t WHERE t.game = g)")
    long countFinalGamesMissingStats(@Param("seasonId") Long seasonId);

    Optional<Game> findByEspnId(String espnId);

    List<Game> findBySeasonId(Long seasonId);

    List<Game> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);

    List<Game> findByStatus(Game.GameStatus status);

    List<Game> findByGameDateBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT g FROM Game g WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) AND g.season.id = :seasonId")
    List<Game> findByTeamAndSeason(@Param("teamId") Long teamId, @Param("seasonId") Long seasonId);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam LEFT JOIN FETCH g.bettingOdds " +
           "WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) AND g.season.id = :seasonId ORDER BY g.gameDate")
    List<Game> findByTeamAndSeasonWithDetails(@Param("teamId") Long teamId, @Param("seasonId") Long seasonId);

    @Query("SELECT g FROM Game g WHERE g.conferenceGame = true AND g.season.id = :seasonId")
    List<Game> findConferenceGamesBySeason(@Param("seasonId") Long seasonId);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam WHERE g.season.id = :seasonId AND g.status = :status")
    List<Game> findBySeasonIdAndStatus(@Param("seasonId") Long seasonId, @Param("status") Game.GameStatus status);

    @Query("SELECT g FROM Game g WHERE g.season.id = :seasonId AND g.status <> :status")
    List<Game> findBySeasonIdAndStatusNot(@Param("seasonId") Long seasonId, @Param("status") Game.GameStatus status);

    @Query("SELECT g FROM Game g WHERE g.season.id = :seasonId AND g.status = 'FINAL' AND g.bettingOdds IS NULL")
    List<Game> findFinalGamesWithoutOdds(@Param("seasonId") Long seasonId);

    @Query("SELECT g FROM Game g WHERE g.season.id = :seasonId AND g.status = 'FINAL' " +
           "AND NOT EXISTS (SELECT 1 FROM TeamGameStats t WHERE t.game = g)")
    List<Game> findFinalGamesWithoutStats(@Param("seasonId") Long seasonId);

    /** Earliest game date among games whose row changed at or after the given instant. */
    @Query(value = "SELECT MIN(CAST(game_date AS date)) FROM games " +
                   "WHERE season_id = :seasonId AND updated_at >= :since",
           nativeQuery = true)
    java.time.LocalDate findMinGameDateUpdatedSince(@Param("seasonId") Long seasonId,
                                                    @Param("since") LocalDateTime since);

    @Query("SELECT DISTINCT s FROM Game g JOIN g.season s WHERE g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId ORDER BY s.year DESC")
    List<Season> findSeasonsByTeamId(@Param("teamId") Long teamId);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.season.id = :seasonId AND g.tournamentType = :type ORDER BY g.gameDate, g.id")
    List<Game> findBySeasonIdAndTournamentTypeWithDetails(
            @Param("seasonId") Long seasonId,
            @Param("type") Game.TournamentType type);

    @Query("SELECT MAX(s.year) FROM Game g JOIN g.season s WHERE g.tournamentType = :type")
    Integer findMaxSeasonYearByTournamentType(@Param("type") Game.TournamentType type);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam JOIN FETCH g.season LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.status = 'SCHEDULED' AND g.gameDate BETWEEN :start AND :end ORDER BY g.gameDate")
    List<Game> findScheduledBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam JOIN FETCH g.season LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.id = :id")
    Optional<Game> findByIdWithDetails(@Param("id") Long id);

    /** All FINAL games with recorded scores for a season year, oldest first (prediction evaluation). */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam JOIN FETCH g.season s LEFT JOIN FETCH g.bettingOdds " +
           "WHERE s.year = :seasonYear AND g.status = 'FINAL' " +
           "  AND g.homeScore IS NOT NULL AND g.awayScore IS NOT NULL ORDER BY g.gameDate, g.id")
    List<Game> findFinalGamesForEvaluation(@Param("seasonYear") int seasonYear);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam " +
           "WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) " +
           "  AND g.status = 'FINAL' AND g.homeScore IS NOT NULL AND g.awayScore IS NOT NULL " +
           "  AND g.gameDate < :beforeDate ORDER BY g.gameDate DESC")
    List<Game> findRecentFinalGamesForTeam(
            @Param("teamId") Long teamId,
            @Param("beforeDate") LocalDateTime beforeDate,
            org.springframework.data.domain.Pageable pageable);

    /** All FINAL H2H games between two teams (any direction), excluding the given game, newest first. */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam " +
           "WHERE ((g.homeTeam.id = :teamAId AND g.awayTeam.id = :teamBId) OR " +
           "       (g.homeTeam.id = :teamBId AND g.awayTeam.id = :teamAId)) " +
           "  AND g.status = 'FINAL' AND g.homeScore IS NOT NULL AND g.awayScore IS NOT NULL " +
           "  AND g.id <> :excludeId ORDER BY g.gameDate DESC")
    List<Game> findAllH2HGames(
            @Param("teamAId") Long teamAId,
            @Param("teamBId") Long teamBId,
            @Param("excludeId") Long excludeId);

    /** FINAL neutral-site games for a team in a given season. */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam " +
           "WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) " +
           "  AND g.season.id = :seasonId AND g.status = 'FINAL' " +
           "  AND g.neutralSite = true AND g.homeScore IS NOT NULL AND g.awayScore IS NOT NULL " +
           "ORDER BY g.gameDate DESC")
    List<Game> findNeutralSiteFinalGames(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId);

    /** FINAL games for a team in a given season, strictly before the cutoff date, oldest first. */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam " +
           "WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) " +
           "  AND g.season.id = :seasonId AND g.status = 'FINAL' " +
           "  AND g.homeScore IS NOT NULL AND g.awayScore IS NOT NULL " +
           "  AND g.gameDate < :beforeDate ORDER BY g.gameDate ASC")
    List<Game> findSeasonFinalGamesForTeamBefore(
            @Param("teamId") Long teamId,
            @Param("seasonId") Long seasonId,
            @Param("beforeDate") LocalDateTime beforeDate);

    /** All games for a season with teams fetched — drives season-wide conference aggregation. */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam " +
           "WHERE g.season.id = :seasonId")
    List<Game> findBySeasonIdWithTeams(@Param("seasonId") Long seasonId);

    /** Games in a season involving any of the given teams, with teams + odds fetched. */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.season.id = :seasonId " +
           "  AND (g.homeTeam.id IN :teamIds OR g.awayTeam.id IN :teamIds) " +
           "ORDER BY g.gameDate, g.id")
    List<Game> findBySeasonAndTeamIds(@Param("seasonId") Long seasonId,
                                      @Param("teamIds") Collection<Long> teamIds);

    // ── Games-by-date scoreboard ───────────────────────────────────────────────
    // gameDate is stored in UTC; callers pass the [startUtc, endUtc) window that an Eastern
    // calendar date maps to, so games are bucketed by their Eastern date, not their UTC date.

    /** Games whose UTC instant falls in [start, end), with teams + odds fetched. */
    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.gameDate >= :startUtc AND g.gameDate < :endUtc ORDER BY g.gameDate, g.id")
    List<Game> findInUtcWindow(@Param("startUtc") LocalDateTime startUtc,
                               @Param("endUtc") LocalDateTime endUtc);

    /** Earliest game instant (UTC) in a season — converted to an Eastern date by the caller. */
    @Query("SELECT MIN(g.gameDate) FROM Game g WHERE g.season.id = :seasonId")
    Optional<LocalDateTime> findMinGameDate(@Param("seasonId") Long seasonId);

    /** Latest game instant (UTC) in a season — converted to an Eastern date by the caller. */
    @Query("SELECT MAX(g.gameDate) FROM Game g WHERE g.season.id = :seasonId")
    Optional<LocalDateTime> findMaxGameDate(@Param("seasonId") Long seasonId);

    /** Latest game instant strictly before the given window start — drives "previous day with games." */
    @Query("SELECT MAX(g.gameDate) FROM Game g WHERE g.gameDate < :startUtc")
    Optional<LocalDateTime> findMaxGameDateBefore(@Param("startUtc") LocalDateTime startUtc);

    /** Earliest game instant on/after the given window end — drives "next day with games." */
    @Query("SELECT MIN(g.gameDate) FROM Game g WHERE g.gameDate >= :endUtc")
    Optional<LocalDateTime> findMinGameDateOnOrAfter(@Param("endUtc") LocalDateTime endUtc);
}
