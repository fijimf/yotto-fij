package com.yotto.basketball.service;

import jakarta.persistence.EntityNotFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Display metadata for the derived box-score stats — the full title, an optional
 * plain-language description, a value format, and a category for grouping.
 *
 * <p>Deliberately standalone (not folded into {@link BoxScoreStatCalculator}'s
 * registry) so the calc-pipeline contract stays untouched. The two lists are kept
 * honest by a completeness test that asserts every registry stat has a catalog
 * entry and that {@code higherIsBetter} matches the registry.
 */
public final class StatCatalog {

    private StatCatalog() {}

    /** How a raw value is rendered for humans. */
    public enum Format { PERCENT, RATE, RATING, PER_GAME, RATIO }

    /**
     * Display metadata for one stat. {@code description} is null for stats obvious
     * enough to need no blurb (e.g. field-goal percentage).
     */
    public record StatInfo(String name,
                           String title,
                           String category,
                           Format format,
                           boolean higherIsBetter,
                           String description) {}

    private static final List<StatInfo> CATALOG = List.of(
            new StatInfo("pace", "Pace", "Efficiency", Format.RATING, true,
                    "Possessions per game; how fast a team plays."),
            new StatInfo("off_efficiency", "Offensive Efficiency", "Efficiency", Format.RATING, true,
                    "Points scored per 100 possessions."),
            new StatInfo("def_efficiency", "Defensive Efficiency", "Efficiency", Format.RATING, false,
                    "Points allowed per 100 possessions."),
            new StatInfo("efg_pct", "Effective Field Goal Percentage", "Four Factors", Format.PERCENT, true,
                    "Field-goal shooting that credits a 3-pointer as 1.5 made shots."),
            new StatInfo("opp_efg_pct", "Opponent Effective Field Goal Percentage", "Four Factors", Format.PERCENT, false,
                    "Opponent eFG%; a defense's shot-quality suppression."),
            new StatInfo("tov_rate", "Turnover Rate", "Four Factors", Format.RATE, false,
                    "Share of possessions that end in a turnover."),
            new StatInfo("opp_tov_rate", "Opponent Turnover Rate", "Four Factors", Format.RATE, true,
                    "Share of opponent possessions a defense forces into turnovers."),
            new StatInfo("orb_pct", "Offensive Rebound Percentage", "Four Factors", Format.PERCENT, true,
                    "Share of available offensive rebounds a team grabs."),
            new StatInfo("drb_pct", "Defensive Rebound Percentage", "Four Factors", Format.PERCENT, true,
                    "Share of available defensive rebounds a team grabs."),
            new StatInfo("ft_rate", "Free Throw Rate", "Four Factors", Format.RATE, true,
                    "Free throws made per field-goal attempt; how often a team gets to the line."),
            new StatInfo("opp_ft_rate", "Opponent Free Throw Rate", "Four Factors", Format.RATE, false,
                    "Opponent free throws made per FGA; fouling tendency of a defense."),
            new StatInfo("ts_pct", "True Shooting Percentage", "Shooting", Format.PERCENT, true,
                    "Scoring efficiency across 2s, 3s, and free throws combined."),
            new StatInfo("fg_pct", "Field Goal Percentage", "Shooting", Format.PERCENT, true, null),
            new StatInfo("fg3_pct", "Three-Point Percentage", "Shooting", Format.PERCENT, true, null),
            new StatInfo("ft_pct", "Free Throw Percentage", "Shooting", Format.PERCENT, true, null),
            new StatInfo("fg3_rate", "Three-Point Attempt Rate", "Shooting", Format.RATE, true,
                    "Share of field-goal attempts taken from three."),
            new StatInfo("trb_pct", "Total Rebound Percentage", "Rebounding", Format.PERCENT, true,
                    "Share of all available rebounds a team grabs."),
            new StatInfo("rpg", "Rebounds Per Game", "Rebounding", Format.PER_GAME, true, null),
            new StatInfo("orpg", "Offensive Rebounds Per Game", "Rebounding", Format.PER_GAME, true, null),
            new StatInfo("drpg", "Defensive Rebounds Per Game", "Rebounding", Format.PER_GAME, true, null),
            new StatInfo("apg", "Assists Per Game", "Playmaking", Format.PER_GAME, true, null),
            new StatInfo("ast_to_ratio", "Assist-to-Turnover Ratio", "Playmaking", Format.RATIO, true,
                    "Assists divided by turnovers."),
            new StatInfo("assisted_fg_pct", "Assisted Field Goal Percentage", "Playmaking", Format.PERCENT, true,
                    "Share of made field goals that were assisted."),
            new StatInfo("stl_rate", "Steal Rate", "Defense", Format.RATE, true,
                    "Steals per opponent possession."),
            new StatInfo("blk_pct", "Block Percentage", "Defense", Format.PERCENT, true,
                    "Share of opponent 2-point attempts blocked."),
            new StatInfo("pf_per_game", "Personal Fouls Per Game", "Defense", Format.PER_GAME, false, null)
    );

    private static final Map<String, StatInfo> BY_NAME;
    static {
        Map<String, StatInfo> m = new LinkedHashMap<>();
        for (StatInfo s : CATALOG) {
            m.put(s.name(), s);
        }
        BY_NAME = Map.copyOf(m);
    }

    /** Whether a stat has a catalog entry (i.e. a dedicated stat-detail page). */
    public static boolean contains(String name) {
        return BY_NAME.containsKey(name);
    }

    /** Catalog entry for a stat, or 404 if the name is unknown. */
    public static StatInfo require(String name) {
        StatInfo info = BY_NAME.get(name);
        if (info == null) {
            throw new EntityNotFoundException("Unknown stat: " + name);
        }
        return info;
    }

    /** All stats in registry order (used to build the glossary). */
    public static List<StatInfo> all() {
        return CATALOG;
    }
}
