#!/usr/bin/env python3
"""Gera o snapshot de meta de singles a partir das estatisticas de uso do Smogon.

Baixa os arquivos "chaos" (https://www.smogon.com/stats/<mes>/chaos/) dos formatos
de singles, escolhe para cada Pokemon o formato em que ele e mais usado e grava um
JSON compacto em src/main/resources/data/cobblemon_team_builder/meta/singles.json.

Com --doubles gera o snapshot de duplas (doubles.json), usado pelo modo 2v2. Sem ele o
mod usa o snapshot de singles com os pesos de duplas.

Uso:  python tools/build_snapshot.py [--month 2026-10] [--doubles]
"""
import argparse
import json
import re
import shutil
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://www.smogon.com/stats/"
UA = "Mozilla/5.0 (compatible; team-builder-snapshot)"
ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / ".cache"
OUT = ROOT / "src/main/resources/data/cobblemon_team_builder/meta/singles.json"
OUT_DOUBLES = ROOT / "src/main/resources/data/cobblemon_team_builder/meta/doubles.json"

# Ordem = prioridade em empates. "tier" e a forca relativa do formato (0 = mais forte).
FORMATS = [
    ("gen9nationaldex", 1),
    ("gen9nationaldexubers", 0),
    ("gen9nationaldexuu", 2),
    ("gen9ou", 1),
    ("gen9ubers", 0),
    ("gen9uu", 2),
    ("gen9ru", 3),
    ("gen9nu", 4),
    ("gen9pu", 5),
    ("gen9zu", 6),
]
# Duplas: alem destes, entram os formatos VGC do mes (os nomes mudam a cada regulamento).
FORMATS_DOUBLES = [
    ("gen9doublesou", 1),
    ("gen9doublesubers", 0),
    ("gen9doublesuu", 2),
    ("gen9nationaldexdoubles", 1),
]
CUTOFF = 1500
MIN_RAW = 30          # ignora entradas com amostra pequena demais
TOP_MOVES = 14
TOP_ITEMS = 5
TOP_ABILITIES = 3
TOP_SPREADS = 4
TOP_TEAMMATES = 25
TOP_COUNTERS = 12


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()
    except urllib.error.URLError as e:
        # Alguns Pythons no Windows falham na validacao do certificado por causa do
        # repositorio de certificados local. O curl do sistema valida pelo do Windows.
        if not isinstance(e.reason, ssl.SSLError) or not shutil.which("curl"):
            raise
        return subprocess.run(["curl", "-sSf", "-A", UA, url], check=True,
                              capture_output=True, timeout=300).stdout


def latest_month() -> str:
    html = fetch(BASE).decode("utf-8", "replace")
    months = sorted(set(re.findall(r'href="(\d{4}-\d{2})/"', html)))
    if not months:
        sys.exit("Nao foi possivel listar os meses em " + BASE)
    return months[-1]


def vgc_formats(month: str):
    """Formatos VGC de geracao 9 disponiveis no mes (ex.: gen9vgc2026regf)."""
    html = fetch(f"{BASE}{month}/chaos/").decode("utf-8", "replace")
    names = sorted(set(re.findall(rf'href="(gen9vgc[a-z0-9]+)-{CUTOFF}\.json"', html)))
    # bo3 e variantes repetem o mesmo meta; fica so com os principais
    return [(n, 1) for n in names if "bo3" not in n]


def load_format(month: str, fmt: str):
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / f"{month}-{fmt}-{CUTOFF}.json"
    if not cached.exists():
        url = f"{BASE}{month}/chaos/{fmt}-{CUTOFF}.json"
        try:
            cached.write_bytes(fetch(url))
        except Exception as e:  # formato pode nao existir no mes
            print(f"  [pulado] {fmt}: {e}")
            return None
    return json.loads(cached.read_text(encoding="utf-8"))


def to_id(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def top(d: dict, weight: float, n: int, min_frac: float = 0.01, skip=("", "nothing")):
    items = [(to_id(k), v / weight) for k, v in d.items() if to_id(k) not in skip]
    items = [(k, round(f, 4)) for k, f in sorted(items, key=lambda x: -x[1]) if f >= min_frac]
    return dict(items[:n])


def build_entry(name: str, e: dict, fmt: str, tier: int):
    weight = sum(e["Abilities"].values())
    if weight <= 0:
        return None
    spreads = sorted(e["Spreads"].items(), key=lambda x: -x[1])[:TOP_SPREADS]
    counters = []
    for other, c in e.get("Checks and Counters", {}).items():
        if c["n"] < 20:
            continue
        score = c["p"] - 4 * c["d"]  # mesma formula usada pelo Smogon
        if score > 0.5:
            counters.append((to_id(other), round(score, 3)))
    counters.sort(key=lambda x: -x[1])
    return {
        "name": name,
        "format": fmt,
        "tier": tier,
        "usage": round(e["usage"], 5),
        "moves": top(e["Moves"], weight, TOP_MOVES),
        "items": top(e["Items"], weight, TOP_ITEMS),
        "abilities": top(e["Abilities"], weight, TOP_ABILITIES),
        "spreads": [
            {"nature": s.split(":")[0].lower(),
             "evs": [int(x) for x in s.split(":")[1].split("/")],
             "freq": round(v / weight, 4)}
            for s, v in spreads if v / weight >= 0.02
        ],
        # P(parceiro no time | este Pokemon no time)
        "teammates": top(e["Teammates"], weight, TOP_TEAMMATES, min_frac=0.03),
        "counters": dict(counters[:TOP_COUNTERS]),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--month", help="AAAA-MM (padrao: mes mais recente)")
    ap.add_argument("--doubles", action="store_true", help="gera o snapshot de duplas (2v2)")
    args = ap.parse_args()
    month = args.month or latest_month()
    print(f"Mes: {month}")
    formats = FORMATS
    out_path = OUT
    if args.doubles:
        formats = FORMATS_DOUBLES + vgc_formats(month)
        out_path = OUT_DOUBLES

    species = {}
    # Uso de cada Pokemon em cada formato: o motor precisa disso para calcular "lift"
    # (o quanto um parceiro aparece acima do esperado).
    format_usage = {}
    for fmt, tier in formats:
        data = load_format(month, fmt)
        if data is None:
            continue
        print(f"  {fmt}: {len(data['data'])} Pokemon, {data['info']['number of battles']} batalhas")
        format_usage[fmt] = {to_id(n): round(e["usage"], 5)
                             for n, e in data["data"].items() if e["usage"] >= 0.0005}
        for name, e in data["data"].items():
            if e["Raw count"] < MIN_RAW:
                continue
            sid = to_id(name)
            cur = species.get(sid)
            # fica com o formato em que o Pokemon e mais usado
            if cur is None or e["usage"] > cur["usage"]:
                entry = build_entry(name, e, fmt, tier)
                if entry:
                    species[sid] = entry

    out = {"month": month, "cutoff": CUTOFF, "formatUsage": format_usage, "species": species}
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
    print(f"{len(species)} Pokemon -> {out_path} ({out_path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
