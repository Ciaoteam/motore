"""Festività nazionali italiane (per le regole: is_holiday, holiday_name, easter)."""

from __future__ import annotations

from datetime import date, timedelta
from functools import lru_cache
from typing import Optional

_FIXED = {
    (1, 1): "Capodanno",
    (1, 6): "Epifania",
    (4, 25): "Festa della Liberazione",
    (5, 1): "Festa del Lavoro",
    (6, 2): "Festa della Repubblica",
    (8, 15): "Ferragosto",
    (11, 1): "Ognissanti",
    (12, 8): "Immacolata",
    (12, 25): "Natale",
    (12, 26): "Santo Stefano",
}


@lru_cache(maxsize=64)
def easter(year: int) -> date:
    """Domenica di Pasqua (calendario gregoriano, algoritmo di Meeus/Jones/Butcher)."""
    a = year % 19
    b, c = divmod(year, 100)
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    l = (32 + 2 * e + 2 * i - h - k) % 7  # noqa: E741
    m = (a + 11 * h + 22 * l) // 451
    month, day = divmod(h + l - 7 * m + 114, 31)
    return date(year, month, day + 1)


def holiday_name(d: date) -> Optional[str]:
    if (d.month, d.day) in _FIXED:
        return _FIXED[(d.month, d.day)]
    e = easter(d.year)
    if d == e:
        return "Pasqua"
    if d == e + timedelta(days=1):
        return "Pasquetta"
    return None


def is_holiday(d: date) -> bool:
    return holiday_name(d) is not None


def week_of_month(d: date) -> int:
    """Quale occorrenza è questo giorno della settimana nel mese (1 = il primo lunedì, ...)."""
    return (d.day - 1) // 7 + 1


def is_last_of_month(d: date) -> bool:
    """True se è l'ultima occorrenza di quel giorno della settimana nel mese."""
    return (d + timedelta(days=7)).month != d.month


def month_start(d: date) -> date:
    return d.replace(day=1)


def month_end(d: date) -> date:
    nxt = (d.replace(day=28) + timedelta(days=4)).replace(day=1)
    return nxt - timedelta(days=1)
