---
name: server-ops
description: Deployment (Docker Compose service topology, deploy.sh, files to copy to the server) and Netdata monitoring runbook (collector configs, alarm/ntfy setup, port and log invariants) for the yotto-fij production server. Use when deploying, changing docker-compose/nginx/netdata config, or debugging monitoring.
---

# Deployment

Docker Compose services:
- **db**: PostgreSQL 16-alpine
- **app**: Spring Boot (built with `--platform linux/amd64` for x86 server)
- **trainer**: always-on ML trainer service (FastAPI, `scripts/trainer_service.py`) on the internal network only — the app POSTs `http://trainer:8000/train`; training runs `train_models.py` as a subprocess, writing ONNX models to the shared `model_data` volume
- **nginx**: Reverse proxy on ports 80/443 with Let's Encrypt SSL
- **goaccess**: real-time analytics from the nginx access log (port 8888, TLS + basic auth)
- **netdata**: monitoring (see below)
- **logrotate**: alpine sidecar running `scripts/rotate-logs.sh` — copy-truncate rotation of `goaccess.log`/`access_timed.log` at 50MB, 3 gzipped generations (no signals needed; nginx/goaccess/netdata tolerate truncation)

Files to copy to server: `.env`, `config/mysite`, `docker-compose.yml`, `netdata/`. The `deploy.sh` script handles the full build-transfer-restart cycle.

# Monitoring (Netdata)

Spec: `docs/monitoring-spec-netdata.md`. Netdata runs as a compose service; configs live in `netdata/` (go.d collector configs, `health.d/` alarms, ntfy notification override). Key invariants:
- Actuator/Micrometer metrics are on management port **8081** — internal-only, scraped by netdata's prometheus collector; never publish or proxy it. Public `/actuator/` is 404'd in nginx.
- The `netdata` Postgres role (pg_monitor, no table access) is created by Flyway **V21**; its password comes from `NETDATA_DB_PASSWORD` via a Flyway placeholder. Changing the password later requires a manual `ALTER ROLE`.
- `netdata/go.d/postgres.conf` is **rendered from postgres.conf.template by deploy.sh** on the server (embeds the DB password; gitignored).
- nginx writes a second timed access log (`access.log` with `rt=`/`urt=` fields) for netdata's web_log collector; `goaccess.log` must stay strict COMBINED for goaccess.
- Dashboard: `127.0.0.1:19999` on the server (ssh tunnel), or remotely via nginx on port **9999** (TLS + basic auth via existing htpasswd; port must be open in the Hetzner firewall).
- Alerts push to ntfy.sh (`NETDATA_NTFY_TOPIC_URL` in `.env`); subscribe to the topic in the ntfy phone app.
