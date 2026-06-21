package com.yotto.basketball.controller;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Presentation catalog for the box-score-derived stats persisted in
 * {@code team_stat_snapshots}. The calc layer ({@code BoxScoreStatCalculator})
 * owns only the stat name + direction; label, grouping, and number formatting
 * live here so display concerns never leak into the calculator.
 *
 * <p>Enum declaration order is the within-category display order. A stat present
 * in the snapshot table but absent from this catalog is rendered under
 * {@link Category#OTHER} with {@link Format#RAW} — a new calc stat is therefore
 * surfaced (never silently dropped), and {@code TeamStatDisplayTest} fails loudly
 * until a catalog entry is added.
 */
public enum TeamStatDisplay {

    // Efficiency
    PACE("pace", "Pace", Category.EFFICIENCY, Format.DECIMAL_1),
    OFF_EFFICIENCY("off_efficiency", "Offensive Rtg", Category.EFFICIENCY, Format.DECIMAL_1),
    DEF_EFFICIENCY("def_efficiency", "Defensive Rtg", Category.EFFICIENCY, Format.DECIMAL_1),

    // Four Factors — Offense
    EFG_PCT("efg_pct", "eFG%", Category.FOUR_FACTORS_OFF, Format.PERCENT_1),
    TOV_RATE("tov_rate", "Turnover Rate", Category.FOUR_FACTORS_OFF, Format.PERCENT_1),
    ORB_PCT("orb_pct", "Off. Reb%", Category.FOUR_FACTORS_OFF, Format.PERCENT_1),
    FT_RATE("ft_rate", "FT Rate", Category.FOUR_FACTORS_OFF, Format.PERCENT_1),

    // Four Factors — Defense
    OPP_EFG_PCT("opp_efg_pct", "Opp eFG%", Category.FOUR_FACTORS_DEF, Format.PERCENT_1),
    OPP_TOV_RATE("opp_tov_rate", "Opp Turnover Rate", Category.FOUR_FACTORS_DEF, Format.PERCENT_1),
    DRB_PCT("drb_pct", "Def. Reb%", Category.FOUR_FACTORS_DEF, Format.PERCENT_1),
    OPP_FT_RATE("opp_ft_rate", "Opp FT Rate", Category.FOUR_FACTORS_DEF, Format.PERCENT_1),

    // Shooting
    TS_PCT("ts_pct", "True Shooting%", Category.SHOOTING, Format.PERCENT_1),
    FG_PCT("fg_pct", "FG%", Category.SHOOTING, Format.PERCENT_1),
    FG3_PCT("fg3_pct", "3PT%", Category.SHOOTING, Format.PERCENT_1),
    FT_PCT("ft_pct", "FT%", Category.SHOOTING, Format.PERCENT_1),
    FG3_RATE("fg3_rate", "3PT Rate", Category.SHOOTING, Format.PERCENT_1),

    // Rebounding
    RPG("rpg", "Rebounds / Game", Category.REBOUNDING, Format.DECIMAL_1),
    ORPG("orpg", "Off. Reb / Game", Category.REBOUNDING, Format.DECIMAL_1),
    DRPG("drpg", "Def. Reb / Game", Category.REBOUNDING, Format.DECIMAL_1),
    TRB_PCT("trb_pct", "Total Reb%", Category.REBOUNDING, Format.PERCENT_1),

    // Assists
    APG("apg", "Assists / Game", Category.PLAYMAKING, Format.DECIMAL_1),
    AST_TO_RATIO("ast_to_ratio", "Assist/TO", Category.PLAYMAKING, Format.DECIMAL_2),
    ASSISTED_FG_PCT("assisted_fg_pct", "Assisted FG%", Category.PLAYMAKING, Format.PERCENT_1),

    // Defense
    STL_RATE("stl_rate", "Steal Rate", Category.DEFENSE, Format.PERCENT_1),
    BLK_PCT("blk_pct", "Block%", Category.DEFENSE, Format.PERCENT_1),
    PF_PER_GAME("pf_per_game", "Fouls / Game", Category.DEFENSE, Format.DECIMAL_1);

    /** Display groups, rendered in declaration order. */
    public enum Category {
        EFFICIENCY("Efficiency"),
        FOUR_FACTORS_OFF("Four Factors — Offense"),
        FOUR_FACTORS_DEF("Four Factors — Defense"),
        SHOOTING("Shooting"),
        REBOUNDING("Rebounding"),
        PLAYMAKING("Assists"),
        DEFENSE("Defense"),
        OTHER("Other");

        private final String header;

        Category(String header) { this.header = header; }

        public String getHeader() { return header; }
    }

    /** Number formats. The name is also emitted to the client for chart tooltips. */
    public enum Format {
        PERCENT_1 { @Override String render(double v) { return String.format(Locale.US, "%.1f%%", v * 100); } },
        DECIMAL_1 { @Override String render(double v) { return String.format(Locale.US, "%.1f", v); } },
        DECIMAL_2 { @Override String render(double v) { return String.format(Locale.US, "%.2f", v); } },
        RAW       { @Override String render(double v) { return String.format(Locale.US, "%.2f", v); } };

        abstract String render(double value);
    }

    private static final Map<String, TeamStatDisplay> BY_NAME =
            java.util.Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(d -> d.statName, Function.identity()));

    private final String statName;
    private final String label;
    private final Category category;
    private final Format format;

    TeamStatDisplay(String statName, String label, Category category, Format format) {
        this.statName = statName;
        this.label = label;
        this.category = category;
        this.format = format;
    }

    /** Catalog entry for a stat name, or {@code null} if uncatalogued. */
    public static TeamStatDisplay forStat(String name) {
        return BY_NAME.get(name);
    }

    public String getStatName() { return statName; }
    public String getLabel() { return label; }
    public Category getCategory() { return category; }
    public Format getFormat() { return format; }

    public String format(double value) { return format.render(value); }
}
