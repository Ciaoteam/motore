# Ciao Team — motore turni (FastAPI + OR-Tools)

Il motore è **fisso**: tutto ciò che cambia arriva nella richiesta API.
Persone e disponibilità, fabbisogno (anche "dal 1° gennaio un cameriere in più"),
turni esistenti (confermati, congelati, proposti) e **le regole come codice
Python** (vibecoding del titolare e, nei loro limiti, dei dipendenti).

Una regola nuova, anche bizzarra, non richiede modifiche al motore: è un pezzo
di codice in più nella richiesta. La versione precedente (Timefold, Java) è nel
branch e nel tag `timefold-archive`.

## Flusso complessivo

```
messaggio ──► Lovable: parser AI ──► dati strutturati + codice delle regole ──► motore
                   calendario UI ◄── stessi dati strutturati ◄──────────────────┘
```

1. **Fabbisogno.** Regole con validità (`requirement_rules`) + eccezioni per data
   (`requirements`). `POST /requirements/resolve` restituisce il fabbisogno
   effettivo giorno per giorno: lo usano il parser (per capire "sera" = quali
   fasce) e il calendario.
2. **Memoria del titolare.** Ogni frase diventa una `CodeRule` (codice Python +
   frase originale in `label`). Prima di salvarla l'app la prova con
   `POST /rules/validate`: se il codice è sbagliato torna l'errore da correggere.
   `about` dice chi riguarda: una sola persona = regola personale.
3. **Disponibilità prima della generazione.** Il dipendente la manda liberamente;
   l'app la salva come finestre (`availability` / `unavailable`) e chiama subito
   `POST /availability/check`. Si verificano **solo le regole personali** di quella
   persona ("Marco solo mattine", "Marco 5 turni"), non quelle di coppia o generali
   ("Marco mai con Marta" la risolve il motore in generazione). Con
   `compatible=false` l'app apre un punto in sospeso al titolare con `conflicts`.
   `unmatched_windows` = finestre che non toccano nessuna fascia del fabbisogno, da
   chiarire. Se è il titolare a modificare la disponibilità, l'app avvisa il
   dipendente (dopo la conferma del titolare sul testo).
4. **Generazione.** `POST /solve` (o `/jobs` in background) con tutto: persone,
   fabbisogno, turni esistenti, regole. Risposta: assegnazioni (con `fixed`,
   `status`, `ref`), posti scoperti, posti a rischio (congelati/proposti),
   violazioni delle regole, regole scartate per errore, riepilogo per persona.
5. **Dopo la generazione.** Il dipendente non cambia più la disponibilità
   liberamente: la richiesta diventa un punto in sospeso che il titolare approva.
   Se approvata, i turni incompatibili diventano `frozen` (posto riservato, non
   copertura certa) finché il titolare decide. Per sostituirli c'è
   `POST /gaps/candidates`. Un turno che il titolare vorrebbe dare a qualcuno, in
   attesa del suo sì, è `proposed`.
6. **Buchi e disponibilità aggiuntiva.** Fabbisogno aumentato, assenze,
   generazione incompleta: `POST /gaps/candidates` con il calendario attuale come
   `fixed_assignments`. Per ogni buco i candidati sono ordinati: prima chi è già
   disponibile (si assegna), poi chi resta nei suoi limiti, poi chi ha lavorato
   meno. Per ognuno, le regole personali che violerebbe. `candidate_filter` sceglie
   a chi chiedere: ruoli, persone, solo tappabuchi o solo dipendenti normali.
7. **Rigenerare solo alcuni giorni.** `plan_dates`: il fabbisogno degli altri giorni
   non entra, i loro turni restano come contesto (ore, riposi, regole).

## API

| Metodo | Percorso | A cosa serve |
|---|---|---|
| GET | `/health` | stato del servizio (senza chiave) |
| POST | `/solve` | genera e risponde a calcolo finito |
| POST | `/jobs` → GET `/jobs/{id}` | come `/solve`, in background (job tenuti un'ora) |
| POST | `/availability/check` | disponibilità di una persona contro le sue regole personali |
| POST | `/gaps/candidates` | buchi e posti a rischio, con chi potrebbe coprirli |
| POST | `/requirements/resolve` | fabbisogno effettivo per data |
| POST | `/rules/validate` | prova le regole (anche di fabbisogno) senza generare |
| POST | `/requirements/preview` | fabbisogno effettivo con regole di fabbisogno in Python ed eventi, più gli errori |
| POST | `/schedule/check` | il calendario così com'è contro regole e fabbisogno: violazioni e posti scoperti |

Schemi completi e prova interattiva su `/docs`. Se è impostata la variabile
`API_KEY`, ogni chiamata (salvo `/health`) vuole `x-api-key: <chiave>` oppure
`Authorization: Bearer <chiave>`.

### Esempio minimo

```json
{
  "week_start": "2026-12-28",
  "employees": [
    {"id": "marco", "name": "Marco Rossi", "roles": ["Cameriere"], "skills": ["Responsabile"],
     "availability": [{"date": "2026-12-28"}, {"date": "2026-12-29", "start": "17:00", "end": "23:59"}]},
    {"id": "marta", "name": "Marta Bianchi", "roles": ["Cameriere"], "max_shifts_per_day": 1}
  ],
  "requirement_rules": [
    {"role": "Cameriere", "start": "19:00", "end": "23:00", "headcount": 2},
    {"role": "Cameriere", "start": "19:00", "end": "23:00", "mode": "add", "headcount": 1, "valid_from": "2027-01-01"}
  ],
  "fixed_assignments": [
    {"employee_id": "marta", "date": "2026-12-28", "start": "19:00", "end": "23:00", "role": "Cameriere",
     "status": "frozen", "ref": "turno-123"}
  ],
  "rules": [
    {"id": "m-mattine", "label": "Marco solo mattine", "about": ["marco"],
     "code": "hard(works('Marco', category=EVENING) == 0)"}
  ]
}
```

### Regole sempre attive (fatti, non preferenze)

- il ruolo del turno è fra i ruoli della persona; la competenza "required" è posseduta;
- il turno è interamente dentro `availability` (null = sempre disponibile, `[]` = mai) e fuori da `unavailable`;
- niente turni sovrapposti, salvo `allow_overlap` (per tutti in `settings` o per persona);
- riposo minimo **fra una giornata e la successiva** (`min_rest_minutes`, per tutti o per persona); pranzo e cena dello stesso giorno non ne sono soggetti;
- al massimo `max_shifts_per_day` turni al giorno (default 2 = spezzato ammesso, con una piccola penalità; 1 per chi non lo fa);
- i turni esistenti restano alla loro persona; `auto_assign: false` (tappabuchi) = mai assegnato dal motore.

Se le regole `hard` non stanno insieme, il motore risolve di nuovo trattandole
come preferenze fortissime: `relaxed_hard: true`, e `violations` dice quali non è
riuscito a rispettare.

## Libreria delle regole

Il codice di una regola è Python, ma solo un sottoinsieme sicuro: niente
`import`, `def`/`class`/`lambda`, `while`, `try`/`with`, niente nomi o attributi che
iniziano con `_`, niente `format`, al massimo 200.000 passi. Ogni regola viene
provata prima su un modello di prova: se fallisce non tocca il calcolo e finisce in
`rule_errors`. Builtins ammessi: `len sum min max abs any all sorted enumerate zip
list set dict tuple int round str bool range date timedelta`.

**Dati:** `shifts` (posti: `id date start end role skill category minutes weekday
fixed status held_by`), `employees` (`id name roles skills hourly_cost_cents
auto_assign`), `dates` (giorni pianificati), `week_start`, `MORNING`, `EVENING`.

**Filtri** (in tutte le funzioni che li accettano): `date`, `dates`, `days`
(0=lunedì … 6=domenica), `start`/`end` (fascia: conta il turno che la tocca),
`role`, `skill` (competenza richiesta dal turno), `category`, `status`.

**Persone:** un id, un nome ("Marco"), `emp("Marco")`, una lista, oppure niente = tutti.

| Funzione | Restituisce |
|---|---|
| `emp(chi)`, `employees_where(role=, skill=)`, `shifts_where(**filtri)` | persone / posti |
| `works(chi, **filtri)` | numero di turni assegnati |
| `minutes(chi, **filtri)`, `cost(chi, **filtri)` | minuti lavorati, costo in centesimi |
| `works_on(chi, giorno, **filtri)` | 1 se lavora quel giorno (nella fascia) |
| `days_worked(chi, **filtri)` | giorni con almeno un turno |
| `together(a, b, **filtri)` | turni sovrapposti fatti insieme |
| `assigned(posto, chi)`, `covered(posto)` | 0/1 |
| `new_bool()`, `new_int(lo, hi)` | variabili libere |
| `any_of(lista)`, `all_of(lista)`, `max_of(lista)`, `min_of(lista)`, `abs_of(expr)` | combinazioni |
| `hard(cond, msg)`, `soft(cond, peso, msg)` | obbligo / preferenza |
| `hard_if(quando, cond, msg)`, `soft_if(quando, cond, peso, msg)` | obbligo / preferenza condizionata |
| `prefer(expr, peso)`, `avoid(expr, peso)` | più alto è meglio / peggio |
| `balance(lista, peso)` | avvicina i valori (equità) |
| `max_streak(chi, n, msg, weight=None, already=0)` | al massimo n giorni di fila |

### Esempi (frase del titolare → codice)

```python
# "Marco solo mattine"
hard(works("Marco", category=EVENING) == 0)

# "Giulia fa 2 mattine e 3 sere"
hard(works("Giulia", category=MORNING) == 2)
hard(works("Giulia", category=EVENING) == 3)

# "Marco almeno 30 ore (se si può), mai più di 40"
soft(minutes("Marco") >= 30 * 60, 40, "Marco sotto le 30 ore")
hard(minutes("Marco") <= 40 * 60)

# "Marco e Marta mai insieme"; "il nuovo sempre con un senior"
hard(together("Marco", "Marta") == 0)
hard(together("Nuovo", "Anna") + together("Nuovo", "Paolo") >= works("Nuovo"))

# "a cena sempre almeno un responsabile in sala"
for d in dates:
    hard(works(employees_where(skill="Responsabile"), date=d, start="19:00", end="23:00") >= 1)

# "massimo 2 domeniche al mese per Giorgia" (ne ha già fatta 1)
hard(days_worked("Giorgia", days=[6]) <= 2 - 1)

# "un giorno libero a settimana per tutti" / "mai più di 5 giorni di fila"
for e in employees:
    hard(days_worked(e) <= 6)
max_streak(None, 5)

# "se Marco chiude il venerdì, il sabato non apre"
chiude = works_on("Marco", week_start + timedelta(days=4), start="22:00", end="23:59")
hard_if(chiude, works("Marco", date=week_start + timedelta(days=5), category=MORNING) == 0)

# "weekend distribuiti in modo equo, contando chi ne ha fatti di più il mese scorso"
storico = {"marco": 3, "marta": 1, "luca": 2}
balance([works(e, days=[5, 6]) + storico.get(e.id, 0) for e in employees], 30)

# "budget personale della settimana 4.000 €"
hard(cost() <= 400000)
```

**Validità:** `valid_from` / `valid_to` su una regola le fanno vedere solo i giorni (e i
turni) del suo periodo: `dates`, `shifts`, `works()`, `days_worked()`… sono già filtrati.

**Storico** (`history`: turni già lavorati prima del periodo, sola lettura):
`past(chi, since=, until=, **filtri)`, `past_minutes(...)`, `past_days(...)`.
**Altro:** `can_work(chi, **filtri)` = quanti posti potrebbe prendere (per non
chiedere l'impossibile), `is_holiday(d)`, `holiday_name(d)`, `easter(anno)`,
`week_of_month(d)`, `is_last_of_month(d)`, `month_start(d)`, `month_end(d)`,
`ceil_div(a, b)`.

```python
# "massimo 2 domeniche al mese per Giorgia" (con lo storico del mese)
hard(past("Giorgia", since=month_start(week_start), days=[6]) + days_worked("Giorgia", days=[6]) <= 2)

# "Marco almeno 4 turni, se la disponibilità lo permette"
hard(works("Marco") >= min(4, can_work("Marco")))
```

### Fabbisogno in Python (`need_rules`)

Girano dopo `requirement_rules` e prima delle eccezioni per data. Vedono `dates`,
`week_start`, `events` (`date start end kind title guests note weekday`), le
funzioni del calendario qui sopra e:

| Funzione | Effetto |
|---|---|
| `need(giorno, inizio, fine, ruolo, n, skill=None, skill_n=1, strength="required", mode="add")` | n persone in più (`add`) o in tutto (`set`) |
| `close(giorno, start=None, end=None, role=None)` | nessuno serve (tutto il giorno, una fascia, un ruolo) |
| `base(giorno, ruolo=None)` | fasce già calcolate (`start end role headcount skill`) |
| `events_on(giorno)` | eventi di quel giorno |

Una regola che fallisce non tocca nulla (le modifiche si applicano solo a fine
regola) e finisce in `rule_errors`.

```python
# "quando ho gli eventi mi serve 1 cameriere ogni 15 persone"
for e in events:
    if e.kind == "evento" and e.guests:
        need(e.date, e.start or "19:00", e.end or "23:00", "Sala", ceil_div(e.guests, 15))

# "nei festivi siamo chiusi"
for d in dates:
    if is_holiday(d):
        close(d)

# "il primo venerdì del mese inventario: uno in più la mattina"
for d in dates:
    if d.weekday() == 4 and week_of_month(d) == 1:
        need(d, "08:00", "12:00", "Magazzino", 1)
```

**Regole dei dipendenti** (`author: "employee"`): vedono e vincolano solo i propri
turni (`me`), non possono usare `together`, e le loro condizioni sono preferenze
con peso massimo 50 finché il titolare non le approva (`approved: true`).

```python
# "il lunedì preferirei riposare"
soft(works(me, days=[0]) == 0, 30)
```

## Casi coperti (bar / ristorante)

| Situazione | Come |
|---|---|
| Fabbisogno stagionale, eventi, "dal 1° gennaio +1" | `requirement_rules` con `valid_from`/`valid_to`, `mode: add` |
| Serata speciale, chiusura per ferie o festività | eccezione in `requirements` (`set` 0 oppure `add`) |
| Picchi orari (12-14 in quattro, 14-15 in due) | più fasce nel fabbisogno |
| Spezzato pranzo + cena, chi non lo fa | `max_shifts_per_day` |
| Turni dopo mezzanotte | fine ≤ inizio = giorno dopo |
| Riposo fra giornate, diverso per persona | `min_rest_minutes` |
| Part-time, ore min/max, straordinari, budget | `minutes`, `cost` |
| Minori o apprendisti (niente notte, massimo ore al giorno) | `works(..., start=, end=)`, `minutes(..., date=)` |
| Giorno libero settimanale, giorni di fila | `days_worked`, `max_streak` |
| Responsabile o chiavi sempre presenti | `works(employees_where(skill=...), ...)` |
| Affiancamento o incompatibilità | `together` |
| Equità su weekend, chiusure, domeniche (anche con lo storico) | `balance` |
| Limiti mensili | costanti nel codice (già fatto nel mese) |
| Disponibilità parziale, ferie, malattia | `availability`, `unavailable` |
| Tappabuchi o personale a chiamata | `auto_assign: false` + `/gaps/candidates` |
| Turni messi a mano, congelati, proposti | `fixed_assignments.status` |
| Rifare solo alcuni giorni | `plan_dates` |
| Malattia dell'ultimo minuto, buchi | `/gaps/candidates` |

**Non ancora supportato:** turni a orario flessibile, dove il motore sceglie anche
l'orario d'inizio (oggi le fasce le dà il fabbisogno), e sedi distinte (si può
rappresentare la sede nel ruolo, es. "Cameriere Centro").

## Sviluppo locale

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
.venv/bin/uvicorn app.main:app --reload
```

Con Docker:

```bash
docker build -t ciaoteam-motore . && docker run --rm -p 8000:8000 -e API_KEY=prova ciaoteam-motore
```

## Deploy su Railway

`Dockerfile` nella radice; `railway.json` imposta il controllo di salute su
`/health`. Variabili: `API_KEY` (consigliata), `SOLVER_WORKERS` (job in parallelo,
default 2), `SOLVER_THREADS` (thread CP-SAT per calcolo, default 4). Railway passa la
porta in `PORT`. Memoria: un job si cancella appena letto (al massimo dopo 10 minuti,
non più di 50 in memoria) e dopo ogni calcolo la memoria torna al sistema.
