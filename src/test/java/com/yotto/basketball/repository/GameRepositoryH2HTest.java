package com.yotto.basketball.repository;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class GameRepositoryH2HTest extends BaseIntegrationTest {

    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;

    Team home, away, third;
    Season season;
    Long currentGameId;

    @BeforeEach
    void setUp() {
        gameRepo.deleteAll();
        teamRepo.deleteAll();
        seasonRepo.deleteAll();

        home = mkTeam("Duke", "DUKE");
        away = mkTeam("UNC", "UNC");
        third = mkTeam("NC State", "NCST");

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        // Current game (to be excluded)
        Game current = mkFinalGame(home, away, 70, 65, LocalDateTime.of(2025, 2, 10, 19, 0), false);
        currentGameId = current.getId();

        // H2H historical games
        mkFinalGame(home, away, 75, 60, LocalDateTime.of(2025, 1, 5, 19, 0), false);   // home win
        mkFinalGame(away, home, 80, 70, LocalDateTime.of(2024, 12, 1, 19, 0), false);   // away wins (Duke is away, loses)
        mkFinalGame(home, away, 65, 70, LocalDateTime.of(2024, 1, 10, 19, 0), false);   // home loses

        // Neutral game — home wins
        mkFinalGame(home, away, 88, 77, LocalDateTime.of(2025, 1, 20, 12, 0), true);
    }

    @Test
    void findAllH2HGames_excludesCurrentGame() {
        List<Game> games = gameRepo.findAllH2HGames(home.getId(), away.getId(), currentGameId);
        assertThat(games).hasSize(4);
        assertThat(games).noneMatch(g -> g.getId().equals(currentGameId));
    }

    @Test
    void findAllH2HGames_orderedByDateDesc() {
        List<Game> games = gameRepo.findAllH2HGames(home.getId(), away.getId(), currentGameId);
        assertThat(games.get(0).getGameDate()).isAfter(games.get(1).getGameDate());
    }

    @Test
    void findNeutralSiteFinalGames_returnsOnlyNeutral() {
        List<Game> neutralGames = gameRepo.findNeutralSiteFinalGames(home.getId(), season.getId());
        assertThat(neutralGames).hasSize(1);
        assertThat(neutralGames.get(0).getNeutralSite()).isTrue();
    }

    @Test
    void findSeasonFinalGamesForTeamBefore_excludesFutureAndCurrentGame() {
        LocalDateTime cutoff = LocalDateTime.of(2025, 2, 10, 0, 0);  // same day as current game
        List<Game> games = gameRepo.findSeasonFinalGamesForTeamBefore(home.getId(), season.getId(), cutoff);
        // Includes Jan 5, Dec 1 2024 (Duke away), Jan 20 (neutral), and Jan 10 2024 — all before cutoff in season
        // but NOT the Feb 10 current game
        assertThat(games).hasSize(4);
        assertThat(games).noneMatch(g -> g.getId().equals(currentGameId));
    }

    private Game mkFinalGame(Team h, Team a, int hScore, int aScore, LocalDateTime date, boolean neutral) {
        Game g = new Game();
        g.setHomeTeam(h);
        g.setAwayTeam(a);
        g.setHomeScore(hScore);
        g.setAwayScore(aScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(neutral);
        g.setSeason(season);
        g.setGameDate(date);
        return gameRepo.save(g);
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setAbbreviation(abbr);
        t.setEspnId(abbr + "-id");
        t.setActive(true);
        return teamRepo.save(t);
    }
}
