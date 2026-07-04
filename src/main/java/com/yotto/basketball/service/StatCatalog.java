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
     * enough to need no blurb (e.g. field-goal percentage). {@code mechanics} is a
     * longer how-it's-computed note for the opaque stats (efficiency, pace, the
     * possession-based rates); null when the one-liner already says it all. The
     * formulas must match {@link BoxScoreStatCalculator}'s registry — all inputs
     * are season-to-date totals, not averages of per-game values.
     */
    public record StatInfo(String name,
                           String title,
                           String category,
                           Format format,
                           boolean higherIsBetter,
                           String description,
                           String mechanics) {

        StatInfo(String name, String title, String category, Format format,
                 boolean higherIsBetter, String description) {
            this(name, title, category, format, higherIsBetter, description, null);
        }
    }

    /**
     * The possession estimate used everywhere below, phrased once. Possessions are
     * not in the box score, so they are estimated with the standard formula.
     */
    private static final String POSS_NOTE =
            "Possessions aren't in the box score, so they're estimated as "
            + "FGA − OffReb + TO + 0.475×FTA (the standard estimator).";

    private static final List<StatInfo> CATALOG = List.of(
            new StatInfo("pace", "Pace", "Efficiency", Format.RATING, true,
                    "Possessions per game; how fast a team plays.",
                    POSS_NOTE + " Each game's possession count is the average of the two teams' "
                    + "estimates (both teams have roughly the same number of possessions in a game), "
                    + "and pace is the season total of those estimates divided by games played."),
            new StatInfo("off_efficiency", "Offensive Efficiency", "Efficiency", Format.RATING, true,
                    "Points scored per 100 possessions.",
                    "100 × season points scored ÷ season possessions. " + POSS_NOTE
                    + " Dividing by possessions instead of games removes tempo: a fast team piles up "
                    + "points by playing more possessions, not necessarily by using them well."),
            new StatInfo("def_efficiency", "Defensive Efficiency", "Efficiency", Format.RATING, false,
                    "Points allowed per 100 possessions.",
                    "100 × season points allowed ÷ opponents' season possessions. " + POSS_NOTE
                    + " Per-possession accounting keeps a slow, stingy-looking defense and a fast, "
                    + "leaky-looking one on the same scale."),
            new StatInfo("efg_pct", "Effective Field Goal Percentage", "Four Factors", Format.PERCENT, true,
                    "Field-goal shooting that credits a 3-pointer as 1.5 made shots.",
                    "(FGM + 0.5×3PM) ÷ FGA. A made three is worth 1.5 times a made two, so it "
                    + "counts as 1.5 makes; plain FG% undervalues teams that shoot a lot of threes."),
            new StatInfo("opp_efg_pct", "Opponent Effective Field Goal Percentage", "Four Factors", Format.PERCENT, false,
                    "Opponent eFG%; a defense's shot-quality suppression.",
                    "(opponent FGM + 0.5×opponent 3PM) ÷ opponent FGA — effective field goal "
                    + "percentage computed on what opponents shot against this team."),
            new StatInfo("tov_rate", "Turnover Rate", "Four Factors", Format.RATE, false,
                    "Share of possessions that end in a turnover.",
                    "Season turnovers ÷ season possessions. " + POSS_NOTE),
            new StatInfo("opp_tov_rate", "Opponent Turnover Rate", "Four Factors", Format.RATE, true,
                    "Share of opponent possessions a defense forces into turnovers.",
                    "Opponents' season turnovers ÷ opponents' season possessions. " + POSS_NOTE),
            new StatInfo("orb_pct", "Offensive Rebound Percentage", "Four Factors", Format.PERCENT, true,
                    "Share of available offensive rebounds a team grabs.",
                    "Own offensive rebounds ÷ (own offensive rebounds + opponents' defensive "
                    + "rebounds). The denominator is every rebound that was available on the team's "
                    + "own missed shots, so the stat isn't inflated just by missing more often."),
            new StatInfo("drb_pct", "Defensive Rebound Percentage", "Four Factors", Format.PERCENT, true,
                    "Share of available defensive rebounds a team grabs.",
                    "Own defensive rebounds ÷ (own defensive rebounds + opponents' offensive "
                    + "rebounds) — the share of opponent misses this team ends up securing."),
            new StatInfo("ft_rate", "Free Throw Rate", "Four Factors", Format.RATE, true,
                    "Free throws made per field-goal attempt; how often a team gets to the line.",
                    "FTM ÷ FGA. Made free throws (not attempts) over field-goal attempts, so it "
                    + "blends getting to the line with converting once there — the Four Factors variant."),
            new StatInfo("opp_ft_rate", "Opponent Free Throw Rate", "Four Factors", Format.RATE, false,
                    "Opponent free throws made per FGA; fouling tendency of a defense.",
                    "Opponent FTM ÷ opponent FGA — how many free-throw points a defense gives up "
                    + "relative to the shots it forces opponents to take from the field."),
            new StatInfo("ts_pct", "True Shooting Percentage", "Shooting", Format.PERCENT, true,
                    "Scoring efficiency across 2s, 3s, and free throws combined.",
                    "Points ÷ (2 × (FGA + 0.475×FTA)). The denominator estimates true "
                    + "scoring attempts: 0.475×FTA converts free-throw trips into the shot "
                    + "attempts they replaced. Unlike FG% or eFG%, it rewards drawing fouls."),
            new StatInfo("fg_pct", "Field Goal Percentage", "Shooting", Format.PERCENT, true, null),
            new StatInfo("fg3_pct", "Three-Point Percentage", "Shooting", Format.PERCENT, true, null),
            new StatInfo("ft_pct", "Free Throw Percentage", "Shooting", Format.PERCENT, true, null),
            new StatInfo("fg3_rate", "Three-Point Attempt Rate", "Shooting", Format.RATE, true,
                    "Share of field-goal attempts taken from three.",
                    "3PA ÷ FGA. A shot-selection stat, not an accuracy stat — it says nothing "
                    + "about whether the threes go in."),
            new StatInfo("trb_pct", "Total Rebound Percentage", "Rebounding", Format.PERCENT, true,
                    "Share of all available rebounds a team grabs.",
                    "(own offensive + defensive rebounds) ÷ all rebounds by both teams. A "
                    + "pace-proof alternative to rebounds per game."),
            new StatInfo("rpg", "Rebounds Per Game", "Rebounding", Format.PER_GAME, true, null),
            new StatInfo("orpg", "Offensive Rebounds Per Game", "Rebounding", Format.PER_GAME, true, null),
            new StatInfo("drpg", "Defensive Rebounds Per Game", "Rebounding", Format.PER_GAME, true, null),
            new StatInfo("apg", "Assists Per Game", "Playmaking", Format.PER_GAME, true, null),
            new StatInfo("ast_to_ratio", "Assist-to-Turnover Ratio", "Playmaking", Format.RATIO, true,
                    "Assists divided by turnovers."),
            new StatInfo("assisted_fg_pct", "Assisted Field Goal Percentage", "Playmaking", Format.PERCENT, true,
                    "Share of made field goals that were assisted.",
                    "AST ÷ FGM. High values suggest ball movement and catch-and-shoot offense; "
                    + "low values suggest isolation scoring and shot creation off the dribble."),
            new StatInfo("stl_rate", "Steal Rate", "Defense", Format.RATE, true,
                    "Steals per opponent possession.",
                    "Season steals ÷ opponents' season possessions. " + POSS_NOTE),
            new StatInfo("blk_pct", "Block Percentage", "Defense", Format.PERCENT, true,
                    "Share of opponent 2-point attempts blocked.",
                    "BLK ÷ (opponent FGA − opponent 3PA). Only 2-point attempts are counted "
                    + "in the denominator because threes are almost never blocked."),
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
