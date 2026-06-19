package com.yotto.basketball.controller;

import java.util.List;

/**
 * Canonical ordering of tournament round names, shared by the team and conference pages so
 * "furthest reached" and round grouping stay consistent. Higher index = deeper. The same scale
 * works for NCAA and conference tournaments; unknown rounds get -1 so they never beat a known round.
 */
final class TournamentRounds {

    static final List<String> ORDER = List.of(
            "First Four",
            "1st Round",
            "2nd Round",
            "Quarterfinal",
            "Sweet 16",
            "Semifinal",
            "Elite 8",
            "Final",
            "Final Four",
            "Championship",
            "National Championship"
    );

    private TournamentRounds() {
    }

    static int indexOf(String round) {
        if (round == null) return -1;
        int idx = ORDER.indexOf(round);
        return idx >= 0 ? idx : -1;
    }
}
