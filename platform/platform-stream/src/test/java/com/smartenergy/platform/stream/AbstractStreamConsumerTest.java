package com.smartenergy.platform.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartenergy.contracts.measurement.DlqFields;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.StreamContract;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Preuve etape 4 : consumer group, ACK apres traitement, reprise apres arret, DLQ.
 */
@Testcontainers
class AbstractStreamConsumerTest {

    private static final String REDIS_PASSWORD = "test-pass";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private RecordingConsumer consumer;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        config.setPassword(REDIS_PASSWORD);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null && consumer.isRunning()) {
            consumer.stop();
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void creeLeGroupeDeFaconIdempotente() {
        consumer = new RecordingConsumer(template, properties("c1"));
        consumer.start();
        consumer.stop();
        consumer.start();

        StreamInfo.XInfoGroups groups = template.opsForStream().groups(StreamContract.STREAM);
        assertThat(groups.stream().map(StreamInfo.XInfoGroup::groupName))
                .contains(StreamContract.STORAGE_GROUP);
    }

    @Test
    void mesureValide_estConsommeeEtAcquittee() {
        publish(measurement("SENSOR-001", Instant.parse("2026-10-26T12:00:00Z")));
        consumer = new RecordingConsumer(template, properties("c-ok"));
        consumer.start();

        awaitUntil(() -> consumer.received.size() == 1, Duration.ofSeconds(5));
        awaitUntil(() -> pendingCount() == 0, Duration.ofSeconds(5));
        assertThat(consumer.received.get(0).sensorId()).isEqualTo("SENSOR-001");
    }

    @Test
    void evenementIllisible_vaEnDlq_etLeFluxContinue() {
        template.opsForStream().add(StringRecord.of(Map.of("broken", "yes"))
                .withStreamKey(StreamContract.STREAM));
        publish(measurement("SENSOR-002", Instant.parse("2026-10-26T12:00:01Z")));

        consumer = new RecordingConsumer(template, properties("c-dlq"));
        consumer.start();

        awaitUntil(() -> consumer.received.size() == 1, Duration.ofSeconds(5));
        awaitUntil(() -> dlqLen() == 1, Duration.ofSeconds(5));
        MapRecord<String, Object, Object> dlq = template.opsForStream()
                .range(StreamContract.DLQ_STREAM, org.springframework.data.domain.Range.unbounded())
                .get(0);
        assertThat(dlq.getValue()).containsKeys(DlqFields.ERROR, DlqFields.ORIGINAL_ID, DlqFields.SERVICE);
        assertThat(dlq.getValue().get(DlqFields.SERVICE)).isEqualTo("storage-service");
        assertThat(consumer.received.get(0).sensorId()).isEqualTo("SENSOR-002");
        awaitUntil(() -> pendingCount() == 0, Duration.ofSeconds(5));
    }

    @Test
    void arretPuisReprise_nePerdAucunMessage() {
        consumer = new RecordingConsumer(template, properties("c-resume"));
        consumer.start();
        awaitUntil(() -> groupExists(), Duration.ofSeconds(5));
        consumer.stop();

        int published = 5;
        for (int i = 0; i < published; i++) {
            publish(measurement("SENSOR-00" + i, Instant.parse("2026-10-26T12:01:0" + i + "Z")));
        }
        assertThat(streamLen()).isEqualTo(published);
        assertThat(groupLag()).isEqualTo(published);

        consumer = new RecordingConsumer(template, properties("c-resume"));
        consumer.start();
        awaitUntil(() -> consumer.received.size() == published, Duration.ofSeconds(8));
        awaitUntil(() -> pendingCount() == 0 && groupLag() == 0, Duration.ofSeconds(5));
    }

    @Test
    void echecDeTraitement_restePending_puisEstRetente() {
        publish(measurement("SENSOR-FAIL", Instant.parse("2026-10-26T12:02:00Z")));
        consumer = new RecordingConsumer(template, properties("c-retry"));
        consumer.fail.set(true);
        consumer.start();

        awaitUntil(() -> pendingCount() >= 1, Duration.ofSeconds(5));
        assertThat(consumer.received).isEmpty();

        consumer.fail.set(false);
        awaitUntil(() -> consumer.received.size() == 1, Duration.ofSeconds(8));
        awaitUntil(() -> pendingCount() == 0, Duration.ofSeconds(5));
    }

    @Test
    void tropDeLivraisons_envoieEnDlq() {
        publish(measurement("SENSOR-POISON", Instant.parse("2026-10-26T12:03:00Z")));
        StreamProperties tight = new StreamProperties(
                StreamContract.STREAM,
                StreamContract.DLQ_STREAM,
                StreamContract.DEFAULT_MAXLEN,
                StreamContract.STORAGE_GROUP,
                "c-max",
                10,
                100L,
                Duration.ofMillis(50),
                Duration.ofMillis(80),
                2);
        consumer = new RecordingConsumer(template, tight);
        consumer.fail.set(true);
        consumer.start();

        awaitUntil(() -> dlqLen() == 1, Duration.ofSeconds(10));
        awaitUntil(() -> pendingCount() == 0, Duration.ofSeconds(5));
        assertThat(consumer.received).isEmpty();
    }

    private StreamProperties properties(String consumerName) {
        return new StreamProperties(
                StreamContract.STREAM,
                StreamContract.DLQ_STREAM,
                StreamContract.DEFAULT_MAXLEN,
                StreamContract.STORAGE_GROUP,
                consumerName,
                10,
                100L,
                Duration.ofMillis(200),
                Duration.ofMillis(150),
                5);
    }

    private void publish(EnergyMeasurement measurement) {
        template.opsForStream().add(StringRecord.of(MeasurementEventCodec.toFields(measurement))
                .withStreamKey(StreamContract.STREAM));
    }

    private static EnergyMeasurement measurement(String sensorId, Instant timestamp) {
        return EnergyMeasurement.from(
                new EnergyMeasurementRequest(sensorId, timestamp, 3.72, 226.4, 29.1, null, null, null),
                "trace",
                Instant.parse("2026-10-26T12:00:00Z"));
    }

    private long streamLen() {
        Long size = template.opsForStream().size(StreamContract.STREAM);
        return size == null ? 0L : size;
    }

    private long dlqLen() {
        Long size = template.opsForStream().size(StreamContract.DLQ_STREAM);
        return size == null ? 0L : size;
    }

    private long pendingCount() {
        var pending = template.opsForStream().pending(StreamContract.STREAM, StreamContract.STORAGE_GROUP);
        return pending == null ? 0L : pending.getTotalPendingMessages();
    }

    private long groupLag() {
        StreamInfo.XInfoGroups groups = template.opsForStream().groups(StreamContract.STREAM);
        return groups.stream()
                .filter(g -> StreamContract.STORAGE_GROUP.equals(g.groupName()))
                .mapToLong(g -> {
                    Object lag = g.getRaw().get("lag");
                    if (lag instanceof Number number) {
                        return number.longValue();
                    }
                    return 0L;
                })
                .findFirst()
                .orElse(0L);
    }

    private boolean groupExists() {
        try {
            return template.opsForStream().groups(StreamContract.STREAM).stream()
                    .anyMatch(g -> StreamContract.STORAGE_GROUP.equals(g.groupName()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError last = new AssertionError("condition non atteinte");
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException | AssertionError e) {
                last = e instanceof AssertionError a ? a : new AssertionError(e);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw last;
    }

    private static final class RecordingConsumer extends AbstractStreamConsumer {
        private final List<EnergyMeasurement> received = new CopyOnWriteArrayList<>();
        private final AtomicBoolean fail = new AtomicBoolean();

        private RecordingConsumer(StringRedisTemplate redis, StreamProperties props) {
            super(redis, props);
        }

        @Override
        protected void processBatch(List<EnergyMeasurement> batch) {
            if (fail.get()) {
                throw new IllegalStateException("persistance refusee");
            }
            received.addAll(batch);
        }

        @Override
        protected String serviceName() {
            return "storage-service";
        }
    }
}
