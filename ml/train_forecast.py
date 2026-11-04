"""Pipeline de prevision — extension 9C (Niveau C, CDC 17.3 et 18).

Implémente les 6 etapes de la demarche CDC 17.3, pour un ou plusieurs capteurs :
    1. preparation des donnees   (chargement, interpolation, fenetrage)
    2. separation chrono         (entraînement / validation / test, min-max sur le train)
    3. entrainement              (regression lineaire simple : y = W·x + b)
    4. mesure de l'erreur        (MAE formule 5, RMSE formule 6, par horizon)
    5. export du modèle          (ONNX via skl2onnx + metadonnees energy_<sensor>.json)
    6. integration               (chargee par le service Java analytics via DJL, voir analytics-service)

Un modèle par sensor est exporte : ``models/energy_<sensor>.onnx`` +
``models/energy_<sensor>.json`` (min/max, fenetre, metriques), consommes par
l'inférence Java (analytics-service, chapitre 18).

Usage :
    python train_forecast.py                            # tous les capteurs trouves dans ml/data/
    python train_forecast.py --sensor SENSOR-001        # un seul capteur
    python train_forecast.py --sensors SENSOR-001 SENSOR-002 --window 24
    python train_forecast.py --data-dir data --out-dir ../models
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LinearRegression
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# --------------------------------------------------------------------------
# Constantes de la demarche (CDC 17.3-17.4)
# --------------------------------------------------------------------------
WINDOW = 24          # fenetre d'entree : 24 dernieres heures (points)
HORIZON = 24         # horizon de prevision : 1 a 24 heures
MAX_MISSING_GAP = 6  # trous > 6 h consecutives -> fenetre rejetee
TRAIN_RATIO, VAL_RATIO = 0.70, 0.15   # le reste (15 %) = test
REPORT_HORIZONS = (1, 6, 12, 24)      # horizons signales dans le rapport


# ==========================================================================
# Etape 1 — preparation des donnees
# ==========================================================================
def load_hourly_series(path: Path) -> pd.Series:
    """Lit la serie horaire d'un capteur (CSV de export_dataset.py).

    Retourne une Series indexee par heure (UTC), valeurs = moyenne kW
    (``sumPowerKw / count``). La grille est completee sur TOUTES les heures
    du range : une heure sans agregat devient ``NaN`` (trou a traiter).
    """
    df = pd.read_csv(path, parse_dates=["hour"])
    df["hour"] = pd.to_datetime(df["hour"], utc=True)
    # moyenne horaire : les agregats sont additifs (sum + count), cf. HourlyAggregateDocument
    df["avgPowerKw"] = df["sumPowerKw"] / df["count"].clip(lower=1)
    series = df.set_index("hour")["avgPowerKw"].sort_index()
    # complete la grille horaire : les heures absentes = NaN (trous reels du pipeline)
    full = pd.date_range(series.index.min(), series.index.max(), freq="h", tz="UTC")
    return series.reindex(full)


@dataclass
class PreparedData:
    """Fenetres pretes pour l'entrainement, deja normalisees avec les stats du TRAIN."""

    X: np.ndarray            # (n, WINDOW) fenetres passees normalisees
    Y: np.ndarray            # (n, HORIZON) cibles normalisees (les H heures suivantes)
    hours: pd.DatetimeIndex  # heure de fin de fenetre (= derniere heure observee)
    stats: dict              # min/max du train, pour la denormalisation (Java)

    @property
    def n(self) -> int:
        return len(self.X)


def make_windows(series: pd.Series, window: int = WINDOW, horizon: int = HORIZON,
                 max_gap: int = MAX_MISSING_GAP) -> pd.DataFrame:
    """Construit les fenetres glissantes (X, y) de la serie.

    - valeurs manquantes : interpolation lineaire entre les heures voisines ;
    - une fenêtre contenant plus de ``max_gap`` heures consecutives manquantes
      est REJETEE (pas de donnee fiable pour la prevision) ;
    - retourne un DataFrame [x0..x{window-1}, y0..y{horizon-1}, hour].
    """
    filled = series.interpolate(limit_area="inside")          # interpolation lineaire
    is_missing = series.isna()
    values = filled.to_numpy()
    missing = is_missing.to_numpy()

    rows: list[np.ndarray] = []
    row_hours: list[pd.Timestamp] = []
    hours = filled.index
    n = len(values)
    for end in range(window - 1, n - horizon):                # end = derniere heure observee
        sl = slice(end - window + 1, end + 1)                 # fenetre d'entree
        if missing[sl].sum() > 0:
            # un trou dans la fenetre : acceptable SEULEMENT s'il est court
            # (deja interpole) — on compte la plus longue serie de trous.
            longest = current = 0
            for m in missing[sl]:
                current = current + 1 if m else 0
                longest = max(longest, current)
            if longest > max_gap:
                continue
        target = slice(end + 1, end + 1 + horizon)
        if np.isnan(values[target]).any():
            continue                                          # cible trouee -> fenetre inutilisable
        rows.append(np.concatenate([values[sl], values[target]]))  # (fenetre + cibles), float
        row_hours.append(hours[end])                          # heure de fin, gardee a part
    cols = [f"x{i}" for i in range(window)] + [f"y{i}" for i in range(horizon)]
    df = pd.DataFrame(np.array(rows, dtype=float), columns=cols)
    df["hour"] = pd.DatetimeIndex(row_hours, tz="UTC")        # priorite temporelle
    return df


# ==========================================================================
# Etape 2 — separation chronologique + normalisation (min-max sur le train)
# ==========================================================================
def chronological_split(raw: pd.DataFrame, window: int = WINDOW, horizon: int = HORIZON,
                        train: float = TRAIN_RATIO, val: float = VAL_RATIO) -> tuple[PreparedData, ...]:
    """Decoupage CHRONOLOGIQUE (jamais aleatoire) 70/15/15 + normalisation.

    La priorite temporelle est donnee par ``hour`` (fin de fenetre) : les
    fenetres les plus ancienne vont a l'entrainement, les plus recentes au test.
    Le min-max est calcule sur le train SEULEMENT (pas de fuite d'information).
    """
    raw = raw.sort_values("hour").reset_index(drop=True)
    n = len(raw)
    i_train = int(n * train)
    i_val = int(n * (train + val))

    train_df, val_df, test_df = raw.iloc[:i_train], raw.iloc[i_train:i_val], raw.iloc[i_val:]

    # stats de normalisation sur le train uniquement (X et y confondus)
    xcols = [f"x{i}" for i in range(window)]
    ycols = [f"y{i}" for i in range(horizon)]
    train_vals = train_df[xcols + ycols].to_numpy(dtype=float)
    vmin, vmax = float(train_vals.min()), float(train_vals.max())
    span = (vmax - vmin) or 1.0                               # evite une division par zero

    def to_prepared(df: pd.DataFrame) -> PreparedData:
        arr = df[xcols + ycols].to_numpy(dtype=float)
        norm = (arr - vmin) / span
        return PreparedData(
            X=norm[:, :window], Y=norm[:, window:],
            hours=pd.DatetimeIndex(df["hour"]),
            stats={"min": vmin, "max": vmax, "span": span},
        )

    return to_prepared(train_df), to_prepared(val_df), to_prepared(test_df)


# ==========================================================================
# Etape 3 — baselines (la reference a battre) puis regression lineaire simple
# ==========================================================================
def baselines(data: PreparedData, raw: pd.DataFrame | None = None,
              window: int = WINDOW, horizon: int = HORIZON) -> dict[str, np.ndarray]:
    """Baseline 1 : persistance  yhat(t+h) = y(t)      (derniere heure observee)
       Baseline 2 : saisonnier naif yhat(t+h) = y(t+h-24) (meme heure la veille)

    Retourne des predictions DENORMALISEES (kW) pour etre comparables aux
    mesures ; ``data`` sert a recuperer les min/max du train.
    """
    span, vmin = data.stats["span"], data.stats["min"]

    def denorm(a: np.ndarray) -> np.ndarray:
        return a * span + vmin

    last = denorm(data.X[:, window - 1])                      # y(t)
    persistence = np.repeat(last[:, None], horizon, axis=1)   # yhat(t+h) = y(t) pour tout h
    # saisonnier naif : y(t+h-24) = X[h-1] (X[j] = y(t-23+j))
    seasonal = denorm(data.X[:, :horizon])
    return {"persistance": persistence, "saisonnier-naif": seasonal}


def train_linear_regression(tr: PreparedData) -> LinearRegression:
    """Entraîne une régression linéaire simple : y = W·x + b.

    scikit-learn LinearRegression est multi-output : il accepte Y de forme (n, HORIZON)
    et apprend une matrice de poids (WINDOW, HORIZON) plus un biais (HORIZON,).
    Entraîné sur le jeu d'entraînement SEULEMENT (déjà normalisé min-max).
    """
    model = LinearRegression()
    model.fit(tr.X, tr.Y)
    return model


# ==========================================================================
# Etape 4 — mesure de l'erreur (MAE formule 5, RMSE formule 6)
# ==========================================================================
def mae(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """MAE = (1/n) * sum |yi - yhati|  (formule 5), en kW."""
    return float(np.mean(np.abs(y_true - y_pred)))


def rmse(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """RMSE = sqrt((1/n) * sum (yi - yhati)^2)  (formule 6), en kW."""
    return float(math.sqrt(np.mean((y_true - y_pred) ** 2)))


def evaluate(model: LinearRegression, data: PreparedData, horizons: tuple[int, ...] = REPORT_HORIZONS,
             window: int = WINDOW, horizon: int = HORIZON) -> dict:
    """MAE / RMSE sur le jeu de TEST, en kW, global + par horizon + baselines."""
    span, vmin = data.stats["span"], data.stats["min"]
    pred_norm = model.predict(data.X)                         # (n, HORIZON) normalise
    pred = pred_norm * span + vmin                            # denormalisation
    truth = data.Y * span + vmin

    def block(p: np.ndarray, t: np.ndarray) -> dict:
        out = {"mae": mae(t, p), "rmse": rmse(t, p)}
        for h in horizons:
            out[f"h{h}"] = {"mae": mae(t[:, h - 1], p[:, h - 1]),
                            "rmse": rmse(t[:, h - 1], p[:, h - 1])}
        return out

    result: dict = {"regression-lineaire": block(pred, truth)}
    for name, p in baselines(data, window=window, horizon=horizon).items():
        result[name] = block(p, truth)
    return result


def beats_baselines(metrics: dict, model_key: str = "regression-lineaire") -> bool:
    """Le modele doit battre les deux baselines sur MAE ET RMSE (criterium du CDC)."""
    m = metrics[model_key]
    return all(m["mae"] < metrics[b]["mae"] and m["rmse"] < metrics[b]["rmse"]
               for b in ("persistance", "saisonnier-naif"))


# ==========================================================================
# Etape 5 — export ONNX + metadonnees (un jeu de fichiers par sensor)
# ==========================================================================
def export_onnx(model: LinearRegression, prepared: PreparedData, metrics: dict, out_dir: Path,
                sensor: str, window: int = WINDOW, horizon: int = HORIZON) -> tuple[Path, Path]:
    """Exporte le modele en ONNX (skl2onnx) + un JSON de metadonnees (min/max, metriques).

    Un fichier par sensor : ``energy_<sensor>.onnx`` et ``energy_<sensor>.json``.
    Le JSON est indispensable a l'inférence Java : il porte la normalisation
    (min/max du train) et les metriques affichees dans ForecastResponse.
    Verifie aussi que l'inference ONNX reproduit la sortie scikit-learn.
    """
    import onnxruntime as ort

    out_dir.mkdir(parents=True, exist_ok=True)
    base = f"energy_{sensor}"
    onnx_path = out_dir / f"{base}.onnx"
    json_path = out_dir / f"{base}.json"

    # Conversion scikit-learn -> ONNX : entree [window], sortie [horizon] (rang 1,
    # identique au contrat CDC float[] -> float[] consomme par DJL/Java).
    initial_types = [("input", FloatTensorType([None, window]))]
    onx = convert_sklearn(model, initial_types=initial_types, target_opset=17)
    onnx_path.write_bytes(onx.SerializeToString())

    # --- verification de coherence Python <-> ONNX (meme sortie, echantillon 1D)
    sample = np.array([prepared.X[0]], dtype=np.float32)      # (1, window) comme en Java
    expected = model.predict(sample).ravel()
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    got = sess.run(None, {"input": sample})[0].ravel()
    max_diff = float(np.max(np.abs(expected - got)))
    if max_diff > 1e-4:
        raise RuntimeError(f"Sortie ONNX incoherente avec scikit-learn (ecart max {max_diff:.2e})")
    print(f"  verification ONNX OK (ecart max vs scikit-learn : {max_diff:.2e})")

    meta = {
        "sensor": sensor,
        "window": window,
        "horizon": horizon,
        "normalization": {"min": prepared.stats["min"], "max": prepared.stats["max"]},
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "metrics": metrics,
        "beatsBaselines": beats_baselines(metrics),
        "input": "float[] de taille window (24 dernieres heures, normalisees min-max)",
        "output": "float[] de taille horizon (les 'horizon' heures suivantes, normalisees)",
    }
    json_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return onnx_path, json_path


# ==========================================================================
# Orchestration par sensor (les 6 etapes CDC 17.3)
# ==========================================================================
def run_sensor(sensor: str, data_dir: Path, out_dir: Path, window: int,
               horizon: int, seed: int) -> dict:
    """Execute les 6 etapes CDC 17.3 pour un seul capteur. Retourne les metriques."""
    np.random.seed(seed)

    csv_path = data_dir / f"hourly_{sensor}.csv"
    if not csv_path.exists():
        print(f"  ERREUR : {csv_path} introuvable — capteur ignore.", file=sys.stderr)
        return {}

    print(f"[1/6] chargement de {csv_path}")
    series = load_hourly_series(csv_path)
    trouees = int(series.isna().sum())
    print(f"      {len(series)} heures, dont {trouees} sans agregat (interpolation + rejet > {MAX_MISSING_GAP} h)")

    print("[2/6] fenetrage + decoupage chronologique 70/15/15 (min-max sur le train)")
    windows = make_windows(series, window, horizon)
    if len(windows) < 50:
        print(f"      ERREUR : seulement {len(windows)} fenetres exploitables — capteur ignore.", file=sys.stderr)
        return {}
    tr, va, te = chronological_split(windows, window, horizon)
    print(f"      {tr.n} fenetres d'entrainement / {va.n} de validation / {te.n} de test")

    print("[3/6] baselines (persistance + saisonnier naif) puis regression lineaire simple")
    model = train_linear_regression(tr)
    nb_params = int(model.coef_.size + model.intercept_.size)
    print(f"      Regression lineaire : {nb_params} parametres (poids {model.coef_.shape} + biais {model.intercept_.shape})")

    print("[4/6] MAE (formule 5) et RMSE (formule 6) sur le TEST (kW)")
    metrics = evaluate(model, te, window=window, horizon=horizon)
    print(f"      {'modele':<20} {'MAE':>8} {'RMSE':>8}   (et h1/h6/h12/h24 en MAE)")
    for name, m in metrics.items():
        hs = "  ".join(f"h{h}={m[f'h{h}']['mae']:.3f}" for h in REPORT_HORIZONS)
        print(f"      {name:<20} {m['mae']:>8.3f} {m['rmse']:>8.3f}   {hs}")
    ok = beats_baselines(metrics)
    print(f"      -> la regression lineaire bat les deux baselines sur MAE et RMSE : "
          f"{'OUI' if ok else 'NON'}")

    print(f"[5/6] export ONNX + metadonnees dans {out_dir}")
    onnx_path, json_path = export_onnx(model, te, metrics, out_dir, sensor, window, horizon)
    print(f"      {onnx_path.name} et {json_path.name} ecrits")

    print("[6/6] integration dans l'application (analytics-service, DJL)")
    print(f"      Le modele ONNX est charge par OnnxForecastPredictor via DJL "
          f"(Criteria<float[], float[]> sur models/energy_{sensor}.onnx).")
    print(f"      Les metriques MAE/RMSE sont exposees dans ForecastResponse.metrics.")
    return metrics


def discover_sensors(data_dir: Path) -> list[str]:
    """Liste les capteurs a partir des fichiers hourly_<sensor>.csv presents."""
    sensors: list[str] = []
    if data_dir.exists():
        for path in sorted(data_dir.glob("hourly_*.csv")):
            name = path.stem  # hourly_SENSOR-001
            if name.startswith("hourly_"):
                sensors.append(name[len("hourly_"):])
    return sensors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Entraînement du modele de prevision (extension 9C)")
    parser.add_argument("--data-dir", default=Path(__file__).parent / "data")
    parser.add_argument("--out-dir", default=Path(__file__).parent.parent / "models")
    parser.add_argument("--sensor", default=None, help="Un seul capteur (defaut : tous)")
    parser.add_argument("--sensors", nargs="*", default=None,
                        help="Liste de capteurs (defaut : tous detects dans --data-dir)")
    parser.add_argument("--window", type=int, default=WINDOW)
    parser.add_argument("--horizon", type=int, default=HORIZON)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    data_dir = Path(args.data_dir)
    out_dir = Path(args.out_dir)

    if args.sensor:
        sensors = [args.sensor]
    elif args.sensors is not None:
        sensors = args.sensors
    else:
        sensors = discover_sensors(data_dir)
        if not sensors:
            print(f"ERREUR : aucun fichier hourly_*.csv dans {data_dir}", file=sys.stderr)
            return 1

    print(f"Capteurs a entrainer : {sensors}")
    all_metrics: dict[str, dict] = {}
    for sensor in sensors:
        print(f"===== Capteur {sensor} =====")
        m = run_sensor(sensor, data_dir, out_dir, args.window, args.horizon, args.seed)
        if m:
            all_metrics[sensor] = m
        print()

    if not all_metrics:
        print("AUCUN modele n'a pu ete entraine.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
