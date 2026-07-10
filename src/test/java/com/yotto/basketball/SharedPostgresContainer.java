package com.yotto.basketball;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton PostgreSQL container shared across every integration-test base
 * class ({@link BaseIntegrationTest} for full-stack tests, {@link BaseDataJpaTest}
 * for JPA-slice tests). Started once on first class-load; reused for the lifetime
 * of the JVM.
 */
final class SharedPostgresContainer {

    static final PostgreSQLContainer<?> INSTANCE;

    static {
        INSTANCE = new PostgreSQLContainer<>("postgres:16-alpine");
        INSTANCE.start();
    }

    /** Comma-separated list of every application table — Flyway-managed. */
    static final String TABLES_TO_TRUNCATE = String.join(", ",
            "betting_odds",
            "conference_memberships",
            "conference_name_history",
            "conferences",
            "games",
            "non_d1_game_observations",
            "power_model_param_snapshots",
            "prediction_evaluations",
            "quotes",
            "scrape_batches",
            "season_population_stats",
            "season_statistics",
            "seasons",
            "stat_calc_watermarks",
            "team_game_stats",
            "team_power_rating_snapshots",
            "team_season_stat_snapshots",
            "team_stat_snapshots",
            "teams",
            "persistent_logins",
            "user_audit_events",
            "user_preferences",
            "user_tokens",
            "users");

    private SharedPostgresContainer() {}
}
