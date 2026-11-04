package com.smartenergy.collector.validation;

import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Regles temporelles de la mesure (CDC section 5.2) : ni trop ancienne (reprise apres coupure
 * reseau d'un capteur -> 24 h), ni dans le futur (derive d'horloge -> 5 min).
 * Les regles STATIQUES (sensorId, plages numeriques) sont dans EnergyMeasurementRequest.
 */
@Component
public class MeasurementTimeValidator {

    private final MeasurementProperties props;
    private final Clock clock;

    public MeasurementTimeValidator(MeasurementProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public void check(Instant timestamp) {
        Instant now = clock.instant();
        if (timestamp.isAfter(now.plus(props.maxFuture()))) {
            throw ApiException.of(ErrorCode.MEASUREMENT_IN_FUTURE,
                    "timestamp dans le futur : tolerance maximale " + props.maxFuture());
        }
        if (timestamp.isBefore(now.minus(props.maxAge()))) {
            throw ApiException.of(ErrorCode.MEASUREMENT_TOO_OLD,
                    "timestamp trop ancien : limite " + props.maxAge());
        }
    }
}
