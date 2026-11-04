package com.smartenergy.storage.repository;

import com.smartenergy.storage.entity.MeasurementByDayEntity;
import com.smartenergy.storage.entity.MeasurementKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;

/**
 * Acces a measurements_by_sensor_day (CDC section 6.1, decision D-08).
 *
 * Regle d'ecriture des requetes (etape 8) : UNE requete par jour, toujours avec la cle
 * de partition complete (sensor_id + day) et bornes de clustering (measured_at) ; jamais
 * de IN sur une cle de partition, jamais de ALLOW FILTERING. Le service d'historique
 * (MeasurementQueryService, etape 8) liste les jours UTC de la plage et appelle
 * {@link #findByDayRange} une fois par jour.
 *
 * La lecture d'une partition renvoie deja les lignes dans l'ordre du clustering
 * (measured_at DESC) : aucun tri applicatif n'est necessaire.
 */
public interface MeasurementByDayRepository extends CassandraRepository<MeasurementByDayEntity, MeasurementKey> {

    /**
     * Mesures d'un capteur pour UN jour UTC, plage de clustering bornee
     * [fromInclusive, toExclusive), dans l'ordre du clustering (DESC).
     */
    @Query("SELECT * FROM measurements_by_sensor_day "
            + "WHERE sensor_id = ?0 AND day = ?1 AND measured_at >= ?2 AND measured_at < ?3")
    List<MeasurementByDayEntity> findByDayRange(String sensorId, LocalDate day,
                                                Instant fromInclusive, Instant toExclusive);

    /**
     * Meme lecture avec une limite de lignes (etape 8 : LIMIT = limit + 1 pour detecter s'il
     * reste des donnees sans lire la partition entiere). CQL accepte un bind marker pour LIMIT.
     */
    @Query("SELECT * FROM measurements_by_sensor_day "
            + "WHERE sensor_id = ?0 AND day = ?1 AND measured_at >= ?2 AND measured_at < ?3 LIMIT ?4")
    List<MeasurementByDayEntity> findByDayRange(String sensorId, LocalDate day,
                                                Instant fromInclusive, Instant toExclusive, int limit);
}
