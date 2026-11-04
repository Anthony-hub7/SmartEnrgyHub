package com.smartenergy.platform.stream;

import com.smartenergy.contracts.measurement.DlqFields;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.MalformedEventException;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Boucle de consommation Redis Streams (etape 4) : consumer group, ACK apres traitement,
 * reprise des messages en attente, DLQ. Utilisee par storage-group et analytics-group.
 * Spring Data Redis 3.5 n'expose pas XAUTOCLAIM : la reprise passe par XPENDING + XCLAIM
 * (meme semantique, avec le compteur de livraisons pour la DLQ).
 */
public abstract class AbstractStreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AbstractStreamConsumer.class);

    protected final StringRedisTemplate redis;
    protected final StreamProperties props;
    private final Object batchLock = new Object();

    private volatile boolean running;
    private String consumerName;
    private Thread pollThread;
    private Thread reclaimThread;

    protected AbstractStreamConsumer(StringRedisTemplate redis, StreamProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Traite un lot ; lever une exception = aucun ACK (le lot sera retente). */
    protected abstract void processBatch(List<EnergyMeasurement> batch);

    /** Nom du service pour le champ DlqFields.SERVICE. */
    protected abstract String serviceName();

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (props.group() == null || props.group().isBlank()) {
            throw new IllegalStateException("smartenergy.stream.group est obligatoire pour un consommateur");
        }
        consumerName = resolveConsumerName();
        ensureGroup();
        running = true;
        pollThread = new Thread(this::pollLoop, serviceName() + "-stream-poll");
        reclaimThread = new Thread(this::reclaimLoop, serviceName() + "-stream-reclaim");
        pollThread.start();
        reclaimThread.start();
        log.info("consommateur stream demarre stream={} group={} consumer={}",
                props.name(), props.group(), consumerName);
    }

    @Override
    public synchronized void stop() {
        running = false;
        interruptQuietly(pollThread);
        interruptQuietly(reclaimThread);
        joinQuietly(pollThread);
        joinQuietly(reclaimThread);
        pollThread = null;
        reclaimThread = null;
        log.info("consommateur stream arrete group={} consumer={}", props.group(), consumerName);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(props.name(), ReadOffset.from("0"), props.group());
        } catch (RuntimeException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    private void pollLoop() {
        drainOwnPending();
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = read(ReadOffset.lastConsumed(), true);
                if (records != null && !records.isEmpty()) {
                    handleRecords(records);
                }
            } catch (RuntimeException e) {
                if (isStopSignal(e)) {
                    break;
                }
                log.warn("lecture XREADGROUP impossible sur {} : {}", props.name(), e.getMessage());
                sleepQuietly(Duration.ofMillis(Math.max(50L, props.blockMs())));
            }
        }
    }

    /** Messages deja livres a cette instance (meme nom de consumer) avant un redemarrage. */
    private void drainOwnPending() {
        while (running) {
            List<MapRecord<String, Object, Object>> batch;
            try {
                batch = read(ReadOffset.from("0-0"), false);
            } catch (RuntimeException e) {
                if (isStopSignal(e)) {
                    return;
                }
                log.warn("reprise PEL impossible : {}", e.getMessage());
                return;
            }
            if (batch == null || batch.isEmpty()) {
                return;
            }
            RecordId first = batch.get(0).getId();
            handleRecords(batch);
            List<MapRecord<String, Object, Object>> still;
            try {
                still = read(ReadOffset.from("0-0"), false);
            } catch (RuntimeException e) {
                return;
            }
            if (still == null || still.isEmpty()) {
                return;
            }
            if (first.equals(still.get(0).getId())) {
                return;
            }
        }
    }

    private void reclaimLoop() {
        while (running) {
            try {
                reclaimOnce();
            } catch (RuntimeException e) {
                if (isStopSignal(e)) {
                    break;
                }
                log.warn("reprise XPENDING/XCLAIM impossible : {}", e.getMessage());
            }
            sleepQuietly(props.claimInterval());
        }
    }

    private void reclaimOnce() {
        PendingMessages pending = redis.opsForStream()
                .pending(props.name(), props.group(), Range.unbounded(), Math.max(props.batchSize(), 100));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<RecordId> toDlq = new ArrayList<>();
        List<RecordId> toRetry = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(props.claimIdle()) < 0) {
                continue;
            }
            if (message.getTotalDeliveryCount() >= props.maxDeliveries()) {
                toDlq.add(message.getId());
            } else {
                toRetry.add(message.getId());
            }
        }
        if (!toDlq.isEmpty()) {
            for (RecordId id : toDlq) {
                Map<String, String> fields = loadFields(id);
                sendToDlq(id.getValue(), fields, "maxDeliveries=" + props.maxDeliveries());
                acknowledge(List.of(id));
            }
        }
        if (toRetry.isEmpty()) {
            return;
        }
        RedisStreamCommands.XClaimOptions options = RedisStreamCommands.XClaimOptions
                .minIdle(props.claimIdle())
                .ids(toRetry.toArray(RecordId[]::new));
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream()
                .claim(props.name(), props.group(), consumerName, options);
        if (claimed != null && !claimed.isEmpty()) {
            handleRecords(claimed);
        }
    }

    private void handleRecords(List<MapRecord<String, Object, Object>> records) {
        synchronized (batchLock) {
            List<EnergyMeasurement> valid = new ArrayList<>();
            List<RecordId> validIds = new ArrayList<>();
            for (MapRecord<String, Object, Object> record : records) {
                Map<String, String> fields = stringFields(record.getValue());
                try {
                    valid.add(MeasurementEventCodec.fromFields(fields));
                    validIds.add(record.getId());
                } catch (MalformedEventException e) {
                    sendToDlq(record.getId().getValue(), fields, e.getMessage());
                    acknowledge(List.of(record.getId()));
                }
            }
            if (valid.isEmpty()) {
                return;
            }
            try {
                processBatch(valid);
                acknowledge(validIds);
            } catch (RuntimeException e) {
                log.warn("traitement du lot echoue (pas d'ACK, retry) group={} size={} : {}",
                        props.group(), valid.size(), e.getMessage());
            }
        }
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset, boolean block) {
        StreamReadOptions options = StreamReadOptions.empty().count(props.batchSize());
        if (block) {
            options = options.block(Duration.ofMillis(props.blockMs()));
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(props.group(), consumerName),
                options,
                StreamOffset.create(props.name(), offset));
        return records == null ? List.of() : records;
    }

    private void acknowledge(List<RecordId> ids) {
        if (ids.isEmpty()) {
            return;
        }
        redis.opsForStream().acknowledge(props.name(), props.group(), ids.toArray(RecordId[]::new));
    }

    private void sendToDlq(String originalId, Map<String, String> fields, String error) {
        Map<String, String> dlq = new LinkedHashMap<>(fields);
        dlq.put(DlqFields.ERROR, error == null ? "unknown" : error);
        dlq.put(DlqFields.FAILED_AT, Instant.now().toString());
        dlq.put(DlqFields.SERVICE, serviceName());
        dlq.put(DlqFields.ORIGINAL_ID, originalId);
        redis.opsForStream().add(StringRecord.of(dlq).withStreamKey(props.dlqName()));
        log.warn("message {} envoye en DLQ {} : {}", originalId, props.dlqName(), error);
    }

    private Map<String, String> loadFields(RecordId id) {
        List<MapRecord<String, Object, Object>> found = redis.opsForStream()
                .range(props.name(), Range.closed(id.getValue(), id.getValue()));
        if (found == null || found.isEmpty()) {
            return Map.of();
        }
        return stringFields(found.get(0).getValue());
    }

    private String resolveConsumerName() {
        if (props.consumerName() != null && !props.consumerName().isBlank()) {
            return props.consumerName();
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return serviceName() + "-" + host;
    }

    private static Map<String, String> stringFields(Map<?, ?> value) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (value == null) {
            return fields;
        }
        value.forEach((k, v) -> fields.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return fields;
    }

    private static boolean isBusyGroup(Throwable error) {
        while (error != null) {
            String message = error.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    private boolean isStopSignal(Throwable error) {
        if (!running) {
            return true;
        }
        while (error != null) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
            String name = error.getClass().getSimpleName();
            if (name.contains("Interrupted")) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static void interruptQuietly(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(props.blockMs() + 2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
