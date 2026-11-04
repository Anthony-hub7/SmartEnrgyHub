package com.smartenergy.collector.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Bornes exactes des regles temporelles, sans Spring (Clock.fixed). */
class MeasurementTimeValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final MeasurementTimeValidator validator = new MeasurementTimeValidator(
            new MeasurementProperties(Duration.ofHours(24), Duration.ofMinutes(5), 10240L),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void nominale_est_acceptee() {
        assertDoesNotThrow(() -> validator.check(NOW));
    }

    @Test
    void exactement_maxAge_est_encore_acceptee() {
        assertDoesNotThrow(() -> validator.check(NOW.minus(Duration.ofHours(24))));
    }

    @Test
    void maxAge_plus_1ms_est_rejete() {
        ApiException ex = assertThrows(ApiException.class,
                () -> validator.check(NOW.minus(Duration.ofHours(24)).minusMillis(1)));
        assertEquals(ErrorCode.MEASUREMENT_TOO_OLD, ex.code());
    }

    @Test
    void exactement_maxFuture_est_encore_acceptee() {
        assertDoesNotThrow(() -> validator.check(NOW.plus(Duration.ofMinutes(5))));
    }

    @Test
    void maxFuture_plus_1ms_est_rejete() {
        ApiException ex = assertThrows(ApiException.class,
                () -> validator.check(NOW.plus(Duration.ofMinutes(5)).plusMillis(1)));
        assertEquals(ErrorCode.MEASUREMENT_IN_FUTURE, ex.code());
    }
}
