package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.StatCalcGateService.Mode;
import com.yotto.basketball.service.StatCalcGateService.RecalcScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StatCalcGateServiceTest extends BaseIntegrationTest {

    @Autowired StatCalcGateService gate;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamGameStatsRepository teamGameStatsRepo;
    @Autowired StatCalcWatermarkRepository watermarkRepo;

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

    private Game mkFinalGame(LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        g.setHomeScore(80);
        g.setAwayScore(70);
        g.setStatus(Game.GameStatus.FINAL);
        return gameRepo.save(g);
    }

    /** Runs check + recordRun, then waits a tick so later changes are strictly after the watermark. */
    private void runAndRecord() throws InterruptedException {
        RecalcScope scope = gate.check(2025);
        gate.recordRun(2025, scope);
        Thread.sleep(5);
    }

    @Test
    void unknownSeason_skips() {
        assertThat(gate.check(9999).mode()).isEqualTo(Mode.SKIP);
    }

    @Test
    void firstRun_isFull() {
        mkFinalGame(LocalDate.of(2025, 1, 10));

        RecalcScope scope = gate.check(2025);

        assertThat(scope.mode()).isEqualTo(Mode.FULL);
        assertThat(scope.calcStartedAt()).isNotNull();
        assertThat(scope.finalGameCount()).isEqualTo(1);
    }

    @Test
    void recordRunThenNoChanges_skips() throws InterruptedException {
        mkFinalGame(LocalDate.of(2025, 1, 10));
        runAndRecord();

        assertThat(gate.check(2025).mode()).isEqualTo(Mode.SKIP);
    }

    @Test
    void scoreChangeAfterRun_isIncrementalFromThatGameDate() throws InterruptedException {
        mkFinalGame(LocalDate.of(2025, 1, 10));
        Game changed = mkFinalGame(LocalDate.of(2025, 1, 20));
        runAndRecord();

        changed.setHomeScore(99);
        gameRepo.save(changed);

        RecalcScope scope = gate.check(2025);
        assertThat(scope.mode()).isEqualTo(Mode.INCREMENTAL);
        assertThat(scope.fromDate()).isEqualTo(LocalDate.of(2025, 1, 20));
    }

    @Test
    void newGameAfterRun_isIncremental() throws InterruptedException {
        mkFinalGame(LocalDate.of(2025, 1, 10));
        runAndRecord();

        mkFinalGame(LocalDate.of(2025, 2, 1));

        RecalcScope scope = gate.check(2025);
        assertThat(scope.mode()).isEqualTo(Mode.INCREMENTAL);
        assertThat(scope.fromDate()).isEqualTo(LocalDate.of(2025, 2, 1));
    }

    @Test
    void newBoxScoreAfterRun_isIncremental() throws InterruptedException {
        Game g = mkFinalGame(LocalDate.of(2025, 1, 10));
        runAndRecord();

        TeamGameStats stats = new TeamGameStats();
        stats.setGame(g);
        stats.setTeam(home);
        stats.setHomeAway("HOME");
        stats.setScrapeDate(LocalDateTime.now());
        teamGameStatsRepo.save(stats);

        RecalcScope scope = gate.check(2025);
        assertThat(scope.mode()).isEqualTo(Mode.INCREMENTAL);
        assertThat(scope.fromDate()).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    @Test
    void deletedFinalGame_forcesFullRecalc() throws InterruptedException {
        mkFinalGame(LocalDate.of(2025, 1, 10));
        Game toDelete = mkFinalGame(LocalDate.of(2025, 1, 20));
        runAndRecord();

        gameRepo.delete(toDelete);

        assertThat(gate.check(2025).mode()).isEqualTo(Mode.FULL);
    }

    @Test
    void recordRun_upsertsSingleWatermarkRow() throws InterruptedException {
        mkFinalGame(LocalDate.of(2025, 1, 10));
        runAndRecord();
        runAndRecord(); // second run must update, not duplicate

        assertThat(watermarkRepo.findAll()).hasSize(1);
        assertThat(watermarkRepo.findBySeasonId(season.getId())).isPresent();
    }
}
