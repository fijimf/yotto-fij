#!/usr/bin/env python3
"""
Long-running trainer service: lets the Spring app trigger ML model training over HTTP
instead of requiring a shell on the server.

Endpoints (internal Docker network only — never exposed through nginx):
    GET  /health            → {"status": "ok"}
    POST /train             → starts a training run; body (all optional):
                              {"train_seasons": [2024, 2025], "test_season": 2025}
                              Seasons are auto-discovered from the DB when omitted
                              (same query as retrain.sh). 409 if a run is in progress.
    GET  /status/{run_id}   → run state: RUNNING / COMPLETED / FAILED, log tail, and
                              (when completed) the metrics from features.json.

Training itself runs train_models.py as a subprocess, so a training crash can never
take down the service. Only one run at a time; state is in-memory (the Spring side
persists run history in ml_training_runs).
"""

import json
import os
import shlex
import subprocess
import sys
import threading
import uuid
from collections import deque
from typing import Optional
from datetime import datetime, timezone

import psycopg2
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="yotto-fij trainer", docs_url=None, redoc_url=None)

MODELS_DIR = os.environ.get("ML_MODEL_DIR", "/models")
LOG_TAIL_LINES = 80

# Override for smoke tests: a shell-style command string run instead of the real
# trainer, e.g. TRAINER_CMD="python fake_train.py". Season args are appended.
TRAINER_CMD = os.environ.get("TRAINER_CMD", f"{sys.executable} train_models.py")

_lock = threading.Lock()
_runs: dict = {}
_current_run_id: Optional[str] = None


class TrainRequest(BaseModel):
    train_seasons: Optional[list] = None
    test_season: Optional[int] = None


def _db_url() -> str:
    host = os.environ.get("DB_HOST", "localhost")
    port = os.environ.get("DB_PORT", "5432")
    dbname = os.environ.get("POSTGRES_DB", "basketball_db")
    user = os.environ.get("POSTGRES_USER", "postgres")
    password = os.environ.get("POSTGRES_PASSWORD", "")
    return f"postgresql://{user}:{password}@{host}:{port}/{dbname}"


def discover_seasons() -> list:
    """Season years that have power-rating snapshots (same gate as retrain.sh)."""
    with psycopg2.connect(_db_url()) as conn, conn.cursor() as cur:
        cur.execute("""
            SELECT DISTINCT s.year
            FROM seasons s
            JOIN team_power_rating_snapshots r ON r.season_id = s.id
            ORDER BY s.year
        """)
        return [row[0] for row in cur.fetchall()]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _read_metrics() -> Optional[dict]:
    try:
        with open(os.path.join(MODELS_DIR, "features.json")) as f:
            meta = json.load(f)
        metrics = meta.get("metrics")
        if isinstance(metrics, dict):
            metrics = dict(metrics)
            metrics["version"] = meta.get("version")
        return metrics
    except (OSError, ValueError):
        return None


def _run_training(run_id: str, train_seasons: list, test_season: int) -> None:
    global _current_run_id
    run = _runs[run_id]
    cmd = shlex.split(TRAINER_CMD) + [
        "--train-seasons", ",".join(str(y) for y in train_seasons),
        "--test-season", str(test_season),
    ]
    log = deque(maxlen=LOG_TAIL_LINES)
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, cwd=os.path.dirname(os.path.abspath(__file__)))
        for line in proc.stdout:
            line = line.rstrip("\n")
            print(f"[{run_id[:8]}] {line}", flush=True)
            log.append(line)
            run["log_tail"] = "\n".join(log)   # live tail for in-progress polling
        exit_code = proc.wait()
        run["log_tail"] = "\n".join(log)
        run["exit_code"] = exit_code
        run["finished_at"] = _now()
        if exit_code == 0:
            run["status"] = "COMPLETED"
            run["metrics"] = _read_metrics()
        else:
            run["status"] = "FAILED"
            run["error"] = f"training exited with code {exit_code}"
    except Exception as e:  # subprocess spawn failure etc.
        run["log_tail"] = "\n".join(log)
        run["finished_at"] = _now()
        run["status"] = "FAILED"
        run["error"] = str(e)
    finally:
        with _lock:
            _current_run_id = None


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/train")
def train(req: Optional[TrainRequest] = None):
    global _current_run_id
    req = req or TrainRequest()

    # Reject before season discovery so a busy trainer answers 409, not a DB error
    with _lock:
        if _current_run_id is not None:
            raise HTTPException(status_code=409,
                                detail=f"training run {_current_run_id} already in progress")

    train_seasons = req.train_seasons
    test_season = req.test_season
    if not train_seasons:
        try:
            train_seasons = discover_seasons()
        except Exception as e:
            raise HTTPException(status_code=503, detail=f"season discovery failed: {e}")
        if not train_seasons:
            raise HTTPException(status_code=409,
                                detail="no seasons with power-rating snapshots — run Power Ratings first")
    if test_season is None:
        test_season = max(train_seasons)

    with _lock:
        if _current_run_id is not None:
            raise HTTPException(status_code=409,
                                detail=f"training run {_current_run_id} already in progress")
        run_id = uuid.uuid4().hex
        _current_run_id = run_id
        _runs[run_id] = {
            "run_id": run_id,
            "status": "RUNNING",
            "train_seasons": train_seasons,
            "test_season": test_season,
            "started_at": _now(),
            "finished_at": None,
            "exit_code": None,
            "log_tail": "",
            "metrics": None,
            "error": None,
        }
    threading.Thread(target=_run_training, args=(run_id, train_seasons, test_season),
                     daemon=True).start()
    return {"run_id": run_id, "train_seasons": train_seasons, "test_season": test_season}


@app.get("/status/{run_id}")
def status(run_id: str):
    run = _runs.get(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="unknown run id")
    return run
