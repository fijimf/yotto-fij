package com.yotto.basketball.service;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class GameService {

    private final GameRepository gameRepository;
    private final TeamService teamService;
    private final SeasonService seasonService;

    public GameService(GameRepository gameRepository, TeamService teamService,
                       SeasonService seasonService) {
        this.gameRepository = gameRepository;
        this.teamService = teamService;
        this.seasonService = seasonService;
    }

    public Game create(Game game, Long homeTeamId, Long awayTeamId, Long seasonId) {
        Team homeTeam = teamService.findById(homeTeamId);
        Team awayTeam = teamService.findById(awayTeamId);
        Season season = seasonService.findById(seasonId);

        game.setHomeTeam(homeTeam);
        game.setAwayTeam(awayTeam);
        game.setSeason(season);

        return gameRepository.save(game);
    }

    @Transactional(readOnly = true)
    public Game findById(Long id) {
        return gameRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Game not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<Game> findAll() {
        return gameRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Game> findBySeason(Long seasonId) {
        return gameRepository.findBySeasonId(seasonId);
    }

    @Transactional(readOnly = true)
    public List<Game> findByTeam(Long teamId) {
        return gameRepository.findByHomeTeamIdOrAwayTeamId(teamId, teamId);
    }

    @Transactional(readOnly = true)
    public List<Game> findByTeamAndSeason(Long teamId, Long seasonId) {
        return gameRepository.findByTeamAndSeason(teamId, seasonId);
    }

    @Transactional(readOnly = true)
    public List<Game> findByDateRange(LocalDateTime start, LocalDateTime end) {
        return gameRepository.findByGameDateBetween(start, end);
    }

    @Transactional(readOnly = true)
    public List<Game> findByStatus(Game.GameStatus status) {
        return gameRepository.findByStatus(status);
    }

    public Game updateScore(Long id, Integer homeScore, Integer awayScore) {
        Game game = findById(id);
        game.setHomeScore(homeScore);
        game.setAwayScore(awayScore);
        game.setStatus(Game.GameStatus.FINAL);
        return gameRepository.save(game);
    }

    public Game updateStatus(Long id, Game.GameStatus status) {
        Game game = findById(id);
        game.setStatus(status);
        return gameRepository.save(game);
    }

    public Game update(Long id, Game game) {
        Game existing = findById(id);
        existing.setGameDate(game.getGameDate());
        existing.setVenue(game.getVenue());
        existing.setHomeScore(game.getHomeScore());
        existing.setAwayScore(game.getAwayScore());
        existing.setStatus(game.getStatus());
        existing.setNeutralSite(game.getNeutralSite());
        existing.setConferenceGame(game.getConferenceGame());
        return gameRepository.save(existing);
    }

    public void delete(Long id) {
        if (!gameRepository.existsById(id)) {
            throw new EntityNotFoundException("Game not found with id: " + id);
        }
        gameRepository.deleteById(id);
    }
}
