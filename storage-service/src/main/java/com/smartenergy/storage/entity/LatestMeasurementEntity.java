package com.smartenergy.storage.entity;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Entite Cassandra latest_measurement_by_sensor (CDC decision D-09, section 6.1).
 *
 * Une ligne par capteur (cle simple sensor_id) : la derniere mesure connue, lue en
 * O(1) pour la route /latest (etape 8).
 *
 * Ecriture (etape 7, CassandraMeasurementWriter) : INSERT ... USING TIMESTAMP
 * = measured_at en microsecondes, pour que Cassandra resolve les conflits sur le
 * temps d'EVENEMENT : une mesure en retard n'ecrase jamais une mesure plus recente.
 *
 * Meme regle de tombstones (D-10) que {@link MeasurementByDayEntity} : jamais de
 * null explicite, les proprietes nulles sont omises a l'insertion.
 */
@Table("latest_measurement_by_sensor")
public class LatestMeasurementEntity {

    @PrimaryKey("sensor_id")
    private String sensorId;

    @Column("measured_at")
    private Instant measuredAt;

    @Column("power_kw")
    private double powerKw;

    @Column("voltage")
    private double voltage;

    @Column("temperature")
    private Double temperature;

    @Column("current_a")
    private Double currentA;

    @Column("frequency_hz")
    private Double frequencyHz;

    @Column("state")
    private String state;

    @Column("event_id")
    private UUID eventId;

    /** Construit l'entite a partir de l'evenement canonique. */
    public static LatestMeasurementEntity from(EnergyMeasurement m) {
        LatestMeasurementEntity e = new LatestMeasurementEntity();
        e.sensorId = m.sensorId();
        e.measuredAt = m.timestamp();
        e.powerKw = m.powerKw();
        e.voltage = m.voltage();
        e.temperature = m.temperature();
        e.currentA = m.currentA();
        e.frequencyHz = m.frequencyHz();
        e.state = m.state() == null ? null : m.state().name();
        e.eventId = m.eventId();
        return e;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(Instant measuredAt) {
        this.measuredAt = measuredAt;
    }

    public double getPowerKw() {
        return powerKw;
    }

    public void setPowerKw(double powerKw) {
        this.powerKw = powerKw;
    }

    public double getVoltage() {
        return voltage;
    }

    public void setVoltage(double voltage) {
        this.voltage = voltage;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getCurrentA() {
        return currentA;
    }

    public void setCurrentA(Double currentA) {
        this.currentA = currentA;
    }

    public Double getFrequencyHz() {
        return frequencyHz;
    }

    public void setFrequencyHz(Double frequencyHz) {
        this.frequencyHz = frequencyHz;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }
}
