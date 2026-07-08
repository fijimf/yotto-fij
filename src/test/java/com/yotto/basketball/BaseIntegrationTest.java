package com.yotto.basketball;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for full-stack integration tests.
 *
 * <p>Provides a shared PostgreSQL Testcontainer (see {@link SharedPostgresContainer})
 * and a {@link #wipeDatabase()} hook that truncates all application tables before
 * every test method, replacing the per-test {@code deleteAll()} chains that each
 * integration test previously carried in its own {@code @BeforeEach}.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgresContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedPostgresContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", SharedPostgresContainer.INSTANCE::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.yotto.basketball.security.RateLimitService rateLimitService;

    @BeforeEach
    void wipeDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE " + SharedPostgresContainer.TABLES_TO_TRUNCATE
                + " RESTART IDENTITY CASCADE");
        // In-memory auth rate limiter is context-scoped; without a reset,
        // login-heavy test classes would trip it for everyone after them
        rateLimitService.clear();
    }
}
