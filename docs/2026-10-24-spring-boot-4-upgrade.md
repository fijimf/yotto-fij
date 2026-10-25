# Spring Boot 3.5.16 → 4.1.2 upgrade and dependency CVE sweep

**Date:** 2026-10-24
**Baseline:** 1010 tests green on 3.5.16 before the change; the same 1010 green on 4.1.2 after.

## Why

An OSV scan of the runtime dependency tree on Boot 3.5.16 flagged 8 artifacts
with published advisories (see table below). Most were fixed only in the
versions Boot 4 manages (Jackson 3, Tomcat 11, Spring Framework 7), and the 3.5
line leaves OSS support in mid-2027, so the upgrade was taken in one step
rather than patching around it. 4.1.2 was the newest GA release on Maven
Central at the time (4.2.0 was at RC1).

## What changed

### pom.xml
- Parent `3.5.16` → `4.1.2`.
- `spring-boot-starter-web` → `spring-boot-starter-webmvc`; added
  `spring-boot-starter-restclient` (the `RestClient.Builder` auto-config moved
  out of the web starter — `EspnApiClient` and `MlTrainingService` inject it).
- `flyway-core` → `spring-boot-starter-flyway` (Boot 4 keeps Flyway
  auto-config in its own module). `flyway-database-postgresql` stays.
- `jackson-datatype-hibernate6` (`com.fasterxml`) → `jackson-datatype-hibernate7`
  (`tools.jackson.datatype`, version managed by the Jackson 3 BOM).
- Test deps: `spring-boot-starter-test` and `spring-security-test` replaced by
  the per-technology Boot 4 test starters: `webmvc-test`, `data-jpa-test`,
  `flyway-test`, `thymeleaf-test`, `validation-test`, `jackson-test`,
  `security-test`. Each pulls in `spring-boot-starter-test` transitively.
  `@SpringBootTest` alone no longer wires `MockMvc`; every test here already
  used `@AutoConfigureMockMvc`, so nothing else moved.
- Testcontainers 2.x coordinates: `postgresql` → `testcontainers-postgresql`,
  `junit-jupiter` → `testcontainers-junit-jupiter`.
- `ognl:ognl` excluded from all three Thymeleaf dependencies (starter, extras
  springsecurity6, layout dialect). Thymeleaf's Spring integration evaluates
  expressions with SpEL; OGNL is only used by the plain `StandardDialect`,
  which nothing here instantiates. Excluding it in one place is not enough —
  Maven resolves `org.thymeleaf:thymeleaf` through whichever path it sees
  first, so the exclusion has to sit on every dependency that leads to it.
- Mockito now loads as a `-javaagent` (surefire argLine +
  `maven-dependency-plugin:properties`) instead of self-attaching, which newer
  JDKs refuse.
- Pinned third-party bumps: jsoup 1.17.2 → 1.23.2, commonmark 0.22.0 → 0.31.0,
  onnxruntime 1.17.3 → 1.31.0, jacoco 0.8.12 → 0.8.15.

### Source (85 files, almost all mechanical, via OpenRewrite `UpgradeSpringBoot_4_0`)
- `@MockBean` → `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`),
  15 test classes.
- `@AutoConfigureMockMvc`, `@WebMvcTest`, `@DataJpaTest`, `TestEntityManager`,
  `@AutoConfigureTestDatabase` moved to `org.springframework.boot.<tech>.test.autoconfigure`.
- Jackson: `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`
  (annotations stay under `com.fasterxml.jackson.annotation`);
  `JsonProcessingException` → `tools.jackson.core.JacksonException` (unchecked);
  `JsonNode.asText(..)` → `asString(..)`; `new ObjectMapper()` → `new JsonMapper()`
  in tests. `asString()` matches `asText()` for text/number/boolean/missing
  nodes; it returns `""` for JSON null (2.x returned `"null"`) and throws for
  object/array nodes instead of returning `""`. The same applies to the
  no-argument `asInt()`, `asDouble()`, `asBoolean()`, `doubleValue()` and
  `textValue()`. **Scraper rule:** every `JsonNode` read of ESPN data uses the
  defaulted form (`asString("")`, `asString(null)`, `asInt(0)`, `asDouble(0.0)`)
  or is guarded by `isNumber()`/`isInt()`, so a field that unexpectedly arrives
  as an object or array costs one event, not the whole date. Follow-up commit
  after the upgrade converted the 18 remaining bare `asString()` calls in the
  scrapers and added `GameScraperTest.scrapeGame_malformedEventIsSkipped_restOfDateStillSaved`.
- `JacksonConfig` registers `Hibernate7Module` (lazy-proxy aware serialization
  for the REST API, same role as before).
- `SecurityConfig`: `SecurityProperties` → `SecurityFilterProperties`
  (`org.springframework.boot.security.autoconfigure`); `CookieSameSiteSupplier`
  → `org.springframework.boot.web.server.servlet`.
- `@Query(nativeQuery = true, …)` → `@NativeQuery(…)` in 8 repositories.
- Removed the explicit `hibernate.dialect` property (Hibernate 7 warns that
  PostgreSQLDialect is auto-detected).

### Behaviour changes worth knowing
- **Spring Security 7 sends relative login redirects.** An unauthenticated
  browser request to `/admin` now gets `Location: /login` instead of
  `http://localhost/login`. Four tests asserted the absolute form and were
  updated; nginx and browsers are indifferent.
- Jackson 3 defaults: `FAIL_ON_UNKNOWN_PROPERTIES` is off; dates serialize as
  ISO strings (unchanged for us — Boot 3 already did that). The
  `spring-boot-properties-migrator` was run once against the test context and
  reported no deprecated or renamed properties.
- Java 17 remains the baseline for Boot 4.1 (only jOOQ needs 21). The
  Dockerfile stays on Temurin 17.

## Version bumps

| Component | Before | After |
|---|---|---|
| Spring Framework | 6.2.19 | 7.0.10 |
| Spring Security | 6.5.11 | 7.1.2 |
| Spring Data | 2025.0.x | 2026.0.2 |
| Hibernate ORM | 6.6.x | 7.4.13.Final |
| Jackson | 2.21.4 (`com.fasterxml`) | 3.1.7 (`tools.jackson`) |
| Tomcat (embedded) | 10.1.55 | 11.0.27 |
| Flyway | 11.7.2 | 12.4.0 |
| HikariCP | 6.x | 7.0.2 |
| Testcontainers | 1.21.x | 2.0.5 |
| PostgreSQL JDBC | 42.7.11 | 42.7.14 |
| log4j-api (transitive) | 2.24.3 | 2.25.5 |
| jsoup | 1.17.2 | 1.23.2 |
| onnxruntime | 1.17.3 | 1.31.0 |

## Vulnerabilities (OSV, runtime scope)

Before: 8 affected artifacts. After: 0.

| Artifact (before) | Advisories | Resolution |
|---|---|---|
| jackson-databind 2.21.4 | 9 GHSAs | Jackson 3.1.7 |
| jackson-core 2.21.4 | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 | Jackson 3.1.7 |
| tomcat-embed-core 10.1.55 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 | Tomcat 11.0.27 |
| spring-webmvc 6.2.19 | GHSA-j9f9-w8pj-32f8, GHSA-pc63-qcmh-9cmg | Spring Framework 7.0.10 |
| jsoup 1.17.2 | GHSA-65r4-943x-97jj, GHSA-pmhh-3w7g-xqp8 | jsoup 1.23.2 |
| postgresql 42.7.11 | GHSA-j92g-9f8w-j867 | 42.7.14 |
| log4j-api 2.24.3 | GHSA-qv9r-c865-cp47 | 2.25.5 |
| ognl 3.3.4 | GHSA-crwc-vw2r-qr6m (fixed in 3.4.13, which Thymeleaf 3.1 does not ship) | excluded — unused with SpEL |

Re-run the scan any time with the dependency list piped through the OSV batch
API (`api.osv.dev/v1/querybatch`, ecosystem `Maven`, package `group:artifact`);
it is free, needs no key, and takes a few seconds for the ~140 artifacts.
