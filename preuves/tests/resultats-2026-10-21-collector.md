# Rapport de tests — Collector Service (étape 3)

- **Date** : 2026-10-21
- **Environnement** : Linux (Ubuntu), Docker 28.2.2, OpenJDK 17.0.18, Maven (wrapper projet)
- **Commit** : 796a6ce (avant modifications étape 3)

## 1. Tests automatisés (Maven)

```bash
mvn -B -pl collector-service -am test
```

Résultat : `BUILD SUCCESS` — **24 tests, 0 échec** :

| Suite | Tests | Statut |
|---|---|---|
| `MeasurementEventCodecTest` + `EventIdsTest` (contrats) | 11 | OK |
| `MeasurementTimeValidatorTest` (bornes 24 h / 5 min, `Clock.fixed`) | 5 | OK |
| `CollectorIntegrationTest` (Testcontainers `redis:7`) | 8 | OK |

Scénarios d'intégration couverts : valide → 202 + `X-Event-Id` + XLEN +1 + champs
conformes au codec ; `sensorId` absent → 400 + XLEN inchangé ; `"powerKw":"3.72"`
→ 400 `MALFORMED_JSON` (pas de coercion) ; 25 h → 400 `MEASUREMENT_TOO_OLD` ;
+6 min → 400 `MEASUREMENT_IN_FUTURE` ; doublon ×2 → 202/202 (eventId identique) ;
corps > 10 Ko → 413 `PAYLOAD_TOO_LARGE` ; Redis stoppé → 503 + `Retry-After: 5`.

## 2. Vérification réelle (Collector local + Redis dockerisé)

Redis : conteneur `smartenergy-redis` (redis:7, port 127.0.0.1:6379), Collector lancé
en local : `java -jar collector-service/target/app.jar`.

| Test | Commande | Résultat | Statut |
|---|---|---|---|
| T01 — santé | `curl /actuator/health` | `{"status":"UP"}` | OK |
| T02 — mesure valide | `POST /api/measurements` (SENSOR-001, 3.72 kW) | `HTTP/1.1 202` + `X-Event-Id: 945ef16d-…` + `X-Request-Id` | OK |
| T02 — événement Redis | `XLEN energy-measurements` | 0 → **1** ; champs du stream : `schemaVersion/eventId/traceId/sensorId/timestamp/receivedAt/powerKw/voltage/temperature` conformes au codec | OK |
| T03 — champ manquant | `POST` sans `sensorId` | `400` `application/problem+json` `VALIDATION_ERROR`, `errors:[{"field":"sensorId",…}]`, **XLEN inchangé (1)** | OK |
| T04 — pas de coercion | `POST` avec `"powerKw":"3.72"` | `400` (`MALFORMED_JSON`) | OK |
| T05 — corps > 10 Ko | `POST` corps ~11 Ko | `413` (`PAYLOAD_TOO_LARGE`), XLEN inchangé | OK |

Extrait (T02) :

```
HTTP/1.1 202
X-Request-Id: e6c11d2f-731f-4590-b502-41ba6e17fce1
X-Event-Id: 945ef16d-a1b9-5d63-82b5-571310285b3c
```

Extrait (T03) :

```
HTTP/1.1 400
Content-Type: application/problem+json
{"type":"https://smartenergy.local/problems/validation-error","title":"Requete invalide",
 "status":400,"detail":"1 champ(s) invalide(s)","code":"VALIDATION_ERROR",
 "errors":[{"field":"sensorId","message":"ne doit pas etre vide"}]}
```

## 3. Correction apportée en cours de test

`platform-web/GlobalExceptionHandler` : annotation **`@ControllerAdvice` manquante** →
sans elle Spring MVC ne découvre pas les `@ExceptionHandler` et toutes les erreurs
ressortaient en 500. Corrigé ; impact positif sur **tous** les services.

## Écarts et limites connues

- Le Redis de vérification tournait sans mot de passe (conteneur de dev antérieur) ;
  le compose actuel impose `REDIS_PASSWORD` via `.env` (à régénérer depuis
  `.env.example`, actuellement absent du dépôt).
- Cas chunked (sans `Content-Length`) couvert par le code mais non testé en curl.
