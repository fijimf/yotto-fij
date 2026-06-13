package com.yotto.basketball.repository;

import com.yotto.basketball.BaseDataJpaTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Game.updatedAt is the change-detection signal for the stats calc gate: it must
 * move on a real change and stay put on a no-op save.
 */
class GameUpdatedAtTest extends BaseDataJpaTest {

    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TestEntityManager em;

    Season season;
    Team home, away;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        home = mkTeam("Alabama", "T1");
        away = mkTeam("Auburn", "T2");
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame() {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        g.setStatus(Game.GameStatus.SCHEDULED);
        return gameRepo.save(g);
    }

    @Test
    void insertSetsUpdatedAt() {
        Game g = mkGame();
        em.flush();

        assertThat(g.getUpdatedAt()).isNotNull();
    }

    @Test
    void noOpSaveDoesNotMoveUpdatedAt() {
        Game g = mkGame();
        em.flush();
        LocalDateTime initial = g.getUpdatedAt();

        // Setting the same values leaves the entity clean — @PreUpdate must not fire
        g.setStatus(Game.GameStatus.SCHEDULED);
        gameRepo.save(g);
        em.flush();

        assertThat(g.getUpdatedAt()).isEqualTo(initial);
    }

    @Test
    void realChangeMovesUpdatedAt() throws InterruptedException {
        Game g = mkGame();
        em.flush();
        LocalDateTime initial = g.getUpdatedAt();

        Thread.sleep(5); // ensure the clock advances past the insert timestamp

        g.setHomeScore(80);
        g.setAwayScore(70);
        g.setStatus(Game.GameStatus.FINAL);
        gameRepo.save(g);
        em.flush();

        assertThat(g.getUpdatedAt()).isAfter(initial);
    }
}
