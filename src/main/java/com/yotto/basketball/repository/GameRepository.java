package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {

    List<Game> findBySeasonId(Long seasonId);

    List<Game> findByTournamentId(Long tournamentId);

    List<Game> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);

    List<Game> findByStatus(Game.GameStatus status);

    List<Game> findByGameDateBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT g FROM Game g WHERE (g.homeTeam.id = :teamId OR g.awayTeam.id = :teamId) AND g.season.id = :seasonId")
    List<Game> findByTeamAndSeason(@Param("teamId") Long teamId, @Param("seasonId") Long seasonId);

    @Query("SELECT g FROM Game g WHERE g.conferenceGame = true AND g.season.id = :seasonId")
    List<Game> findConferenceGamesBySeason(@Param("seasonId") Long seasonId);
}
