package com.smartenergy.collector.web;

import com.smartenergy.collector.stream.MeasurementPublisher;
import com.smartenergy.collector.validation.MeasurementTimeValidator;
import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.common.HeaderNames;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
import com.smartenergy.platform.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/measurements (CDC section 5.2) : recoit, valide et publie dans Redis Streams.
 * 202 = acceptee pour traitement, PAS encore persistee (le stockage est decouple, guide 11.2).
 * Un doublon est accepte (202) : il est neutralise en aval par l'idempotence.
 */
@RestController
@RequestMapping(ApiPaths.MEASUREMENTS)
public class MeasurementController {

    private static final Logger log = LoggerFactory.getLogger(MeasurementController.class);

    private final MeasurementPublisher publisher;
    private final MeasurementTimeValidator timeValidator;
    private final Clock clock;

    public MeasurementController(MeasurementPublisher publisher, MeasurementTimeValidator timeValidator,
            Clock clock) {
        this.publisher = publisher;
        this.timeValidator = timeValidator;
        this.clock = clock;
    }

    @PostMapping
    @Operation(summary = "Recoit une mesure energetique",
            description = "Publie la mesure dans le stream Redis et repond 202. "
                    + "Les regles de validation (champs, plages, fraicheur temporelle) repondent 400.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Mesure acceptee pour traitement",
                content = @Content(examples = @ExampleObject())),
        @ApiResponse(responseCode = "400", description = "Mesure invalide (application/problem+json)"),
        @ApiResponse(responseCode = "503", description = "Redis indisponible (Retry-After)")
    })
    public ResponseEntity<Void> receive(@Valid @RequestBody EnergyMeasurementRequest request) {
        timeValidator.check(request.timestamp());
        EnergyMeasurement measurement =
                EnergyMeasurement.from(request, RequestIdFilter.currentRequestId(), clock.instant());
        String redisId = publisher.publish(measurement);
        log.info("mesure recue sensorId={} eventId={} redisId={}",
                measurement.sensorId(), measurement.eventId(), redisId);
        return ResponseEntity.accepted()
                .header(HeaderNames.EVENT_ID, measurement.eventId().toString())
                .build();
    }
}
