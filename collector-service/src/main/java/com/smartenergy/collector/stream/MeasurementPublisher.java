package com.smartenergy.collector.stream;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.platform.stream.StreamProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publie chaque mesure validee dans le stream Redis (XADD MAXLEN ~, decision D-06).
 * La serialisation passe UNIQUEMENT par MeasurementEventCodec : c'est le contrat partage
 * avec storage-service et analytics-service.
 */
@Component
public class MeasurementPublisher {

    private static final Logger log = LoggerFactory.getLogger(MeasurementPublisher.class);

    private final StringRedisTemplate redis;
    private final StreamProperties props;
    private final Counter published;
    private final Timer publishTimer;

    public MeasurementPublisher(StringRedisTemplate redis, StreamProperties props, MeterRegistry registry) {
        this.redis = redis;
        this.props = props;
        this.published = registry.counter("energy_measurements_published_total");
        this.publishTimer = Timer.builder("energy_measurement_publish").register(registry);
    }

    /** @return l'id du message Redis (ex. 1700000000000-0) pour le journal. */
    public String publish(EnergyMeasurement measurement) {
        Map<String, String> fields = MeasurementEventCodec.toFields(measurement);
        StringRecord record = StringRecord.of(fields).withStreamKey(props.name());
        RedisStreamCommands.XAddOptions options = RedisStreamCommands.XAddOptions
                .maxlen(props.maxLen())
                .approximateTrimming(true);
        long start = System.nanoTime();
        try {
            String redisId = redis.opsForStream().add(record, options).getValue();
            published.increment();
            return redisId;
        } catch (RuntimeException e) {
            log.warn("XADD impossible sur {} : {}", props.name(), e.getMessage());
            throw ApiException.unavailable("Redis indisponible", 5);
        } finally {
            publishTimer.record(Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
