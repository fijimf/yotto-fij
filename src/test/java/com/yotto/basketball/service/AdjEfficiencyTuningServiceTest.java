package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.RatingTuningRun;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.RatingTuningRunRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The walk-forward sweep against the same hand-solved two-team scenario as
 * {@link AdjustedEfficiencyRatingServiceTest}: after date 1 (X home 110–90 Y, 100
 * possessions, λ irrelevant because the unpenalized μ/η absorb the single game's
 * residuals) the solution is off/def = 0, μ = 100, η = 10, τ = 0, ν = 100. Date 2's
 * game (Y home vs X) is therefore predicted at spread = 2η = 20 for EVERY λ, while
 * the actual margin is −10.
 */
class AdjEfficiencyTuningServiceTest extends BaseIntegrationTest {

    @Autowired AdjEfficiencyTuningService service;
    @Autowired RatingTuningRunRepository runRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamGameStatsRepository statsRepo;

    private static final LocalDate D1 = LocalDate.of(2025, 1, 5);
    private static final LocalDate D2 = LocalDate.of(2025, 1, 12);

    Season season;
    Team x, y;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);
        x = mkTeam("Xavier", "TX");
        y = mkTeam("Yale", "TY");

        Game g1 = mkFinalGame(x, y, 110, 90, D1);
        Game g2 = mkFinalGame(y, x, 95, 105, D2);
        mkBox(g1, x, "home");
        mkBox(g1, y, "away");
        mkBox(g2, y, "home");
        mkBox(g2, x, "away");
    }

    @Test
    void sweep_handSolvedPredictionAndLeakageGuard() {
        AdjEfficiencyTuningService.SweepReport report = service.runSweep();

        assertThat(report.seasons()).containsExactly(2025);
        assertThat(report.perLambda()).hasSize(8);   // default grid

        for (AdjEfficiencyTuningService.LambdaResult lr : report.perLambda()) {
            // g1 excluded (no prior fit games — the leakage guard); only g2 predicted
            assertThat(lr.overall().n()).isEqualTo(1);
            // predicted spread 20 (Y favored at home), actual −10 → MAE 30.
            // Tolerance 1e-3: the solver's 1e-6 stability nudge on μ/η shifts
            // the closed-form solution by ~2e-4 (same convention as the rating tests).
            assertThat(lr.overall().mae()).isCloseTo(30.0, within(1e-3));
            // one residual → population stddev 0
            assertThat(lr.overall().residSigma()).isCloseTo(0.0, within(1e-9));
            // home (Y) lost: log loss = −ln(1 − Φ(20/σ))
            double expected = -Math.log(1 - WinProbability.fromMargin(20.0, report.sigma()));
            assertThat(lr.overall().logLoss()).isCloseTo(expected, within(1e-3));
        }
    }

    @Test
    void sweep_isDeterministic() {
        AdjEfficiencyTuningService.SweepReport first  = service.runSweep();
        AdjEfficiencyTuningService.SweepReport second = service.runSweep();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void startRun_rejectsConcurrentSweep_andFailsStaleOne() {
        RatingTuningRun fresh = new RatingTuningRun();
        fresh.setModelType("ADJ_EFF");
        fresh.setStatus(RatingTuningRun.Status.RUNNING);
        fresh.setStartedAt(LocalDateTime.now());
        runRepo.save(fresh);

        assertThatThrownBy(() -> service.startRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");

        // A stale RUNNING row (presumed orphaned) is failed and does not block
        fresh.setStartedAt(LocalDateTime.now().minusHours(3));
        runRepo.save(fresh);
        Long newRunId = service.startRun();
        assertThat(newRunId).isNotNull();
        assertThat(runRepo.findById(fresh.getId()).orElseThrow().getStatus())
                .isEqualTo(RatingTuningRun.Status.FAILED);
    }

    @Test
    void fullLifecycle_completesRunWithParsableResults() throws Exception {
        Long runId = service.startRun();
        service.runSweepAsync(runId);

        RatingTuningRun run = null;
        for (int i = 0; i < 60; i++) {
            run = runRepo.findById(runId).orElseThrow();
            if (run.getStatus() != RatingTuningRun.Status.RUNNING) break;
            Thread.sleep(500);
        }
        assertThat(run.getStatus()).isEqualTo(RatingTuningRun.Status.COMPLETED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getParams()).contains("grid");

        AdjEfficiencyTuningService.RunView view = service.recentRunViews().stream()
                .filter(v -> v.run().getId().equals(runId)).findFirst().orElseThrow();
        assertThat(view.report()).isNotNull();
        assertThat(view.report().perLambda()).hasSize(8);
        assertThat(view.report().bestLambda()).isNotNull();
    }

    @Test
    void parseGrid_parsesAndRejectsEmpty() {
        assertThat(AdjEfficiencyTuningService.parseGrid("1, 2.5 ,4"))
                .containsExactly(1.0, 2.5, 4.0);
        assertThatThrownBy(() -> AdjEfficiencyTuningService.parseGrid(" , "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Fixtures (mirrors AdjustedEfficiencyRatingServiceTest) ───────────────

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkFinalGame(Team home, Team away, int homeScore, int awayScore, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    /** Box line with exactly 100 possessions: 80 − 10 + 11 + 0.475·40 = 100. */
    private void mkBox(Game game, Team team, String homeAway) {
        TeamGameStats s = new TeamGameStats();
        s.setGame(game);
        s.setTeam(team);
        s.setHomeAway(homeAway);
        s.setFgAttempted(80);
        s.setOffensiveReb(10);
        s.setTurnovers(11);
        s.setFtAttempted(40);
        s.setScrapeDate(LocalDateTime.now());
        statsRepo.save(s);
    }
}
