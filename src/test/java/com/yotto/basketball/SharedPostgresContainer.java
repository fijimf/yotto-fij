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
        // Spring's test-context cache keeps up to 32 contexts alive, each holding a
        // ~10-connection Hikari pool; postgres's default max_connections=100 overflows
        // once the suite accumulates enough distinct contexts ("sorry, too many clients").
        INSTANCE = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "max_connections=500");
        INSTANCE.start();
    }

    /** Comma-separated list of every application table — Flyway-managed. */
    static final String TABLES_TO_TRUNCATE = String.join(", ",
            "betting_odds",
            "conference_memberships",
            "conference_name_history",
            "daily_digest_runs",
            "conferences",
            "games",
            "ml_models",
            "ml_training_runs",
            "news_article_conferences",
            "news_article_teams",
            "news_articles",
            "news_aliases",
            "news_sources",
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
