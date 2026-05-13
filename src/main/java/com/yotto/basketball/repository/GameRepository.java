package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {

    long countByStatus(Game.GameStatus status);

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

    @Query("SELECT DISTINCT s FROM Game g JOIN g.season s WHERE g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId ORDER BY s.year DESC")
    List<Season> findSeasonsByTeamId(@Param("teamId") Long teamId);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam JOIN FETCH g.season LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.status = 'SCHEDULED' AND g.gameDate BETWEEN :start AND :end ORDER BY g.gameDate")
    List<Game> findScheduledBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT g FROM Game g JOIN FETCH g.homeTeam JOIN FETCH g.awayTeam JOIN FETCH g.season LEFT JOIN FETCH g.bettingOdds " +
           "WHERE g.id = :id")
    Optional<Game> findByIdWithDetails(@Param("id") Long id);

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
}
