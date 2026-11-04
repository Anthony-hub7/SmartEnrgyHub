package com.smartenergy.storage.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

/**
 * Cle primaire composite de measurements_by_sensor_day (CDC section 6.1, decision D-08).
 *
 * <pre>
 * PRIMARY KEY ((sensor_id, day), measured_at)
 * </pre>
 *
 * <ul>
 *   <li>{@code sensor_id} + {@code day} = cle de PARTITION : la partition reste bornee
 *       (~43 200 lignes/jour a 1 mesure / 2 s) au lieu de cumuler plusieurs annees
 *       de mesures dans une seule partition (guide 13.1).</li>
 *   <li>{@code measured_at} = cle de CLUSTERING en DESCENDING : les mesures les plus
 *       recentes d'abord, sans tri applicatif.</li>
 * </ul>
 *
 * {@code day} est la date UTC de {@code measured_at} (voir {@code EnergyMeasurement.day()}).
 */
@PrimaryKeyClass
public class MeasurementKey implements java.io.Serializable {

    @PrimaryKeyColumn(name = "sensor_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String sensorId;

    @PrimaryKeyColumn(name = "day", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private LocalDate day;

    @PrimaryKeyColumn(name = "measured_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED,
            ordering = org.springframework.data.cassandra.core.cql.Ordering.DESCENDING)
    private Instant measuredAt;

    public MeasurementKey() {}

    public MeasurementKey(String sensorId, LocalDate day, Instant measuredAt) {
        this.sensorId = sensorId;
        this.day = day;
        this.measuredAt = measuredAt;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(Instant measuredAt) {
        this.measuredAt = measuredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeasurementKey other)) {
            return false;
        }
        return Objects.equals(sensorId, other.sensorId)
                && Objects.equals(day, other.day)
                && Objects.equals(measuredAt, other.measuredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sensorId, day, measuredAt);
    }

    @Override
    public String toString() {
        return "MeasurementKey[" + sensorId + ", " + day + ", " + measuredAt + "]";
    }
}
