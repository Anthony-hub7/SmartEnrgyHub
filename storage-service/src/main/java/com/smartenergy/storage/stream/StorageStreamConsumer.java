package com.smartenergy.storage.stream;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.platform.stream.AbstractStreamConsumer;
import com.smartenergy.platform.stream.StreamProperties;
import com.smartenergy.storage.service.CassandraMeasurementWriter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consommateur Redis du storage-group (etape 7, CDC section 7.3).
 *
 * <p>Etend {@link AbstractStreamConsumer} (plateforme) : la boucle XREADGROUP / ACK apres
 * persistance / reprise (XPENDING+XCLAIM) / DLQ apres maxDeliveries y est deja implementee et
 * testee (etape 4). Ce qui est specifique au stockage tient en une methode.
 *
 * <p>Traitements CDC couverts par la plateforme + ce consumer :
 * <ol>
 *   <li>lire un evenement Redis (boucle de base) ;</li>
 *   <li>verifier sa structure (MeasurementEventCodec → MalformedEventException → DLQ) ;</li>
 *   <li>enregistrer la mesure dans Cassandra ({@link CassandraMeasurementWriter#writeBatch}) ;</li>
 *   <li>"mettre a jour un cache" = la table latest (D-09, faite par le writer) ;</li>
 *   <li>accuser le message (ACK apres que processBatch est retour sans exception) ;</li>
 *   <li>journaliser les erreurs sans arreter le consumer (catch + log dans la base).</li>
 * </ol>
 *
 * <p>Une exception de {@code writeBatch} = aucun ACK = le lot sera retente par la reprise
 * (le writer est idempotent : sans danger).
 */
@Component
public class StorageStreamConsumer extends AbstractStreamConsumer {

    private final CassandraMeasurementWriter writer;

    public StorageStreamConsumer(StringRedisTemplate redis, StreamProperties props,
                                 CassandraMeasurementWriter writer) {
        super(redis, props);
        this.writer = writer;
    }

    @Override
    protected void processBatch(List<EnergyMeasurement> batch) {
        writer.writeBatch(batch);
    }

    @Override
    protected String serviceName() {
        return "storage-service";
    }
}
