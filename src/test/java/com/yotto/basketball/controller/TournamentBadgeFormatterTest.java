package com.yotto.basketball.controller;

import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Game.TournamentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TournamentBadgeFormatterTest {

    private final TournamentBadgeFormatter formatter = new TournamentBadgeFormatter();

    @Test
    void regularSeasonGameYieldsNoBadge() {
        Game g = new Game();
        assertThat(formatter.format(g)).isNull();
    }

    @Test
    void ncaaTournamentFirstRoundWithRegion() {
        Game g = new Game();
        g.setTournamentType(TournamentType.NCAA_TOURNAMENT);
        g.setTournamentName("NCAA Tournament");
        g.setTournamentRound("1st Round");
        g.setTournamentRegion("Midwest");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("NCAA 1R · Midwest");
        assertThat(badge.variant()).isEqualTo("ncaa");
        assertThat(badge.tooltip()).contains("NCAA Tournament").contains("Midwest").contains("1st Round");
    }

    @Test
    void ncaaNationalChampionshipNoRegion() {
        Game g = new Game();
        g.setTournamentType(TournamentType.NCAA_TOURNAMENT);
        g.setTournamentName("NCAA Tournament");
        g.setTournamentRound("National Championship");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("NCAA Final");
    }

    @Test
    void conferenceTournamentStripsSuffix() {
        Game g = new Game();
        g.setTournamentType(TournamentType.CONFERENCE_TOURNAMENT);
        g.setTournamentName("ACC Tournament");
        g.setTournamentRound("Quarterfinal");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("ACC QF");
        assertThat(badge.variant()).isEqualTo("conf");
    }

    @Test
    void inSeasonTournamentKeepsFullName() {
        Game g = new Game();
        g.setTournamentType(TournamentType.IN_SEASON_TOURNAMENT);
        g.setTournamentName("Maui Invitational");
        g.setTournamentRound("Championship");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("Maui Invitational Final");
        assertThat(badge.variant()).isEqualTo("in-season");
    }

    @Test
    void nitFirstRound() {
        Game g = new Game();
        g.setTournamentType(TournamentType.NIT);
        g.setTournamentName("NIT");
        g.setTournamentRound("1st Round");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("NIT 1R");
    }
}
