package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.service.ChartDataService.EfficiencyLandscape;
import com.yotto.basketball.service.ChartDataService.LandscapeTeam;
import com.yotto.basketball.service.ChartDataService.RatingRiver;
import com.yotto.basketball.service.ChartDataService.RiverModel;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartDataServiceTest extends BaseIntegrationTest {

    @Autowired ChartDataService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

    static final LocalDate D1 = LocalDate.of(2025, 1, 1), D2 = LocalDate.of(2025, 1, 2), D3 = LocalDate.of(2025, 1, 3);

    Season season;
    Conference acc, big;
    Team duke, unc, wake, purdue;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);
        acc = mkConf("ACC", "acc"); big = mkConf("Big Ten", "big");
        duke = mkTeam("Duke", "DUKE", "001A57"); unc = mkTeam("North Carolina", "UNC", "#7BAFD4");
        wake = mkTeam("Wake Forest", "WAKE", null); purdue = mkTeam("Purdue", "PUR", "CEB888");
        enroll(duke, acc); enroll(unc, acc); enroll(wake, acc); enroll(purdue, big);
    }

    // ── rating river ──

    @Test
    void river_alignsRanksToSnapshotDates_withNullsForMissingDays_andSortsByLatestRank() {
        rate(duke, "MASSEY", D1, 10.0, 2); rate(duke, "MASSEY", D2, 11.0, 1); rate(duke, "MASSEY", D3, 12.0, 1);
        rate(unc,  "MASSEY", D1,  9.0, 3); /* UNC missing on D2 */          rate(unc,  "MASSEY", D3, 10.5, 2);
        rate(wake, "MASSEY", D1,  1.0, 40); rate(wake, "MASSEY", D2, 1.5, 38); rate(wake, "MASSEY", D3, 1.2, 41);
        rate(purdue, "MASSEY", D1, 13.0, 1); // other conference: excluded

        RatingRiver river = service.buildRatingRiver(acc.getId(), 2025, RiverModel.MASSEY);

        assertThat(river.dates()).containsExactly(D1, D2, D3);
        assertThat(river.conference()).isEqualTo("ACC");
        assertThat(river.teams()).extracting("abbr").containsExactly("DUKE", "UNC", "WAKE");
        var uncRow = river.teams().get(1);
        assertThat(uncRow.rank()).containsExactly(3, null, 2);
        assertThat(uncRow.rating()).containsExactly(9.0, null, 10.5);
        assertThat(river.teams().get(0).color()).isEqualTo("#001A57");   // '#' normalized on
        assertThat(uncRow.color()).isEqualTo("#7BAFD4");
        assertThat(river.teams().get(2).color()).isNull();
    }

    @Test
    void river_emptyWhenModelHasNoSnapshots() {
        RatingRiver river = service.buildRatingRiver(acc.getId(), 2025, RiverModel.BRADLEY_TERRY);
        assertThat(river.dates()).isEmpty();
        assertThat(river.teams()).isEmpty();
    }

    @Test
    void river_unknownConferenceOrSeason_is404() {
        assertThatThrownBy(() -> service.buildRatingRiver(999_999L, 2025, RiverModel.MASSEY))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.buildRatingRiver(acc.getId(), 1999, RiverModel.MASSEY))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── efficiency landscape ──

    @Test
    void landscape_samplesFrames_andDisplaysAbsolutesFromCenteredParams() {
        // 9 consecutive snapshot days -> frames at 0,4,8 (step 4 + last)
        LocalDate[] days = new LocalDate[9];
        for (int i = 0; i < 9; i++) days[i] = LocalDate.of(2025, 2, 1).plusDays(i);
        for (LocalDate d : days) {
            param("ADJ_OFF", "eff_intercept", d, 100.0 + days.length);  // μ = 109
            param("ADJ_TEMPO", "tempo_intercept", d, 68.0);
            rate(duke, "ADJ_OFF", d, 8.0, 1); rate(duke, "ADJ_DEF", d, 5.0, 1); rate(duke, "ADJ_TEMPO", d, 2.0, 1);
            rate(purdue, "ADJ_OFF", d, -3.0, 2); rate(purdue, "ADJ_DEF", d, -1.0, 2);
        }
        // UNC has an offense row but no defense row — must be dropped, not crash.
        rate(unc, "ADJ_OFF", days[0], 1.0, 3);

        EfficiencyLandscape land = service.buildEfficiencyLandscape(2025);

        assertThat(land.dates()).containsExactly(days[0], days[4], days[8]);
        assertThat(land.teams()).extracting(LandscapeTeam::name).containsExactly("Duke", "Purdue");
        LandscapeTeam d = land.teams().get(0);
        assertThat(d.conf()).isEqualTo("ACC");
        assertThat(d.off()).containsExactly(117.0, 117.0, 117.0);   // μ + off
        assertThat(d.def()).containsExactly(104.0, 104.0, 104.0);   // μ − def (lower is better)
        assertThat(d.tempo()).containsExactly(70.0, 70.0, 70.0);    // base + τ
        assertThat(d.gp()).containsExactly(1, 1, 1);
        LandscapeTeam p = land.teams().get(1);
        assertThat(p.conf()).isEqualTo("Big Ten");
        assertThat(p.tempo()).containsExactly(Arrays.asList(new Double[]{null, null, null}).toArray(new Double[0]));
    }

    @Test
    void landscape_emptyWithoutAdjRatings() {
        EfficiencyLandscape land = service.buildEfficiencyLandscape(2025);
        assertThat(land.dates()).isEmpty();
        assertThat(land.teams()).isEmpty();
    }

    // ── helpers ──

    private Conference mkConf(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name); c.setAbbreviation(name); c.setEspnId(espnId);
        return conferenceRepo.save(c);
    }

    private Team mkTeam(String name, String abbr, String color) {
        Team t = new Team();
        t.setName(name); t.setEspnId(abbr.toLowerCase()); t.setAbbreviation(abbr); t.setActive(true); t.setColor(color);
        return teamRepo.save(t);
    }

    private void enroll(Team team, Conference conf) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team); m.setConference(conf); m.setSeason(season);
        membershipRepo.save(m);
    }

    private void rate(Team team, String modelType, LocalDate date, double rating, int rank) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team); s.setSeason(season); s.setModelType(modelType); s.setSnapshotDate(date);
        s.setRating(rating); s.setRank(rank); s.setGamesPlayed(1); s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void param(String modelType, String name, LocalDate date, double value) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season); p.setModelType(modelType); p.setSnapshotDate(date);
        p.setParamName(name); p.setParamValue(value); p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }
}
