# Yotto Fij - College Basketball Statistics and Analysis

## Project Overview

Spring Boot 3.2.2 supporting both a REST API as well as a rich web UI powered by Thymeleaf and HTMX for college basketball statistics. Java 17, PostgreSQL 16, Flyway migrations.
Deployed on a server running Ubuntu 24.04.4 LTS.

**Artifact:** `deepfij` (0.0.1-SNAPSHOT)
**Base package:** `com.yotto.basketball`

## Build & Run

```bash
# Build
./mvnw clean package

# Run locally (start Postgres first)
./scripts/start-postgres.sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Run tests (uses Testcontainers, needs Docker running)
./mvnw test

# Deploy to server
export DEPLOY_HOST=your-server
./scripts/deploy.sh
```

## Architecture

Standard layered Spring Boot: Controller -> Service -> Repository -> Entity

## UI
- Thymeleaf templates in `src/main/resources/templates`
- HTMX for AJAX requests
- CSS in `src/main/resources/static/css`
- JS in `src/main/resources/static/js`
See [UI.md](UI.md) for more details and guidelines. 

### Entities (7)
- **Conference** - name (unique), abbreviation, division
- **Season** - year (unique), startDate, endDate
- **Team** - name, nickname, mascot, city, state
- **ConferenceMembership** - links Team+Conference+Season (unique per team+season)
- **Tournament** - name, type (enum: NCAA_TOURNAMENT, NIT, CONFERENCE_TOURNAMENT, PRESEASON, INVITATIONAL)
- **Game** - homeTeam, awayTeam, gameDate, scores, status (enum: SCHEDULED, IN_PROGRESS, FINAL, POSTPONED, CANCELLED)
- **BettingOdds** - spread, overUnder, moneylines (OneToOne with Game)

### API Endpoints
All REST controllers are at `/api/{resource}` with standard CRUD. Notable custom endpoints:
- `GET /api/teams/search?name=` - case-insensitive search
- `GET /api/conference-memberships/team/{id}/current` - latest membership
- `GET /api/games/date-range?start=&end=` - date range query
- `PUT /api/games/{id}/score?homeScore=&awayScore=` - update score (sets FINAL)

### Error Handling
`GlobalExceptionHandler` returns consistent JSON with timestamp, status, error, message:
- EntityNotFoundException -> 404
- IllegalArgumentException -> 400
- Validation errors -> 400 with field-level details
- Generic exceptions -> 500

## Database

- PostgreSQL 16, managed by Flyway
- Schema: `src/main/resources/db/migration/V1__initial_schema.sql`
- DDL mode: `validate` (Flyway owns the schema, Hibernate only validates)
- Credentials are in `.env` (gitignored), never hardcoded

## Configuration

- **Secrets**: All in `.env` file (see `.env.example`). Properties files use `${ENV_VAR:default}` syntax.
- **Profiles**: `dev` (debug logging), `test` (Testcontainers)
- **Server port**: 8080

## Deployment

Docker Compose with 3 services:
- **db**: PostgreSQL 16-alpine
- **app**: Spring Boot (built with `--platform linux/amd64` for x86 server)
- **nginx**: Reverse proxy on ports 80/443 with Let's Encrypt SSL

Files to copy to server: `.env`, `config/mysite`, `docker-compose.yml`. The `deploy.sh` script handles the full build-transfer-restart cycle.

## Testing

- Integration tests extend `BaseIntegrationTest` which provides a shared Testcontainers PostgreSQL instance
- Flyway runs automatically in tests
- Requires Docker to be running

## Key Conventions

- Entities use `@NotNull`/`@NotBlank` for validation; service layer enforces business rules (e.g., unique conference names)
- Lazy loading on all `@ManyToOne` and `@OneToOne` relationships
- Services throw `EntityNotFoundException` (-> 404) or `IllegalArgumentException` (-> 400)
- `config/` and `.env` are gitignored — contain server-specific config and secrets
