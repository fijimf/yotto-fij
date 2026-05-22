package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MasseyRatingServiceTest extends BaseIntegrationTest {

    @Autowired MasseyRatingService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

    // Cleanup deps (shared singleton container — see test_beforeeach_cleanup_order memory)
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Season season;
    Team teamA, teamB, teamC;

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
        teamC = mkTeam("Clemson", "TC");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkFinalGame(Team home, Team away, int homeScore, int awayScore,
                             boolean neutral, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(neutral);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void seasonNotFound_doesNothing() {
        service.calculateAndStoreForSeason(9999);
        assertThat(ratingRepo.findAll()).isEmpty();
        assertThat(paramRepo.findAll()).isEmpty();
    }

    @Test
    void noFinalGames_doesNothing() {
        Game scheduled = new Game();
        scheduled.setHomeTeam(teamA);
        scheduled.setAwayTeam(teamB);
        scheduled.setStatus(Game.GameStatus.SCHEDULED);
        scheduled.setNeutralSite(false);
        scheduled.setSeason(season);
        scheduled.setGameDate(LocalDateTime.of(2025, 1, 10, 20, 0));
        gameRepo.save(scheduled);

        service.calculateAndStoreForSeason(2025);

        assertThat(ratingRepo.findAll()).isEmpty();
        assertThat(paramRepo.findAll()).isEmpty();
    }

    @Test
    void singleGame_producesSpreadAndTotalsSnapshotsForBothTeams() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));

        service.calculateAndStoreForSeason(2025);

        List<TeamPowerRatingSnapshot> spreadSnaps = ratingRepo.findBySeasonModelAndDate(
                season.getId(), MasseyRatingService.MODEL_TYPE, LocalDate.of(2025, 1, 10));
        List<TeamPowerRatingSnapshot> totalSnaps  = ratingRepo.findBySeasonModelAndDate(
                season.getId(), MasseyRatingService.MODEL_TYPE_TOTALS, LocalDate.of(2025, 1, 10));

        assertThat(spreadSnaps).hasSize(2);
        assertThat(totalSnaps).hasSize(2);
        // Team C never played — no snapshot
        assertThat(spreadSnaps).extracting(s -> s.getTeam().getId())
                .containsExactlyInAnyOrder(teamA.getId(), teamB.getId());
    }

    @Test
    void winningTeamRatedHigherThanLoser() {
        mkFinalGame(teamA, teamB, 90, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 85, 65, false, LocalDate.of(2025, 1, 17));

        service.calculateAndStoreForSeason(2025);

        LocalDate latest = LocalDate.of(2025, 1, 17);
        double ratingA = ratingFor(teamA, MasseyRatingService.MODEL_TYPE, latest);
        double ratingB = ratingFor(teamB, MasseyRatingService.MODEL_TYPE, latest);

        assertThat(ratingA).isGreaterThan(ratingB);
    }

    @Test
    void ranksAssignedByRatingDescending() {
        // Sweep — teamA dominates teamB and teamC
        mkFinalGame(teamA, teamB, 90, 60, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamC, 95, 65, false, LocalDate.of(2025, 1, 12));
        mkFinalGame(teamB, teamC, 75, 70, false, LocalDate.of(2025, 1, 14));

        service.calculateAndStoreForSeason(2025);

        List<TeamPowerRatingSnapshot> snaps = ratingRepo.findBySeasonModelAndDate(
                season.getId(), MasseyRatingService.MODEL_TYPE, LocalDate.of(2025, 1, 14));
        // findBySeasonModelAndDate orders by rank — rank 1 must be the highest-rated team
        assertThat(snaps.get(0).getRank()).isEqualTo(1);
        assertThat(snaps.get(0).getRating()).isGreaterThanOrEqualTo(snaps.get(1).getRating());
        assertThat(snaps.get(1).getRating()).isGreaterThanOrEqualTo(snaps.get(2).getRating());
        // teamA swept everyone — should rank 1
        assertThat(snaps.get(0).getTeam().getId()).isEqualTo(teamA.getId());
    }

    @Test
    void hcaParam_positiveWhenHomeTeamsConsistentlyWin() {
        // Same matchups, home team always wins by a comfortable margin
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamA, 80, 70, false, LocalDate.of(2025, 1, 14));
        mkFinalGame(teamA, teamC, 82, 72, false, LocalDate.of(2025, 1, 18));
        mkFinalGame(teamC, teamA, 82, 72, false, LocalDate.of(2025, 1, 22));

        service.calculateAndStoreForSeason(2025);

        double hca = paramValue(MasseyRatingService.MODEL_TYPE, "hca", LocalDate.of(2025, 1, 22));
        assertThat(hca).isPositive();
    }

    @Test
    void totalsModel_storesInterceptAndHcaTotalParams() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamA, 75, 65, false, LocalDate.of(2025, 1, 17));

        service.calculateAndStoreForSeason(2025);

        // Both 'intercept' and 'hca_total' params must be persisted on the latest date
        LocalDate latest = LocalDate.of(2025, 1, 17);
        assertThat(paramRepo.findLatestParamBefore(
                season.getId(), MasseyRatingService.MODEL_TYPE_TOTALS,
                "intercept", latest.plusDays(1))).isPresent();
        assertThat(paramRepo.findLatestParamBefore(
                season.getId(), MasseyRatingService.MODEL_TYPE_TOTALS,
                "hca_total", latest.plusDays(1))).isPresent();
    }

    @Test
    void snapshotsCreatedPerGameDate() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 82, 72, false, LocalDate.of(2025, 1, 17));
        mkFinalGame(teamA, teamB, 85, 75, false, LocalDate.of(2025, 1, 24));

        service.calculateAndStoreForSeason(2025);

        List<LocalDate> dates = ratingRepo.findSnapshotDates(
                season.getId(), MasseyRatingService.MODEL_TYPE);
        assertThat(dates).containsExactly(
                LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 1, 17),
                LocalDate.of(2025, 1, 24));
    }

    @Test
    void gamesPlayedReflectsCumulativeCount() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 82, 72, false, LocalDate.of(2025, 1, 17));
        mkFinalGame(teamA, teamB, 85, 75, false, LocalDate.of(2025, 1, 24));

        service.calculateAndStoreForSeason(2025);

        TeamPowerRatingSnapshot lastA = ratingRepo
                .findByTeamSeasonAndModel(teamA.getId(), season.getId(), MasseyRatingService.MODEL_TYPE)
                .stream().reduce((a, b) -> b).orElseThrow();
        assertThat(lastA.getGamesPlayed()).isEqualTo(3);
    }

    @Test
    void idempotent_secondCallReplacesPriorRows() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        service.calculateAndStoreForSeason(2025);
        long countAfterFirst = ratingRepo.count();
        long paramCountAfterFirst = paramRepo.count();

        service.calculateAndStoreForSeason(2025);

        // Same input → same number of persisted rows (no duplication).
        assertThat(ratingRepo.count()).isEqualTo(countAfterFirst);
        assertThat(paramRepo.count()).isEqualTo(paramCountAfterFirst);
    }

    @Test
    void gameWithNullScores_excludedFromFit() {
        Game incomplete = new Game();
        incomplete.setHomeTeam(teamA);
        incomplete.setAwayTeam(teamB);
        incomplete.setStatus(Game.GameStatus.FINAL);
        incomplete.setNeutralSite(false);
        incomplete.setSeason(season);
        incomplete.setGameDate(LocalDateTime.of(2025, 1, 10, 20, 0));
        gameRepo.save(incomplete);

        service.calculateAndStoreForSeason(2025);

        assertThat(ratingRepo.findAll()).isEmpty();
    }

    @Test
    void neutralSiteGames_doNotContributeToHca() {
        // All neutral-site games → HCA has no signal → α should be near zero
        mkFinalGame(teamA, teamB, 80, 70, true, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamA, 75, 65, true, LocalDate.of(2025, 1, 17));
        mkFinalGame(teamA, teamC, 78, 72, true, LocalDate.of(2025, 1, 18));
        mkFinalGame(teamC, teamA, 76, 70, true, LocalDate.of(2025, 1, 22));

        service.calculateAndStoreForSeason(2025);

        double hca = paramValue(MasseyRatingService.MODEL_TYPE, "hca", LocalDate.of(2025, 1, 22));
        // With only neutral games, the HCA column is never activated; the small stability
        // nudge (1e-6) on the diagonal drives the solution toward zero.
        assertThat(hca).isCloseTo(0.0, within(0.01));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double ratingFor(Team team, String modelType, LocalDate date) {
        Optional<TeamPowerRatingSnapshot> snap = ratingRepo.findLatestBefore(
                team.getId(), season.getId(), modelType, date.plusDays(1));
        return snap.orElseThrow().getRating();
    }

    private double paramValue(String modelType, String paramName, LocalDate date) {
        return paramRepo.findLatestParamBefore(season.getId(), modelType, paramName, date.plusDays(1))
                .orElseThrow().getParamValue();
    }
}
