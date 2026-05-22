package com.yotto.basketball;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for repository-only tests.
 *
 * <p>Uses {@code @DataJpaTest} — a much lighter Spring slice than {@code @SpringBootTest}.
 * Only JPA, the EntityManager, and Spring Data repositories are loaded. No web,
 * security, or MVC stack.
 *
 * <p>{@code Replace.NONE} disables Spring Boot's embedded-database substitution so
 * the test runs against the shared Testcontainers PostgreSQL — important because the
 * application uses Postgres-specific SQL in some queries.
 *
 * <p>The same database-wipe hook as {@link BaseIntegrationTest} runs before each
 * test, so the two bases play well together when their tests share the singleton
 * Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class BaseDataJpaTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgresContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedPostgresContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", SharedPostgresContainer.INSTANCE::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void wipeDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE " + SharedPostgresContainer.TABLES_TO_TRUNCATE
                + " RESTART IDENTITY CASCADE");
    }
}
