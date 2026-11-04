package com.smartenergy.storage.entity;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Entite Cassandra measurements_by_sensor_day (CDC section 6.1, decision D-08).
 *
 * Une ligne = une mesure, indexee par (sensor_id, day, measured_at DESC). La cle
 * composite est {@link MeasurementKey}.
 *
 * ATTENTION tombstones (decision D-10) : les colonnes optionnelles (temperature,
 * current_a, frequency_hz, state) sont des wrapper (Double/String) et ne sont JAMAIS
 * ecrites explicitement en null : le mapper Spring Data omet les proprietes nulles
 * a l'insertion (insertNulls = false par defaut). Une valeur absente = colonne
 * absente = aucun tombstone.
 */
@Table("measurements_by_sensor_day")
public class MeasurementByDayEntity {

    @PrimaryKey
    private MeasurementKey key;

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

    /** Construit l'entite a partir de l'evenement canonique (jour UTC = cle de partition). */
    public static MeasurementByDayEntity from(EnergyMeasurement m) {
        MeasurementByDayEntity e = new MeasurementByDayEntity();
        e.key = new MeasurementKey(m.sensorId(), m.day(), m.timestamp());
        e.powerKw = m.powerKw();
        e.voltage = m.voltage();
        e.temperature = m.temperature();
        e.currentA = m.currentA();
        e.frequencyHz = m.frequencyHz();
        e.state = m.state() == null ? null : m.state().name();
        e.eventId = m.eventId();
        return e;
    }

    public MeasurementKey getKey() {
        return key;
    }

    public void setKey(MeasurementKey key) {
        this.key = key;
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
