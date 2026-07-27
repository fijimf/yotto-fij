package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end contract test for the prior-v3 feature path: seeds real data, loads the
 * prior-v3 ONNX fixture bundle (whose spread output echoes {@code home_prev_beta} and
 * whose total output echoes {@code home_massey_resid_l5}), and asserts the full
 * predict pipeline produces the HAND-COMPUTED values.
 *
 * <p>The residual scenario uses the SAME literals as the Python side
 * ({@code scripts/tests/test_train_models.py}, TestMasseyResidualL5) — do not change
 * one side only:
 *
 * <pre>
 * hca = 3.0 (from 2025-01-02)
 * g1 2025-01-05  H 80–70 O   snapshots 01-04: H=5.0 O=2.0 A=4.0
 * g2 2025-01-12  O 75–72 H   snapshots 01-11: H=5.5 O=2.5
 * g3 2025-01-10  A 60–58 O
 * resid(H) = ((10 − (5.0−2.0+3.0)) + −(3 − (2.5−5.5+3.0))) / 2 = 0.5
 * prev-season (2024) final Massey for H = 7.25 (a mid-season 6.0 must NOT be used)
 * </pre>
 */
class PriorV3PredictionIntegrationTest extends BaseIntegrationTest {

    @Autowired PredictionService predictionService;
    @Autowired MlModelRegistryService mlModelRegistryService;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired TeamStatSnapshotRepository statSnapshotRepo;
    @Autowired TeamSeasonStatSnapshotRepository seasonSnapshotRepo;

    private static final LocalDate STATS_DATE = LocalDate.of(2025, 1, 14);

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) {
        registry.add("prediction.ml.enabled", () -> "true");
        registry.add("prediction.ml.model-dir", () -> {
            try {
                return Paths.get(PriorV3PredictionIntegrationTest.class.getClassLoader()
                        .getResource("ml-models-prior-v3/features.json").toURI()).getParent().toString();
            } catch (Exception e) {
                throw new IllegalStateException("ml-models-prior-v3 fixtures missing", e);
            }
        });
    }

    @Test
    void priorV3Bundle_predictsWithHandComputedPriorAndResidualFeatures() {
        Season prior  = mkSeason(2024);
        Season season = mkSeason(2025);
        Team h = mkTeam("Home U", "H1");
        Team a = mkTeam("Away U", "A1");
        Team o = mkTeam("Opponent U", "O1");

        // Contract games (all FINAL, non-neutral)
        mkFinal(season, h, o, LocalDate.of(2025, 1, 5), 80, 70);   // g1
        mkFinal(season, o, h, LocalDate.of(2025, 1, 12), 75, 72);  // g2
        mkFinal(season, a, o, LocalDate.of(2025, 1, 10), 60, 58);  // g3

        // Massey series for the residual computation + the current prediction
        snap(h, season, "MASSEY", 5.0, LocalDate.of(2025, 1, 4));
        snap(o, season, "MASSEY", 2.0, LocalDate.of(2025, 1, 4));
        snap(a, season, "MASSEY", 4.0, LocalDate.of(2025, 1, 4));
        snap(h, season, "MASSEY", 5.5, LocalDate.of(2025, 1, 11));
        snap(o, season, "MASSEY", 2.5, LocalDate.of(2025, 1, 11));
        param(season, "MASSEY", "hca", 3.0, LocalDate.of(2025, 1, 2));

        // Remaining rating systems for hasAll() (values arbitrary)
        for (Team t : List.of(h, a)) {
            snap(t, season, "MASSEY_TOTALS", 70.0, STATS_DATE);
            snap(t, season, "BRADLEY_TERRY", 0.5, STATS_DATE);
            snap(t, season, "BRADLEY_TERRY_W", 0.6, STATS_DATE);
        }

        // Box stats: every stat name the prior-v3 manifest needs
        List<String> boxStats = List.of(
                "pace", "off_efficiency", "def_efficiency", "efg_pct", "opp_efg_pct", "tov_rate",
                "orb_pct", "drb_pct", "ft_rate", "opp_ft_rate", "opp_tov_rate", "fg3_rate");
        for (Team t : List.of(h, a)) {
            for (String stat : boxStats) {
                boxStat(t, season, stat, 0.5);
            }
            seasonSnapshot(t, season);
        }

        // Previous-season final ratings: a mid-season snapshot must NOT win
        snap(h, prior, "MASSEY", 6.0, LocalDate.of(2024, 1, 15));
        snap(h, prior, "MASSEY", 7.25, LocalDate.of(2024, 3, 30));
        snap(h, prior, "BRADLEY_TERRY", 0.85, LocalDate.of(2024, 3, 30));
        snap(a, prior, "MASSEY", 3.5, LocalDate.of(2024, 3, 30));
        snap(a, prior, "BRADLEY_TERRY", 0.4, LocalDate.of(2024, 3, 30));

        // The game to predict
        Game game = new Game();
        game.setHomeTeam(h);
        game.setAwayTeam(a);
        game.setStatus(Game.GameStatus.SCHEDULED);
        game.setNeutralSite(false);
        game.setSeason(season);
        game.setGameDate(LocalDate.of(2025, 1, 20).atTime(20, 0));
        game = gameRepo.save(game);

        // Load the prior-v3 fixture bundle (first model → ACTIVE default) post-truncate
        mlModelRegistryService.reloadAndReconcile();
        assertThat(mlModelRegistryService.plan().needsPriorRatings()).isTrue();
        assertThat(mlModelRegistryService.plan().needsResidualForm()).isTrue();

        PredictionResult result = predictionService.predict(game.getId());

        assertThat(result.ml()).as("prior-v3 bundle should predict").isNotNull();
        // spread fixture echoes home_prev_beta → prior-season FINAL Massey rating
        assertThat(result.ml().spread()).isCloseTo(7.25, within(1e-4));
        // total fixture echoes home_massey_resid_l5 → hand-computed 0.5
        assertThat(result.ml().total()).isCloseTo(0.5, within(1e-4));
        assertThat(result.ml().homeWinProbability()).isCloseTo(0.5, within(1e-6));
    }

    @Test
    void missingPriorSeason_stillPredictsWithZeroImputedPriors() {
        Season season = mkSeason(2025);   // no 2024 season exists
        Team h = mkTeam("Home U", "H1");
        Team a = mkTeam("Away U", "A1");
        Team o = mkTeam("Opponent U", "O1");

        mkFinal(season, h, o, LocalDate.of(2025, 1, 5), 80, 70);
        mkFinal(season, o, h, LocalDate.of(2025, 1, 12), 75, 72);
        mkFinal(season, a, o, LocalDate.of(2025, 1, 10), 60, 58);

        snap(h, season, "MASSEY", 5.0, LocalDate.of(2025, 1, 4));
        snap(o, season, "MASSEY", 2.0, LocalDate.of(2025, 1, 4));
        snap(a, season, "MASSEY", 4.0, LocalDate.of(2025, 1, 4));
        snap(h, season, "MASSEY", 5.5, LocalDate.of(2025, 1, 11));
        snap(o, season, "MASSEY", 2.5, LocalDate.of(2025, 1, 11));
        param(season, "MASSEY", "hca", 3.0, LocalDate.of(2025, 1, 2));
        for (Team t : List.of(h, a)) {
            snap(t, season, "MASSEY_TOTALS", 70.0, STATS_DATE);
            snap(t, season, "BRADLEY_TERRY", 0.5, STATS_DATE);
            snap(t, season, "BRADLEY_TERRY_W", 0.6, STATS_DATE);
            for (String stat : List.of(
                    "pace", "off_efficiency", "def_efficiency", "efg_pct", "opp_efg_pct", "tov_rate",
                    "orb_pct", "drb_pct", "ft_rate", "opp_ft_rate", "opp_tov_rate", "fg3_rate")) {
                boxStat(t, season, stat, 0.5);
            }
            seasonSnapshot(t, season);
        }

        Game game = new Game();
        game.setHomeTeam(h);
        game.setAwayTeam(a);
        game.setStatus(Game.GameStatus.SCHEDULED);
        game.setNeutralSite(false);
        game.setSeason(season);
        game.setGameDate(LocalDate.of(2025, 1, 20).atTime(20, 0));
        game = gameRepo.save(game);

        mlModelRegistryService.reloadAndReconcile();
        PredictionResult result = predictionService.predict(game.getId());

        assertThat(result.ml()).as("priors are imputed, not required").isNotNull();
        // spread fixture echoes home_prev_beta → 0.0 when no prior season exists
        assertThat(result.ml().spread()).isCloseTo(0.0, within(1e-6));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Season mkSeason(int year) {
        Season s = new Season();
        s.setYear(year);
        s.setStartDate(LocalDate.of(year - 1, 11, 1));
        s.setEndDate(LocalDate.of(year, 4, 30));
        return seasonRepo.save(s);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkFinal(Season season, Team home, Team away, LocalDate date,
                         int homeScore, int awayScore) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(Game.GameStatus.FINAL);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(date.atTime(19, 0));
        gameRepo.save(g);
    }

    private void snap(Team team, Season season, String modelType, double rating, LocalDate date) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(date);
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void param(Season season, String modelType, String name, double value, LocalDate date) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setParamName(name);
        p.setParamValue(value);
        p.setSnapshotDate(date);
        p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }

    private void boxStat(Team team, Season season, String statName, double value) {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(STATS_DATE);
        s.setStatName(statName);
        s.setValue(value);
        s.setGamesPlayed(10);
        statSnapshotRepo.save(s);
    }

    private void seasonSnapshot(Team team, Season season) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(STATS_DATE);
        s.setGamesPlayed(10);
        s.setWins(6);
        s.setLosses(4);
        s.setRpi(0.55);
        s.setStddevMargin(9.0);
        s.setRpiOwp(0.51);
        seasonSnapshotRepo.save(s);
    }
}
