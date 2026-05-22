package com.yotto.basketball.scraping;

import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game.TournamentType;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TournamentClassifierTest {

    private ConferenceMembershipRepository memberships;
    private TournamentClassifier classifier;

    private Team home;
    private Team away;
    private Season season;
    private Conference accConf;
    private Conference bigEastConf;

    @BeforeEach
    void setUp() {
        memberships = mock(ConferenceMembershipRepository.class);
        classifier = new TournamentClassifier(memberships);

        home = new Team();
        home.setId(1L);
        away = new Team();
        away.setId(2L);
        season = new Season();
        season.setId(100L);
        season.setYear(2025);

        accConf = new Conference();
        accConf.setId(10L);
        accConf.setName("ACC");

        bigEastConf = new Conference();
        bigEastConf.setId(11L);
        bigEastConf.setName("Big East");
    }

    @Test
    void nullNoteIsRegularSeason() {
        var r = classifier.classify(null, "2", LocalDate.of(2025, 1, 4), home, away, season);
        assertThat(r.type()).isNull();
        assertThat(r.name()).isNull();
        assertThat(r.round()).isNull();
    }

    @Test
    void blankNoteIsRegularSeason() {
        var r = classifier.classify("   ", "2", LocalDate.of(2025, 1, 4), home, away, season);
        assertThat(r.type()).isNull();
    }

    @Test
    void ncaaTournamentWithRegion() {
        var r = classifier.classify(
                "Men's Basketball Championship - Midwest Region - 1st Round",
                "3", LocalDate.of(2025, 3, 20), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.NCAA_TOURNAMENT);
        assertThat(r.name()).isEqualTo("NCAA Tournament");
        assertThat(r.region()).isEqualTo("Midwest");
        assertThat(r.round()).isEqualTo("1st Round");
    }

    @Test
    void ncaaTournamentFirstFour() {
        var r = classifier.classify(
                "Men's Basketball Championship - South Region - First Four",
                "3", LocalDate.of(2025, 3, 18), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.NCAA_TOURNAMENT);
        assertThat(r.region()).isEqualTo("South");
        assertThat(r.round()).isEqualTo("First Four");
    }

    @Test
    void ncaaTournamentNationalChampionship() {
        var r = classifier.classify(
                "Men's Basketball Championship - National Championship",
                "3", LocalDate.of(2025, 4, 7), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.NCAA_TOURNAMENT);
        assertThat(r.name()).isEqualTo("NCAA Tournament");
        assertThat(r.region()).isNull();
        assertThat(r.round()).isEqualTo("National Championship");
    }

    @Test
    void nitFirstRound() {
        var r = classifier.classify("NIT - 1st Round", "3", LocalDate.of(2025, 3, 18),
                home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.NIT);
        assertThat(r.name()).isEqualTo("NIT");
        assertThat(r.round()).isEqualTo("1st Round");
    }

    @Test
    void nitSeasonTipOffIsInSeasonNotNit() {
        // November cross-conference label that starts with "NIT" — must not collide with NIT bracket.
        when(memberships.findByTeamIdAndSeasonId(eq(home.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));
        when(memberships.findByTeamIdAndSeasonId(eq(away.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(bigEastConf)));

        var r = classifier.classify("NIT Season Tip-Off - Final", "2",
                LocalDate.of(2024, 11, 28), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.IN_SEASON_TOURNAMENT);
        assertThat(r.name()).isEqualTo("NIT Season Tip-Off");
        assertThat(r.round()).isEqualTo("Final");
    }

    @Test
    void cbiTournament() {
        var r = classifier.classify("Purple CBI - Semifinal", "3",
                LocalDate.of(2025, 3, 26), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.CBI);
        assertThat(r.name()).isEqualTo("CBI");
        assertThat(r.round()).isEqualTo("Semifinal");
    }

    @Test
    void collegeBasketballCrown() {
        var r = classifier.classify("College Basketball Crown - Quarterfinal", "3",
                LocalDate.of(2025, 4, 2), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.CROWN);
        assertThat(r.name()).isEqualTo("College Basketball Crown");
        assertThat(r.round()).isEqualTo("Quarterfinal");
    }

    @Test
    void conferenceTournamentSameConferenceInMarch() {
        when(memberships.findByTeamIdAndSeasonId(eq(home.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));
        when(memberships.findByTeamIdAndSeasonId(eq(away.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));

        var r = classifier.classify("T. Rowe Price ACC Tournament - Quarterfinal", "2",
                LocalDate.of(2025, 3, 13), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.CONFERENCE_TOURNAMENT);
        assertThat(r.name()).isEqualTo("ACC Tournament");
        assertThat(r.round()).isEqualTo("Quarterfinal");
    }

    @Test
    void conferenceTournamentSponsoredBig12() {
        when(memberships.findByTeamIdAndSeasonId(eq(home.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));
        when(memberships.findByTeamIdAndSeasonId(eq(away.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));

        var r = classifier.classify("Phillips 66 Big 12 Championship - Quarterfinal", "2",
                LocalDate.of(2025, 3, 13), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.CONFERENCE_TOURNAMENT);
        assertThat(r.name()).isEqualTo("Big 12 Championship");
    }

    @Test
    void bigEastTournamentNoSponsor() {
        when(memberships.findByTeamIdAndSeasonId(eq(home.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(bigEastConf)));
        when(memberships.findByTeamIdAndSeasonId(eq(away.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(bigEastConf)));

        var r = classifier.classify("Big East Tournament - Quarterfinal", "2",
                LocalDate.of(2025, 3, 13), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.CONFERENCE_TOURNAMENT);
        assertThat(r.name()).isEqualTo("Big East Tournament");
    }

    @Test
    void mauiInvitationalIsInSeason() {
        when(memberships.findByTeamIdAndSeasonId(eq(home.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));
        when(memberships.findByTeamIdAndSeasonId(eq(away.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(bigEastConf)));

        var r = classifier.classify("The Maui Invitational Presented by Novavax - Championship",
                "2", LocalDate.of(2024, 11, 27), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.IN_SEASON_TOURNAMENT);
        assertThat(r.name()).isEqualTo("Maui Invitational");
        assertThat(r.round()).isEqualTo("Championship");
    }

    @Test
    void marchSameConferenceWithoutNoteIsNotForced() {
        // Conference-tournament classification only triggers when there's a populated note.
        var r = classifier.classify(null, "2", LocalDate.of(2025, 3, 13), home, away, season);
        assertThat(r.type()).isNull();
    }

    @Test
    void marchCrossConferenceLabelIsInSeasonNotConferenceTournament() {
        // Defensive: if ESPN ever labels a March game with cross-conference teams, it's not a conf tournament.
        when(memberships.findByTeamIdAndSeasonId(eq(home.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(accConf)));
        when(memberships.findByTeamIdAndSeasonId(eq(away.getId()), eq(season.getId())))
                .thenReturn(Optional.of(membership(bigEastConf)));

        var r = classifier.classify("Some Showcase - Final", "2",
                LocalDate.of(2025, 3, 13), home, away, season);

        assertThat(r.type()).isEqualTo(TournamentType.IN_SEASON_TOURNAMENT);
        assertThat(r.name()).isEqualTo("Some Showcase");
    }

    private ConferenceMembership membership(Conference c) {
        ConferenceMembership m = new ConferenceMembership();
        m.setConference(c);
        m.setSeason(season);
        return m;
    }
}
