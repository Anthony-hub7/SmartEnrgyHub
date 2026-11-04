package com.smartenergy.analytics.web;

import com.smartenergy.analytics.forecast.ForecastService;
import com.smartenergy.analytics.service.AnalyticsQueryService;
import com.smartenergy.contracts.analytics.AnomalyResponse;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.DailySummaryResponse;
import com.smartenergy.contracts.analytics.ExceedanceResponse;
import com.smartenergy.contracts.analytics.ForecastResponse;
import com.smartenergy.contracts.analytics.SummaryResponse;
import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes GET de consultation analytics (etape 8, CDC section 5.4 ; decision D-17 pour l'alias).
 *
 * <p>Aucune logique ici : delegation a {@link AnalyticsQueryService} (et
 * {@link ForecastService} pour l'extension prevision). Les donnees agrégrees sont ecrites par
 * le consumer analytics a l'etape 9 : en attendant, summary/daily-summary repondent 404 NO_DATA
 * et les listes repondent 200 vide.
 *
 * <p>Alias du guide : {@code GET /api/sensors/{id}/daily-summary} est servi par CE service
 * (sensor-service ne connait pas ce chemin ; la gateway route
 * <code>/api/sensors/&#42;/daily-summary</code> vers analytics AVANT la regle generale
 * {@code "/api/sensors/**"}).
 */
@RestController
public class AnalyticsController {

    private final AnalyticsQueryService service;
    private final ForecastService forecastService;

    public AnalyticsController(AnalyticsQueryService service, ForecastService forecastService) {
        this.service = service;
        this.forecastService = forecastService;
    }

    @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/summary")
    @Operation(summary = "Résumé énergétique d'un capteur (moyenne, min/max, énergie)",
            description = "Bornes [from, to) alignées sur les heures pleines (granularité horaire). "
                    + "Défauts : to = maintenant, from = to - 24 h ; plage max 31 jours. "
                    + "Moyenne pondérée (somme des sommes / somme des comptes). "
                    + "404 NO_DATA si aucun agrégat n'existe pour la période.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Indicateurs de la période",
            content = @Content(schema = @Schema(implementation = SummaryResponse.class),
                examples = @ExampleObject(name = "resume-24h", value = """
                    {
                      "sensorId": "SENSOR-001",
                      "effectiveFrom": "2026-10-26T14:00:00Z",
                      "effectiveTo": "2026-10-27T14:00:00Z",
                      "count": 43200,
                      "avgPowerKw": 3.71,
                      "minPowerKw": 0.42,
                      "maxPowerKw": 9.81,
                      "energyKwh": 160.2,
                      "thresholdKw": 10.0,
                      "thresholdExceededCount": 0,
                      "anomalyCount": 2
                    }"""))),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (from >= to) "
                + "ou RANGE_TOO_LARGE (plage > 31 jours)"),
        @ApiResponse(responseCode = "404", description = "NO_DATA — aucune donnée pour la période")
    })
    public SummaryResponse summary(
            @Parameter(description = "Identifiant du capteur", example = "SENSOR-001")
            @PathVariable String sensorId,
            @Parameter(description = "Bornes ISO-8601, UTC", example = "2026-10-26T14:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.summary(sensorId, from, to);
    }

    @GetMapping({ApiPaths.ANALYTICS + "/{sensorId}/daily-summary",
            ApiPaths.SENSORS + "/{sensorId}/daily-summary"})
    @Operation(summary = "Résumé énergétique d'une journée UTC (alias guide sur /api/sensors)",
            description = "Deux chemins identiques : /api/analytics/{id}/daily-summary et "
                    + "/api/sensors/{id}/daily-summary (decision D-17, route par la gateway). "
                    + "Défaut : date = jour UTC courant. 404 NO_DATA si la journée n'a pas d'agrégat.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Résumé de la journée",
            content = @Content(schema = @Schema(implementation = DailySummaryResponse.class),
                examples = @ExampleObject(name = "journee", value = """
                    {"sensorId": "SENSOR-001", "date": "2026-10-27", "count": 43200,
                     "avgPowerKw": 3.71, "minPowerKw": 0.42, "maxPowerKw": 9.81,
                     "energyKwh": 160.2, "thresholdExceededCount": 0, "anomalyCount": 2}"""))),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (date illisible)"),
        @ApiResponse(responseCode = "404", description = "NO_DATA — journée sans agrégat")
    })
    public DailySummaryResponse dailySummary(
            @Parameter(description = "Identifiant du capteur", example = "SENSOR-001")
            @PathVariable String sensorId,
            @Parameter(description = "Jour UTC YYYY-MM-DD (défaut : aujourd'hui)", example = "2026-10-27")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dailySummary(sensorId, date);
    }

    @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/anomalies")
    @Operation(summary = "Anomalies détectées d'un capteur (paginé)",
            description = "Plage [from, to) défaute sur les dernières 24 h, tri du plus récent "
                    + "au plus ancien ; filtre optionnel par type ; pagination 0-indexée "
                    + "(page/size, size max 500). Liste vide = aucune anomalie (200).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page d'anomalies",
            content = @Content(schema = @Schema(implementation = PageResponse.class),
                examples = @ExampleObject(name = "page-anomalies", value = """
                    {
                      "items": [{
                        "anomalyId": "6521f0...", "sensorId": "SENSOR-001",
                        "timestamp": "2026-10-27T12:00:02Z", "eventId": "…",
                        "type": "THRESHOLD", "severity": "HIGH", "powerKw": 28.9,
                        "thresholdKw": 10.0, "thresholdSource": "SENSOR", "detectedAt": "2026-10-27T12:00:02Z"
                      }],
                      "page": 0, "size": 20, "totalElements": 1
                    }"""))),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER ou RANGE_TOO_LARGE (> 31 jours)")
    })
    public PageResponse<AnomalyResponse> anomalies(
            @Parameter(description = "Identifiant du capteur", example = "SENSOR-001")
            @PathVariable String sensorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Filtre : THRESHOLD ou ZSCORE", example = "THRESHOLD")
            @RequestParam(required = false) AnomalyType type,
            @Parameter(description = "Page 0-indexée (défaut 0)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Taille de page, 1-500 (défaut 50)", example = "20")
            @RequestParam(defaultValue = "50") int size) {
        return service.anomalies(sensorId, from, to, type, page, size);
    }

    @GetMapping(ApiPaths.ANALYTICS + "/exceedances")
    @Operation(summary = "Capteurs en dépassement de seuil",
            description = "Sans from/to : état courant (dernière puissance > threshold). "
                    + "Avec from/to : max des agrégats quotidiens de la période > threshold. "
                    + "threshold est obligatoire (kW). Liste vide = aucun dépassement (200).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Capteurs en dépassement (liste potentiellement vide)",
            content = @Content(schema = @Schema(implementation = ExceedanceResponse.class),
                examples = @ExampleObject(name = "courant", value = """
                    [{"sensorId": "SENSOR-003", "thresholdKw": 10.0,
                      "lastPowerKw": 12.4, "lastMeasuredAt": "2026-10-27T13:59:58Z"}]"""))),
        @ApiResponse(responseCode = "400", description = "threshold absent/illisible (INVALID_PARAMETER) "
                + "ou plage invalide")
    })
    public List<ExceedanceResponse> exceedances(
            @Parameter(description = "Seuil en kW (obligatoire)", example = "10.0")
            @RequestParam(required = false) Double threshold,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (threshold == null) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER, "Le parametre threshold (kW) est obligatoire");
        }
        return service.exceedances(threshold, from, to);
    }

    @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/forecast")
    @Operation(summary = "[Extension] Prévision de consommation (module IA)",
            description = "horizonHours 1-24 (défaut 6). Si smartenergy.analytics.forecast.enabled "
                    + "= false (défaut) ou si le modèle n'est pas chargé : 501 FORECAST_DISABLED.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Points de prévision + métriques (MAE/RMSE)",
            content = @Content(schema = @Schema(implementation = ForecastResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (horizon hors [1, 24])"),
        @ApiResponse(responseCode = "501", description = "FORECAST_DISABLED — module IA inactif")
    })
    public ForecastResponse forecast(
            @Parameter(description = "Identifiant du capteur", example = "SENSOR-001")
            @PathVariable String sensorId,
            @Parameter(description = "Horizon en heures, 1-24 (défaut 6)", example = "6")
            @RequestParam(defaultValue = "6") int horizonHours) {
        return forecastService.forecast(sensorId, horizonHours);
    }
}
