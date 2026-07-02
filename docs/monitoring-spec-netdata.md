# Spec: Add Netdata monitoring to the site

## Goal

Add a single, low-maintenance, web-based monitoring view for this project covering:
- **Java/Spring app**: JVM heap/GC, threads, HTTP request rate + latency (p50/p95/p99) + error rate per endpoint, DB connection pool.
- **Postgres**: connections, transaction/commit rate, cache hit ratio, locks, replication (if any), DB size, slow activity.
- **nginx / overall site**: request rate, response-code mix (2xx/3xx/4xx/5xx), request latency, upstream latency.
- **Host + containers**: CPU, memory, disk space/IO, network, per-container resource usage.

The maintainer wants to glance at a dashboard once or twice a day and confirm everything is healthy, plus get pushed an alert when it is not. **Optimize for minimal setup and near-zero ongoing maintenance.** This is a solo side-project.

## Chosen approach

Use **Netdata** as a single additional container in the existing `docker-compose` stack. Netdata auto-discovers containers, ships with pre-built dashboards and sane default health alarms, and requires no separate time-series database to run or maintain. App-level metrics come from Spring Boot Actuator + Micrometer (Prometheus format), which Netdata scrapes.

Do **not** introduce Prometheus, Grafana, ClickHouse, or any additional storage component. Do **not** expose any new monitoring or metrics endpoint to the public internet.

## Existing architecture (context)

Docker Compose stack:
- `postgres` — PostgreSQL database.
- `app` — Spring Boot (Java) application. *(Adjust service name / port to match the real compose file.)*
- `nginx` — reverse proxy / TLS termination, public entry point.
- `logwatch` — existing Go service tailing the nginx access log. *(Leave it running; Netdata's web_log collector is complementary, not a replacement. Do not remove it.)*

All services share a docker-compose network. Assume the network lets containers reach each other by service name.

---

## Tasks

### 1. Instrument the Spring Boot app (Actuator + Micrometer → Prometheus)

1. Add dependencies:
   - `org.springframework.boot:spring-boot-starter-actuator`
   - `io.micrometer:micrometer-registry-prometheus`
2. In `application.properties` / `application.yml`:
   ```properties
   management.endpoints.web.exposure.include=health,info,prometheus
   management.endpoint.health.show-details=when-authorized
   management.metrics.tags.application=${spring.application.name:app}
   management.prometheus.metrics.export.enabled=true
   # HTTP server request timing with percentile histograms (enables p95/p99):
   management.metrics.distribution.percentiles-histogram.http.server.requests=true
   management.metrics.distribution.slo.http.server.requests=50ms,100ms,200ms,500ms,1s,2s
   ```
3. The metrics endpoint must be reachable **only inside the docker network** at `http://app:<port>/actuator/prometheus` — verify nginx does **not** proxy `/actuator/**` to the public internet. If Actuator runs on the same port as the app, add an nginx rule that blocks external access to `/actuator/`. Prefer moving Actuator to a separate management port (`management.server.port=8081`) that is never published or proxied.
4. Confirm HikariCP (or the active pool) metrics are exported (`hikaricp.connections.*`). Enable pool metrics if not automatic.

**Acceptance:** `curl http://app:<port>/actuator/prometheus` from inside the network returns Prometheus text including `jvm_memory_used_bytes`, `http_server_requests_seconds_*`, and `hikaricp_connections*`. The endpoint returns 403/404 from the public internet.

### 2. Enable an nginx status/log surface for Netdata

1. Add an internal-only `stub_status` endpoint in the nginx config:
   ```nginx
   server {
       listen 8080;                 # internal port, not published publicly
       location /nginx_status {
           stub_status;
           allow 127.0.0.1;
           allow 172.16.0.0/12;     # docker network range; tighten to actual subnet
           deny all;
       }
   }
   ```
2. Ensure the nginx **access log format includes timing**, so per-request latency and status codes can be parsed:
   ```nginx
   log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                   '$status $body_bytes_sent "$http_referer" '
                   '"$http_user_agent" rt=$request_time urt=$upstream_response_time';
   access_log /var/log/nginx/access.log main;
   ```
3. Mount the nginx access log so Netdata can read it (see compose task). Do not change anything the existing Go `logwatch` service depends on beyond additive log fields.

**Acceptance:** `curl http://nginx:8080/nginx_status` from inside the network returns stub_status output; access log lines contain `rt=` / `urt=` values.

### 3. Create a Postgres monitoring role

Netdata's postgres collector needs a low-privilege user.

```sql
CREATE USER netdata WITH PASSWORD 'CHANGE_ME_STRONG';
GRANT pg_monitor TO netdata;
-- pg_monitor covers pg_stat_* views without granting data access.
```

Store the password via the existing secrets mechanism (`.env` / docker secrets), not hardcoded.

**Acceptance:** `netdata` user can `SELECT` from `pg_stat_database` but cannot read application tables.

### 4. Add the Netdata service to docker-compose

Add to `docker-compose.yml`. Attach it to the **existing app network** so it can scrape `app`, `postgres`, and `nginx` by service name.

```yaml
  netdata:
    image: netdata/netdata:stable
    container_name: netdata
    hostname: <site-name>            # shown as the node name in the UI
    restart: unless-stopped
    pid: host
    cap_add:
      - SYS_PTRACE
      - SYS_ADMIN
    security_opt:
      - apparmor:unconfined
    ports:
      - "127.0.0.1:19999:19999"      # LOCAL ONLY. Do not bind 0.0.0.0.
    volumes:
      - netdataconfig:/etc/netdata
      - netdatalib:/var/lib/netdata
      - netdatacache:/var/cache/netdata
      - ./netdata/go.d:/etc/netdata/go.d:ro        # our collector configs (task 5)
      - /etc/passwd:/host/etc/passwd:ro
      - /etc/group:/host/etc/group:ro
      - /etc/os-release:/host/etc/os-release:ro
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro   # container discovery + names
      - <nginx-access-log-volume-or-path>:/var/log/nginx:ro
    networks:
      - <app-network>

volumes:
  netdataconfig:
  netdatalib:
  netdatacache:
```

Notes:
- `127.0.0.1:19999` keeps the dashboard local-only. Remote access is handled in Task 7 — do **not** publish 19999 to the internet.
- The `docker.sock` mount is read-only and used only for container/cgroup naming.
- Adjust `<app-network>`, `<site-name>`, and the nginx log mount to the real values.

**Acceptance:** `docker compose up -d netdata` starts cleanly; `http://localhost:19999` loads and shows host + per-container charts.

### 5. Configure Netdata collectors

Create `./netdata/go.d/` config files (mounted in Task 4).

`./netdata/go.d/prometheus.conf` — scrape the Spring app:
```yaml
jobs:
  - name: spring_app
    url: http://app:8081/actuator/prometheus   # match management port from Task 1
```

`./netdata/go.d/postgres.conf`:
```yaml
jobs:
  - name: local
    dsn: 'postgres://netdata:CHANGE_ME_STRONG@postgres:5432/<dbname>?sslmode=disable'
```

`./netdata/go.d/nginx.conf`:
```yaml
jobs:
  - name: local
    url: http://nginx:8080/nginx_status
```

`./netdata/go.d/web_log.conf` — parse nginx access log for site latency + status codes:
```yaml
jobs:
  - name: nginx
    path: /var/log/nginx/access.log
    log_type: auto
```
If `log_type: auto` does not pick up the custom `rt=`/`urt=` fields, define an explicit CSV/regex pattern matching the `log_format` from Task 2.

**Acceptance:** In the Netdata UI, dedicated sections appear for the Spring app (JVM, HTTP requests), Postgres, and nginx; the web_log section shows response codes and request time.

### 6. Alerts / notifications

1. Keep Netdata's built-in health alarms (CPU, RAM, disk space/inodes, disk IO, network errors, container OOM) — they cover host/infra with no config.
2. Add a notification channel so alerts are pushed (pick one, simplest wins): **Discord/Slack webhook**, **ntfy**, or **email**. Configure in `health_alarm_notify.conf` (via `netdata edit-config health_alarm_notify.conf` or a mounted override). Set `DEFAULT_RECIPIENT_*` accordingly.
3. Add a small set of app-specific alarms in `./netdata/health.d/` (mount `./netdata/health.d:/etc/netdata/health.d:ro`):
   - **HTTP 5xx rate** from the web_log collector exceeds a threshold over 5 min (warning/critical).
   - **JVM heap usage** > 90% of max sustained for several minutes.
   - **Postgres connections** approaching `max_connections` (e.g., > 80% / > 90%).
   - **Disk space** on the volume holding Postgres data < 15% / < 5% free.

Use Netdata's health config format (`alarm`, `on`, `lookup`, `warn`, `crit`, `to`). Reference the exact chart/dimension names shown in the UI after Task 5.

**Acceptance:** A deliberately triggered condition (e.g., temporarily lower a threshold) sends a notification to the chosen channel.

### 7. Remote access (optional but recommended for "glance from phone")

Two options — implement **one**:
- **Netdata Cloud (recommended, free):** claim the node with a claim token so the dashboard is viewable remotely (incl. mobile) without exposing port 19999. Add `NETDATA_CLAIM_TOKEN` / `NETDATA_CLAIM_ROOMS` / `NETDATA_CLAIM_URL` env vars to the netdata service. No inbound port opened.
- **Reverse-proxy through nginx with auth:** add an nginx `location` for the dashboard behind HTTP basic auth + TLS, proxying to `netdata:19999`. Only if Netdata Cloud is undesirable.

Do not expose 19999 directly.

**Acceptance:** Dashboard reachable remotely via the chosen method; port 19999 is not open to the public internet.

---

## Global constraints / definition of done

- Everything runs from the existing `docker compose up -d`; no manual per-boot steps.
- No new component listens on a public port except via the existing nginx entry point with auth+TLS.
- Actuator, stub_status, and 19999 are not publicly reachable.
- Secrets (`netdata` DB password, claim token, webhook URL) come from `.env`/secrets, not committed.
- The existing Go `logwatch` service is untouched functionally.
- Resource budget: the netdata container should sit comfortably in a few hundred MB RAM; note actual usage after deploy.

## Verification checklist (run after implementation)

1. `docker compose config` validates; `docker compose up -d` brings up all services incl. `netdata`.
2. From inside the network: Actuator, `/nginx_status`, and the Postgres `netdata` role all respond as specified.
3. Netdata UI at `http://localhost:19999` shows populated sections for: host/containers, Spring app (JVM + HTTP latency/errors), Postgres, nginx, and web_log status codes.
4. Public probes confirm Actuator / stub_status / 19999 are **not** externally reachable.
5. A forced alert reaches the notification channel.
6. Record steady-state CPU/RAM of the netdata container.

## Out of scope

- Distributed tracing, log aggregation/search, and long-term (multi-month) metric retention. If needed later, ship app metrics to Grafana Cloud's free tier via Grafana Alloy — but that is a separate spec.
