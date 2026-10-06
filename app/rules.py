"""Sandbox per le regole scritte in Python (vibecoding del titolare e dei dipendenti).

Il codice di una regola NON è Python libero: è un sottoinsieme controllato.
- Prima dell'esecuzione l'albero sintattico viene verificato: niente import,
  def/class/lambda, while, try/with, global, accesso ad attributi o nomi che
  iniziano con "_", niente chiamate a funzioni non fornite.
- Gira con un dizionario di builtins ridotto e la sola libreria del motore.
- Un contatore di passi interrompe i programmi troppo lunghi.

La libreria (oggetti `shifts`, `employees`, `dates` e funzioni `works`,
`count`, `minutes`, `days_worked`, `hard`, `soft`, `prefer`, ...) è costruita
dal motore per ogni richiesta: vedi solver._RuleApi e il README.
"""

from __future__ import annotations

import ast
import sys
from datetime import date, timedelta

MAX_STEPS = 200_000
MAX_RANGE = 10_000

_ALLOWED_NODES = (
    ast.Module, ast.Expr, ast.Assign, ast.AugAssign, ast.AnnAssign, ast.For, ast.If, ast.Pass, ast.Break,
    ast.Continue, ast.Compare, ast.BoolOp, ast.BinOp, ast.UnaryOp, ast.Call, ast.keyword, ast.Name, ast.Load,
    ast.Store, ast.Constant, ast.List, ast.Tuple, ast.Dict, ast.Set, ast.ListComp, ast.SetComp, ast.DictComp,
    ast.GeneratorExp, ast.comprehension, ast.Subscript, ast.Slice, ast.Attribute, ast.IfExp, ast.JoinedStr,
    ast.FormattedValue, ast.Starred,
    # operatori
    ast.And, ast.Or, ast.Not, ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.USub, ast.UAdd,
    ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn, ast.Is, ast.IsNot,
)


# format/format_map possono attraversare attributi nascosti tramite la stringa
# di formato ("{0.__class__}"): vietati anche se non iniziano con "_".
_BLOCKED_ATTRS = {"format", "format_map", "mro", "gi_frame", "gi_code", "f_globals", "f_locals", "f_back"}


class RuleRejected(Exception):
    """Il codice usa qualcosa che la sandbox non permette."""


class RuleTooLong(Exception):
    """Il codice ha superato il numero massimo di passi."""


def _safe_range(*args):
    r = range(*args)
    if len(r) > MAX_RANGE:
        raise RuleRejected(f"range troppo grande (massimo {MAX_RANGE})")
    return r


SAFE_BUILTINS = {
    "len": len, "sum": sum, "min": min, "max": max, "abs": abs, "any": any, "all": all, "sorted": sorted,
    "enumerate": enumerate, "zip": zip, "list": list, "set": set, "dict": dict, "tuple": tuple, "int": int,
    "round": round, "str": str, "bool": bool, "range": _safe_range, "True": True, "False": False, "None": None,
    "date": date, "timedelta": timedelta,
}


def check(code: str, allowed_names: set[str]) -> ast.Module:
    """Rifiuta tutto ciò che non è nel sottoinsieme permesso. Ritorna l'albero."""
    try:
        tree = ast.parse(code, mode="exec")
    except SyntaxError as e:
        raise RuleRejected(f"errore di sintassi alla riga {e.lineno}: {e.msg}") from None
    assigned: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            assigned.add(node.id)
    for node in ast.walk(tree):
        if not isinstance(node, _ALLOWED_NODES):
            raise RuleRejected(f"costrutto non permesso: {type(node).__name__} (riga {getattr(node, 'lineno', '?')})")
        if isinstance(node, ast.Attribute) and (node.attr.startswith("_") or node.attr in _BLOCKED_ATTRS):
            raise RuleRejected(f"attributo non permesso: {node.attr}")
        if isinstance(node, ast.Name):
            if node.id.startswith("_"):
                raise RuleRejected(f"nome non permesso: {node.id}")
            if isinstance(node.ctx, ast.Load) and node.id not in allowed_names and node.id not in assigned \
                    and node.id not in SAFE_BUILTINS:
                raise RuleRejected(f"nome sconosciuto: {node.id}")
    return tree


def run(code: str, api: dict) -> None:
    """Esegue il codice verificato con la sola libreria `api`."""
    tree = check(code, set(api))
    compiled = compile(tree, "<regola>", "exec")
    env = {"__builtins__": dict(SAFE_BUILTINS), **api}
    steps = [0]

    def tracer(frame, event, arg):
        if frame.f_code.co_filename != "<regola>":
            return None
        if event in ("line", "call"):
            steps[0] += 1
            if steps[0] > MAX_STEPS:
                raise RuleTooLong(f"regola troppo lunga (oltre {MAX_STEPS} passi)")
        return tracer

    previous = sys.gettrace()
    sys.settrace(tracer)
    try:
        exec(compiled, env)  # noqa: S102 — codice verificato da check(), builtins ridotti
    finally:
        sys.settrace(previous)
