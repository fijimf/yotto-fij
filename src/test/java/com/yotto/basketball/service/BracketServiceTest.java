package com.yotto.basketball.service;

import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Team;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the bracket slot-tree derivation — no Spring context. Builds
 * games in memory and verifies placement, ordering, placeholders, and pairing.
 */
class BracketServiceTest {

    private static final List<String> REGIONS = List.of("East", "West", "South", "Midwest");
    /** Canonical first-round pairings, top slot to bottom slot. */
    private static final int[][] R1_PAIRS = {{1, 16}, {8, 9}, {5, 12}, {4, 13}, {6, 11}, {3, 14}, {7, 10}, {2, 15}};

    private long gameId = 1000;
    private final Map<String, Map<Integer, Team>> teams = new HashMap<>();

    @Test
    void fullChalkBracketPlacesEveryGameAndDerivesPairing() {
        List<Game> games = chalkTournament();
        // shuffle to prove placement does not depend on input order
        Collections.shuffle(games, new Random(42));

        BracketView view = BracketService.build(2026, games);

        // Semifinals were East-South and West-Midwest, so those regions share sides
        assertThat(view.leftRegions()).extracting(BracketView.Region::name).containsExactly("East", "South");
        assertThat(view.rightRegions()).extracting(BracketView.Region::name).containsExactly("West", "Midwest");

        for (BracketView.Region region : view.regions()) {
            assertThat(region.rounds()).hasSize(4);
            assertThat(region.rounds().get(0)).hasSize(8);
            assertThat(region.rounds().get(1)).hasSize(4);
            assertThat(region.rounds().get(2)).hasSize(2);
            assertThat(region.rounds().get(3)).hasSize(1);

            // first round follows canonical seed order, better seed on top
            for (int i = 0; i < 8; i++) {
                BracketView.Slot slot = region.rounds().get(0).get(i);
                assertThat(slot.top().seed()).isEqualTo(R1_PAIRS[i][0]);
                assertThat(slot.bottom().seed()).isEqualTo(R1_PAIRS[i][1]);
                assertThat(slot.top().winner()).isTrue();
                assertThat(slot.metaLabel()).isNull();
            }
            // chalk Sweet 16 top slot is 1 vs 4, Elite 8 is 1 vs 2
            assertThat(region.rounds().get(2).get(0).top().seed()).isEqualTo(1);
            assertThat(region.rounds().get(2).get(0).bottom().seed()).isEqualTo(4);
            assertThat(region.rounds().get(3).get(0).top().seed()).isEqualTo(1);
            assertThat(region.rounds().get(3).get(0).bottom().seed()).isEqualTo(2);
        }

        assertThat(view.champion()).isNotNull();
        assertThat(view.champion().label()).isEqualTo("East 1");
        assertThat(view.championship().top().label()).isEqualTo("East 1");

        // First Four note lands on the East 16-seed slot (first-round slot 0)
        assertThat(view.regions().get(0).rounds().get(0).get(0).firstFourNote())
                .isNotNull()
                .contains("FF:");
    }

    @Test
    void partialBracketRendersPlaceholdersFromFeeders() {
        List<Game> games = new ArrayList<>();
        // East first round complete; everything else unplayed/unscheduled
        for (int i = 0; i < R1_PAIRS.length; i++) {
            games.add(finalGame("East", "1st Round", R1_PAIRS[i][0], R1_PAIRS[i][1]));
        }
        // give the other regions one game each so all four regions exist
        for (String region : List.of("West", "South", "Midwest")) {
            games.add(scheduledGame(region, "1st Round", 1, 16, null));
        }

        BracketView view = BracketService.build(2026, games);
        BracketView.Region east = view.regions().get(0);
        assertThat(east.name()).isEqualTo("East");

        // 2nd-round slots have no game but carry the advancing winners from feeders
        BracketView.Slot r2top = east.rounds().get(1).get(0);
        assertThat(r2top.gameId()).isNull();
        assertThat(r2top.top().label()).isEqualTo("East 1");
        assertThat(r2top.top().score()).isNull();
        assertThat(r2top.bottom().label()).isEqualTo("East 8");

        // Sweet 16 feeders are missing games entirely -> TBD
        assertThat(east.rounds().get(2).get(0).top().tbd()).isTrue();
        assertThat(east.rounds().get(2).get(0).top().label()).isEqualTo("TBD");

        // West has a scheduled (not final) 1v16, so its 2nd round shows "W 1/16"
        BracketView.Region west = view.regions().stream()
                .filter(r -> r.name().equals("West")).findFirst().orElseThrow();
        assertThat(west.rounds().get(1).get(0).top().label()).isEqualTo("W 1/16");
        assertThat(west.rounds().get(1).get(0).top().tbd()).isTrue();

        // no semis yet: canonical split and region-champion placeholders
        assertThat(view.leftRegions()).extracting(BracketView.Region::name).containsExactly("East", "West");
        assertThat(view.semifinalLeft().top().label()).isEqualTo("East");
        assertThat(view.semifinalLeft().top().tbd()).isTrue();
        assertThat(view.championship().top().label()).isEqualTo("TBD");
        assertThat(view.champion()).isNull();
    }

    @Test
    void betterSeedRendersOnTopRegardlessOfHomeAway() {
        // home team is the 16 seed; the 1 seed must still render on top
        Game g = scheduledGame("East", "1st Round", 16, 1, null);
        BracketView view = BracketService.build(2026, List.of(g));
        BracketView.Slot slot = view.regions().get(0).rounds().get(0).get(0);
        assertThat(slot.top().seed()).isEqualTo(1);
        assertThat(slot.bottom().seed()).isEqualTo(16);
    }

    @Test
    void scheduledGameMetaShowsDateAndSpread() {
        Game g = scheduledGame("East", "1st Round", 1, 16, new BigDecimal("-6.5"));
        BracketView view = BracketService.build(2026, List.of(g));
        BracketView.Slot slot = view.regions().get(0).rounds().get(0).get(0);
        // home team (seed 1, abbreviation E1) is favored
        assertThat(slot.metaLabel()).isEqualTo("Mar 19 · E1 -6.5");

        Game away = scheduledGame("East", "2nd Round", 1, 8, new BigDecimal("3.5"));
        view = BracketService.build(2026, List.of(away));
        slot = view.regions().get(0).rounds().get(1).get(0);
        assertThat(slot.metaLabel()).isEqualTo("Mar 19 · E8 -3.5");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Full 67-game tournament where the better seed always wins. */
    private List<Game> chalkTournament() {
        List<Game> games = new ArrayList<>();
        for (String region : REGIONS) {
            for (int[] pair : R1_PAIRS) {
                games.add(finalGame(region, "1st Round", pair[0], pair[1]));
            }
            int[][] r2 = {{1, 8}, {4, 5}, {3, 6}, {2, 7}};
            for (int[] pair : r2) games.add(finalGame(region, "2nd Round", pair[0], pair[1]));
            games.add(finalGame(region, "Sweet 16", 1, 4));
            games.add(finalGame(region, "Sweet 16", 2, 3));
            games.add(finalGame(region, "Elite 8", 1, 2));
        }
        // First Four: two 16 seeds in the East (loser team gets a fresh id)
        Team altSixteen = namedTeam("East FF 16", "EFF", 99016L);
        games.add(finalGameBetween(team("East", 16), altSixteen, 16, 16, "East", "First Four"));
        // Final Four pairs East-South and West-Midwest; East beats West for the title
        games.add(finalGameBetween(team("East", 1), team("South", 1), 1, 1, null, "Final Four"));
        games.add(finalGameBetween(team("West", 1), team("Midwest", 1), 1, 1, null, "Final Four"));
        games.add(finalGameBetween(team("East", 1), team("West", 1), 1, 1, null, "National Championship"));
        return games;
    }

    private Game finalGame(String region, String round, int winnerSeed, int loserSeed) {
        return finalGameBetween(team(region, winnerSeed), team(region, loserSeed), winnerSeed, loserSeed, region, round);
    }

    private Game finalGameBetween(Team home, Team away, int homeSeed, int awaySeed, String region, String round) {
        Game g = baseGame(home, away, homeSeed, awaySeed, region, round);
        g.setStatus(Game.GameStatus.FINAL);
        g.setHomeScore(80);
        g.setAwayScore(70);
        return g;
    }

    private Game scheduledGame(String region, String round, int homeSeed, int awaySeed, BigDecimal spread) {
        Game g = baseGame(team(region, homeSeed), team(region, awaySeed), homeSeed, awaySeed, region, round);
        g.setStatus(Game.GameStatus.SCHEDULED);
        if (spread != null) {
            BettingOdds odds = new BettingOdds();
            odds.setSpread(spread);
            g.setBettingOdds(odds);
        }
        return g;
    }

    private Game baseGame(Team home, Team away, int homeSeed, int awaySeed, String region, String round) {
        Game g = new Game();
        g.setId(gameId++);
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeSeed(homeSeed);
        g.setAwaySeed(awaySeed);
        g.setTournamentType(Game.TournamentType.NCAA_TOURNAMENT);
        g.setTournamentRound(round);
        g.setTournamentRegion(region);
        g.setGameDate(LocalDateTime.of(2026, 3, 19, 19, 0));
        return g;
    }

    private Team team(String region, int seed) {
        return teams.computeIfAbsent(region, k -> new HashMap<>())
                .computeIfAbsent(seed, s -> namedTeam(region + " " + s,
                        region.charAt(0) + String.valueOf(s),
                        REGIONS.indexOf(region) * 100L + 1000L + s));
    }

    private Team namedTeam(String name, String abbreviation, long id) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setNickname(name);
        t.setAbbreviation(abbreviation);
        return t;
    }
}
