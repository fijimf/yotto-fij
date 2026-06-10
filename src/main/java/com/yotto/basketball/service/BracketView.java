package com.yotto.basketball.service;

import java.util.ArrayList;
import java.util.List;

/**
 * View model for the NCAA tournament bracket page. Slot-based rather than game-based:
 * every bracket position renders even when its game has not been scheduled yet, so a
 * mid-tournament bracket shows placeholders ("W 8/9", "TBD") for future rounds.
 */
public record BracketView(
        int year,
        List<Region> leftRegions,
        List<Region> rightRegions,
        Slot semifinalLeft,
        Slot semifinalRight,
        Slot championship,
        TeamLine champion) {

    /** All regions in display/tab order: left top, left bottom, right top, right bottom. */
    public List<Region> regions() {
        List<Region> all = new ArrayList<>(leftRegions);
        all.addAll(rightRegions);
        return all;
    }

    /** One region: name plus four rounds of slots (8, 4, 2, 1 top to bottom). */
    public record Region(String name, List<List<Slot>> rounds) {}

    /**
     * One bracket position. gameId is null when no game row exists yet; the team lines
     * then carry placeholders derived from the feeder slots. metaLabel is the muted
     * line under the teams (date, spread) and is null for FINAL games to save space.
     */
    public record Slot(
            Long gameId,
            String metaLabel,
            boolean live,
            TeamLine top,
            TeamLine bottom,
            String firstFourNote,
            String firstFourTooltip) {}

    /** One team line in a slot. When tbd is true only label/tooltip are populated. */
    public record TeamLine(
            Integer seed,
            String label,
            String tooltip,
            Long teamId,
            String logoUrl,
            Integer score,
            boolean winner,
            boolean tbd) {}
}
