package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-model scenarios against the 2026 calendar: First Four Tue 2026-03-17,
 * Selection Sunday 2026-03-15, championship Mon 2026-04-06.
 */
class SeasonPhaseServiceTest extends BaseIntegrationTest {

    @Autowired SeasonPhaseService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;

    Team a, b;

    @BeforeEach
    void setUp() {
        a = mkTeam("Alabama", "a");
        b = mkTeam("Auburn", "b");
    }

    @AfterEach
    void clearOverride() {
        // service is a context-scoped singleton; a leaked override would poison later tests
        service.clearOverride();
    }

    // ── empty / off-season ──

    @Test
    void emptyDatabase_isOffseasonWithNullSeason() {
        SeasonPhase p = service.asOf(LocalDate.of(2026, 7, 15));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.OFFSEASON);
        assertThat(p.season()).isNull();
    }

    @Test
    void midSummerAfterCompletedSeason_isOffseason() {
        seedFullSeason2026();
        SeasonPhase p = service.asOf(LocalDate.of(2026, 7, 15));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.OFFSEASON);
        assertThat(p.season().getYear()).isEqualTo(2026);
        assertThat(p.championshipDate()).isEqualTo(LocalDate.of(2026, 4, 6));
    }

    // ── preseason ──

    @Test
    void preseason_startsMidOctober_evenWithNoScheduleScraped() {
        mkSeason(2027, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 4, 30));
        // preseason start = min(Oct 15, Nov 1 − 21d = Oct 11)
        assertThat(service.asOf(LocalDate.of(2026, 10, 10)).phase())
                .isEqualTo(SeasonPhase.Phase.OFFSEASON);
        assertThat(service.asOf(LocalDate.of(2026, 10, 12)).phase())
                .isEqualTo(SeasonPhase.Phase.PRESEASON);
    }

    @Test
    void preseason_anchorsToFirstScheduledGame_thenFlipsInSeasonAtTipoff() {
        Season s27 = mkSeason(2027, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 4, 30));
        LocalDate opener = LocalDate.of(2026, 11, 3);
        mkGame(s27, opener, Game.GameStatus.SCHEDULED, null, null);

        SeasonPhase pre = service.asOf(LocalDate.of(2026, 10, 20));
        assertThat(pre.phase()).isEqualTo(SeasonPhase.Phase.PRESEASON);
        assertThat(pre.firstGameDate()).isEqualTo(opener);

        assertThat(service.asOf(opener).phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);
    }

    // ── in-season + flairs ──

    @Test
    void midJanuary_isInSeason_noFlairs() {
        seedFullSeason2026();
        SeasonPhase p = service.asOf(LocalDate.of(2026, 1, 15));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);
        assertThat(p.confTourneyWeek()).isFalse();
        assertThat(p.selectionSunday()).isFalse();
    }

    @Test
    void championshipWeek_isInSeasonWithConfTourneyFlair_untilSelectionSunday() {
        seedFullSeason2026();
        SeasonPhase p = service.asOf(LocalDate.of(2026, 3, 11));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);
        assertThat(p.confTourneyWeek()).isTrue();
        // Saturday of championship week: NCAA schedule already exists but Sunday hasn't come
        assertThat(service.asOf(LocalDate.of(2026, 3, 14)).phase())
                .isEqualTo(SeasonPhase.Phase.IN_SEASON);
    }

    // ── postseason ──

    @Test
    void selectionSunday_entersPostseasonWithFlair() {
        seedFullSeason2026();
        SeasonPhase p = service.asOf(LocalDate.of(2026, 3, 15));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.POSTSEASON);
        assertThat(p.selectionSunday()).isTrue();

        SeasonPhase later = service.asOf(LocalDate.of(2026, 3, 19));
        assertThat(later.phase()).isEqualTo(SeasonPhase.Phase.POSTSEASON);
        assertThat(later.selectionSunday()).isFalse();
    }

    @Test
    void championshipNight_isStillPostseason() {
        seedFullSeason2026();
        assertThat(service.asOf(LocalDate.of(2026, 4, 6)).phase())
                .isEqualTo(SeasonPhase.Phase.POSTSEASON);
    }

    @Test
    void nitGames_doNotTriggerPostseason() {
        Season s = mkSeason(2026, LocalDate.of(2025, 11, 1), LocalDate.of(2026, 4, 30));
        mkFinal(s, LocalDate.of(2025, 11, 3));
        mkFinal(s, LocalDate.of(2026, 3, 8));
        mkGame(s, LocalDate.of(2026, 3, 18), Game.GameStatus.SCHEDULED, Game.TournamentType.NIT, "1st Round");
        mkGame(s, LocalDate.of(2026, 3, 25), Game.GameStatus.SCHEDULED, Game.TournamentType.NIT, "Semifinal");

        SeasonPhase p = service.asOf(LocalDate.of(2026, 3, 20));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);
    }

    // ── epilogue / wind-down ──

    @Test
    void epilogue_runsFourteenDaysPastChampionship_thenOffseason() {
        seedFullSeason2026();
        assertThat(service.asOf(LocalDate.of(2026, 4, 7)).phase())
                .isEqualTo(SeasonPhase.Phase.EPILOGUE);
        assertThat(service.asOf(LocalDate.of(2026, 4, 20)).phase())
                .isEqualTo(SeasonPhase.Phase.EPILOGUE);
        assertThat(service.asOf(LocalDate.of(2026, 4, 21)).phase())
                .isEqualTo(SeasonPhase.Phase.OFFSEASON);
    }

    @Test
    void unlabeledRounds_fallBackToLastFinalNcaaGame() {
        Season s = mkSeason(2026, LocalDate.of(2025, 11, 1), LocalDate.of(2026, 4, 30));
        mkFinal(s, LocalDate.of(2025, 11, 3));
        mkGame(s, LocalDate.of(2026, 3, 19), Game.GameStatus.FINAL, Game.TournamentType.NCAA_TOURNAMENT, null);
        mkGame(s, LocalDate.of(2026, 4, 6), Game.GameStatus.FINAL, Game.TournamentType.NCAA_TOURNAMENT, null);

        SeasonPhase p = service.asOf(LocalDate.of(2026, 4, 10));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.EPILOGUE);
        assertThat(p.championshipDate()).isEqualTo(LocalDate.of(2026, 4, 6));
    }

    @Test
    void seasonWithoutNcaaData_goesQuietAfterLastGamePlusGrace() {
        Season s = mkSeason(2026, LocalDate.of(2025, 11, 1), LocalDate.of(2026, 4, 30));
        mkFinal(s, LocalDate.of(2025, 11, 3));
        mkFinal(s, LocalDate.of(2026, 3, 8));

        assertThat(service.asOf(LocalDate.of(2026, 3, 15)).phase())
                .isEqualTo(SeasonPhase.Phase.IN_SEASON);
        assertThat(service.asOf(LocalDate.of(2026, 3, 30)).phase())
                .isEqualTo(SeasonPhase.Phase.OFFSEASON);
    }

    // ── admin override ──

    @Test
    void override_forcesPhaseAndDate_andClears() {
        seedFullSeason2026();

        service.setOverride(SeasonPhase.Phase.POSTSEASON, null);
        assertThat(service.current().phase()).isEqualTo(SeasonPhase.Phase.POSTSEASON);
        assertThat(service.isOverridden()).isTrue();

        service.setOverride(null, LocalDate.of(2026, 1, 15));
        SeasonPhase p = service.current();
        assertThat(p.today()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(p.phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);

        service.clearOverride();
        assertThat(service.isOverridden()).isFalse();
    }

    // ── fixtures ──

    /**
     * A complete 2026 season: opener Nov 3, regular games through Mar 8, conf tourney Mar 10–15
     * (final Sun Mar 15), NCAA First Four Tue Mar 17 → championship Mon Apr 6, plus an NIT game
     * that must never matter.
     */
    private void seedFullSeason2026() {
        Season s = mkSeason(2026, LocalDate.of(2025, 11, 1), LocalDate.of(2026, 4, 30));
        mkFinal(s, LocalDate.of(2025, 11, 3));
        mkFinal(s, LocalDate.of(2026, 1, 15));
        mkFinal(s, LocalDate.of(2026, 3, 8));
        mkGame(s, LocalDate.of(2026, 3, 10), Game.GameStatus.FINAL, Game.TournamentType.CONFERENCE_TOURNAMENT, "Quarterfinal");
        mkGame(s, LocalDate.of(2026, 3, 15), Game.GameStatus.FINAL, Game.TournamentType.CONFERENCE_TOURNAMENT, "Final");
        mkGame(s, LocalDate.of(2026, 3, 17), Game.GameStatus.FINAL, Game.TournamentType.NCAA_TOURNAMENT, "First Four");
        mkGame(s, LocalDate.of(2026, 3, 19), Game.GameStatus.FINAL, Game.TournamentType.NCAA_TOURNAMENT, "1st Round");
        mkGame(s, LocalDate.of(2026, 4, 6), Game.GameStatus.FINAL, Game.TournamentType.NCAA_TOURNAMENT, "National Championship");
        mkGame(s, LocalDate.of(2026, 3, 18), Game.GameStatus.FINAL, Game.TournamentType.NIT, "1st Round");
    }

    private Season mkSeason(int year, LocalDate start, LocalDate end) {
        Season s = new Season();
        s.setYear(year);
        s.setStartDate(start);
        s.setEndDate(end);
        return seasonRepo.save(s);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkFinal(Season s, LocalDate easternDate) {
        return mkGame(s, easternDate, Game.GameStatus.FINAL, null, null);
    }

    private Game mkGame(Season s, LocalDate easternDate, Game.GameStatus status,
                        Game.TournamentType type, String round) {
        Game g = new Game();
        g.setHomeTeam(a);
        g.setAwayTeam(b);
        g.setSeason(s);
        g.setGameDate(easternNoonUtc(easternDate));
        g.setStatus(status);
        if (status == Game.GameStatus.FINAL) {
            g.setHomeScore(80);
            g.setAwayScore(70);
        }
        g.setTournamentType(type);
        g.setTournamentRound(round);
        return gameRepo.save(g);
    }

    /** Noon ET on the given date, expressed as the stored UTC instant. */
    private static LocalDateTime easternNoonUtc(LocalDate d) {
        return d.atTime(12, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
