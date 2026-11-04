#!/usr/bin/env python3
"""Simulateur de capteurs energetiques en Python (stdlib uniquement).

Genere N capteurs et envoie une mesure par intervalle vers le Collector Service
POST http://<host>:<port>/api/measurements

Modele de puissance :
    Pt = Pbase + A * sin(2*pi*t / T) + bruit gaussien

Scenario d'anomalie : la puissance est multipliee par un facteur sur une fenetre
configurable pour un capteur donne.

Variables d'environnement (priorite faible, remplacees par les options CLI) :
    SIMULATOR_COLLECTOR_URL, SIMULATOR_SENSORS, SIMULATOR_INTERVAL,
    SIMULATOR_DURATION, SIMULATOR_PBASE, SIMULATOR_AMPLITUDE,
    SIMULATOR_PERIOD, SIMULATOR_NOISE, SIMULATOR_ANOMALY,
    SIMULATOR_ANOMALY_SENSOR, SIMULATOR_ANOMALY_START,
    SIMULATOR_ANOMALY_DURATION, SIMULATOR_ANOMALY_FACTOR
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone


DEFAULT_URL = "http://localhost:8081/api/measurements"
DEFAULT_SENSORS = 5
DEFAULT_INTERVAL = 2.0
DEFAULT_DURATION = 120.0
DEFAULT_PBASE = 3.0
DEFAULT_AMPLITUDE = 1.5
DEFAULT_PERIOD = 60.0
DEFAULT_NOISE = 0.2

DEFAULT_ANOMALY = False
DEFAULT_ANOMALY_SENSOR = "SENSOR-001"
DEFAULT_ANOMALY_START = 30.0
DEFAULT_ANOMALY_DURATION = 20.0
DEFAULT_ANOMALY_FACTOR = 3.0

VOLTAGE = 226.4
TEMPERATURE = 29.1
CURRENT_A = 10.0
FREQUENCY_HZ = 50.0
STATE = "ON"


def env_float(name, default):
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def env_int(name, default):
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def env_bool(name, default):
    return os.environ.get(name, str(default)).lower() in ("1", "true", "yes", "on")


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_payload(sensor_id, t, power_kw, anomaly_active, anomaly_factor):
    p = power_kw
    if anomaly_active:
        p = p * anomaly_factor
    p = max(0.0, min(1000.0, round(p, 3)))
    return {
        "sensorId": sensor_id,
        "timestamp": now_iso(),
        "powerKw": p,
        "voltage": VOLTAGE,
        "temperature": TEMPERATURE,
        "currentA": CURRENT_A,
        "frequencyHz": FREQUENCY_HZ,
        "state": STATE,
    }


def post(url, payload, timeout=5.0):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers)
    except Exception:
        return None, {}


def run(args):
    sensors = [f"SENSOR-{i+1:03d}" for i in range(args.sensors)]
    rng = random.Random(args.seed)
    phases = {s: rng.uniform(0.0, 2 * math.pi) for s in sensors}
    pbase = {s: args.pbase + rng.uniform(-0.3, 0.3) for s in sensors}

    start = time.monotonic()
    end = start + args.duration if args.duration > 0 else None

    counts = {"sent": 0, "accepted": 0, "rejected": 0, "errors": 0}
    anomaly_logged = False

    tick = 0
    while True:
        now = time.monotonic()
        if end is not None and now >= end:
            break

        elapsed = now - start
        anomaly_active_window = (
            args.anomaly
            and args.anomaly_start <= elapsed <= args.anomaly_start + args.anomaly_duration
        )

        for sensor_id in sensors:
            tick += 1
            t = elapsed
            base = pbase[sensor_id]
            signal = base + args.amplitude * math.sin(2 * math.pi * t / args.period + phases[sensor_id])
            noise = rng.gauss(0.0, args.noise)
            power = signal + noise

            anomaly_active = (
                anomaly_active_window
                and sensor_id == args.anomaly_sensor
                and not anomaly_logged
            )
            if anomaly_active and not anomaly_logged:
                print(
                    f"[ANOMALIE] debut capteur={sensor_id} "
                    f"t={elapsed:.1f}s facteur={args.anomaly_factor} "
                    f"fenetre=[{args.anomaly_start},{args.anomaly_start+args.anomaly_duration}]s",
                    flush=True,
                )
                anomaly_logged = True

            payload = build_payload(sensor_id, t, power, anomaly_active, args.anomaly_factor)
            status, _headers = post(args.url, payload)
            counts["sent"] += 1
            if status == 202:
                counts["accepted"] += 1
            elif status is not None and 400 <= status < 500:
                counts["rejected"] += 1
            else:
                counts["errors"] += 1

            if counts["sent"] % 50 == 0:
                print(
                    f"progres sent={counts['sent']} accepted={counts['accepted']} "
                    f"rejected={counts['rejected']} errors={counts['errors']}",
                    flush=True,
                )

        time.sleep(args.interval)

    print("=== Resume simulateur ===")
    print(f"capteurs      : {args.sensors}")
    print(f"intervalle    : {args.interval}s")
    print(f"duree         : {args.duration}s")
    print(f"envoyees      : {counts['sent']}")
    print(f"acceptees 202 : {counts['accepted']}")
    print(f"rejetees 4xx  : {counts['rejected']}")
    print(f"erreurs       : {counts['errors']}")
    print(f"anomalie      : {'active' if args.anomaly else 'desactivee'}")
    if args.anomaly:
        print(f"  capteur     : {args.anomaly_sensor}")
        print(f"  fenetre     : [{args.anomaly_start},{args.anomaly_start+args.anomaly_duration}]s")
        print(f"  facteur     : {args.anomaly_factor}")

    return 0 if counts["errors"] == 0 else 1


def build_parser():
    p = argparse.ArgumentParser(
        description="Simulateur de capteurs energetiques vers le Collector Service."
    )
    p.add_argument("--url", default=os.environ.get("SIMULATOR_COLLECTOR_URL", DEFAULT_URL),
                   help="URL du collector (défaut %(default)s)")
    p.add_argument("--sensors", type=int, default=env_int("SIMULATOR_SENSORS", DEFAULT_SENSORS),
                   help="Nombre de capteurs (défaut %(default)s)")
    p.add_argument("--interval", type=float, default=env_float("SIMULATOR_INTERVAL", DEFAULT_INTERVAL),
                   help="Intervalle entre envois en secondes (défaut %(default)s)")
    p.add_argument("--duration", type=float, default=env_float("SIMULATOR_DURATION", DEFAULT_DURATION),
                   help="Duree totale en secondes (0 = sans fin, défaut %(default)s)")
    p.add_argument("--pbase", type=float, default=env_float("SIMULATOR_PBASE", DEFAULT_PBASE),
                   help="Puissance de base kW (défaut %(default)s)")
    p.add_argument("--amplitude", type=float, default=env_float("SIMULATOR_AMPLITUDE", DEFAULT_AMPLITUDE),
                   help="Amplitude des variations kW (défaut %(default)s)")
    p.add_argument("--period", type=float, default=env_float("SIMULATOR_PERIOD", DEFAULT_PERIOD),
                   help="Periode sinusoidale secondes (défaut %(default)s)")
    p.add_argument("--noise", type=float, default=env_float("SIMULATOR_NOISE", DEFAULT_NOISE),
                   help="Ecart-type bruit gaussien kW (défaut %(default)s)")
    p.add_argument("--seed", type=int, default=42, help="Graine aleatoire (défaut 42)")

    an = p.add_argument_group("anomalie")
    an.add_argument("--anomaly", action="store_true", default=env_bool("SIMULATOR_ANOMALY", DEFAULT_ANOMALY),
                    help="Activer le scenario d'anomalie")
    an.add_argument("--anomaly-sensor", default=os.environ.get("SIMULATOR_ANOMALY_SENSOR", DEFAULT_ANOMALY_SENSOR),
                    help="Capteur cible de l'anomalie (défaut %(default)s)")
    an.add_argument("--anomaly-start", type=float, default=env_float("SIMULATOR_ANOMALY_START", DEFAULT_ANOMALY_START),
                    help="Debut de l'anomalie en secondes (défaut %(default)s)")
    an.add_argument("--anomaly-duration", type=float, default=env_float("SIMULATOR_ANOMALY_DURATION", DEFAULT_ANOMALY_DURATION),
                    help="Duree de l'anomalie en secondes (défaut %(default)s)")
    an.add_argument("--anomaly-factor", type=float, default=env_float("SIMULATOR_ANOMALY_FACTOR", DEFAULT_ANOMALY_FACTOR),
                    help="Facteur multiplicateur de puissance (défaut %(default)s)")
    return p


def main():
    parser = build_parser()
    args = parser.parse_args()
    sys.exit(run(args))


if __name__ == "__main__":
    main()
