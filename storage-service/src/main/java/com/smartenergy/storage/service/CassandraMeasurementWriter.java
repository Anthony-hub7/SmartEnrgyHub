package com.smartenergy.storage.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ecriture idempotente des mesures dans les DEUX tables Cassandra (etape 7, CDC section 7.3 ;
 * decisions D-08, D-09, D-10).
 *
 * <p>Traitement attendu par le CDC : "enregistrer la mesure dans Cassandra" + "mettre a jour si
 * necessaire un cache" — ici le "cache" est la table {@code latest_measurement_by_sensor} (lecture
 * O(1) de la derniere mesure, decision D-09). MongoDB appartient a analytics-service (decision
 * D-02) : le storage ne touche pas au documentaire.
 *
 * <p>Garanties :
 * <ul>
 *   <li><b>Idempotence</b> (attention du CDC) : chaque upsert est idempotent par cle primaire.
 *       Rejouer un evenement ecrase la meme ligne avec les memes valeurs (l'eventId deterministe
 *       D-07 rend le rejeu totalement transparent).</li>
 *   <li><b>D-09 (USING TIMESTAMP)</b> : l'ecriture dans {@code latest} porte le timestamp
 *       d'ECRITURE = temps d'evenement (measured_at en microsecondes). Une mesure en retard qui
 *       arrive apres une plus recente ne l'ecrase donc jamais (conflit resolu par le serveur).</li>
 *   <li><b>D-10 (pas de tombstones)</b> : les colonnes optionnelles absentes sont
 *       {@code unset()} (jamais liees a null) : la valeur existante est conservee, aucune
 *       colonne creuse n'est ecrite.</li>
 *   <li><b>Debit</b> : les ecritures sont lancees en asynchrone (executeAsync) puis attente
 *       groupee ; la premiere erreur se propage → le consumer n'acquitte pas → le lot sera
 *       retente (sans danger, le writer est idempotent).</li>
 * </ul>
 *
 * <p>Pas de batch "logged" entre les deux tables : deux upserts independants et idempotents
 * suffisent (un logged batch n'apporterait qu'un cout de tombstone sans atomicite reelle).
 *
 * <p>Metriques : {@code energy_persist_duration_seconds} (Timer) et
 * {@code energy_persist_errors_total} (Counter).
 */
@Component
public class CassandraMeasurementWriter {

    private static final Logger log = LoggerFactory.getLogger(CassandraMeasurementWriter.class);

    // Ordre des marqueurs = index positionnels (0..9). Le 10e marqueur de la deuxieme requete
    // (USING TIMESTAMP ?) recoit le temps d'ecriture en microsecondes (D-09).
    private static final String INSERT_MAIN = """
            INSERT INTO measurements_by_sensor_day
              (sensor_id, day, measured_at, power_kw, voltage,
               temperature, current_a, frequency_hz, state, event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_LATEST = """
            INSERT INTO latest_measurement_by_sensor
              (sensor_id, measured_at, power_kw, voltage,
               temperature, current_a, frequency_hz, state, event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            USING TIMESTAMP ?
            """;

    private final CqlSession session;
    private final Timer persistTimer;
    private final Counter persistErrors;

    private volatile PreparedStatement mainStatement;
    private volatile PreparedStatement latestStatement;

    public CassandraMeasurementWriter(CqlSession session, MeterRegistry registry) {
        this.session = session;
        this.persistTimer = Timer.builder("energy_persist_duration_seconds")
                .description("Duree d'ecriture d'un lot de mesures dans Cassandra")
                .register(registry);
        this.persistErrors = registry.counter("energy_persist_errors_total");
    }

    /**
     * Persiste un lot dans les deux tables, de facon idempotente.
     *
     * @throws RuntimeException a la premiere ecriture echouee : le consumer n'acquitte pas et le
     *         lot sera retente (le writer est idempotent, donc sans danger).
     */
    public void writeBatch(List<EnergyMeasurement> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        try {
            ensurePrepared();
            List<CompletableFuture<AsyncResultSet>> futures = new ArrayList<>(batch.size() * 2);
            for (EnergyMeasurement m : batch) {
                futures.add(session.executeAsync(bindMain(m)).toCompletableFuture());
                futures.add(session.executeAsync(bindLatest(m)).toCompletableFuture());
            }
            // attente groupee : la premiere exception se propage (pas d'ACK -> re-tentative).
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException e) {
            // join() leve CompletionException (subclass de RuntimeException) des que futur echoue.
            persistErrors.increment();
            log.warn("ecriture Cassandra echouee (lot de {} mesure(s)) : {}",
                    batch.size(), e.getMessage());
            throw e;
        } finally {
            persistTimer.record(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /** Prepare les deux statements une seule fois (appels concurrents possibles : double verification). */
    private void ensurePrepared() {
        if (mainStatement == null) {
            synchronized (this) {
                if (mainStatement == null) {
                    latestStatement = session.prepare(INSERT_LATEST);
                    mainStatement = session.prepare(INSERT_MAIN);
                }
            }
        }
    }

    private BoundStatement bindMain(EnergyMeasurement m) {
        BoundStatementBuilder b = mainStatement.boundStatementBuilder();
        b.setString(0, m.sensorId());
        b.setLocalDate(1, m.day());
        b.setInstant(2, m.timestamp());
        b.setDouble(3, m.powerKw());
        b.setDouble(4, m.voltage());
        setOptionalDouble(b, 5, m.temperature());
        setOptionalDouble(b, 6, m.currentA());
        setOptionalDouble(b, 7, m.frequencyHz());
        setOptionalString(b, 8, m.state() == null ? null : m.state().name());
        b.setUuid(9, m.eventId());
        return b.build();
    }

    private BoundStatement bindLatest(EnergyMeasurement m) {
        BoundStatementBuilder b = latestStatement.boundStatementBuilder();
        b.setString(0, m.sensorId());
        b.setInstant(1, m.timestamp());
        b.setDouble(2, m.powerKw());
        b.setDouble(3, m.voltage());
        setOptionalDouble(b, 4, m.temperature());
        setOptionalDouble(b, 5, m.currentA());
        setOptionalDouble(b, 6, m.frequencyHz());
        setOptionalString(b, 7, m.state() == null ? null : m.state().name());
        b.setUuid(8, m.eventId());
        // D-09 : temps d'ecriture = temps d'evenement (µs). Une mesure en retard n'ecrase pas
        // une plus recente, car son timestamp d'ecriture est plus ancien.
        b.setLong(9, m.timestamp().toEpochMilli() * 1000L);
        return b.build();
    }

    /** D-10 : valeur absente → colonne non liee (unset), jamais null. */
    private static void setOptionalDouble(BoundStatementBuilder b, int index, Double value) {
        if (value == null) {
            b.unset(index);
        } else {
            b.setDouble(index, value);
        }
    }

    /** D-10 : valeur absente → colonne non liee (unset), jamais null. */
    private static void setOptionalString(BoundStatementBuilder b, int index, String value) {
        if (value == null) {
            b.unset(index);
        } else {
            b.setString(index, value);
        }
    }
}
