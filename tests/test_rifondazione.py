"""Fabbisogno da codice, eventi, festivi, storico, validità delle regole, controllo del calendario."""

from datetime import date

from fastapi.testclient import TestClient

from app.calendar_it import easter, holiday_name, is_last_of_month, week_of_month
from app.main import app
from app.models import (
    RequirementsPreviewRequest,
    RulesValidateRequest,
    ScheduleCheckRequest,
    SolveRequest,
)
from app.solver import check_schedule, preview_requirements, solve, validate_rules

MON = "2026-12-21"  # lunedì; venerdì 25 è Natale
GRID = [{"role": "Sala", "start": "18:00", "end": "23:00", "headcount": 1}]


def emp(id_, roles=("Sala",), **kw):
    return {"id": id_, "name": id_.capitalize(), "roles": list(roles), **kw}


def need_rule(id_, code, **kw):
    return {"id": id_, "label": id_, "code": code, **kw}


def preview(**kw):
    base = {"week_start": MON, "requirement_rules": GRID}
    base.update(kw)
    return preview_requirements(RequirementsPreviewRequest.model_validate(base))


def heads(res, d, start="18:00", role="Sala"):
    return sum(r.headcount for r in res.requirements if str(r.date) == d and r.start == start and r.role == role)


# ── calendario ──────────────────────────────────────────────────────────────


def test_festivi_italiani():
    assert easter(2027) == date(2027, 3, 28)
    assert holiday_name(date(2027, 3, 29)) == "Pasquetta"
    assert holiday_name(date(2026, 12, 25)) == "Natale"
    assert holiday_name(date(2026, 12, 22)) is None
    assert week_of_month(date(2026, 12, 4)) == 1 and week_of_month(date(2026, 12, 25)) == 4
    assert is_last_of_month(date(2026, 12, 25)) and not is_last_of_month(date(2026, 12, 18))


# ── fabbisogno da codice ────────────────────────────────────────────────────


def test_un_cameriere_ogni_15_ospiti_per_gli_eventi():
    code = """
for e in events:
    if e.kind == "evento" and e.guests:
        need(e.date, e.start or "18:00", e.end or "23:00", "Sala", ceil_div(e.guests, 15))
"""
    res = preview(need_rules=[need_rule("eventi", code)],
                  events=[{"date": "2026-12-22", "start": "18:00", "end": "23:00", "guests": 30, "title": "Cena"}])
    assert res.rule_errors == []
    assert heads(res, "2026-12-22") == 3  # 1 della griglia + 2 per 30 ospiti
    assert heads(res, "2026-12-23") == 1


def test_chiuso_nei_festivi_e_per_le_chiusure():
    code = """
for d in dates:
    if is_holiday(d):
        close(d)
for e in events:
    if e.kind == "chiusura":
        close(e.date)
"""
    res = preview(need_rules=[need_rule("chiusure", code)], events=[{"date": "2026-12-23", "kind": "chiusura"}])
    assert heads(res, "2026-12-25") == 0
    assert heads(res, "2026-12-23") == 0
    assert heads(res, "2026-12-24") == 1


def test_validita_stagionale_e_set():
    code = 'for d in dates:\n    need(d, "12:00", "15:00", "Sala", 2, mode="set")'
    res = preview(need_rules=[need_rule("estate", code, valid_from="2026-12-24")])
    assert heads(res, "2026-12-23", "12:00") == 0
    assert heads(res, "2026-12-24", "12:00") == 2


def test_eccezione_per_data_vince_sul_codice():
    code = 'for d in dates:\n    need(d, "18:00", "23:00", "Sala", 1)'
    res = preview(need_rules=[need_rule("piu-uno", code)],
                  requirements=[{"date": "2026-12-22", "start": "18:00", "end": "23:00", "role": "Sala",
                                 "headcount": 5, "mode": "set"}])
    assert heads(res, "2026-12-21") == 2
    assert heads(res, "2026-12-22") == 5


def test_regola_di_fabbisogno_sbagliata_non_tocca_nulla():
    code = 'need(dates[0], "18:00", "23:00", "Sala", 3)\nx = 1 / 0'
    res = preview(need_rules=[need_rule("rotta", code)])
    assert len(res.rule_errors) == 1 and res.rule_errors[0].rule_id == "rotta"
    assert heads(res, "2026-12-21") == 1


def test_base_legge_la_griglia():
    code = """
for d in dates:
    for f in base(d, "Sala"):
        if d.weekday() == 5:
            need(d, f.start, f.end, "Sala", f.headcount)
"""
    res = preview(need_rules=[need_rule("sabato-doppio", code)])
    assert heads(res, "2026-12-26") == 2 and heads(res, "2026-12-24") == 1


def test_solve_usa_il_fabbisogno_da_codice_e_riporta_gli_errori():
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "horizon_days": 1, "time_limit_seconds": 5,
        "employees": [emp("anna"), emp("bea")], "requirement_rules": GRID,
        "need_rules": [need_rule("piu-uno", 'need(dates[0], "18:00", "23:00", "Sala", 1)'),
                       need_rule("rotta", "x = nome_che_non_esiste")],
    }))
    assert len([a for a in r.assignments if a.employee_id]) == 2
    assert [e.rule_id for e in r.rule_errors] == ["rotta"]


# ── storico e validità ──────────────────────────────────────────────────────


def test_massimo_due_domeniche_al_mese_con_lo_storico():
    code = 'hard(past("anna", since=month_start(week_start), days=[6]) + days_worked("anna", days=[6]) <= 2)'
    history = [{"employee_id": "anna", "date": d, "start": "18:00", "end": "23:00", "role": "Sala"}
               for d in ("2026-12-06", "2026-12-13")]
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "time_limit_seconds": 5, "employees": [emp("anna")], "requirement_rules": GRID,
        "history": history, "rules": [{"id": "dom", "label": "max 2 domeniche", "code": code, "about": ["anna"]}],
    }))
    sunday = [a for a in r.assignments if str(a.date) == "2026-12-27"]
    assert sunday and sunday[0].employee_id is None
    assert r.violations == []


def test_regola_con_validita_vede_solo_i_suoi_giorni():
    # "Anna non lavora" solo da giovedì: lunedì-mercoledì resta assegnabile.
    code = 'hard(works("anna") == 0)'
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "time_limit_seconds": 5, "employees": [emp("anna")], "requirement_rules": GRID,
        "rules": [{"id": "x", "label": "x", "code": code, "about": ["anna"], "valid_from": "2026-12-24"}],
    }))
    worked = sorted(str(a.date) for a in r.assignments if a.employee_id == "anna")
    assert worked == ["2026-12-21", "2026-12-22", "2026-12-23"]


def test_can_work_per_minimi_raggiungibili():
    # Anna vorrebbe 5 turni ma è disponibile solo 2 sere: il minimo si adatta e non rompe il resto.
    code = 'hard(works("anna") >= min(5, can_work("anna")))'
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "time_limit_seconds": 5,
        "employees": [emp("anna", availability=[{"date": "2026-12-21"}, {"date": "2026-12-22"}]), emp("bea")],
        "requirement_rules": GRID,
        "rules": [{"id": "min", "label": "min", "code": code, "about": ["anna"]}],
    }))
    assert not r.relaxed_hard
    assert len([a for a in r.assignments if a.employee_id == "anna"]) == 2


def test_validate_controlla_anche_il_fabbisogno():
    res = validate_rules(RulesValidateRequest.model_validate({
        "week_start": MON, "employees": [emp("anna")], "requirement_rules": GRID,
        "need_rules": [need_rule("rotta", "need(1, 2)")],
    }))
    assert not res.ok and res.rule_errors[0].rule_id == "rotta"


# ── controllo del calendario attuale ────────────────────────────────────────


def test_controllo_calendario_violazioni_e_buchi():
    fixed = [{"employee_id": "anna", "date": "2026-12-21", "start": "18:00", "end": "23:00", "role": "Sala"},
             {"employee_id": "anna", "date": "2026-12-22", "start": "18:00", "end": "23:00", "role": "Sala"}]
    res = check_schedule(ScheduleCheckRequest.model_validate({
        "week_start": MON, "horizon_days": 3, "employees": [emp("anna"), emp("bea")],
        "requirement_rules": GRID, "fixed_assignments": fixed,
        "rules": [{"id": "uno", "label": "Anna al massimo 1 turno", "code": 'hard(works("anna") <= 1)',
                   "about": ["anna"]}],
    }))
    assert [v.rule_id for v in res.violations] == ["uno"]
    assert [(str(o.date), o.missing) for o in res.open_slots] == [("2026-12-23", 1)]
    anna = next(e for e in res.employees if e.employee_id == "anna")
    assert anna.shifts == 2


def test_api_nuove_rispondono():
    client = TestClient(app)
    r = client.post("/requirements/preview", json={"week_start": MON, "requirement_rules": GRID})
    assert r.status_code == 200 and len(r.json()["requirements"]) == 7
    r = client.post("/schedule/check", json={"week_start": MON, "employees": [emp("anna")],
                                             "requirement_rules": GRID})
    assert r.status_code == 200 and len(r.json()["open_slots"]) == 7


def test_alternativa_due_giorni_liberi_e_combinazioni_con_costanti():
    # any_of/all_of su espressioni "1 - x": OR-Tools da solo darebbe un modello impossibile.
    code = """
a = all_of([1 - works_on("luca", week_start), 1 - works_on("luca", week_start + timedelta(days=1))])
b = all_of([1 - works_on("luca", week_start + timedelta(days=1)), 1 - works_on("luca", week_start + timedelta(days=2))])
hard(any_of([a, b]) == 1)
hard(works("luca") == min(5, can_work("luca")))
balance([works(e) + 0 for e in employees], 5)
"""
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "time_limit_seconds": 5, "employees": [emp("luca"), emp("bea")],
        "requirement_rules": GRID, "rules": [{"id": "alt", "label": "alt", "code": code, "about": ["luca"]}],
    }))
    assert r.status == "OPTIMAL" and not r.relaxed_hard
    days = sorted(str(a.date) for a in r.assignments if a.employee_id == "luca")
    assert len(days) == 5 and "2026-12-22" not in days
    assert ("2026-12-21" not in days) or ("2026-12-23" not in days)


def test_ore_della_scheda_per_settimana_su_due_settimane():
    # Come le genera l'app: una regola per settimana con validità (max 10 ore).
    rules = []
    for ws, we in (("2026-12-21", "2026-12-27"), ("2026-12-28", "2027-01-03")):
        for e in ("anna", "bea"):
            rules.append({"id": f"scheda:max:{e}:{ws}", "label": "max", "code": f'hard(minutes("{e}") <= 600, "{e} oltre")',
                          "about": [e], "valid_from": ws, "valid_to": we})
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "horizon_days": 14, "time_limit_seconds": 10,
        "employees": [emp("anna"), emp("bea")], "requirement_rules": GRID, "rules": rules,
    }))
    for ws, we in (("2026-12-21", "2026-12-27"), ("2026-12-28", "2027-01-03")):
        for e in ("anna", "bea"):
            n = sum(1 for a in r.assignments if a.employee_id == e and ws <= str(a.date) <= we)
            assert n == 2, (e, ws, n)


def test_controllo_buchi_con_e_senza_competenza():
    grid = GRID + [{"role": "Sala", "start": "12:00", "end": "15:00", "headcount": 1, "required_skill": "Responsabile"}]
    res = check_schedule(ScheduleCheckRequest.model_validate({
        "week_start": MON, "horizon_days": 1, "employees": [emp("anna")], "requirement_rules": grid,
    }))
    assert sorted((o.start, o.required_skill) for o in res.open_slots) == [("12:00", "responsabile"), ("18:00", None)] \
        or sorted((o.start, o.required_skill or "") for o in res.open_slots) == [("12:00", "Responsabile"), ("18:00", "")]


def test_within_conta_solo_i_turni_dentro_la_fascia():
    # "Luca, quando lavora, solo turni dentro 18:00-02:00": 16-23 tocca la fascia ma non ci sta dentro.
    grid = [{"role": "Sala", "start": "16:00", "end": "23:00", "headcount": 1},
            {"role": "Sala", "start": "18:00", "end": "01:00", "headcount": 1}]
    code = 'hard(works("luca") == works("luca", within="18:00-02:00"))'
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "horizon_days": 1, "time_limit_seconds": 5,
        "employees": [emp("luca"), emp("bea")], "requirement_rules": grid,
        "rules": [{"id": "w", "label": "w", "code": code, "about": ["luca"]}],
    }))
    luca = [(a.start, a.end) for a in r.assignments if a.employee_id == "luca"]
    assert ("16:00", "23:00") not in luca
    assert not [v for v in r.violations if v.rule_id == "w"]


def test_within_formato_sbagliato_rifiutato():
    res = validate_rules(RulesValidateRequest.model_validate({
        "week_start": MON, "employees": [emp("luca")], "requirement_rules": GRID,
        "rules": [{"id": "w", "label": "w", "code": 'hard(works("luca", within="18") == 0)'}],
    }))
    assert not res.ok and "within" in res.rule_errors[0].error


def test_senza_limite_di_tempo_si_ferma_alla_soluzione_ottima():
    r = solve(SolveRequest.model_validate({
        "week_start": MON, "employees": [emp("anna"), emp("bea")], "requirement_rules": GRID,
    }))
    assert r.status == "OPTIMAL" and len(r.assignments) == 7
