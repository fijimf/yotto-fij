package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamGameStats;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamGameStatsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the ridge fit against a hand-solved two-team scenario. Both games are
 * constructed to have exactly 100 possessions per team
 * (FGA 80 − ORB 10 + TO 11 + 0.475·FTA 40 = 100), so efficiencies equal raw scores:
 *
 * <pre>
 * g1 2025-01-05  X home 110–90 Y
 * g2 2025-01-12  Y home 95–105 X
 *
 * After g1 only (residuals can vanish with zero team params):
 *   off/def = 0 for both teams, μ = 100, η = 10
 * After both games (derived in closed form for λ = 1):
 *   u = offX − defY satisfies u(4+λ) = 430 − 4μ; symmetry forces μ = 100, η = 2.5,
 *   u = 6 → offX = defX = +3, offY = defY = −3
 * </pre>
 */
class AdjustedEfficiencyRatingServiceTest extends BaseIntegrationTest {

    @Autowired AdjustedEfficiencyRatingService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamGameStatsRepository statsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

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
    }

    private void seedTwoGameScenario() {
        Game g1 = mkFinalGame(x, y, 110, 90, D1);
        Game g2 = mkFinalGame(y, x, 95, 105, D2);
        mkBox(g1, x, "home");
        mkBox(g1, y, "away");
        mkBox(g2, y, "home");
        mkBox(g2, x, "away");
    }

    @Test
    void handSolvedScenario_producesExactRatingsAndParams() {
        seedTwoGameScenario();

        service.calculateAndStoreForSeason(2025);

        // Date 1: one game — residuals vanish via μ and η alone, team params shrink to 0
        assertThat(rating(x, "ADJ_OFF", D1)).isCloseTo(0.0, within(1e-3));
        assertThat(rating(y, "ADJ_DEF", D1)).isCloseTo(0.0, within(1e-3));
        assertThat(param("eff_intercept", D1)).isCloseTo(100.0, within(1e-3));
        assertThat(param("eff_hca", D1)).isCloseTo(10.0, within(1e-3));

        // Date 2: closed-form solution for λ = 1
        assertThat(rating(x, "ADJ_OFF", D2)).isCloseTo(3.0, within(1e-3));
        assertThat(rating(x, "ADJ_DEF", D2)).isCloseTo(3.0, within(1e-3));
        assertThat(rating(y, "ADJ_OFF", D2)).isCloseTo(-3.0, within(1e-3));
        assertThat(rating(y, "ADJ_DEF", D2)).isCloseTo(-3.0, within(1e-3));
        assertThat(param("eff_intercept", D2)).isCloseTo(100.0, within(1e-3));
        assertThat(param("eff_hca", D2)).isCloseTo(2.5, within(1e-3));

        // Ranks: X leads both boards on date 2; games played counts fit games
        TeamPowerRatingSnapshot xOff = snap(x, "ADJ_OFF", D2);
        assertThat(xOff.getRank()).isEqualTo(1);
        assertThat(xOff.getGamesPlayed()).isEqualTo(2);
        assertThat(snap(x, "ADJ_DEF", D2).getRank()).isEqualTo(1);
        assertThat(snap(y, "ADJ_OFF", D2).getRank()).isEqualTo(2);
    }

    @Test
    void gameWithoutBoxScores_isExcludedFromFitButDateCarriesForward() {
        seedTwoGameScenario();
        // Third game with NO box scores: must not move the ratings
        mkFinalGame(x, y, 150, 50, LocalDate.of(2025, 1, 19));

        service.calculateAndStoreForSeason(2025);

        LocalDate d3 = LocalDate.of(2025, 1, 19);
        assertThat(rating(x, "ADJ_OFF", d3)).isCloseTo(3.0, within(1e-3));
        assertThat(param("eff_hca", d3)).isCloseTo(2.5, within(1e-3));
        // games played unchanged — the blowout was not fit
        assertThat(snap(x, "ADJ_OFF", d3).getGamesPlayed()).isEqualTo(2);
    }

    @Test
    void rerunIsIdempotent() {
        seedTwoGameScenario();

        service.calculateAndStoreForSeason(2025);
        long countFirst = ratingRepo.count();
        service.calculateAndStoreForSeason(2025);

        assertThat(ratingRepo.count()).isEqualTo(countFirst);
        assertThat(rating(x, "ADJ_OFF", D2)).isCloseTo(3.0, within(1e-3));
    }

    @Test
    void watermarkRun_rewritesOnlyFromDate() {
        seedTwoGameScenario();
        service.calculateAndStoreForSeason(2025);
        LocalDateTime firstCalc = snap(x, "ADJ_OFF", D1).getCalculatedAt();

        service.calculateAndStoreForSeason(2025, D2);

        // D1 snapshot untouched, D2 rewritten with identical values
        assertThat(snap(x, "ADJ_OFF", D1).getCalculatedAt()).isEqualTo(firstCalc);
        assertThat(rating(x, "ADJ_OFF", D2)).isCloseTo(3.0, within(1e-3));
        assertThat(param("eff_intercept", D2)).isCloseTo(100.0, within(1e-3));
    }

    @Test
    void seasonWithNoUsableBoxScores_writesNoSnapshots() {
        mkFinalGame(x, y, 80, 70, D1);   // no box scores at all

        service.calculateAndStoreForSeason(2025);

        assertThat(ratingRepo.count()).isZero();
        assertThat(paramRepo.count()).isZero();
    }

    @Test
    void seasonNotFound_doesNothing() {
        service.calculateAndStoreForSeason(9999);
        assertThat(ratingRepo.count()).isZero();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

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

    private double rating(Team team, String modelType, LocalDate date) {
        return snap(team, modelType, date).getRating();
    }

    private TeamPowerRatingSnapshot snap(Team team, String modelType, LocalDate date) {
        return ratingRepo.findLatestBefore(team.getId(), season.getId(), modelType, date.plusDays(1))
                .filter(s -> s.getSnapshotDate().equals(date))
                .orElseThrow(() -> new AssertionError(
                        "no " + modelType + " snapshot for " + team.getName() + " on " + date));
    }

    private double param(String name, LocalDate date) {
        return paramRepo.findLatestParamBefore(season.getId(),
                        AdjustedEfficiencyRatingService.MODEL_TYPE_OFF, name, date.plusDays(1))
                .filter(p -> p.getSnapshotDate().equals(date))
                .map(PowerModelParamSnapshot::getParamValue)
                .orElseThrow(() -> new AssertionError("no param " + name + " on " + date));
    }
}
