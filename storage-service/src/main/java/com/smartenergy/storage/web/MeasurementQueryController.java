package com.smartenergy.storage.web;

import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.measurement.MeasurementHistoryResponse;
import com.smartenergy.contracts.measurement.MeasurementItem;
import com.smartenergy.storage.service.MeasurementQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes GET de consultation du storage (etape 8, CDC section 5.4).
 *
 * <p>Ne pas confondre avec {@code POST /api/measurements} du collector-service : meme prefixe,
 * autre service (la gateway route par methode/extension). Les controles metier (plage max,
 * limit) vivent dans {@link MeasurementQueryService} ; ici on ne fait que lier les parametres
 * HTTP et documenter l'API en OpenAPI (Swagger UI sur /swagger-ui.html).
 */
@RestController
@RequestMapping(ApiPaths.MEASUREMENTS)
public class MeasurementQueryController {

    private final MeasurementQueryService service;

    public MeasurementQueryController(MeasurementQueryService service) {
        this.service = service;
    }

    @GetMapping("/{sensorId}")
    @Operation(summary = "Historique d'un capteur (filtre par dates, paginé)",
            description = "Plage [from, to) : from inclus, to exclu (ISO-8601, UTC). "
                    + "Défauts : to = maintenant, from = to - 24 h, limit = 100 (max 1000), order = desc. "
                    + "Pagination opaque : renvoyez nextCursor dans le paramètre cursor jusqu'à ce qu'il soit null. "
                    + "Plage max 7 jours (au-delà : 400 RANGE_TOO_LARGE). "
                    + "Un capteur sans mesure répond 200 avec items = [].")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page d'historique (nextCursor null en fin de liste)",
            content = @Content(schema = @Schema(implementation = MeasurementHistoryResponse.class),
                examples = @ExampleObject(name = "historique-desc",
                    value = """
                    {
                      "sensorId": "SENSOR-001",
                      "from": "2026-10-27T00:00:00Z",
                      "to": "2026-10-27T12:00:00Z",
                      "items": [
                        {"timestamp": "2026-10-27T11:59:58Z", "powerKw": 3.72, "voltage": 226.4,
                         "temperature": 29.1, "state": "ON"},
                        {"timestamp": "2026-10-27T11:59:56Z", "powerKw": 3.71, "voltage": 226.5, "state": "ON"}
                      ],
                      "nextCursor": "MjAyNi0xMC0yN3wxNzkyNzY2Mzk4MDAw"
                    }"""))),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (limit/order/cursor/plage) "
                + "ou RANGE_TOO_LARGE (plage > 7 jours) — application/problem+json"),
        @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (chemin inconnu)")
    })
    public MeasurementHistoryResponse history(
            @Parameter(description = "Identifiant du capteur", example = "SENSOR-001")
            @PathVariable String sensorId,
            @Parameter(description = "Bornes ISO-8601, UTC", example = "2026-10-27T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "1 à 1000 (défaut 100)", example = "100")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Jeton opaque de reprise (nextCursor)", example = "MjAyNi0xMC0yN3wxNzkyNzY2Mzk4MDAw")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "desc (défaut) ou asc", example = "desc")
            @RequestParam(defaultValue = "desc") String order) {
        return service.history(sensorId, from, to, limit, cursor, order);
    }

    @GetMapping("/{sensorId}/latest")
    @Operation(summary = "Dernière mesure connue d'un capteur",
            description = "Lecture O(1) de la table latest (cache-aside Redis 30 s). "
                    + "404 si aucune mesure n'existe pour ce capteur.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dernière mesure",
            content = @Content(schema = @Schema(implementation = MeasurementItem.class),
                examples = @ExampleObject(name = "derniere-mesure",
                    value = """
                    {"timestamp": "2026-10-27T11:59:58Z", "powerKw": 3.72, "voltage": 226.4,
                     "temperature": 29.1, "currentA": 16.0, "frequencyHz": 50.0, "state": "ON"}"""))),
        @ApiResponse(responseCode = "404", description = "MEASUREMENT_NOT_FOUND — application/problem+json",
            content = @Content(examples = @ExampleObject(value = """
                {"type": "https://smartenergy.local/problems/measurement-not-found",
                 "title": "Mesure introuvable", "status": 404, "code": "MEASUREMENT_NOT_FOUND",
                 "detail": "Aucune mesure connue pour le capteur SENSOR-INCONNU"}""")))
    })
    public MeasurementItem latest(
            @Parameter(description = "Identifiant du capteur", example = "SENSOR-001")
            @PathVariable String sensorId) {
        return service.latest(sensorId);
    }
}
