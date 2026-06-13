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

/**
 * Drift guard between the writer's hand-written column lists and the JPA entity
 * mappings: every field written through JDBC must read back identically through
 * the repositories.
 */
class SnapshotJdbcWriterTest extends BaseIntegrationTest {

    @Autowired SnapshotJdbcWriter writer;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired TeamSeasonStatSnapshotRepository statSnapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

    Season season;
    Team team;
    Conference conference;

    private static final LocalDate DATE = LocalDate.of(2025, 1, 15);
    // Timestamp column has microsecond precision; truncate to avoid round-trip noise
    private static final LocalDateTime CALC_AT = LocalDateTime.of(2025, 1, 15, 12, 30, 45);

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        team = new Team();
        team.setName("Alabama");
        team.setEspnId("T1");
        team.setActive(true);
        teamRepo.save(team);

        conference = new Conference();
        conference.setName("SEC");
        conference.setEspnId("sec1");
        conferenceRepo.save(conference);
    }

    @Test
    void teamSeasonStatSnapshot_allFieldsRoundTrip() {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(DATE);
        s.setGamesPlayed(10);
        s.setWins(7);
        s.setLosses(3);
        s.setWinPct(0.7);
        s.setMeanPtsFor(81.5);
        s.setStddevPtsFor(8.25);
        s.setMeanPtsAgainst(72.1);
        s.setStddevPtsAgainst(6.5);
        s.setCorrelationPts(0.12);
        s.setMeanMargin(9.4);
        s.setStddevMargin(11.0);
        s.setRollingWins(6);
        s.setRollingLosses(4);
        s.setRollingMeanPtsFor(83.0);
        s.setRollingMeanPtsAgainst(71.0);
        s.setZscoreWinPct(1.1);
        s.setZscoreMeanPtsFor(0.9);
        s.setZscoreMeanPtsAgainst(-0.4);
        s.setZscoreMeanMargin(1.3);
        s.setZscoreCorrelationPts(0.05);
        s.setConfZscoreWinPct(0.8);
        s.setConfZscoreMeanPtsFor(0.6);
        s.setConfZscoreMeanPtsAgainst(-0.2);
        s.setConfZscoreMeanMargin(0.7);
        s.setRpi(0.611);
        s.setRpiWp(0.65);
        s.setRpiOwp(0.58);
        s.setRpiOowp(0.61);

        writer.writeTeamSeasonStatSnapshots(List.of(s));

        TeamSeasonStatSnapshot r = statSnapshotRepo
                .findByTeamAndSeason(team.getId(), season.getId()).get(0);
        assertThat(r.getSnapshotDate()).isEqualTo(DATE);
        assertThat(r.getGamesPlayed()).isEqualTo(10);
        assertThat(r.getWins()).isEqualTo(7);
        assertThat(r.getLosses()).isEqualTo(3);
        assertThat(r.getWinPct()).isEqualTo(0.7);
        assertThat(r.getMeanPtsFor()).isEqualTo(81.5);
        assertThat(r.getStddevPtsFor()).isEqualTo(8.25);
        assertThat(r.getMeanPtsAgainst()).isEqualTo(72.1);
        assertThat(r.getStddevPtsAgainst()).isEqualTo(6.5);
        assertThat(r.getCorrelationPts()).isEqualTo(0.12);
        assertThat(r.getMeanMargin()).isEqualTo(9.4);
        assertThat(r.getStddevMargin()).isEqualTo(11.0);
        assertThat(r.getRollingWins()).isEqualTo(6);
        assertThat(r.getRollingLosses()).isEqualTo(4);
        assertThat(r.getRollingMeanPtsFor()).isEqualTo(83.0);
        assertThat(r.getRollingMeanPtsAgainst()).isEqualTo(71.0);
        assertThat(r.getZscoreWinPct()).isEqualTo(1.1);
        assertThat(r.getZscoreMeanPtsFor()).isEqualTo(0.9);
        assertThat(r.getZscoreMeanPtsAgainst()).isEqualTo(-0.4);
        assertThat(r.getZscoreMeanMargin()).isEqualTo(1.3);
        assertThat(r.getZscoreCorrelationPts()).isEqualTo(0.05);
        assertThat(r.getConfZscoreWinPct()).isEqualTo(0.8);
        assertThat(r.getConfZscoreMeanPtsFor()).isEqualTo(0.6);
        assertThat(r.getConfZscoreMeanPtsAgainst()).isEqualTo(-0.2);
        assertThat(r.getConfZscoreMeanMargin()).isEqualTo(0.7);
        assertThat(r.getRpi()).isEqualTo(0.611);
        assertThat(r.getRpiWp()).isEqualTo(0.65);
        assertThat(r.getRpiOwp()).isEqualTo(0.58);
        assertThat(r.getRpiOowp()).isEqualTo(0.61);
    }

    @Test
    void teamSeasonStatSnapshot_nullOptionalsRoundTrip() {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(DATE);
        s.setGamesPlayed(1);
        s.setWins(1);
        s.setLosses(0);
        // every nullable stat left null (single-game state)

        writer.writeTeamSeasonStatSnapshots(List.of(s));

        TeamSeasonStatSnapshot r = statSnapshotRepo
                .findByTeamAndSeason(team.getId(), season.getId()).get(0);
        assertThat(r.getWinPct()).isNull();
        assertThat(r.getStddevPtsFor()).isNull();
        assertThat(r.getCorrelationPts()).isNull();
        assertThat(r.getRollingWins()).isNull();
        assertThat(r.getZscoreWinPct()).isNull();
        assertThat(r.getConfZscoreMeanMargin()).isNull();
        assertThat(r.getRpi()).isNull();
    }

    @Test
    void seasonPopulationStat_leagueWideAndConferenceScoped() {
        SeasonPopulationStat league = new SeasonPopulationStat();
        league.setSeason(season);
        league.setConference(null);
        league.setStatDate(DATE);
        league.setStatName("win_pct");
        league.setPopMean(0.5);
        league.setPopStddev(0.18);
        league.setPopMin(0.0);
        league.setPopMax(1.0);
        league.setTeamCount(360);

        SeasonPopulationStat conf = new SeasonPopulationStat();
        conf.setSeason(season);
        conf.setConference(conference);
        conf.setStatDate(DATE);
        conf.setStatName("win_pct");
        conf.setPopMean(0.55);
        conf.setPopStddev(0.2);
        conf.setPopMin(0.1);
        conf.setPopMax(0.95);
        conf.setTeamCount(16);

        writer.writeSeasonPopulationStats(List.of(league, conf));

        List<SeasonPopulationStat> leagueRows =
                popStatRepo.findLeagueWideBySeasonAndDate(season.getId(), DATE);
        assertThat(leagueRows).hasSize(1);
        assertThat(leagueRows.get(0).getPopMean()).isEqualTo(0.5);
        assertThat(leagueRows.get(0).getPopStddev()).isEqualTo(0.18);
        assertThat(leagueRows.get(0).getPopMin()).isEqualTo(0.0);
        assertThat(leagueRows.get(0).getPopMax()).isEqualTo(1.0);
        assertThat(leagueRows.get(0).getTeamCount()).isEqualTo(360);

        List<SeasonPopulationStat> confRows =
                popStatRepo.findBySeasonDateAndConference(season.getId(), DATE, conference.getId());
        assertThat(confRows).hasSize(1);
        assertThat(confRows.get(0).getPopMean()).isEqualTo(0.55);
        assertThat(confRows.get(0).getTeamCount()).isEqualTo(16);
    }

    @Test
    void teamPowerRatingSnapshot_allFieldsRoundTrip() {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType("MASSEY");
        s.setSnapshotDate(DATE);
        s.setRating(12.345);
        s.setRank(3);
        s.setGamesPlayed(18);
        s.setCalculatedAt(CALC_AT);

        writer.writeTeamPowerRatingSnapshots(List.of(s));

        List<TeamPowerRatingSnapshot> rows =
                ratingRepo.findBySeasonModelAndDate(season.getId(), "MASSEY", DATE);
        assertThat(rows).hasSize(1);
        TeamPowerRatingSnapshot r = rows.get(0);
        assertThat(r.getTeam().getId()).isEqualTo(team.getId());
        assertThat(r.getRating()).isEqualTo(12.345);
        assertThat(r.getRank()).isEqualTo(3);
        assertThat(r.getGamesPlayed()).isEqualTo(18);
        assertThat(r.getCalculatedAt()).isEqualTo(CALC_AT);
    }

    @Test
    void powerModelParamSnapshot_allFieldsRoundTrip() {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType("MASSEY");
        p.setSnapshotDate(DATE);
        p.setParamName("hca");
        p.setParamValue(3.21);
        p.setCalculatedAt(CALC_AT);

        writer.writePowerModelParamSnapshots(List.of(p));

        List<PowerModelParamSnapshot> rows =
                paramRepo.findBySeasonAndModel(season.getId(), "MASSEY");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getParamName()).isEqualTo("hca");
        assertThat(rows.get(0).getParamValue()).isEqualTo(3.21);
        assertThat(rows.get(0).getSnapshotDate()).isEqualTo(DATE);
        assertThat(rows.get(0).getCalculatedAt()).isEqualTo(CALC_AT);
    }

    @Test
    void batchLargerThanChunkSize_writesAllRows() {
        // 2500 rows exceeds the writer's 1000-row chunk — exercises chunk boundaries
        List<PowerModelParamSnapshot> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            PowerModelParamSnapshot p = new PowerModelParamSnapshot();
            p.setSeason(season);
            p.setModelType("MASSEY");
            p.setSnapshotDate(LocalDate.of(2024, 11, 1).plusDays(i % 180));
            p.setParamName("p" + i);
            p.setParamValue((double) i);
            p.setCalculatedAt(CALC_AT);
            rows.add(p);
        }

        writer.writePowerModelParamSnapshots(rows);

        assertThat(paramRepo.count()).isEqualTo(2500);
    }
}
