package com.yotto.basketball.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BracketTextRendererTest {

    private static BracketView.TeamLine team(Integer seed, String name, Integer score, boolean winner) {
        return new BracketView.TeamLine(seed, name, name, 1L, null, score, winner, false);
    }

    private static BracketView.TeamLine tbd(String label) {
        return new BracketView.TeamLine(null, label, null, null, null, null, false, true);
    }

    private static BracketView.Slot slot(BracketView.TeamLine top, BracketView.TeamLine bottom) {
        return new BracketView.Slot(1L, null, false, top, bottom, null, null);
    }

    private static BracketView.Region region(String name) {
        List<List<BracketView.Slot>> rounds = new ArrayList<>();
        for (int size : new int[]{8, 4, 2, 1}) {
            List<BracketView.Slot> round = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                round.add(slot(tbd("TBD"), tbd("TBD")));
            }
            rounds.add(round);
        }
        // one real first-round game so the layout has content to anchor assertions on
        rounds.get(0).set(0, slot(team(1, "Duke", 71, true), team(16, "Siena", 65, false)));
        return new BracketView.Region(name, rounds);
    }

    private static BracketView bracket(BracketView.TeamLine champion) {
        return new BracketView(2026,
                List.of(region("East"), region("South")),
                List.of(region("Midwest"), region("West")),
                slot(team(2, "UConn", 71, true), team(3, "Illinois", 62, false)),
                slot(team(1, "Arizona", 73, false), team(1, "Michigan", 91, true)),
                slot(team(1, "Michigan", 69, true), team(2, "UConn", 63, false)),
                champion);
    }

    @Test
    void rendersRegionsSeedsScoresAndWinnerMarks() {
        String txt = BracketTextRenderer.render(bracket(team(1, "Michigan", null, true)));

        assertThat(txt).contains("2026 NCAA TOURNAMENT");
        assertThat(txt).contains("EAST").contains("SOUTH").contains("MIDWEST").contains("WEST");
        assertThat(txt).contains("1st Round").contains("Elite 8");
        assertThat(txt).contains("( 1) Duke              71*");
        assertThat(txt).contains("(16) Siena             65 ");
        assertThat(txt).contains("FINAL FOUR").contains("CHAMPIONSHIP");
        assertThat(txt).contains("★ MICHIGAN — NATIONAL CHAMPIONS ★");
        assertThat(txt).contains("not betting advice");
    }

    @Test
    void winnerAndLoserConnectorsAlign() {
        String txt = BracketTextRenderer.render(bracket(team(1, "Michigan", null, true)));
        int dukeCol = txt.lines().filter(l -> l.contains("Duke") && l.contains("─┐"))
                .findFirst().orElseThrow().indexOf('┐');
        int sienaCol = txt.lines().filter(l -> l.contains("Siena"))
                .findFirst().orElseThrow().indexOf('┘');
        assertThat(dukeCol).isEqualTo(sienaCol);
    }

    @Test
    void noChampionYet_omitsChampionLine() {
        String txt = BracketTextRenderer.render(bracket(tbd("TBD")));
        assertThat(txt).doesNotContain("NATIONAL CHAMPIONS");
    }
}
