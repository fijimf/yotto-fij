package com.yotto.basketball.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The stat categories with public landing pages (/stats/{slug}). Each maps a
 * URL slug to a {@link StatCatalog} category name plus page prose. Slugs share
 * the /stats/{x} namespace with stat names and the reserved words below — a
 * test pins that all three sets stay disjoint.
 */
public enum StatCategory {

    RESULTS("results", "Results",
            "Winning and schedule strength: win percentage and the RPI schedule "
            + "components, computed from game results alone."),
    SCORING("scoring", "Scoring",
            "Raw scoring: points per game for and against, average margin, and "
            + "how much those margins swing game to game."),
    EFFICIENCY("efficiency", "Efficiency",
            "Tempo-free performance: how fast a team plays and how many points it "
            + "scores and allows per 100 possessions."),
    FOUR_FACTORS("four-factors", "Four Factors",
            "Dean Oliver's four factors of winning — shooting, turnovers, "
            + "rebounding, and free throws — on both sides of the ball."),
    SHOOTING("shooting", "Shooting",
            "Shot-making and shot selection: overall efficiency, splits by shot "
            + "type, and how often a team lets it fly from three."),
    REBOUNDING("rebounding", "Rebounding",
            "The battle on the glass, both as pace-proof percentages of available "
            + "rebounds and as per-game counts."),
    PLAYMAKING("playmaking", "Playmaking",
            "Ball movement and shot creation: assists, ball security, and how many "
            + "buckets come off a pass."),
    DEFENSE("defense", "Defense",
            "Defensive playmaking and discipline: steals, blocks, and fouling.");

    /** /stats/{x} names that are never stat names or category slugs. */
    public static final Set<String> RESERVED_SLUGS = Set.of("predictor", "correlation");

    private final String slug;
    private final String catalogCategory;
    private final String description;

    StatCategory(String slug, String catalogCategory, String description) {
        this.slug = slug;
        this.catalogCategory = catalogCategory;
        this.description = description;
    }

    public static Optional<StatCategory> fromSlug(String slug) {
        for (StatCategory c : values()) {
            if (c.slug.equals(slug)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** The category holding a catalog entry (by its category display name). */
    public static Optional<StatCategory> forCatalogCategory(String categoryName) {
        for (StatCategory c : values()) {
            if (c.catalogCategory.equals(categoryName)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** This category's stats, in catalog order. */
    public List<StatCatalog.StatInfo> stats() {
        return StatCatalog.all().stream()
                .filter(i -> i.category().equals(catalogCategory))
                .toList();
    }

    public String getSlug() { return slug; }
    public String getCatalogCategory() { return catalogCategory; }
    public String getDescription() { return description; }
}
