package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.controller.dto.StatPageDto;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The scatter plots each team's season-to-date value *entering* the game — the
 * latest snapshot strictly before the game date — never the value in or after
 * the game (spec R1).
 */
class StatPageServiceTest extends BaseIntegrationTest {

    private static final LocalDate SNAP1 = LocalDate.of(2025, 1, 10);
    private static final LocalDate SNAP2 = LocalDate.of(2025, 1, 20);

    @Autowired StatPageService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamStatSnapshotRepository snapshotRepo;

    Season season;
    Team a, b;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        a = mkTeam("Alabama", "TA");
        b = mkTeam("Auburn", "TB");

        mkSnapshot(a, SNAP1, 0.50, 1);
        mkSnapshot(b, SNAP1, 0.40, 2);
        mkSnapshot(a, SNAP2, 0.55, 1);
        mkSnapshot(b, SNAP2, 0.45, 2);

        // Jan 10: first game — no snapshot strictly before it → dropped
        mkFinalGame(a, b, 80, 70, SNAP1);
        // Jan 15: uses the Jan 10 snapshots
        mkFinalGame(a, b, 80, 70, LocalDate.of(2025, 1, 15));
        // Jan 20: played ON a snapshot date — must use Jan 10 values, not Jan 20
        mkFinalGame(b, a, 75, 72, SNAP2);
        // Jan 25: uses the Jan 20 snapshots
        mkFinalGame(b, a, 60, 90, LocalDate.of(2025, 1, 25));
    }

    @Test
    void scatterUsesEnteringValues_strictlyBeforeGameDate() {
        // Explicit as-of date past the last game (the default is the latest
        // snapshot date, which would exclude the Jan 25 game here)
        StatPageDto dto = service.build(2025, "efg_pct", LocalDate.of(2025, 1, 31));

        StatPageDto.Scatter scatter = dto.scatter();
        assertThat(scatter.gamesTotal()).isEqualTo(4);
        assertThat(scatter.gamesPlotted()).isEqualTo(3);
        assertThat(scatter.points()).hasSize(3);

        // Jan 15 (home A, away B): entering values from Jan 10
        StatPageDto.Point p1 = scatter.points().get(0);
        assertThat(p1.x()).isCloseTo(0.50, within(1e-9));
        assertThat(p1.y()).isCloseTo(0.40, within(1e-9));
        assertThat(p1.homeWin()).isTrue();

        // Jan 20 (home B, away A): strictly-before → still the Jan 10 values,
        // never the same-day snapshot (which already includes this game)
        StatPageDto.Point p2 = scatter.points().get(1);
        assertThat(p2.x()).isCloseTo(0.40, within(1e-9));
        assertThat(p2.y()).isCloseTo(0.50, within(1e-9));
        assertThat(p2.homeWin()).isTrue();

        // Jan 25 (home B, away A): entering values from Jan 20
        StatPageDto.Point p3 = scatter.points().get(2);
        assertThat(p3.x()).isCloseTo(0.45, within(1e-9));
        assertThat(p3.y()).isCloseTo(0.55, within(1e-9));
        assertThat(p3.homeWin()).isFalse();
    }

    @Test
    void dateParamLimitsGamesAndSnapshots() {
        // As of Jan 10 only the opener has been played, and it has no entering values
        StatPageDto dto = service.build(2025, "efg_pct", SNAP1);

        assertThat(dto.scatter().gamesTotal()).isEqualTo(1);
        assertThat(dto.scatter().gamesPlotted()).isZero();
        assertThat(dto.scatter().points()).isEmpty();
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkSnapshot(Team team, LocalDate date, double value, int rank) {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(date);
        s.setStatName("efg_pct");
        s.setValue(value);
        s.setGamesPlayed(1);
        s.setRank(rank);
        snapshotRepo.save(s);
    }

    private void mkFinalGame(Team home, Team away, int homeScore, int awayScore, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setStatus(Game.GameStatus.FINAL);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        gameRepo.save(g);
    }
}
