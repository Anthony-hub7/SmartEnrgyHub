"""Export du jeu de donnees horaire pour la prevision (extension 9C, etape 2).

Lit ``analytics_db.hourly_aggregates`` (ecrits par le niveau A, ``AggregateWriter``) et
ecrit un CSV par capteur : ``ml/data/hourly_<sensorId>.csv``. Ces CSV sont l'entree de
``train_forecast.py`` (voir ml/README.md).

Usage :
    python export_dataset.py                          # tous les capteurs, sorties dans ./data/
    python export_dataset.py --sensor SENSOR-001      # un seul capteur
    python export_dataset.py --mongo-uri mongodb://root:root_dev@localhost:27017/analytics_db?authSource=analytics_db

Variables d'environnement (defaut = developpement local, cf. ../.env) :
    MONGO_URI  ex. mongodb://analytics_app:pwd@localhost:27017/analytics_db?authSource=analytics_db
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
from pathlib import Path

from pymongo import MongoClient

DEFAULT_URI = (
    "mongodb://root:{pwd}@localhost:{port}/analytics_db?authSource=admin".format(
        pwd=os.getenv("MONGO_ROOT_PASSWORD", "root_dev"),
        port=os.getenv("MONGO_PORT", "27017"),
    )
)


def _iso_hour(value) -> str:
    """BSON Date -> ISO UTC ; accepte aussi le format EJSON {'$date': ...}."""
    if isinstance(value, dict) and "$date" in value:
        value = value["$date"]
    if hasattr(value, "isoformat"):
        text = value.isoformat()
    else:
        text = str(value)
    return text.replace("+00:00", "Z") if text.endswith("+00:00") else text + ("Z" if "T" in text and not text.endswith("Z") else "")


def export(uri: str, sensor: str | None, out_dir: Path) -> dict[str, int]:
    """Exporte hourly_aggregates -> un CSV par capteur. Retourne {sensorId: nb_lignes}."""
    client = MongoClient(uri, serverSelectionTimeoutMS=5000)
    coll = client["analytics_db"]["hourly_aggregates"]

    query = {"sensorId": sensor} if sensor else {}
    counts: dict[str, int] = {}
    out_dir.mkdir(parents=True, exist_ok=True)
    handles: dict[str, tuple[object, csv.writer]] = {}

    try:
        for doc in coll.find(query).sort([("sensorId", 1), ("hour", 1)]):
            sid = doc["sensorId"]
            if sid not in handles:
                path = out_dir / f"hourly_{sid}.csv"
                fh = open(path, "w", newline="", encoding="utf-8")
                writer = csv.writer(fh)
                writer.writerow(["hour", "count", "sumPowerKw"])
                handles[sid] = (fh, writer)
                counts[sid] = 0
            _, writer = handles[sid]
            # hour est un BSON Date -> datetime UTC (parfois EJSON dict selon le driver) -> ISO
            writer.writerow([_iso_hour(doc["hour"]), doc["count"], f"{doc['sumPowerKw']:.6f}"])
            counts[sid] += 1
    finally:
        for fh, _ in handles.values():
            fh.close()
        client.close()
    return counts


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Export hourly_aggregates -> CSV par capteur")
    parser.add_argument("--mongo-uri", default=os.getenv("MONGO_URI", DEFAULT_URI))
    parser.add_argument("--sensor", default=None, help="Un seul capteur (defaut : tous)")
    parser.add_argument("--out-dir", default=Path(__file__).parent / "data")
    args = parser.parse_args(argv)

    try:
        counts = export(args.mongo_uri, args.sensor, Path(args.out_dir))
    except Exception as exc:  # noqa: BLE001 - message clair pour l'etudiant
        print(f"ERREUR de lecture MongoDB : {exc}", file=sys.stderr)
        return 1

    if not counts:
        print("Aucune donnee dans hourly_aggregates : lancez d'abord le backfill "
              "(voir simulator/README.md, section « Mode backfill »).", file=sys.stderr)
        return 2
    for sid, n in sorted(counts.items()):
        print(f"{sid} : {n} heures exportees -> {Path(args.out_dir) / f'hourly_{sid}.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
