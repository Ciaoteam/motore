"""Motore turni con OR-Tools CP-SAT. FISSO: tutto ciò che cambia arriva dall'API.

Ingresso: persone e disponibilità, fabbisogno (regole con date di validità +
eccezioni per data), turni esistenti (confermati, congelati, proposti) e le
REGOLE come codice Python (vibecoding del titolare e dei dipendenti), eseguite
in sandbox con la libreria di _RuleApi (documentata nel README).

Sempre duri (fatti, non preferenze):
- ruolo del posto fra i ruoli della persona; competenza "required" posseduta;
- posto interamente dentro la disponibilità dichiarata e fuori dalle
  indisponibilità;
- niente sovrapposizioni (salvo override), riposo minimo fra una giornata e
  la successiva, massimo turni al giorno (2 = spezzato pranzo + cena);
- turni esistenti restano alla loro persona; i tappabuchi (auto_assign=false)
  non vengono mai assegnati dal motore.

Obiettivo: massima copertura (coverage_weight × priority per posto) meno le
penalità delle regole soft, dello spezzato e dello squilibrio fra persone.
Se le regole hard non stanno insieme si risolve di nuovo con le hard come
preferenze fortissime: relaxed_hard=true e violations dice quali sono saltate.
"""

from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Optional

from ortools.sat.python import cp_model

from . import rules as sandbox
from .models import (
    Assignment,
    AvailabilityCheckRequest,
    AvailabilityCheckResponse,
    CodeRule,
    Employee,
    EmployeeSummary,
    Gap,
    GapCandidate,
    GapCandidatesRequest,
    GapCandidatesResponse,
    RequirementsResolveRequest,
    ResolvedRequirement,
    RuleError,
    RulesValidateRequest,
    RulesValidateResponse,
    SlotRef,
    SolveRequest,
    SolveResponse,
    TimeWindow,
    Violation,
)

RELAXED_HARD_WEIGHT = 100_000
EMPLOYEE_MAX_WEIGHT = 50
MAX_INT = 10_000_000
DAY = 24 * 60
AT_RISK = ("frozen", "proposed")


def _norm(s: str) -> str:
    return s.strip().casefold()


def _hm(v: str) -> int:
    h, m = v.split(":")
    return int(h) * 60 + int(m)


def _overlaps(a: tuple[int, int], b: tuple[int, int]) -> bool:
    return min(a[1], b[1]) > max(a[0], b[0])


def _merge(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
    out: list[list[int]] = []
    for a, b in sorted(intervals):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


# ── Dati preparati ──────────────────────────────────────────────────────────


@dataclass
class _Shift:
    id: str
    date: date
    start: int  # minuti assoluti dalla data base
    end: int
    start_label: str
    end_label: str
    role_label: str
    role: str
    skill_label: Optional[str]
    required_skill: Optional[str]
    skill_strength: str
    pinned: Optional[str]
    fixed: bool
    priority: int
    status: str
    ref: Optional[str]
    category: str

    @property
    def minutes(self) -> int:
        return self.end - self.start


@dataclass
class _Ctx:
    base: date
    plan_dates: list[date]
    shifts: list[_Shift]
    employees: list[Employee]
    emp_by_id: dict[str, Employee]
    eligible: dict[tuple[int, str], bool] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)

    @property
    def emp_ids(self) -> list[str]:
        return [e.id for e in self.employees]

    def window(self, d: date, start: Optional[str], end: Optional[str]) -> tuple[int, int]:
        s0 = (d - self.base).days * DAY
        if start is None:
            return s0, s0 + DAY
        a, b = s0 + _hm(start), s0 + _hm(end)
        return (a, b + DAY) if b <= a else (a, b)

    def tw(self, w: TimeWindow) -> tuple[int, int]:
        return self.window(w.date, w.start, w.end)


def _slot_key(d: date, start: str, end: str, role: str) -> tuple:
    return (d, start, end, _norm(role))


def resolve_requirements(week_start: Optional[date], horizon_days: int, requirement_rules, requirements) -> list[dict]:
    """Fabbisogno effettivo per data, fascia e ruolo.

    Ordine: regole "set" attive quel giorno (vince quella con valid_from più
    recente), poi regole "add" attive, poi eccezioni della singola data ("set"
    sostituisce, "add" somma). Mai sotto zero.
    """
    slots: dict[tuple, dict] = {}

    def apply(d: date, n) -> None:
        key = _slot_key(d, n.start, n.end, n.role)
        cur = slots.get(key)
        if n.mode == "set" or cur is None:
            prev = 0 if cur is None else cur["headcount"]
            slots[key] = dict(
                date=d, start=n.start, end=n.end, role=n.role,
                headcount=n.headcount if n.mode == "set" else prev + n.headcount,
                required_skill=n.required_skill, skill_headcount=n.skill_headcount,
                skill_strength=n.skill_strength, priority=n.priority,
            )
        else:
            cur["headcount"] += n.headcount

    if week_start is not None:
        for k in range(horizon_days):
            d = week_start + timedelta(days=k)
            active = [
                r for r in requirement_rules
                if (r.days_of_week is None or d.weekday() in r.days_of_week)
                and (r.valid_from is None or r.valid_from <= d)
                and (r.valid_to is None or d <= r.valid_to)
            ]
            for r in sorted((r for r in active if r.mode == "set"), key=lambda r: r.valid_from or date.min):
                apply(d, r)
            for r in active:
                if r.mode == "add":
                    apply(d, r)
    for r in requirements:
        apply(r.date, r)
    out = [{**v, "headcount": max(0, v["headcount"])} for v in slots.values()]
    return sorted(out, key=lambda v: (v["date"], v["start"], v["role"]))


def _prepare(req: SolveRequest) -> _Ctx:
    plan = set(req.plan_dates) if req.plan_dates else None
    raw: list[dict] = []
    for r in resolve_requirements(req.week_start, req.horizon_days, req.requirement_rules, req.requirements):
        if plan is not None and r["date"] not in plan:
            continue
        with_skill = min(r["skill_headcount"], r["headcount"]) if r["required_skill"] else 0
        for n in range(r["headcount"]):
            raw.append(dict(
                id=f"req:{r['date']}:{r['start']}-{r['end']}:{r['role']}:{n + 1}",
                date=r["date"], start=r["start"], end=r["end"], role=r["role"],
                skill=r["required_skill"] if n < with_skill else None, strength=r["skill_strength"],
                pinned=None, fixed=False, priority=r["priority"], status="new", ref=None,
            ))
    # Un turno esistente occupa il posto corrispondente (stessa data, orario e
    # ruolo; a parità quello con la stessa competenza): niente doppia copertura.
    # Fuori dalle date pianificate resta comunque come contesto (ore, riposi).
    for k, f in enumerate(req.fixed_assignments):
        key = _slot_key(f.date, f.start, f.end, f.role)
        free = [s for s in raw if s["pinned"] is None and _slot_key(s["date"], s["start"], s["end"], s["role"]) == key]
        same = [s for s in free if s["skill"] and f.required_skill and _norm(s["skill"]) == _norm(f.required_skill)]
        target = (same or free or [None])[0]
        if target is not None:
            target.update(pinned=f.employee_id, fixed=True, status=f.status, ref=f.ref)
        else:
            raw.append(dict(id=f"fixed:{f.ref or k + 1}", date=f.date, start=f.start, end=f.end, role=f.role,
                            skill=f.required_skill, strength="required", pinned=f.employee_id, fixed=True,
                            priority=1, status=f.status, ref=f.ref))

    known = [s["date"] for s in raw] + [w.date for e in req.employees for w in (e.availability or []) + e.unavailable]
    if req.week_start:
        known.append(req.week_start)
    base = min(known) if known else date.today()
    if req.plan_dates:
        plan_dates = sorted(req.plan_dates)
    elif req.week_start:
        plan_dates = [req.week_start + timedelta(days=k) for k in range(req.horizon_days)]
    else:
        plan_dates = sorted({s["date"] for s in raw})
    cutoff = _hm(req.settings.category_cutoff)
    ctx = _Ctx(base=base, plan_dates=plan_dates, shifts=[], employees=list(req.employees),
               emp_by_id={e.id: e for e in req.employees})
    for s in raw:
        a = (s["date"] - base).days * DAY + _hm(s["start"])
        b = (s["date"] - base).days * DAY + _hm(s["end"])
        ctx.shifts.append(_Shift(
            id=s["id"], date=s["date"], start=a, end=b + DAY if b <= a else b, start_label=s["start"],
            end_label=s["end"], role_label=s["role"], role=_norm(s["role"]), skill_label=s["skill"],
            required_skill=_norm(s["skill"]) if s["skill"] else None, skill_strength=s["strength"],
            pinned=s["pinned"], fixed=s["fixed"], priority=s["priority"], status=s["status"], ref=s["ref"],
            category="MORNING" if _hm(s["start"]) < cutoff else "EVENING",
        ))

    for e in req.employees:
        roles = {_norm(r) for r in e.roles}
        skills = {_norm(k) for k in e.skills}
        avail = None if e.availability is None else _merge([ctx.tw(w) for w in e.availability])
        unavail = [ctx.tw(w) for w in e.unavailable]
        for i, s in enumerate(ctx.shifts):
            ok = e.auto_assign and s.role in roles
            if ok and s.required_skill and s.skill_strength == "required":
                ok = s.required_skill in skills
            if ok and avail is not None:
                ok = any(a <= s.start and s.end <= b for a, b in avail)
            if ok and any(_overlaps((s.start, s.end), u) for u in unavail):
                ok = False
            ctx.eligible[(i, e.id)] = ok

    for s in ctx.shifts:
        if s.pinned and s.pinned not in ctx.emp_by_id:
            ctx.warnings.append(f"{s.id}: persona del turno esistente sconosciuta ({s.pinned}), posto lasciato scoperto")
    for i, s in enumerate(ctx.shifts):
        if not s.pinned and not any(ctx.eligible[(i, e)] for e in ctx.emp_ids):
            ctx.warnings.append(
                f"{s.date} {s.start_label}-{s.end_label} {s.role_label}: nessuna persona ammissibile "
                "(ruolo, competenza o disponibilità)"
            )
    return ctx


# ── Libreria delle regole (quello che vede il codice Python) ────────────────


class ShiftView:
    """Un posto da coprire, in sola lettura."""

    def __init__(self, s: _Shift):
        self.id, self.date, self.start, self.end = s.id, s.date, s.start_label, s.end_label
        self.role, self.skill, self.category, self.minutes = s.role_label, s.skill_label, s.category, s.minutes
        self.weekday, self.fixed, self.status, self.held_by = s.date.weekday(), s.fixed, s.status, s.pinned

    def __repr__(self) -> str:
        return f"<turno {self.date} {self.start}-{self.end} {self.role}>"


class EmployeeView:
    """Una persona, in sola lettura."""

    def __init__(self, e: Employee):
        self.id, self.name, self.roles, self.skills = e.id, e.name, list(e.roles), list(e.skills)
        self.hourly_cost_cents, self.auto_assign = e.hourly_cost_cents, e.auto_assign

    def __repr__(self) -> str:
        return f"<persona {self.name or self.id}>"


@dataclass
class _Cond:
    rule: CodeRule
    severity: str
    msg: str
    ok: Optional[object] = None  # letterale "condizione rispettata" (se reificata)
    enforce: Optional[object] = None  # letterale "condizione attiva" (hard_if/soft_if)
    always_false: bool = False

    def broken(self, solver: cp_model.CpSolver) -> bool:
        if self.always_false:
            return True
        if self.ok is None:
            return False
        active = self.enforce is None or solver.BooleanValue(self.enforce)
        return active and not solver.BooleanValue(self.ok)


class _RuleApi:
    """La libreria per UNA regola. Il codice dei dipendenti vede e vincola solo
    i propri turni; le sue condizioni sono preferenze (peso massimo 50) finché
    il titolare non approva la regola."""

    _FILTERS = ("date", "dates", "days", "start", "end", "role", "skill", "category", "status")

    def __init__(self, ctx: _Ctx, model: cp_model.CpModel, x, by_emp, rule: CodeRule, *, relax_hard: bool,
                 check_mode: bool, conds: list, penalties: list, day_cache: dict):
        self.ctx, self.model, self.x, self.by_emp, self.rule = ctx, model, x, by_emp, rule
        self.relax_hard, self.check_mode, self.conds, self.penalties = relax_hard, check_mode, conds, penalties
        self.me = rule.author_employee_id if rule.author == "employee" else None
        self.views = [ShiftView(s) for s in ctx.shifts]
        self.emps = {e.id: EmployeeView(e) for e in ctx.employees}
        self._day_cache = day_cache

    # ── persone e filtri ──
    def _emp_id(self, who) -> str:
        if isinstance(who, EmployeeView):
            eid = who.id
        elif isinstance(who, str):
            if who in self.ctx.emp_by_id:
                eid = who
            else:
                hits = [e.id for e in self.ctx.employees if e.name and _norm(e.name) == _norm(who)]
                hits = hits or [e.id for e in self.ctx.employees if e.name and _norm(who) in _norm(e.name).split()]
                if len(hits) != 1:
                    raise sandbox.RuleRejected(f"persona '{who}' {'ambigua' if hits else 'sconosciuta'}")
                eid = hits[0]
        else:
            raise sandbox.RuleRejected("indica una persona (id, nome o emp(...))")
        if self.me is not None and eid != self.me:
            raise sandbox.RuleRejected("la regola di un dipendente può riguardare solo i suoi turni")
        return eid

    def _many(self, who) -> list[str]:
        if who is None:
            return [self.me] if self.me is not None else list(self.ctx.emp_ids)
        if isinstance(who, (list, tuple, set)):
            return [self._emp_id(w) for w in who]
        return [self._emp_id(who)]

    def _filters(self, kw: dict) -> dict:
        bad = set(kw) - set(self._FILTERS)
        if bad:
            raise sandbox.RuleRejected(f"filtri sconosciuti: {', '.join(sorted(bad))}")
        if (kw.get("start") is None) != (kw.get("end") is None):
            raise sandbox.RuleRejected("start ed end vanno indicati insieme")
        return kw

    def _match(self, s: _Shift, f: dict) -> bool:
        if f.get("date") is not None and s.date != f["date"]:
            return False
        if f.get("dates") is not None and s.date not in set(f["dates"]):
            return False
        if f.get("days") is not None and s.date.weekday() not in set(f["days"]):
            return False
        if f.get("role") is not None and s.role != _norm(f["role"]):
            return False
        if f.get("skill") is not None and s.required_skill != _norm(f["skill"]):
            return False
        if f.get("category") is not None and s.category != f["category"]:
            return False
        if f.get("status") is not None and s.status != f["status"]:
            return False
        if f.get("start") is not None and not _overlaps((s.start, s.end), self.ctx.window(s.date, f["start"], f["end"])):
            return False
        return True

    def _vars(self, eid: str, f: dict):
        return [(i, self.x[(i, eid)]) for i in self.by_emp.get(eid, []) if self._match(self.ctx.shifts[i], f)]

    def _w(self, weight) -> int:
        w = int(weight)
        return min(w, EMPLOYEE_MAX_WEIGHT) if self.me is not None else w

    # ── lettura ──
    def emp(self, who) -> EmployeeView:
        return self.emps[self._emp_id(who)]

    def shifts_where(self, **kw) -> list[ShiftView]:
        f = self._filters(kw)
        return [v for v, s in zip(self.views, self.ctx.shifts) if self._match(s, f)]

    def employees_where(self, role=None, skill=None) -> list[EmployeeView]:
        out = [self.emps[e] for e in self._many(None)]
        if role is not None:
            out = [e for e in out if _norm(role) in {_norm(r) for r in e.roles}]
        if skill is not None:
            out = [e for e in out if _norm(skill) in {_norm(k) for k in e.skills}]
        return out

    # ── espressioni sulle assegnazioni ──
    def assigned(self, shift: ShiftView, who):
        eid = self._emp_id(who)
        i = next((k for k, s in enumerate(self.ctx.shifts) if s.id == shift.id), None)
        return self.x.get((i, eid), 0) if i is not None else 0

    def covered(self, shift: ShiftView):
        """1 se il posto è coperto da qualcuno."""
        i = next((k for k, s in enumerate(self.ctx.shifts) if s.id == shift.id), None)
        return sum(v for (k, _e), v in self.x.items() if k == i) if i is not None else 0

    def works(self, who=None, **kw):
        f = self._filters(kw)
        return sum(v for e in self._many(who) for _, v in self._vars(e, f))

    def minutes(self, who=None, **kw):
        f = self._filters(kw)
        return sum(v * self.ctx.shifts[i].minutes for e in self._many(who) for i, v in self._vars(e, f))

    def cost(self, who=None, **kw):
        f = self._filters(kw)
        total = 0
        for e in self._many(who):
            cents = self.ctx.emp_by_id[e].hourly_cost_cents or 0
            total += sum(v * (self.ctx.shifts[i].minutes * cents // 60) for i, v in self._vars(e, f))
        return total

    def works_on(self, who, day: date, **kw):
        eid = self._emp_id(who)
        f = self._filters({**kw, "date": day})
        key = (eid, day, tuple(sorted((k, str(v)) for k, v in f.items())))
        if key not in self._day_cache:
            vs = [v for _, v in self._vars(eid, f)]
            if not vs:
                self._day_cache[key] = 0
            else:
                w = self.model.NewBoolVar("")
                self.model.AddMaxEquality(w, vs)
                self._day_cache[key] = w
        return self._day_cache[key]

    def days_worked(self, who=None, **kw):
        f = self._filters(kw)
        days = list(self.ctx.plan_dates)
        if f.get("days") is not None:
            days = [d for d in days if d.weekday() in set(f["days"])]
        if f.get("dates") is not None:
            days = [d for d in days if d in set(f["dates"])]
        rest = {k: v for k, v in f.items() if k not in ("days", "dates", "date")}
        return sum(self.works_on(e, d, **rest) for e in self._many(who) for d in days)

    def together(self, a, b, **kw):
        """Quanti turni sovrapposti fanno insieme due persone."""
        if self.me is not None:
            raise sandbox.RuleRejected("la regola di un dipendente non può riguardare altre persone")
        ea, eb = self._emp_id(a), self._emp_id(b)
        f = self._filters(kw)
        terms = []
        for i, va in self._vars(ea, f):
            for j, vb in self._vars(eb, f):
                si, sj = self.ctx.shifts[i], self.ctx.shifts[j]
                if i != j and _overlaps((si.start, si.end), (sj.start, sj.end)):
                    y = self.model.NewBoolVar("")
                    self.model.AddMultiplicationEquality(y, [va, vb])
                    terms.append(y)
        return sum(terms)

    # ── mattoni OR-Tools ──
    def new_bool(self):
        return self.model.NewBoolVar("")

    def new_int(self, lo: int, hi: int):
        if not (-MAX_INT <= lo <= hi <= MAX_INT):
            raise sandbox.RuleRejected("limiti di new_int fuori intervallo")
        return self.model.NewIntVar(lo, hi, "")

    def any_of(self, items):
        """Vale 1 se almeno uno degli elementi (0/1) vale 1."""
        lits = [i for i in items if not isinstance(i, int)]
        if any(isinstance(i, int) and i for i in items):
            return 1
        if not lits:
            return 0
        b = self.model.NewBoolVar("")
        self.model.AddMaxEquality(b, lits)
        return b

    def all_of(self, items):
        """Vale 1 se tutti gli elementi (0/1) valgono 1."""
        if any(isinstance(i, int) and not i for i in items):
            return 0
        lits = [i for i in items if not isinstance(i, int)]
        if not lits:
            return 1
        b = self.model.NewBoolVar("")
        self.model.AddMinEquality(b, lits)
        return b

    def max_of(self, items):
        items = list(items)
        if not items:
            return 0
        v = self.model.NewIntVar(-MAX_INT, MAX_INT, "")
        self.model.AddMaxEquality(v, items)
        return v

    def min_of(self, items):
        items = list(items)
        if not items:
            return 0
        v = self.model.NewIntVar(-MAX_INT, MAX_INT, "")
        self.model.AddMinEquality(v, items)
        return v

    def abs_of(self, expr):
        v = self.model.NewIntVar(0, MAX_INT, "")
        self.model.AddAbsEquality(v, expr)
        return v

    # ── condizioni ──
    def _cond(self, cond, severity: str, weight: int, msg: Optional[str], enforce=None):
        label = msg or self.rule.label
        if self.me is not None and not self.rule.approved:
            severity, weight = "soft", min(weight, EMPLOYEE_MAX_WEIGHT)
        if isinstance(enforce, int):
            if not enforce:
                return
            enforce = None
        if isinstance(cond, bool):
            if not cond:
                if enforce is None:
                    self.conds.append(_Cond(self.rule, severity, label, always_false=True))
                else:
                    # "se enforce allora falso" = enforce deve essere 0.
                    self._cond(enforce == 0, severity, weight, msg)
            return
        strict = (severity == "hard" and not self.relax_hard) or (self.check_mode and not self.relax_hard)
        if strict:
            ct = self.model.Add(cond)
            if enforce is not None:
                ct.OnlyEnforceIf(enforce)
            self.conds.append(_Cond(self.rule, severity, label))
            return
        ok = self.model.NewBoolVar("")
        lits = [ok] if enforce is None else [ok, enforce]
        self.model.Add(cond).OnlyEnforceIf(lits)
        w = RELAXED_HARD_WEIGHT if (severity == "hard" or self.check_mode) else weight
        if enforce is None:
            self.penalties.append((1 - ok, w))
        else:
            viol = self.model.NewBoolVar("")
            self.model.Add(viol >= enforce - ok)
            self.penalties.append((viol, w))
        self.conds.append(_Cond(self.rule, severity, label, ok=ok, enforce=enforce))

    def hard(self, cond, msg: Optional[str] = None):
        self._cond(cond, "hard", RELAXED_HARD_WEIGHT, msg)

    def soft(self, cond, weight: int = 50, msg: Optional[str] = None):
        self._cond(cond, "soft", self._w(weight), msg)

    def hard_if(self, when, cond, msg: Optional[str] = None):
        """La condizione vale solo quando `when` (0/1) vale 1."""
        self._cond(cond, "hard", RELAXED_HARD_WEIGHT, msg, enforce=when)

    def soft_if(self, when, cond, weight: int = 50, msg: Optional[str] = None):
        self._cond(cond, "soft", self._w(weight), msg, enforce=when)

    def prefer(self, expr, weight: int = 10):
        """Premia expr: più è alto, meglio è ("preferirebbe")."""
        if not isinstance(expr, int):
            self.penalties.append((-expr, self._w(weight)))

    def avoid(self, expr, weight: int = 10):
        """Penalizza expr: più è alto, peggio è ("preferirebbe di no")."""
        if not isinstance(expr, int):
            self.penalties.append((expr, self._w(weight)))

    def balance(self, exprs, weight: int = 10):
        """Avvicina fra loro i valori (es. weekend di ciascuno): penalizza max − min."""
        exprs = list(exprs)
        if len(exprs) > 1:
            self.avoid(self.max_of(exprs) - self.min_of(exprs), weight)

    def max_streak(self, who, n: int, msg: Optional[str] = None, weight: Optional[int] = None, already: int = 0):
        """Al massimo n giorni lavorati di fila (already = giorni di fila già fatti prima del periodo)."""
        days = list(self.ctx.plan_dates)
        for e in self._many(who):
            worked = [self.works_on(e, d) for d in days]
            seq = [1] * min(already, n + 1) + worked
            for k in range(0, len(seq) - n):
                window = seq[k:k + n + 1]
                expr = sum(window)
                if weight is None:
                    self.hard(expr <= n, msg)
                else:
                    self.soft(expr <= n, weight, msg)

    def build(self) -> dict:
        api = {
            "shifts": list(self.views),
            "employees": [self.emps[e] for e in self._many(None)],
            "dates": list(self.ctx.plan_dates),
            "week_start": self.ctx.plan_dates[0] if self.ctx.plan_dates else None,
            "MORNING": "MORNING",
            "EVENING": "EVENING",
        }
        for name in ("emp", "shifts_where", "employees_where", "assigned", "covered", "works", "works_on",
                     "days_worked", "minutes", "cost", "new_bool", "new_int", "any_of", "all_of", "max_of",
                     "min_of", "abs_of", "hard", "soft", "hard_if", "soft_if", "prefer", "avoid", "balance",
                     "max_streak"):
            api[name] = getattr(self, name)
        if self.me is None:
            api["together"] = self.together
        else:
            api["me"] = self.emps[self.me]
        return api


# ── Modello ─────────────────────────────────────────────────────────────────


def _build_and_solve(req: SolveRequest, ctx: _Ctx, *, relax_hard: bool, check_mode: bool = False,
                     time_limit: Optional[float] = None):
    model = cp_model.CpModel()
    st = req.settings
    x: dict[tuple[int, str], cp_model.IntVar] = {}
    for i, s in enumerate(ctx.shifts):
        for e in ctx.emp_ids:
            if ctx.eligible[(i, e)] or s.pinned == e:
                x[(i, e)] = model.NewBoolVar("")

    for i, s in enumerate(ctx.shifts):
        if s.pinned:
            for e in ctx.emp_ids:
                if (i, e) in x:
                    model.Add(x[(i, e)] == (1 if e == s.pinned else 0))

    covered = []
    for i, s in enumerate(ctx.shifts):
        vs = [x[(i, e)] for e in ctx.emp_ids if (i, e) in x]
        if vs:
            model.Add(sum(vs) <= 1)
            # I posti tenuti da turni congelati/proposti non sono copertura certa.
            if s.status not in AT_RISK:
                covered.append((sum(vs), st.coverage_weight * s.priority))

    by_emp: dict[str, list[int]] = defaultdict(list)
    for (i, e) in x:
        by_emp[e].append(i)

    penalties: list[tuple] = []
    for e, idxs in by_emp.items():
        emp = ctx.emp_by_id[e]
        allow_overlap = st.allow_overlap if emp.allow_overlap is None else emp.allow_overlap
        rest = st.min_rest_minutes if emp.min_rest_minutes is None else emp.min_rest_minutes
        max_day = st.max_shifts_per_day if emp.max_shifts_per_day is None else emp.max_shifts_per_day
        idxs.sort(key=lambda i: ctx.shifts[i].start)
        for pos, a in enumerate(idxs):
            sa = ctx.shifts[a]
            for b in idxs[pos + 1:]:
                sb = ctx.shifts[b]
                if sb.start >= sa.end + rest:
                    break
                if sa.pinned == e and sb.pinned == e:
                    continue
                if sb.start < sa.end:
                    if not allow_overlap:
                        model.Add(x[(a, e)] + x[(b, e)] <= 1)
                elif sa.date != sb.date:
                    # Riposo fra giornate; pranzo e cena dello stesso giorno no.
                    model.Add(x[(a, e)] + x[(b, e)] <= 1)
        per_day: dict[date, list] = defaultdict(list)
        for i in idxs:
            per_day[ctx.shifts[i].date].append(x[(i, e)])
        for vs in per_day.values():
            if len(vs) > max_day:
                model.Add(sum(vs) <= max_day)
            if len(vs) > 1 and st.split_shift_weight:
                extra = model.NewIntVar(0, len(vs) - 1, "")
                model.Add(extra >= sum(vs) - 1)
                penalties.append((extra, st.split_shift_weight))

    # Ogni regola gira prima su un modello di prova: nel modello vero entrano
    # solo le regole eseguite per intero (CP-SAT non toglie i vincoli già
    # aggiunti, quindi una regola che fallisce a metà non deve mai toccarlo).
    usable, errors, _counts = _probe_rules(req, ctx)
    # Competenza "preferibile" del fabbisogno: chi non la possiede costa un po'.
    for (i, e), v in x.items():
        sh = ctx.shifts[i]
        if sh.required_skill and sh.skill_strength == "preferred" and not sh.pinned:
            if sh.required_skill not in {_norm(k) for k in ctx.emp_by_id[e].skills}:
                penalties.append((v, st.preferred_skill_weight))

    conds: list[_Cond] = []
    day_cache: dict = {}
    for rule in usable:
        api = _RuleApi(ctx, model, x, by_emp, rule, relax_hard=relax_hard, check_mode=check_mode,
                       conds=conds, penalties=penalties, day_cache=day_cache)
        sandbox.run(rule.code, api.build())

    loads = [sum(x[(i, e)] for i in by_emp[e]) for e in ctx.emp_ids
             if ctx.emp_by_id[e].auto_assign and by_emp.get(e)]
    if st.fairness_weight and len(loads) > 1:
        hi = model.NewIntVar(0, len(ctx.shifts), "")
        lo = model.NewIntVar(0, len(ctx.shifts), "")
        model.AddMaxEquality(hi, loads)
        model.AddMinEquality(lo, loads)
        penalties.append((hi - lo, st.fairness_weight * 10))

    obj = [cov * w for cov, w in covered] + [-expr * w for expr, w in penalties]
    model.Maximize(sum(obj) if obj else 0)

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit or req.time_limit_seconds
    solver.parameters.num_workers = 8
    solver.parameters.random_seed = 7
    status = solver.Solve(model)
    return solver, status, x, conds, errors


def _violations(solver, conds: list[_Cond]) -> list[Violation]:
    return [Violation(rule_id=c.rule.id, label=c.rule.label, severity=c.severity, detail=c.msg)
            for c in conds if c.broken(solver)]


def solve(req: SolveRequest) -> SolveResponse:
    t0 = time.monotonic()
    ctx = _prepare(req)
    solver, status, x, conds, errors = _build_and_solve(req, ctx, relax_hard=False)
    relaxed = False
    if status == cp_model.INFEASIBLE:
        relaxed = True
        solver, status, x, conds, errors = _build_and_solve(req, ctx, relax_hard=True)
    elapsed = round(time.monotonic() - t0, 3)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return SolveResponse(
            status="INFEASIBLE" if status == cp_model.INFEASIBLE else "NO_SOLUTION", relaxed_hard=relaxed,
            solve_seconds=elapsed, assignments=[], unassigned_shift_ids=[s.id for s in ctx.shifts], violations=[],
            rule_errors=errors, employees=[], warnings=ctx.warnings,
        )
    owner: dict[int, Optional[str]] = {i: None for i in range(len(ctx.shifts))}
    for (i, e), v in x.items():
        if solver.Value(v):
            owner[i] = e
    summary = []
    for e in ctx.emp_ids:
        mine = [ctx.shifts[i] for i, o in owner.items() if o == e]
        summary.append(EmployeeSummary(
            employee_id=e, shifts=len(mine), minutes=sum(s.minutes for s in mine),
            morning=sum(1 for s in mine if s.category == "MORNING"),
            evening=sum(1 for s in mine if s.category == "EVENING"),
        ))
    return SolveResponse(
        status="OPTIMAL" if status == cp_model.OPTIMAL else "FEASIBLE",
        relaxed_hard=relaxed,
        objective=solver.ObjectiveValue(),
        solve_seconds=elapsed,
        assignments=[
            Assignment(shift_id=s.id, employee_id=owner[i], date=s.date, start=s.start_label, end=s.end_label,
                       role=s.role_label, required_skill=s.skill_label, fixed=s.fixed,
                       status=s.status if s.fixed else "new", ref=s.ref)
            for i, s in enumerate(ctx.shifts)
        ],
        unassigned_shift_ids=[ctx.shifts[i].id for i, o in owner.items() if o is None],
        at_risk_shift_ids=[s.id for s in ctx.shifts if s.status in AT_RISK],
        violations=_violations(solver, conds),
        rule_errors=errors,
        employees=summary,
        warnings=list(dict.fromkeys(ctx.warnings)),
    )


# ── Fabbisogno risolto (per parser, calendario e controlli) ─────────────────


def resolve(req: RequirementsResolveRequest) -> list[ResolvedRequirement]:
    return [ResolvedRequirement(**{k: v for k, v in r.items() if k != "priority"})
            for r in resolve_requirements(req.week_start, req.horizon_days, req.requirement_rules, req.requirements)]


# ── Prova delle regole (ciclo di vibecoding) ────────────────────────────────


def _rule_error(rule: CodeRule, exc: Exception) -> RuleError:
    if isinstance(exc, (sandbox.RuleRejected, sandbox.RuleTooLong)):
        msg = str(exc)
    else:
        msg = f"{type(exc).__name__}: {exc}"
    return RuleError(rule_id=rule.id, label=rule.label, error=msg)


def _probe_rules(req: SolveRequest, ctx: _Ctx) -> tuple[list[CodeRule], list[RuleError], dict[str, int]]:
    """Esegue ogni regola su un modello usa-e-getta: separa quelle valide dagli errori."""
    model = cp_model.CpModel()
    x = {(i, e): model.NewBoolVar("") for i in range(len(ctx.shifts)) for e in ctx.emp_ids
         if ctx.eligible[(i, e)] or ctx.shifts[i].pinned == e}
    by_emp: dict[str, list[int]] = defaultdict(list)
    for (i, e) in x:
        by_emp[e].append(i)
    ok, errors, counts = [], [], {}
    for rule in req.rules:
        conds: list[_Cond] = []
        try:
            api = _RuleApi(ctx, model, x, by_emp, rule, relax_hard=False, check_mode=False, conds=conds,
                           penalties=[], day_cache={})
            sandbox.run(rule.code, api.build())
            ok.append(rule)
            counts[rule.id] = len(conds)
        except Exception as exc:  # noqa: BLE001 — una regola sbagliata non ferma il motore
            errors.append(_rule_error(rule, exc))
    return ok, errors, counts


def validate_rules(req: RulesValidateRequest) -> RulesValidateResponse:
    sreq = SolveRequest(week_start=req.week_start, horizon_days=req.horizon_days, employees=req.employees,
                        requirement_rules=req.requirement_rules, requirements=req.requirements,
                        fixed_assignments=req.fixed_assignments, rules=req.rules, time_limit_seconds=1)
    _ok, errors, counts = _probe_rules(sreq, _prepare(sreq))
    return RulesValidateResponse(ok=not errors, rule_errors=errors, conditions=counts)


# ── Controllo immediato della disponibilità di una persona ──────────────────


def personal_rules(rules: list[CodeRule], employee_id: str) -> list[CodeRule]:
    """Solo le regole che riguardano UNA persona: coppie e regole generali
    dipendono dagli altri e le risolve il motore in generazione."""
    return [r for r in rules if r.about == [employee_id]]


def check_availability(req: AvailabilityCheckRequest) -> AvailabilityCheckResponse:
    """La persona da sola, con tutto il fabbisogno a disposizione: se nemmeno
    così le sue regole personali sono rispettabili, la disponibilità è
    incompatibile con le aspettative del titolare."""
    emp = req.employee.model_copy(update={"auto_assign": True})
    base = dict(week_start=req.week_start, horizon_days=req.horizon_days, employees=[emp],
                requirement_rules=req.requirement_rules, requirements=req.requirements, time_limit_seconds=10)
    sreq = SolveRequest(**base, rules=personal_rules(req.rules, emp.id),
                        settings=req.settings.model_copy(update={"coverage_weight": 1, "fairness_weight": 0}))
    ctx = _prepare(sreq)
    solver, status, _x, conds, errors = _build_and_solve(sreq, ctx, relax_hard=False, check_mode=True)
    conflicts: list[Violation] = []
    if status == cp_model.INFEASIBLE:
        solver, status, _x, conds, errors = _build_and_solve(sreq, ctx, relax_hard=True, check_mode=True)
        if status in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            conflicts = _violations(solver, conds)
    else:
        conflicts = [Violation(rule_id=c.rule.id, label=c.rule.label, severity=c.severity, detail=c.msg)
                     for c in conds if c.always_false]

    free = solve(SolveRequest(**base, settings=req.settings))
    coverable = [SlotRef(date=s.date, start=s.start_label, end=s.end_label, role=s.role_label)
                 for i, s in enumerate(ctx.shifts) if ctx.eligible[(i, emp.id)]]
    roles = {_norm(r) for r in emp.roles}
    role_slots = [(s.start, s.end) for s in ctx.shifts if s.role in roles]
    unmatched = [w for w in (emp.availability or []) if not any(_overlaps(ctx.tw(w), r) for r in role_slots)]
    summary = next((e for e in free.employees if e.employee_id == emp.id), None)
    return AvailabilityCheckResponse(
        employee_id=emp.id,
        compatible=not any(c.severity == "hard" for c in conflicts),
        conflicts=conflicts,
        rule_errors=errors,
        coverable_slots=list({(c.date, c.start, c.end, c.role): c for c in coverable}.values()),
        unmatched_windows=unmatched,
        max_shifts=summary.shifts if summary else 0,
        max_minutes=summary.minutes if summary else 0,
    )


# ── Buchi: a chi chiedere disponibilità aggiuntiva ──────────────────────────


def gap_candidates(req: GapCandidatesRequest) -> GapCandidatesResponse:
    """Buchi del calendario attuale (e posti a rischio) e, per ognuno, chi
    potrebbe coprirlo. Esclude i fatti certi (ruolo, indisponibilità,
    conflitti con i suoi turni); le regole personali che violerebbe restano
    visibili in would_exceed."""
    sreq = SolveRequest(week_start=req.week_start, horizon_days=req.horizon_days, settings=req.settings,
                        employees=req.employees, requirement_rules=req.requirement_rules,
                        requirements=req.requirements, fixed_assignments=req.fixed_assignments)
    ctx = _prepare(sreq)
    st, flt = req.settings, req.candidate_filter
    current: dict[str, list[_Shift]] = defaultdict(list)
    for s in ctx.shifts:
        if s.pinned in ctx.emp_by_id:
            current[s.pinned].append(s)

    groups: dict[tuple, list[_Shift]] = {}
    for s in ctx.shifts:
        at_risk = s.status in AT_RISK
        if s.pinned is not None and not (at_risk and req.include_at_risk):
            continue
        if not req.include_past and req.today and s.date < req.today:
            continue
        key = (s.date, s.start_label, s.end_label, s.role_label, s.skill_label, s.id if at_risk else None)
        groups.setdefault(key, []).append(s)

    pool = list(req.employees)
    if flt.employee_ids is not None:
        pool = [e for e in pool if e.id in set(flt.employee_ids)]
    if flt.roles is not None:
        want = {_norm(r) for r in flt.roles}
        pool = [e for e in pool if want & {_norm(r) for r in e.roles}]
    if flt.auto_assign is not None:
        pool = [e for e in pool if e.auto_assign == flt.auto_assign]

    gaps: list[Gap] = []
    for (d, start, end, role, skill, _risk), slots in groups.items():
        s = slots[0]
        iv = (s.start, s.end)
        holder = s.pinned if s.status in AT_RISK else None
        cands: list[GapCandidate] = []
        for emp in pool:
            if emp.id == holder or s.role not in {_norm(r) for r in emp.roles}:
                continue
            if s.required_skill and s.skill_strength == "required" and s.required_skill not in {_norm(k) for k in emp.skills}:
                continue
            if any(_overlaps(iv, ctx.tw(w)) for w in emp.unavailable):
                continue
            allow_overlap = st.allow_overlap if emp.allow_overlap is None else emp.allow_overlap
            rest = st.min_rest_minutes if emp.min_rest_minutes is None else emp.min_rest_minutes
            max_day = st.max_shifts_per_day if emp.max_shifts_per_day is None else emp.max_shifts_per_day
            mine = current[emp.id]
            same_day = [o for o in mine if o.date == s.date]
            clash = len(same_day) >= max_day
            for o in mine:
                if _overlaps(iv, (o.start, o.end)):
                    clash = clash or not allow_overlap
                elif o.date != s.date and s.start < o.end + rest and o.start < s.end + rest:
                    clash = True
            if clash:
                continue
            avail = None if emp.availability is None else _merge([ctx.tw(w) for w in emp.availability])
            already = avail is None or any(a <= s.start and s.end <= b for a, b in avail)
            cands.append(GapCandidate(
                employee_id=emp.id, name=emp.name, already_available=already,
                would_exceed=_personal_breaks(req, emp, mine, s),
                shifts_this_week=len(mine), minutes_this_week=sum(o.minutes for o in mine),
                same_day_shifts=len(same_day),
            ))
        cands.sort(key=lambda c: (not c.already_available, bool(c.would_exceed), c.minutes_this_week, c.same_day_shifts))
        gaps.append(Gap(date=d, start=start, end=end, role=role, required_skill=skill, missing=len(slots),
                        at_risk=holder is not None, held_by=holder, held_status=s.status if holder else None,
                        candidates=cands[: req.max_candidates]))
    gaps.sort(key=lambda g: (g.date, g.start, g.role))
    return GapCandidatesResponse(gaps=gaps, warnings=list(dict.fromkeys(ctx.warnings)))


def _personal_breaks(req: GapCandidatesRequest, emp: Employee, mine: list[_Shift], gap: _Shift) -> list[str]:
    """Regole personali hard della persona violate se prendesse anche questo turno."""
    rules = personal_rules(req.rules, emp.id)
    if not rules:
        return []
    fixed = [dict(employee_id=emp.id, date=o.date, start=o.start_label, end=o.end_label, role=o.role_label,
                  required_skill=o.skill_label) for o in mine + [gap]]
    sreq = SolveRequest(week_start=req.week_start, horizon_days=req.horizon_days, settings=req.settings,
                        employees=[emp.model_copy(update={"auto_assign": False})], fixed_assignments=fixed,
                        rules=rules, time_limit_seconds=2)
    ctx = _prepare(sreq)
    _solver, status, *_ = _build_and_solve(sreq, ctx, relax_hard=False, time_limit=2)
    if status != cp_model.INFEASIBLE:
        return []
    solver, status, _x, conds, _e = _build_and_solve(sreq, ctx, relax_hard=True, time_limit=2)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return ["regole personali"]
    return [v.detail for v in _violations(solver, conds) if v.severity == "hard"]
