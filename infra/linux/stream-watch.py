#!/usr/bin/env python3
# ============================================================
# SmartEnergy Hub - Moniteur du stream Redis en direct (XREAD json)
# Usage : ./infra/linux/stream-watch.sh   (Ctrl+C pour arreter)
# Affiche chaque mesure du tail du stream (XREAD BLOCK, HORS
# consumer group : lecture seule, aucun ACK - decision D-21)
# + un rappel XLEN / pending / RAM toutes les 10 s.
# Mot de passe Redis lu dans .env a la racine du depot (ENF-02 :
# rien d'importe dans le depot; le mot de passe reste hors chmod).
# Variables d'environnement optionnelles :
#   SE_REDIS_CONTAINER  nom du conteneur Redis (defaut smartenergy-hub-redis-1)
#   SE_STREAM_KEY       cle du stream (defaut energy-measurements)
#   SE_STATS_INTERVAL   periode du rappel de stats en s (defaut 10)
# Principes : Python 3 stdlib uniquement, docker exec redis-cli --json
# ============================================================
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEY = os.environ.get("SE_STREAM_KEY", "energy-measurements")
CONTAINER = os.environ.get("SE_REDIS_CONTAINER", "smartenergy-hub-redis-1")
STATS_INTERVAL = float(os.environ.get("SE_STATS_INTERVAL", "10"))
BLOCK_MS = "5000"


def get_password():
    """Lit REDIS_PASSWORD dans .env (raccourci grep : la ligne contient des
    espaces internes qui cassent un sourcing bash complet, cf infra/README)."""
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if line.startswith("REDIS_PASSWORD="):
                value = line.split("=", 1)[1].strip().strip('"').strip("'")
                if value:
                    return value
    pw = os.environ.get("REDIS_PASSWORD", "")
    if pw:
        return pw
    raise SystemExit(
        f"REDIS_PASSWORD introuvable : {env_file} absent ou sans REDIS_PASSWORD"
    )


RPW = get_password()
RED = ["docker", "exec", CONTAINER, "redis-cli", "-a", RPW]


def cli_json(*args):
    """redis-cli --json : reponse JSON (retourne None si vide)."""
    out = subprocess.run(RED + ["--json"] + list(args), capture_output=True,
                         text=True, stdin=subprocess.DEVNULL).stdout.strip()
    return json.loads(out) if out else None


def cli_text(*args):
    return subprocess.run(RED + list(args), capture_output=True, text=True,
                          stdin=subprocess.DEVNULL).stdout.strip()


def stat_line():
    xlen = cli_text("XLEN", KEY) or "?"
    dlq = cli_text("XLEN", KEY + "-dlq") or "?"
    pend = {}
    for g in ("storage-group", "analytics-group"):
        v = cli_text("XPENDING", KEY, g)
        first = (v.splitlines()[0].replace("(integer)", "").strip() if v else "")
        pend[g] = first or "0"
    mem = [l.split(":", 1)[1].rstrip() for l in cli_text("INFO", "memory").splitlines()
           if l.startswith("used_memory_human")]
    return (f"XLEN={xlen}  DLQ={dlq}  "
            f"pending storage={pend['storage-group']} "
            f"analytics={pend['analytics-group']}  "
            f"ram={mem[0] if mem else '?'}")


def main():
    print(f"Moniteur du stream Redis ({KEY}, conteneur {CONTAINER}) - "
          "Ctrl+C pour arreter\n", flush=True)
    print(f"[etat initial] {stat_line()}", flush=True)
    ids = cli_json("XREVRANGE", KEY, "+", "-", "COUNT", "1")
    last = ids[0][0] if ids else "0-0"
    last_stat = time.time()
    while True:
        data = cli_json("XREAD", "BLOCK", BLOCK_MS, "COUNT", "20",
                        "STREAMS", KEY, last)
        if data:
            # --json : dict {cle: [[id, [k, v, ...]], ...]]} pour XREAD
            entries = (list(data.values())[0]
                       if isinstance(data, dict) else data[0][1])
            for ent in entries:
                last = ent[0]
                f = ent[1]
                if isinstance(f, list):  # paires plates [k, v, k, v, ...]
                    f = {f[j]: f[j + 1] for j in range(0, len(f), 2)}
                sid = f.get("sensorId", "?")
                pw = f.get("powerKw", f.get("power", "?"))
                ts = f.get("timestamp", "")
                print(f"{last:<20}{sid:<12}{pw:>9} kW  {ts}", flush=True)
        if time.time() - last_stat >= STATS_INTERVAL:
            print(f"[stat] {stat_line()}", flush=True)
            last_stat = time.time()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nArret du moniteur.")
        sys.exit(0)
