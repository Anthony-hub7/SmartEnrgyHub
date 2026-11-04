package com.smartenergy.collector.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Regles de validation du collector (CDC section 5.2) : smartenergy.measurement.* */
@ConfigurationProperties(prefix = "smartenergy.measurement")
public record MeasurementProperties(
        /** Une mesure plus vieille que ca est rejetee (400 MEASUREMENT_TOO_OLD). */
        @DefaultValue("PT24H") Duration maxAge,
        /** Tolerance de derive d'horloge : au-dela, 400 MEASUREMENT_IN_FUTURE. */
        @DefaultValue("PT5M") Duration maxFuture,
        /** Taille maximale du corps HTTP (413 PAYLOAD_TOO_LARGE). */
        @DefaultValue("10240") long maxBodyBytes) {}
