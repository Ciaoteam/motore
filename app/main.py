"""API del motore turni Ciao Team (FastAPI + OR-Tools CP-SAT).

Documentazione interattiva: /docs. Autenticazione: se la variabile
d'ambiente API_KEY è impostata, ogni chiamata (salvo /health) deve avere
l'header `x-api-key: <API_KEY>` oppure `Authorization: Bearer <API_KEY>`.
"""

from __future__ import annotations

import ctypes
import gc
import os
import secrets
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Literal, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from . import solver
from .models import (
    AvailabilityCheckRequest,
    AvailabilityCheckResponse,
    GapCandidatesRequest,
    GapCandidatesResponse,
    RequirementsPreviewRequest,
    RequirementsPreviewResponse,
    RequirementsResolveRequest,
    ResolvedRequirement,
    ScheduleCheckRequest,
    ScheduleCheckResponse,
    RulesValidateRequest,
    RulesValidateResponse,
    SolveRequest,
    SolveResponse,
)

app = FastAPI(
    title="Ciao Team — motore turni",
    version="2.0.0",
    description="Pianificazione turni con OR-Tools CP-SAT. Fabbisogno, disponibilità, turni esistenti e regole "
    "(codice Python in sandbox) arrivano tutti nella richiesta: il motore non cambia.",
)

# Memoria bassa: un risultato resta solo finché il chiamante non lo legge
# (al massimo 10 minuti) e dopo ogni calcolo la memoria torna al sistema.
JOB_TTL_SECONDS = 600
MAX_JOBS = 50
_executor = ThreadPoolExecutor(max_workers=int(os.environ.get("SOLVER_WORKERS", "2")))
_jobs: dict[str, dict] = {}
_jobs_lock = threading.Lock()


def require_key(x_api_key: Optional[str] = Header(default=None), authorization: Optional[str] = Header(default=None)):
    expected = os.environ.get("API_KEY", "").strip()
    if not expected:
        return
    supplied = x_api_key or (authorization[7:] if authorization and authorization.lower().startswith("bearer ") else None)
    if not supplied or not secrets.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="API key mancante o non valida")


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/solve", response_model=SolveResponse, dependencies=[Depends(require_key)])
def solve(req: SolveRequest) -> SolveResponse:
    """Genera i turni e risponde a calcolo finito (entro time_limit_seconds)."""
    try:
        return solver.solve(req)
    finally:
        _release_memory()


class JobCreated(BaseModel):
    job_id: str


class JobState(BaseModel):
    job_id: str
    status: Literal["SOLVING", "DONE", "FAILED"]
    result: Optional[SolveResponse] = None
    error: Optional[str] = None


def _gc_jobs() -> None:
    now = time.time()
    with _jobs_lock:
        for k in [k for k, v in _jobs.items() if now - v["created"] > JOB_TTL_SECONDS]:
            del _jobs[k]
        done = sorted((v["created"], k) for k, v in _jobs.items() if v["status"] != "SOLVING")
        for _, k in done[: max(0, len(_jobs) - MAX_JOBS)]:
            del _jobs[k]


try:
    _libc = ctypes.CDLL("libc.so.6")
except OSError:  # non Linux (sviluppo locale)
    _libc = None


def _release_memory() -> None:
    gc.collect()
    if _libc is not None:
        _libc.malloc_trim(0)


def _run_job(job_id: str, req: SolveRequest) -> None:
    try:
        res = solver.solve(req)
        with _jobs_lock:
            _jobs[job_id].update(status="DONE", result=res)
    except Exception as exc:  # noqa: BLE001 — l'errore torna al chiamante, non uccide il worker
        with _jobs_lock:
            _jobs[job_id].update(status="FAILED", error=f"{type(exc).__name__}: {exc}")
    finally:
        _release_memory()


@app.post("/jobs", response_model=JobCreated, status_code=202, dependencies=[Depends(require_key)])
def create_job(req: SolveRequest) -> JobCreated:
    """Come /solve, ma in background: poi GET /jobs/{job_id}."""
    _gc_jobs()
    job_id = uuid.uuid4().hex
    with _jobs_lock:
        _jobs[job_id] = {"status": "SOLVING", "created": time.time(), "result": None, "error": None}
    _executor.submit(_run_job, job_id, req)
    return JobCreated(job_id=job_id)


@app.get("/jobs/{job_id}", response_model=JobState, dependencies=[Depends(require_key)])
def get_job(job_id: str) -> JobState:
    with _jobs_lock:
        job = _jobs.get(job_id)
        if job is not None and job["status"] != "SOLVING":
            del _jobs[job_id]  # consegnato: non serve tenerlo
    if job is None:
        raise HTTPException(status_code=404, detail="job non trovato (già letto, scaduto dopo 10 minuti o mai creato)")
    return JobState(job_id=job_id, status=job["status"], result=job["result"], error=job["error"])


@app.post("/availability/check", response_model=AvailabilityCheckResponse, dependencies=[Depends(require_key)])
def availability_check(req: AvailabilityCheckRequest) -> AvailabilityCheckResponse:
    """Disponibilità di una persona contro le sue regole personali e il fabbisogno."""
    return solver.check_availability(req)


@app.post("/gaps/candidates", response_model=GapCandidatesResponse, dependencies=[Depends(require_key)])
def gaps_candidates(req: GapCandidatesRequest) -> GapCandidatesResponse:
    """Buchi del calendario (e posti a rischio) con chi potrebbe coprirli."""
    return solver.gap_candidates(req)


@app.post("/requirements/resolve", response_model=list[ResolvedRequirement], dependencies=[Depends(require_key)])
def requirements_resolve(req: RequirementsResolveRequest) -> list[ResolvedRequirement]:
    """Fabbisogno effettivo giorno per giorno (regole con date + eccezioni)."""
    return solver.resolve(req)


@app.post("/rules/validate", response_model=RulesValidateResponse, dependencies=[Depends(require_key)])
def rules_validate(req: RulesValidateRequest) -> RulesValidateResponse:
    """Prova le regole Python senza generare: per correggerle prima di salvarle."""
    return solver.validate_rules(req)


@app.post("/requirements/preview", response_model=RequirementsPreviewResponse, dependencies=[Depends(require_key)])
def requirements_preview(req: RequirementsPreviewRequest) -> RequirementsPreviewResponse:
    """Fabbisogno effettivo con le regole di fabbisogno in Python e gli eventi, più gli errori delle regole."""
    return solver.preview_requirements(req)


@app.post("/schedule/check", response_model=ScheduleCheckResponse, dependencies=[Depends(require_key)])
def schedule_check(req: ScheduleCheckRequest) -> ScheduleCheckResponse:
    """Il calendario così com'è contro regole e fabbisogno: violazioni e posti scoperti, nessuna assegnazione."""
    try:
        return solver.check_schedule(req)
    finally:
        _release_memory()
