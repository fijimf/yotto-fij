package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.service.ChartDataService.EfficiencyLandscape;
import com.yotto.basketball.service.ChartDataService.FingerprintAxis;
import com.yotto.basketball.service.ChartDataService.FingerprintTeam;
import com.yotto.basketball.service.ChartDataService.Fingerprints;
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
import java.util.List;

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
    @Autowired com.yotto.basketball.repository.TeamStatSnapshotRepository statSnapshotRepo;

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

    // ── fingerprints ──

    @Test
    void fingerprints_orientsPercentilesByBetterDirection_andResolvesDates() {
        LocalDate d1 = LocalDate.of(2025, 2, 1), d2 = LocalDate.of(2025, 2, 8);
        for (LocalDate d : List.of(d1, d2)) {
            stat(duke, "efg_pct", d, 0.56); stat(unc, "efg_pct", d, 0.50); stat(purdue, "efg_pct", d, 0.47);
            stat(duke, "tov_rate", d, 0.14); stat(unc, "tov_rate", d, 0.18); stat(purdue, "tov_rate", d, 0.22);
            stat(duke, "pace", d, 70.0); stat(unc, "pace", d, 66.0); stat(purdue, "pace", d, 62.0);
            stat(duke, "off_efficiency", d, 118.0); stat(duke, "def_efficiency", d, 95.0);
            stat(unc, "off_efficiency", d, 110.0); stat(unc, "def_efficiency", d, 100.0);
        }
        // wake has no box-score stats at all: must be absent, not a row of nulls

        Fingerprints fp = service.buildFingerprints(2025, null);

        assertThat(fp.date()).isEqualTo(d2);
        assertThat(fp.latestDate()).isEqualTo(d2);
        assertThat(fp.axes()).extracting(FingerprintAxis::key).containsExactly("efg", "tov", "reb", "ft", "tempo");
        assertThat(fp.teams()).extracting(FingerprintTeam::name).containsExactly("Duke", "North Carolina", "Purdue"); // by net desc, null last
        FingerprintTeam d = fp.teams().get(0), p = fp.teams().get(2);
        assertThat(d.pct().get("efg_pct")).isEqualTo(1.0);     // best shooting
        assertThat(p.pct().get("efg_pct")).isEqualTo(0.0);
        assertThat(d.pct().get("tov_rate")).isEqualTo(1.0);    // lowest turnover rate → best
        assertThat(p.pct().get("tov_rate")).isEqualTo(0.0);
        assertThat(fp.teams().get(1).pct().get("tov_rate")).isEqualTo(0.5);
        assertThat(d.pct().get("pace")).isEqualTo(1.0);        // tempo: faster points outward
        assertThat(d.net()).isEqualTo(23.0);
        assertThat(p.net()).isNull();
        assertThat(d.conf()).isEqualTo("ACC");
        assertThat(fp.confMedianPct().get("ACC").get("efg_pct")).isEqualTo(0.75);   // median of Duke 1.0, UNC 0.5
        assertThat(fp.confMedianPct().get("Big Ten").get("efg_pct")).isEqualTo(0.0);

        // Date resolution: on/before → that snapshot; before the first → the first.
        assertThat(service.buildFingerprints(2025, LocalDate.of(2025, 2, 5)).date()).isEqualTo(d1);
        assertThat(service.buildFingerprints(2025, LocalDate.of(2025, 1, 1)).date()).isEqualTo(d1);
        assertThat(service.buildFingerprints(2025, LocalDate.of(2026, 1, 1)).date()).isEqualTo(d2);
    }

    @Test
    void fingerprints_emptyWithoutStats() {
        Fingerprints fp = service.buildFingerprints(2025, null);
        assertThat(fp.date()).isNull();
        assertThat(fp.teams()).isEmpty();
    }

    @Test
    void percentile_tiesCountHalf_andSingleValueIsMedian() {
        double[] sorted = {1, 2, 2, 3};
        assertThat(ChartDataService.percentile(sorted, 1, true)).isEqualTo(0.0);
        assertThat(ChartDataService.percentile(sorted, 3, true)).isEqualTo(1.0);
        assertThat(ChartDataService.percentile(sorted, 2, true)).isEqualTo(0.5);
        assertThat(ChartDataService.percentile(sorted, 3, false)).isEqualTo(0.0);
        assertThat(ChartDataService.percentile(new double[]{7}, 7, true)).isEqualTo(0.5);
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

    private void stat(Team team, String statName, LocalDate date, double value) {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team); s.setSeason(season); s.setStatName(statName); s.setSnapshotDate(date);
        s.setValue(value);
        statSnapshotRepo.save(s);
    }

    private void param(String modelType, String name, LocalDate date, double value) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season); p.setModelType(modelType); p.setSnapshotDate(date);
        p.setParamName(name); p.setParamValue(value); p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }
}
