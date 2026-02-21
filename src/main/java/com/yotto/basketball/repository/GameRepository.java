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
}
