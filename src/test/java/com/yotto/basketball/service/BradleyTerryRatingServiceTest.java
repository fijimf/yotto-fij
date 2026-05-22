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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BradleyTerryRatingServiceTest extends BaseIntegrationTest {

    @Autowired BradleyTerryRatingService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

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
        service.calculateAndStoreForSeason(2025);
        assertThat(ratingRepo.findAll()).isEmpty();
    }

    @Test
    void singleGame_producesUnweightedAndWeightedSnapshotsForBothTeams() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));

        service.calculateAndStoreForSeason(2025);

        List<TeamPowerRatingSnapshot> unweighted = ratingRepo.findBySeasonModelAndDate(
                season.getId(), BradleyTerryRatingService.MODEL_TYPE, LocalDate.of(2025, 1, 10));
        List<TeamPowerRatingSnapshot> weighted   = ratingRepo.findBySeasonModelAndDate(
                season.getId(), BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, LocalDate.of(2025, 1, 10));

        assertThat(unweighted).hasSize(2);
        assertThat(weighted).hasSize(2);
        assertThat(unweighted).extracting(s -> s.getTeam().getId())
                .containsExactlyInAnyOrder(teamA.getId(), teamB.getId());
    }

    @Test
    void winningTeamRatedHigherThanLoser() {
        mkFinalGame(teamA, teamB, 80, 65, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 78, 60, false, LocalDate.of(2025, 1, 17));

        service.calculateAndStoreForSeason(2025);

        double ratingA = ratingFor(teamA, BradleyTerryRatingService.MODEL_TYPE, LocalDate.of(2025, 1, 17));
        double ratingB = ratingFor(teamB, BradleyTerryRatingService.MODEL_TYPE, LocalDate.of(2025, 1, 17));

        assertThat(ratingA).isGreaterThan(ratingB);
    }

    @Test
    void rankingsOrderedByRatingDescending() {
        // teamA sweeps; teamC loses to everyone
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamC, 90, 60, false, LocalDate.of(2025, 1, 12));
        mkFinalGame(teamB, teamC, 75, 65, false, LocalDate.of(2025, 1, 14));

        service.calculateAndStoreForSeason(2025);

        List<TeamPowerRatingSnapshot> snaps = ratingRepo.findBySeasonModelAndDate(
                season.getId(), BradleyTerryRatingService.MODEL_TYPE, LocalDate.of(2025, 1, 14));

        assertThat(snaps).hasSize(3);
        assertThat(snaps.get(0).getRank()).isEqualTo(1);
        assertThat(snaps.get(0).getTeam().getId()).isEqualTo(teamA.getId());
        assertThat(snaps.get(2).getTeam().getId()).isEqualTo(teamC.getId());
        assertThat(snaps.get(0).getRating()).isGreaterThanOrEqualTo(snaps.get(1).getRating());
        assertThat(snaps.get(1).getRating()).isGreaterThanOrEqualTo(snaps.get(2).getRating());
    }

    @Test
    void hcaParam_recordedAsPositiveWhenHomeTeamsConsistentlyWin() {
        // Symmetric matchups, home team always wins → HCA must be positive
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamA, 80, 70, false, LocalDate.of(2025, 1, 14));
        mkFinalGame(teamA, teamC, 82, 72, false, LocalDate.of(2025, 1, 18));
        mkFinalGame(teamC, teamA, 82, 72, false, LocalDate.of(2025, 1, 22));
        mkFinalGame(teamB, teamC, 78, 68, false, LocalDate.of(2025, 1, 26));
        mkFinalGame(teamC, teamB, 78, 68, false, LocalDate.of(2025, 1, 30));

        service.calculateAndStoreForSeason(2025);

        double hca = paramValue(BradleyTerryRatingService.MODEL_TYPE, "hca", LocalDate.of(2025, 1, 30));
        assertThat(hca).isPositive();
    }

    @Test
    void tieGames_excludedFromFit() {
        // A tie (impossible in real CBB, but the service explicitly filters it).
        // With only a tie present, the fit has no data and no snapshots are written.
        mkFinalGame(teamA, teamB, 70, 70, false, LocalDate.of(2025, 1, 10));

        service.calculateAndStoreForSeason(2025);

        assertThat(ratingRepo.findAll()).isEmpty();
    }

    @Test
    void weightedModel_blowoutsAffectRatingsMoreThanNarrowWins() {
        // teamA wins by blowouts; teamB wins narrowly.
        // The weighted model gives blowouts more influence, so teamA should be
        // *more* dominant in the weighted ratings than in the unweighted ratings.
        mkFinalGame(teamA, teamB, 100, 50, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 95, 55, false, LocalDate.of(2025, 1, 12));
        mkFinalGame(teamB, teamC, 71, 70, false, LocalDate.of(2025, 1, 14));
        mkFinalGame(teamB, teamC, 72, 70, false, LocalDate.of(2025, 1, 16));

        service.calculateAndStoreForSeason(2025);

        LocalDate latest = LocalDate.of(2025, 1, 16);
        double unweightedGapA = ratingFor(teamA, BradleyTerryRatingService.MODEL_TYPE, latest)
                              - ratingFor(teamB, BradleyTerryRatingService.MODEL_TYPE, latest);
        double weightedGapA   = ratingFor(teamA, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, latest)
                              - ratingFor(teamB, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, latest);

        assertThat(weightedGapA).isGreaterThan(unweightedGapA);
    }

    @Test
    void snapshotsCreatedPerGameDate() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 82, 72, false, LocalDate.of(2025, 1, 17));
        mkFinalGame(teamA, teamB, 85, 75, false, LocalDate.of(2025, 1, 24));

        service.calculateAndStoreForSeason(2025);

        List<LocalDate> dates = ratingRepo.findSnapshotDates(
                season.getId(), BradleyTerryRatingService.MODEL_TYPE);
        assertThat(dates).containsExactly(
                LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 1, 17),
                LocalDate.of(2025, 1, 24));
    }

    @Test
    void gamesPlayedReflectsCumulativeCount() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamA, teamB, 82, 72, false, LocalDate.of(2025, 1, 17));

        service.calculateAndStoreForSeason(2025);

        TeamPowerRatingSnapshot lastA = ratingRepo
                .findByTeamSeasonAndModel(teamA.getId(), season.getId(), BradleyTerryRatingService.MODEL_TYPE)
                .stream().reduce((a, b) -> b).orElseThrow();
        assertThat(lastA.getGamesPlayed()).isEqualTo(2);
    }

    @Test
    void idempotent_secondCallReplacesPriorRows() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        service.calculateAndStoreForSeason(2025);
        long ratingCount = ratingRepo.count();
        long paramCount  = paramRepo.count();

        service.calculateAndStoreForSeason(2025);

        assertThat(ratingRepo.count()).isEqualTo(ratingCount);
        assertThat(paramRepo.count()).isEqualTo(paramCount);
    }

    @Test
    void allNeutralGames_hcaNearZero() {
        mkFinalGame(teamA, teamB, 80, 70, true, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamA, 75, 65, true, LocalDate.of(2025, 1, 17));
        mkFinalGame(teamA, teamC, 78, 72, true, LocalDate.of(2025, 1, 18));
        mkFinalGame(teamC, teamA, 76, 70, true, LocalDate.of(2025, 1, 22));

        service.calculateAndStoreForSeason(2025);

        double hca = paramValue(BradleyTerryRatingService.MODEL_TYPE, "hca", LocalDate.of(2025, 1, 22));
        assertThat(hca).isCloseTo(0.0, within(0.01));
    }

    @Test
    void hcaParam_persistedForBothUnweightedAndWeightedModels() {
        mkFinalGame(teamA, teamB, 80, 70, false, LocalDate.of(2025, 1, 10));
        mkFinalGame(teamB, teamA, 80, 70, false, LocalDate.of(2025, 1, 14));

        service.calculateAndStoreForSeason(2025);

        LocalDate after = LocalDate.of(2025, 1, 15);
        assertThat(paramRepo.findLatestParamBefore(
                season.getId(), BradleyTerryRatingService.MODEL_TYPE, "hca", after)).isPresent();
        assertThat(paramRepo.findLatestParamBefore(
                season.getId(), BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", after)).isPresent();
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double ratingFor(Team team, String modelType, LocalDate date) {
        return ratingRepo.findLatestBefore(team.getId(), season.getId(), modelType, date.plusDays(1))
                .orElseThrow().getRating();
    }

    private double paramValue(String modelType, String paramName, LocalDate date) {
        return paramRepo.findLatestParamBefore(season.getId(), modelType, paramName, date.plusDays(1))
                .orElseThrow().getParamValue();
    }
}
