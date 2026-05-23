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
    void ncaaBadgeIsAlwaysJustNcaaTournament() {
        Game g = new Game();
        g.setTournamentType(TournamentType.NCAA_TOURNAMENT);
        g.setTournamentName("NCAA Tournament");
        g.setTournamentRound("1st Round");
        g.setTournamentRegion("Midwest");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("NCAA Tournament");
        assertThat(badge.variant()).isEqualTo("ncaa");
        assertThat(badge.tooltip())
                .contains("NCAA Tournament").contains("Midwest").contains("1st Round");
    }

    @Test
    void ncaaNationalChampionshipBadgeIsStillJustNcaaTournament() {
        Game g = new Game();
        g.setTournamentType(TournamentType.NCAA_TOURNAMENT);
        g.setTournamentName("NCAA Tournament");
        g.setTournamentRound("National Championship");

        assertThat(formatter.format(g).label()).isEqualTo("NCAA Tournament");
    }

    @Test
    void conferenceTournamentStripsSuffixAndDropsRound() {
        Game g = new Game();
        g.setTournamentType(TournamentType.CONFERENCE_TOURNAMENT);
        g.setTournamentName("Big 12 Championship");
        g.setTournamentRound("Quarterfinal");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("Big 12");
        assertThat(badge.variant()).isEqualTo("conf");
    }

    @Test
    void inSeasonTournamentKeepsFullName() {
        Game g = new Game();
        g.setTournamentType(TournamentType.IN_SEASON_TOURNAMENT);
        g.setTournamentName("Maui Invitational");
        g.setTournamentRound("Championship");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("Maui Invitational");
        assertThat(badge.variant()).isEqualTo("in-season");
    }

    @Test
    void nitUsesPostseasonVariant() {
        Game g = new Game();
        g.setTournamentType(TournamentType.NIT);
        g.setTournamentName("NIT");
        g.setTournamentRound("1st Round");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("NIT");
        assertThat(badge.variant()).isEqualTo("postseason");
    }

    @Test
    void cbiUsesPostseasonVariant() {
        Game g = new Game();
        g.setTournamentType(TournamentType.CBI);
        g.setTournamentName("CBI");
        g.setTournamentRound("Semifinal");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("CBI");
        assertThat(badge.variant()).isEqualTo("postseason");
    }

    @Test
    void crownUsesPostseasonVariant() {
        Game g = new Game();
        g.setTournamentType(TournamentType.CROWN);
        g.setTournamentName("College Basketball Crown");
        g.setTournamentRound("Quarterfinal");

        var badge = formatter.format(g);
        assertThat(badge.label()).isEqualTo("College Basketball Crown");
        assertThat(badge.variant()).isEqualTo("postseason");
    }
}
