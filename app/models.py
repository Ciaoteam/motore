"""Schemi di ingresso e uscita del motore turni (FastAPI + OR-Tools).

Convenzioni:
- date "YYYY-MM-DD", orari "HH:MM" (fuso del locale: il motore non converte).
- un turno con fine <= inizio finisce il giorno dopo (es. 18:00-02:00).
- ogni Shift è UNA unità da coprire con UNA persona: il fabbisogno "2 camerieri"
  arriva come due Shift distinti.
- severity "hard" = va rispettato; "soft" = preferenza pesata da `weight`.
"""

from __future__ import annotations

import re
from datetime import date
from typing import Annotated, Literal, Optional

from pydantic import AfterValidator, BaseModel, Field, model_validator

_HHMM_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


def _check_time(v: str) -> str:
    if not _HHMM_RE.match(v):
        raise ValueError("orario non valido, usa HH:MM (00:00-23:59)")
    return v


HHMM = Annotated[str, AfterValidator(_check_time)]


class TimeWindow(BaseModel):
    """Una finestra in una data. Senza start/end vale tutta la giornata."""

    date: date
    start: Optional[HHMM] = None
    end: Optional[HHMM] = None


    @model_validator(mode="after")
    def _both_or_none(self) -> "TimeWindow":
        if (self.start is None) != (self.end is None):
            raise ValueError("start ed end vanno indicati insieme (o nessuno dei due)")
        return self


class Employee(BaseModel):
    id: str
    name: Optional[str] = None
    roles: list[str] = Field(default_factory=list, description="Ruoli che può coprire (es. Cameriere).")
    skills: list[str] = Field(default_factory=list, description="Competenze (es. Responsabile).")
    availability: Optional[list[TimeWindow]] = Field(
        default=None,
        description="Finestre in cui PUÒ lavorare. null = sempre disponibile; [] = mai. "
        "Un turno è assegnabile solo se cade interamente in una finestra.",
    )
    unavailable: list[TimeWindow] = Field(default_factory=list, description="Indisponibilità certe (vincolo duro).")
    max_shifts_per_day: Optional[int] = Field(
        default=None, ge=1, le=4, description="Override personale (1 = niente spezzato). null = valore di settings."
    )
    min_rest_minutes: Optional[int] = Field(
        default=None, ge=0, le=24 * 60, description="Riposo minimo personale fra due giornate. null = settings."
    )
    allow_overlap: Optional[bool] = Field(
        default=None, description="true = il titolare permette turni sovrapposti a questa persona. null = settings."
    )
    hourly_cost_cents: Optional[int] = Field(default=None, ge=0, description="Costo orario (per measure=cost).")
    auto_assign: bool = Field(
        default=True,
        description="false = tappabuchi/extra: mai assegnato dal motore, conta solo nei turni fissi.",
    )


class _Need(BaseModel):
    start: HHMM
    end: HHMM
    role: str
    mode: Literal["set", "add"] = Field(
        default="set", description='"set" = servono N persone; "add" = N in più (o in meno se negativo).'
    )
    headcount: int = Field(ge=-50, le=50, description="Persone (set) o variazione (add).")
    required_skill: Optional[str] = None
    skill_headcount: int = Field(default=1, ge=0, description="Di cui N con la competenza (≤ headcount).")
    skill_strength: Literal["required", "preferred"] = "required"
    priority: int = Field(default=1, ge=1, le=100)

    @model_validator(mode="after")
    def _set_not_negative(self):
        if self.mode == "set" and self.headcount < 0:
            raise ValueError('con mode="set" headcount non può essere negativo')
        return self


class RequirementRule(_Need):
    """Fabbisogno ricorrente con periodo di validità.
    Es. "dal 1° gennaio a cena serve un cameriere in più":
    {mode: "add", headcount: 1, role: "Cameriere", start: "19:00", end: "23:00", valid_from: "2027-01-01"}."""

    days_of_week: Optional[list[int]] = Field(default=None, description="0=lunedì … 6=domenica; null = ogni giorno.")
    valid_from: Optional[date] = Field(default=None, description="Da questa data inclusa; null = da sempre.")
    valid_to: Optional[date] = Field(default=None, description="Fino a questa data inclusa; null = senza fine.")


class Requirement(_Need):
    """Eccezione di una singola data (applicata dopo le regole ricorrenti)."""

    date: date


class FixedAssignment(BaseModel):
    """Turno già esistente che resta com'è (messo a mano, congelato, giorni non rigenerati).
    Occupa il posto corrispondente del fabbisogno; se non corrisponde a nessun posto resta come turno in più."""

    employee_id: str
    date: date
    start: HHMM
    end: HHMM
    role: str
    required_skill: Optional[str] = None
    status: Literal["confirmed", "frozen", "proposed"] = Field(
        default="confirmed",
        description="confirmed = turno certo; frozen = congelato in attesa della decisione del titolare; "
        "proposed = proposto al dipendente, si aspetta la sua risposta. frozen e proposed tengono il posto "
        "riservato ma non contano come copertura certa (finiscono in at_risk).",
    )
    ref: Optional[str] = Field(default=None, description="Id del turno nell'app, restituito tale e quale.")


class Settings(BaseModel):
    min_rest_minutes: int = Field(
        default=480, ge=0, le=24 * 60,
        description="Riposo minimo fra la fine di una giornata e l'inizio della successiva (non fra pranzo e cena "
        "dello stesso giorno).",
    )
    max_shifts_per_day: int = Field(
        default=2, ge=1, le=4, description="Turni al giorno per persona: 2 permette lo spezzato (pranzo + cena)."
    )
    split_shift_weight: int = Field(
        default=30, ge=0, le=100000, description="Penalità per ogni secondo turno nella stessa giornata."
    )
    allow_overlap: bool = Field(default=False, description="true = turni sovrapposti ammessi per tutti.")
    preferred_skill_weight: int = Field(
        default=30, ge=0, le=100000,
        description="Penalità per un posto con competenza preferibile coperto da chi non la possiede.",
    )
    category_cutoff: HHMM = Field(default="14:00", description="Mattina se il turno inizia prima di quest'ora.")
    fairness_weight: int = Field(default=2, ge=0, le=1000, description="Quanto bilanciare i turni fra le persone.")
    coverage_weight: int = Field(default=1000, ge=1, description="Valore di ogni turno coperto.")



# ── Regole: codice Python ───────────────────────────────────────────────────
#
# Ogni vincolo è un piccolo programma Python scritto da Lovable a partire da
# ciò che dice il titolare (o un dipendente, nei suoi limiti). Gira in una
# sandbox con la libreria del motore (vedi app/rules.py e README): può
# esprimere anche i vincoli più particolari senza tipi nuovi nel motore.


class CodeRule(BaseModel):
    id: str = Field(description="Id stabile della regola (riportato in violazioni ed errori).")
    label: str = Field(description="La regola a parole, come l'ha detta il titolare.")
    code: str = Field(max_length=8000, description="Codice Python della regola (libreria in README).")
    about: list[str] = Field(
        default_factory=list,
        description="Persone che la regola riguarda. Una sola = regola personale (verificata anche sulla "
        "disponibilità); più persone o nessuna = regola generale, solo per il motore.",
    )
    author: Literal["owner", "employee"] = "owner"
    author_employee_id: Optional[str] = Field(default=None, description="Per author=employee: chi l'ha scritta.")
    approved: bool = Field(
        default=False,
        description="Per author=employee: true = il titolare l'ha approvata (allora hard vale come hard).",
    )
    valid_from: Optional[date] = Field(
        default=None, description="La regola vale da questa data inclusa (filtra date e turni che vede)."
    )
    valid_to: Optional[date] = Field(default=None, description="La regola vale fino a questa data inclusa.")

    @model_validator(mode="after")
    def _employee_author(self) -> "CodeRule":
        if self.author == "employee" and not self.author_employee_id:
            raise ValueError("una regola scritta da un dipendente richiede author_employee_id")
        return self


class NeedRule(BaseModel):
    """Fabbisogno calcolato da codice Python: eventi, stagioni, festivi, chiusure.
    Gira dopo `requirement_rules` e prima delle eccezioni per data (`requirements`).
    Libreria: need(), close(), base(), events, dates, is_holiday(), ... (README)."""

    id: str
    label: str
    code: str = Field(max_length=8000)
    valid_from: Optional[date] = None
    valid_to: Optional[date] = None


class Event(BaseModel):
    """Un fatto del calendario del locale: evento con ospiti, chiusura, serata speciale."""

    id: Optional[str] = None
    date: date
    start: Optional[HHMM] = None
    end: Optional[HHMM] = None
    kind: str = Field(default="evento", description="evento | chiusura | festivo | altro (testo libero).")
    title: Optional[str] = None
    guests: Optional[int] = Field(default=None, ge=0, le=100000)
    note: Optional[str] = None

    @model_validator(mode="after")
    def _both_or_none(self) -> "Event":
        if (self.start is None) != (self.end is None):
            raise ValueError("start ed end vanno indicati insieme (o nessuno dei due)")
        return self


class SolveRequest(BaseModel):
    week_start: Optional[date] = None
    time_limit_seconds: Optional[float] = Field(
        default=None, gt=0, le=1800,
        description="Limite di tempo. null = quanto serve: si ferma alla soluzione ottima o quando smette di "
        "migliorare, con un tetto di sicurezza (SOLVER_MAX_SECONDS).",
    )
    settings: Settings = Field(default_factory=Settings)
    employees: list[Employee]
    horizon_days: int = Field(default=7, ge=1, le=31, description="Giorni da pianificare a partire da week_start.")
    plan_dates: Optional[list[date]] = Field(
        default=None,
        description="Pianifica solo queste date (es. rigenerare venerdì e sabato): il fabbisogno degli altri "
        "giorni non entra; i turni fissi degli altri giorni restano come contesto (ore, riposi, regole).",
    )
    requirement_rules: list[RequirementRule] = Field(
        default_factory=list, description="Fabbisogno ricorrente con validità: richiede week_start."
    )
    requirements: list[Requirement] = Field(
        default_factory=list, description="Eccezioni per singola data, applicate dopo le regole."
    )
    fixed_assignments: list[FixedAssignment] = Field(
        default_factory=list, description="Turni esistenti da tenere: occupano il posto del fabbisogno."
    )
    rules: list[CodeRule] = Field(default_factory=list, description="Vincoli come codice Python.")
    need_rules: list[NeedRule] = Field(default_factory=list, description="Fabbisogno calcolato da codice Python.")
    events: list[Event] = Field(default_factory=list, description="Eventi e chiusure (dati per need_rules).")
    history: list[FixedAssignment] = Field(
        default_factory=list,
        description="Turni già lavorati prima del periodo (sola lettura, per past()/limiti del mese).",
    )

    @model_validator(mode="after")
    def _rules_need_week(self) -> "SolveRequest":
        if (self.requirement_rules or self.plan_dates) and self.week_start is None:
            raise ValueError("requirement_rules e plan_dates richiedono week_start (primo giorno da pianificare)")
        return self

    @model_validator(mode="after")
    def _unique_ids(self) -> "SolveRequest":
        ids = [e.id for e in self.employees]
        if len(ids) != len(set(ids)):
            raise ValueError("id duplicati in employees")
        return self


# ── Uscita ──────────────────────────────────────────────────────────────────


class Assignment(BaseModel):
    shift_id: str
    employee_id: Optional[str]
    date: date
    start: str
    end: str
    role: str
    required_skill: Optional[str] = None
    fixed: bool = Field(default=False, description="true = turno già esistente, da non riscrivere.")
    status: Literal["new", "confirmed", "frozen", "proposed"] = Field(
        default="new", description="new = assegnazione calcolata ora dal motore."
    )
    ref: Optional[str] = None


class Violation(BaseModel):
    rule_id: str
    label: str
    severity: Literal["hard", "soft"]
    detail: str = Field(description="Il messaggio della condizione non rispettata.")


class RuleError(BaseModel):
    rule_id: str
    label: str
    error: str = Field(description="Perché la regola non è stata applicata (sintassi, divieto, errore).")


class EmployeeSummary(BaseModel):
    employee_id: str
    shifts: int
    minutes: int
    morning: int
    evening: int


class SolveResponse(BaseModel):
    status: Literal["OPTIMAL", "FEASIBLE", "INFEASIBLE", "NO_SOLUTION"]
    relaxed_hard: bool = Field(
        default=False,
        description="true = i vincoli duri non erano tutti rispettabili insieme: sono stati trattati come "
        "preferenze molto forti e quelli non rispettati sono in violations.",
    )
    objective: Optional[float] = None
    solve_seconds: float
    assignments: list[Assignment]
    unassigned_shift_ids: list[str]
    at_risk_shift_ids: list[str] = Field(
        default_factory=list, description="Posti tenuti da turni congelati o proposti: copertura non certa."
    )
    violations: list[Violation]
    rule_errors: list[RuleError] = Field(
        default_factory=list, description="Regole scartate perché il codice non è valido: vanno corrette."
    )
    employees: list[EmployeeSummary]
    warnings: list[str]


# ── Controllo disponibilità e fabbisogno ────────────────────────────────────


class AvailabilityCheckRequest(BaseModel):
    """La disponibilità appena comunicata (o modificata dal titolare) di UNA persona,
    confrontata con le sue aspettative e con il fabbisogno della settimana."""

    week_start: date
    horizon_days: int = Field(default=7, ge=1, le=31)
    settings: Settings = Field(default_factory=Settings)
    employee: Employee
    requirement_rules: list[RequirementRule] = Field(default_factory=list)
    requirements: list[Requirement] = Field(default_factory=list)
    rules: list[CodeRule] = Field(
        default_factory=list, description="Si applicano solo le regole personali di questa persona (about=[lei])."
    )
    need_rules: list[NeedRule] = Field(default_factory=list)
    events: list[Event] = Field(default_factory=list)
    history: list[FixedAssignment] = Field(default_factory=list)


class SlotRef(BaseModel):
    date: date
    start: str
    end: str
    role: str


class AvailabilityCheckResponse(BaseModel):
    employee_id: str
    compatible: bool = Field(description="false = aprire un punto per il titolare con i conflitti.")
    conflicts: list[Violation] = Field(description="Aspettative del titolare che questa disponibilità non permette.")
    rule_errors: list[RuleError] = Field(default_factory=list)
    coverable_slots: list[SlotRef] = Field(description="Fasce del fabbisogno che la persona può coprire.")
    unmatched_windows: list[TimeWindow] = Field(
        description="Finestre dichiarate che non coprono nessuna fascia del fabbisogno del suo ruolo: da chiarire."
    )
    max_shifts: int = Field(description="Quanti turni potrebbe fare al massimo con questa disponibilità.")
    max_minutes: int


class ResolvedRequirement(BaseModel):
    date: date
    start: str
    end: str
    role: str
    headcount: int
    required_skill: Optional[str] = None
    skill_headcount: int = 1
    skill_strength: str = "required"


class RequirementsResolveRequest(BaseModel):
    week_start: date
    horizon_days: int = Field(default=7, ge=1, le=31)
    requirement_rules: list[RequirementRule] = Field(default_factory=list)
    requirements: list[Requirement] = Field(default_factory=list)


class RequirementsPreviewRequest(RequirementsResolveRequest):
    """Come /requirements/resolve, con il fabbisogno calcolato da codice e gli eventi."""

    need_rules: list[NeedRule] = Field(default_factory=list)
    events: list[Event] = Field(default_factory=list)


class RequirementsPreviewResponse(BaseModel):
    requirements: list[ResolvedRequirement]
    rule_errors: list[RuleError] = Field(default_factory=list)


# ── Buchi e richiesta di disponibilità aggiuntiva ───────────────────────────


class CandidateFilter(BaseModel):
    """A chi si può chiedere: tutto null = a chiunque abbia il ruolo giusto."""

    roles: Optional[list[str]] = None
    employee_ids: Optional[list[str]] = None
    auto_assign: Optional[bool] = Field(
        default=None, description="true = solo dipendenti normali; false = solo tappabuchi/extra; null = tutti."
    )


class GapCandidatesRequest(BaseModel):
    week_start: date
    horizon_days: int = Field(default=7, ge=1, le=31)
    settings: Settings = Field(default_factory=Settings)
    employees: list[Employee]
    requirement_rules: list[RequirementRule] = Field(default_factory=list)
    requirements: list[Requirement] = Field(default_factory=list)
    fixed_assignments: list[FixedAssignment] = Field(
        default_factory=list, description="Il calendario attuale: i posti non coperti sono i buchi."
    )
    rules: list[CodeRule] = Field(
        default_factory=list, description="Si usano le regole personali hard dei candidati (limiti da segnalare)."
    )
    need_rules: list[NeedRule] = Field(default_factory=list)
    events: list[Event] = Field(default_factory=list)
    history: list[FixedAssignment] = Field(default_factory=list)
    candidate_filter: CandidateFilter = Field(default_factory=CandidateFilter)
    max_candidates: int = Field(default=5, ge=1, le=50)
    include_past: bool = Field(default=False, description="false = ignora i buchi prima di `today`.")
    include_at_risk: bool = Field(
        default=True, description="true = anche i posti tenuti da turni congelati o proposti, con i possibili sostituti."
    )
    today: Optional[date] = None


class GapCandidate(BaseModel):
    employee_id: str
    name: Optional[str] = None
    already_available: bool = Field(description="true = la fascia rientra già nella sua disponibilità: assegnalo.")
    would_exceed: list[str] = Field(description="Regole personali che violerebbe prendendo il turno.")
    shifts_this_week: int
    minutes_this_week: int
    same_day_shifts: int


class Gap(BaseModel):
    date: date
    start: str
    end: str
    role: str
    required_skill: Optional[str] = None
    missing: int
    at_risk: bool = Field(default=False, description="true = posto tenuto da un turno congelato o proposto.")
    held_by: Optional[str] = Field(default=None, description="Chi tiene il posto a rischio.")
    held_status: Optional[str] = None
    candidates: list[GapCandidate]


class GapCandidatesResponse(BaseModel):
    gaps: list[Gap]
    warnings: list[str]


class RulesValidateRequest(BaseModel):
    """Prova le regole sui dati indicati senza generare: per il ciclo di vibecoding."""

    week_start: date
    horizon_days: int = Field(default=7, ge=1, le=31)
    employees: list[Employee]
    requirement_rules: list[RequirementRule] = Field(default_factory=list)
    requirements: list[Requirement] = Field(default_factory=list)
    fixed_assignments: list[FixedAssignment] = Field(default_factory=list)
    rules: list[CodeRule] = Field(default_factory=list)
    need_rules: list[NeedRule] = Field(default_factory=list)
    events: list[Event] = Field(default_factory=list)
    history: list[FixedAssignment] = Field(default_factory=list)


class RulesValidateResponse(BaseModel):
    ok: bool
    rule_errors: list[RuleError]
    conditions: dict[str, int] = Field(description="Quante condizioni ha generato ogni regola valida.")


# ── Controllo del calendario attuale ────────────────────────────────────────


class ScheduleCheckRequest(BaseModel):
    """Il calendario così com'è (fixed_assignments) contro regole e fabbisogno:
    nessuna assegnazione nuova, solo violazioni e posti scoperti."""

    week_start: date
    horizon_days: int = Field(default=7, ge=1, le=31)
    settings: Settings = Field(default_factory=Settings)
    employees: list[Employee]
    requirement_rules: list[RequirementRule] = Field(default_factory=list)
    requirements: list[Requirement] = Field(default_factory=list)
    need_rules: list[NeedRule] = Field(default_factory=list)
    events: list[Event] = Field(default_factory=list)
    fixed_assignments: list[FixedAssignment] = Field(default_factory=list)
    history: list[FixedAssignment] = Field(default_factory=list)
    rules: list[CodeRule] = Field(default_factory=list)


class OpenSlot(BaseModel):
    date: date
    start: str
    end: str
    role: str
    required_skill: Optional[str] = None
    missing: int


class ScheduleCheckResponse(BaseModel):
    violations: list[Violation]
    rule_errors: list[RuleError] = Field(default_factory=list)
    open_slots: list[OpenSlot] = Field(description="Posti del fabbisogno senza nessuno.")
    employees: list[EmployeeSummary]
    warnings: list[str] = Field(default_factory=list)
