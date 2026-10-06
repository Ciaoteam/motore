from datetime import date

from app.models import AvailabilityCheckRequest, GapCandidatesRequest, RulesValidateRequest, SolveRequest
from app.solver import check_availability, gap_candidates, resolve_requirements, solve, validate_rules

MON = "2026-12-28"  # lunedì: la settimana attraversa il 1° gennaio 2027
TUE, WED = "2026-12-29", "2026-12-30"


def emp(id_, roles=("Cameriere",), **kw):
    return {"id": id_, "name": id_.capitalize(), "roles": list(roles), **kw}


def need(d, start="18:00", end="23:00", role="Cameriere", n=1, **kw):
    return {"date": d, "start": start, "end": end, "role": role, "headcount": n, **kw}


def rule(id_, code, about=(), **kw):
    return {"id": id_, "label": id_, "code": code, "about": list(about), **kw}


def req(**kw):
    base = {"time_limit_seconds": 5, "week_start": MON, "employees": []}
    base.update(kw)
    return SolveRequest.model_validate(base)


def by_person(res):
    out: dict = {}
    for a in res.assignments:
        out.setdefault(a.employee_id, []).append(a)
    return out


# ── regole di base ──────────────────────────────────────────────────────────


def test_copre_rispettando_ruolo_e_disponibilita():
    r = solve(req(
        employees=[emp("anna", availability=[{"date": MON, "start": "09:00", "end": "15:00"}]),
                   emp("bea"), emp("cuoco", roles=["Cuoco"])],
        requirements=[need(MON)],
    ))
    assert r.status == "OPTIMAL"
    assert r.assignments[0].employee_id == "bea"


def test_niente_sovrapposizioni_salvo_override_del_titolare():
    reqs = [need(MON, "18:00", "23:00"), need(MON, "20:00", "23:30")]
    assert len(by_person(solve(req(employees=[emp("anna")], requirements=reqs))).get("anna", [])) == 1
    assert len(by_person(solve(req(employees=[emp("anna", allow_overlap=True)], requirements=reqs)))["anna"]) == 2


def test_spezzato_pranzo_e_cena_e_chi_non_lo_fa():
    reqs = [need(MON, "11:30", "15:00"), need(MON, "19:00", "23:00")]
    assert len(by_person(solve(req(employees=[emp("anna")], requirements=reqs)))["anna"]) == 2
    one = solve(req(employees=[emp("anna", max_shifts_per_day=1)], requirements=reqs))
    assert len(by_person(one)["anna"]) == 1


def test_riposo_fra_giornate_personalizzabile():
    reqs = [need(MON, "18:00", "02:00"), need(TUE, "08:00", "14:00")]
    assert len(by_person(solve(req(employees=[emp("anna")], requirements=reqs)))["anna"]) == 1
    assert len(by_person(solve(req(employees=[emp("anna", min_rest_minutes=360)], requirements=reqs)))["anna"]) == 2


def test_tappabuchi_mai_assegnato_dal_motore():
    r = solve(req(employees=[emp("extra", auto_assign=False)], requirements=[need(MON)]))
    assert r.assignments[0].employee_id is None


# ── fabbisogno con date ─────────────────────────────────────────────────────


def test_fabbisogno_dal_primo_gennaio_un_cameriere_in_piu():
    r = req(requirement_rules=[
        {"role": "Cameriere", "start": "19:00", "end": "23:00", "headcount": 2},
        {"role": "Cameriere", "start": "19:00", "end": "23:00", "mode": "add", "headcount": 1,
         "valid_from": "2027-01-01"},
    ])
    n = {x["date"]: x["headcount"] for x in resolve_requirements(r.week_start, 7, r.requirement_rules, [])}
    assert n[date(2026, 12, 31)] == 2 and n[date(2027, 1, 1)] == 3 and n[date(2027, 1, 3)] == 3


def test_regola_piu_recente_vince_ed_eccezione_per_ultima():
    r = req(
        requirement_rules=[
            {"role": "Cuoco", "start": "18:00", "end": "23:00", "headcount": 1},
            {"role": "Cuoco", "start": "18:00", "end": "23:00", "headcount": 2, "valid_from": WED,
             "days_of_week": [2, 3, 4]},
        ],
        requirements=[need("2026-12-31", role="Cuoco", n=-2, mode="add")],
    )
    n = {x["date"]: x["headcount"] for x in resolve_requirements(r.week_start, 7, r.requirement_rules, r.requirements)}
    assert n[date(2026, 12, 29)] == 1 and n[date(2026, 12, 30)] == 2
    assert n[date(2026, 12, 31)] == 0 and n[date(2027, 1, 2)] == 1


def test_solo_alcune_date_gli_altri_giorni_restano_contesto():
    r = solve(req(
        employees=[emp("anna"), emp("bea")],
        requirements=[need(MON), need(TUE)],
        fixed_assignments=[{"employee_id": "anna", "date": MON, "start": "18:00", "end": "23:00", "role": "Cameriere"}],
        plan_dates=[TUE],
    ))
    assert {(str(a.date), a.employee_id, a.fixed) for a in r.assignments} == {
        (MON, "anna", True), (TUE, "bea", False)
    }  # equilibrio: Bea prende martedì perché Anna lavora già lunedì


# ── turni esistenti: confermati, congelati, proposti ────────────────────────


def test_turno_esistente_occupa_il_posto_e_congelato_e_a_rischio():
    r = solve(req(
        employees=[emp("anna"), emp("bea")],
        requirements=[need(MON, n=2)],
        fixed_assignments=[
            {"employee_id": "anna", "date": MON, "start": "18:00", "end": "23:00", "role": "Cameriere", "ref": "s1"},
            {"employee_id": "bea", "date": MON, "start": "18:00", "end": "23:00", "role": "Cameriere",
             "status": "frozen", "ref": "s2"},
        ],
    ))
    assert len(r.assignments) == 2
    assert {a.ref: a.status for a in r.assignments} == {"s1": "confirmed", "s2": "frozen"}
    assert len(r.at_risk_shift_ids) == 1


def test_sostituti_per_un_turno_congelato():
    g = gap_candidates(GapCandidatesRequest.model_validate({
        "week_start": MON, "employees": [emp("anna"), emp("bea"), emp("cuoco", roles=["Cuoco"])],
        "requirements": [need(MON)],
        "fixed_assignments": [{"employee_id": "anna", "date": MON, "start": "18:00", "end": "23:00",
                               "role": "Cameriere", "status": "frozen"}],
    }))
    assert len(g.gaps) == 1 and g.gaps[0].at_risk and g.gaps[0].held_by == "anna"
    assert [c.employee_id for c in g.gaps[0].candidates] == ["bea"]


# ── regole Python (vibecoding) ──────────────────────────────────────────────


def test_regola_personale_solo_mattine():
    r = solve(req(
        employees=[emp("marco"), emp("marta")],
        requirements=[need(MON, "18:00", "23:00"), need(MON, "08:00", "13:00")],
        rules=[rule("solo-mattine", 'hard(works("Marco", category=EVENING) == 0)', about=["marco"])],
    ))
    mine = by_person(r)
    assert [a.start for a in mine["marco"]] == ["08:00"]


def test_regola_bizzarra_se_chiude_il_venerdi_il_sabato_non_apre():
    fri, sat = "2027-01-01", "2027-01-02"
    code = """
chiude = works_on("Marco", date(2027, 1, 1), start="22:00", end="23:59")
hard_if(chiude, works("Marco", date=date(2027, 1, 2), category=MORNING) == 0, "dopo la chiusura niente apertura")
"""
    r = solve(req(
        employees=[emp("marco", min_rest_minutes=0)],
        requirements=[need(fri, "18:00", "23:59"), need(sat, "08:00", "13:00")],
        rules=[rule("chiusura", code, about=["marco"])],
    ))
    assert len(by_person(r)["marco"]) == 1


def test_coppia_mai_insieme_e_equilibrio_weekend():
    code = """
hard(together("Marco", "Marta") == 0)
balance([works(e, days=[5, 6]) for e in employees], 30)
"""
    r = solve(req(
        employees=[emp("marco"), emp("marta"), emp("luca")],
        requirements=[need(MON, n=2)],
        rules=[rule("coppia", code, about=["marco", "marta"])],
    ))
    people = {a.employee_id for a in r.assignments}
    assert not {"marco", "marta"} <= people


def test_regole_hard_incompatibili_rilassate_e_segnalate():
    r = solve(req(
        employees=[emp("anna")], requirements=[need(MON)],
        rules=[rule("troppi", 'hard(works("Anna") >= 3, "Anna deve fare almeno 3 turni")', about=["anna"])],
    ))
    assert r.relaxed_hard is True
    assert [(v.rule_id, v.detail) for v in r.violations] == [("troppi", "Anna deve fare almeno 3 turni")]


def test_regola_del_dipendente_resta_preferenza_finche_non_approvata():
    code = 'hard(works(me, days=[0]) == 0, "il lunedì preferisco riposare")'
    base = dict(employees=[emp("anna")], requirements=[need(MON)])
    r = solve(req(**base, rules=[rule("pref", code, about=["anna"], author="employee", author_employee_id="anna")]))
    assert r.assignments[0].employee_id == "anna"  # copertura > preferenza
    assert r.violations and r.violations[0].severity == "soft"
    r2 = solve(req(**base, rules=[rule("pref", code, about=["anna"], author="employee", author_employee_id="anna",
                                        approved=True)]))
    assert r2.assignments[0].employee_id is None


def test_dipendente_non_puo_toccare_i_turni_altrui():
    v = validate_rules(RulesValidateRequest.model_validate({
        "week_start": MON, "employees": [emp("anna"), emp("bea")], "requirements": [need(MON)],
        "rules": [rule("furbo", 'hard(works("Bea") == 0)', author="employee", author_employee_id="anna")],
    }))
    assert not v.ok and "solo i suoi turni" in v.rule_errors[0].error


def test_sandbox_rifiuta_codice_pericoloso_e_le_altre_regole_valgono():
    bad = [
        rule("import", "import os"),
        rule("dunder", "x = shifts.__class__"),
        rule("format", '"{0.__class__}".format(shifts)'),
        rule("while", "while True:\n    pass"),
        rule("open", "open('/etc/passwd')"),
    ]
    r = solve(req(employees=[emp("anna")], requirements=[need(MON)],
                  rules=bad + [rule("buona", 'hard(works("Anna") <= 1)')]))
    assert {e.rule_id for e in r.rule_errors} == {"import", "dunder", "format", "while", "open"}
    assert r.assignments[0].employee_id == "anna"


def test_regola_infinita_interrotta():
    v = validate_rules(RulesValidateRequest.model_validate({
        "week_start": MON, "employees": [emp("anna")],
        "rules": [rule("lunga", "t = 0\nfor a in range(9999):\n    for b in range(9999):\n        t += 1")],
    }))
    assert not v.ok and "troppo lunga" in v.rule_errors[0].error


# ── controllo disponibilità ─────────────────────────────────────────────────


def test_disponibilita_incompatibile_con_le_aspettative_personali():
    reqs = [need(d) for d in (MON, TUE, WED)]
    rules = [
        rule("3-turni", 'hard(works("Marco") >= 3, "Marco deve fare 3 turni")', about=["marco"]),
        rule("coppia", 'hard(together("Marco", "Marta") == 0)', about=["marco", "marta"]),
    ]
    c = check_availability(AvailabilityCheckRequest.model_validate({
        "week_start": MON, "employee": emp("marco", availability=[{"date": MON}, {"date": TUE}]),
        "requirements": reqs, "rules": rules,
    }))
    assert c.compatible is False
    assert [v.rule_id for v in c.conflicts] == ["3-turni"]  # la regola di coppia non si verifica qui
    assert c.max_shifts == 2


def test_disponibilita_fuori_dal_fabbisogno_da_chiarire():
    c = check_availability(AvailabilityCheckRequest.model_validate({
        "week_start": MON, "employee": emp("marco", availability=[{"date": MON, "start": "08:00", "end": "12:00"}]),
        "requirements": [need(MON)],
    }))
    assert c.compatible and len(c.unmatched_windows) == 1


# ── buchi e disponibilità aggiuntiva ────────────────────────────────────────


def test_buchi_con_candidati_filtrati_e_limiti_segnalati():
    g = gap_candidates(GapCandidatesRequest.model_validate({
        "week_start": MON,
        "employees": [emp("anna", availability=[]), emp("bea"), emp("extra", auto_assign=False),
                      emp("carla", unavailable=[{"date": MON}])],
        "requirements": [need(MON)],
        "rules": [rule("max0", 'hard(works("Bea") <= 0, "Bea niente turni questa settimana")', about=["bea"])],
    }))
    gap = g.gaps[0]
    ids = [c.employee_id for c in gap.candidates]
    assert "carla" not in ids  # indisponibilità certa
    bea = next(c for c in gap.candidates if c.employee_id == "bea")
    assert bea.already_available and bea.would_exceed == ["Bea niente turni questa settimana"]
    only_extra = gap_candidates(GapCandidatesRequest.model_validate({
        "week_start": MON, "employees": [emp("bea"), emp("extra", auto_assign=False)],
        "requirements": [need(MON)], "candidate_filter": {"auto_assign": False},
    }))
    assert [c.employee_id for c in only_extra.gaps[0].candidates] == ["extra"]
