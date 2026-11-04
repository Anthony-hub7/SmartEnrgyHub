# Résultats · Performance Redis (stream + collector) — 2026-11-02

**Objectif** : arbiter la capacité de Redis et le débit réel de la chaîne, pour dimensionner
l'interface (flux « stream en direct ») et le volume que la stack peut encaisser.

**Méthodologie** : `redis-benchmark` dans le conteneur (A/B), bursts `POST /api/measurements`
en parallèle via Python stdlib (C), XADD par `--pipe` (D), échantillonnage XLEN/RAM/pending
toutes les 2 s pendant les tests. Redis : `maxmemory=0` (pas de limite interne) ; seule
protection = limite du conteneur **256 Mo**.

## A. Plafond XADD brut (50k ops, 20 connexions)

| Opération | Req/s | p50 |
|---|---|---|
| **XADD** (petit paquet, 4 champs) | **~32 100** | 0,41 ms |
| SET | ~34 800 | 0,32 ms |
| GET | ~34 800 | 0,29 ms |
| INCR | ~26 000 | 0,34 ms |

## B. Injection massive par `--pipe` (D inclus)

- **520 000 XADD (petit paquet)** : 4,6 s → **~113 000 obs/s**, +13 Mo RAM seulement
  (compression listpack des streams : petites entrées tassées).
- **250 000 entrées réalistes** (8 champs, tailles réelles, `MAXLEN ~ 250000` appliqué en
  direct) : 5,6 s (~46 400 obs/s), XLEN final **250 005** — le trim `~` fonctionne (marge 5).
  Mémoire : `used_memory` 26 Mo / RSS 43 Mo pour 250k → **extrapolé à 500k ≈ 52-84 Mo**, très
  en dessous de la limite de 256 Mo. Cleanup `DEL` + `MEMORY PURGE` : retour à 8 Mo.

## C. Débit réel bout-en-bout (Collector → Redis)

| Burst | Mesures | Threads | Acceptées | RPS effectif | Latence p50/p99/max | Pending après |
|---|---|---|---|---|---|---|
| 1 | 5 000 | 20 | 5000/202 | **166,9 obs/s** | 98 / 377 / 567 ms | 0 |
| 2 | 10 000 | 60 | 10 000/202 | **327,1 obs/s** | 138 / 595 / 987 ms | 0 |

- Le goulot n'est **pas Redis** (32k/s) mais le collector Spring (HTTP, validation, XADD) :
  plafond observé **≈ 330 obs/s ≈ 28 M mesures/jour** — la latence s'envole au-delà
  (p99 595 ms à 60 threads = saturation).
- Les consommateurs `storage-group` et `analytics-group` ont **tout resorbé en temps réel**
  : XLEN statique dès la fin du burst, `pending=0` à chaque échantillon, 0 entrée en DLQ.
- Cadence nominale du simulateur (5 capteurs × 0,5 Hz = 2,5 obs/s) : marge ~**×130**.

## Conclusion / recommandations

1. **Redis n'est jamais le goulot** : XADD 32k/s, brut 113k/s en pipeline ; à la cadence de
   l'interface prévue (< 100 obs/s), la marge est énorme.
2. **Le collector plafonne à ~330 obs/s** ; si l'interface doit shooter plus fort (demos,
   backfill), augmenter les threads Tomcat ou multiplier les instances collector.
3. **Mémoire du stream sans risque** : à 500k entrées réalistes ≈ 52-84 Mo utilisés (limité
   par `MAXLEN ~500000`), très sous la limite conteneur de 256 Mo. Le trim est vérifié.
4. Détail config : `STREAM_MAXLEN=500000` (`application.yml` du collector, défaut D-06).
5. À verifier plus tard si besoin : `maxmemory` reste 0 — en cas d'incident mémoire c'est la
   limite du conteneur qui tue le process (OOM) ; un `maxmemory 200mb` + politique
   d'éviction `noeviction` pourrait être préféré pour un échec propre (503 du collector)
   plutôt qu'un redémarrage brutal de Redis.
