package com.smartenergy.storage;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.jayway.jsonpath.JsonPath;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EventIds;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.MeasurementSchema;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.contracts.measurement.StreamContract;
import com.smartenergy.storage.entity.LatestMeasurementEntity;
import com.smartenergy.storage.entity.MeasurementByDayEntity;
import com.smartenergy.storage.repository.MeasurementByDayRepository;
import com.smartenergy.storage.service.CassandraMeasurementWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests d'integration etape 5 (CDC section 12, scenarios 3 et 8 au niveau Cassandra) :
 * ecriture d'entitee, lecture de l'historique d'un capteur pour UN jour (bornee,
 * ordonnee DESC par clustering), doublon -> une seule ligne, et mesure de temps de
 * reponse sur un jeu de donnees plus important (~10 000 lignes).
 *
 * Cible : la stack docker-compose de l'APPLICATION (smartenergy-cassandra publie sur
 * localhost:9042 via docker-compose.override.yml) — pas de conteneur dedie au test.
 * Le schema est celui de scripts/init-cassandra.cql : @BeforeAll rejoue le script
 * (idempotent, CREATE IF NOT EXISTS) puis VERIFIE les colonnes attendues ; un schema
 * obsolete (ancienne version du script) produit un message d'aide clair plutot qu'une
 * erreur obscure lors du premier INSERT.
 *
 * Les cles primaires sont deterministes (eventId v5 f(sensorId, timestamp)) : rejeu du
 * test = memes lignes en base, resultats stables sans accumulation.
 *
 * Aucun acces Redis a l'etape 5 (pas encore de consumer stream dans le storage) :
 * seul Cassandra est requis ; docker-compose up suffit (voir README sections 3 et 4).
 *
 * Etape 7 : la boucle consumer / ACK / reprise / DLQ est couverte par le test de la
 * plateforme (AbstractStreamConsumerTest, etape 4). Ici on prouve le BRANCHEMENT :
 * publication -> StorageStreamConsumer -> CassandraMeasurementWriter -> les deux tables,
 * idempotence (sc. 8), USING TIMESTAMP D-09 (mesure en retard), D-10 (pas de null).
 *
 * Etape 8 : scenario 3 du CDC au niveau API — publication -> GET historique (from/to,
 * pagination curseur sur 2 pages, ordre asc/desc), GET latest (200/404 + cache-aside
 * Redis), parametres invalides (400) et documentation OpenAPI (/v3/api-docs).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageIntegrationTest {

    private static final String CASSANDRA_HOST = "localhost";
    private static final int CASSANDRA_PORT = 9042;
    private static final String LOCAL_DATACENTER = "datacenter1";
    private static final String KEYSPACE = "smartenergy";

    private static final Set<String> COLONNES_ATTENDUES = Set.of(
            "sensor_id", "day", "measured_at", "power_kw", "voltage",
            "temperature", "current_a", "frequency_hz", "state", "event_id");

    @BeforeAll
    static void preparerSchema() throws IOException, InterruptedException {
        String script = Files.readString(schemaPath());
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(InetSocketAddress.createUnresolved(CASSANDRA_HOST, CASSANDRA_PORT))
                .withLocalDatacenter(LOCAL_DATACENTER)
                .build()) {
            // Rejeu du livrable script CQL : idempotent, comme cassandra-init du compose.
            for (String statement : cqlStatements(script)) {
                session.execute(SimpleStatement.newInstance(statement));
            }
            // meme colonnes de valeurs que la table principale, sans "day"
            // (cle simple sensor_id) : voir scripts/init-cassandra.cql.
            verifierColonnes(session, "measurements_by_sensor_day", COLONNES_ATTENDUES);
            verifierColonnes(session, "latest_measurement_by_sensor",
                    COLONNES_ATTENDUES.stream().filter(c -> !c.equals("day")).collect(Collectors.toSet()));
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.cassandra.contact-points", () -> CASSANDRA_HOST + ':' + CASSANDRA_PORT);
        registry.add("spring.cassandra.local-datacenter", () -> LOCAL_DATACENTER);
        registry.add("spring.cassandra.keyspace-name", () -> KEYSPACE);
    }

    @Autowired
    MeasurementByDayRepository repository;

    @Autowired
    org.springframework.boot.autoconfigure.cassandra.CassandraProperties cassandraProps;

    @Autowired
    org.springframework.core.env.Environment env;

    @Autowired
    org.springframework.data.cassandra.core.CassandraTemplate cassandraTemplate;

    @Autowired
    CassandraMeasurementWriter writer;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    MockMvc mockMvc;

    // ------------------------------------------------------------------ etape 5

    @Test
    void ecriturePuisLectureHistoriqueUnJour_ordonneDESC_etBornesClustering() {
        // 3 mesures le 2026-09-30, 1 autre le 2026-10-01 : la lecture d'un jour ne
        // doit pas voir les autres partitions.
        EnergyMeasurement m0800 = measurement("SENSOR-001", Instant.parse("2026-09-30T08:15:00Z"),
                29.1, 16.0, 50.0, SensorState.ON);
        EnergyMeasurement m0802 = measurement("SENSOR-001", Instant.parse("2026-09-30T08:15:02Z"),
                null, null, null, null);   // champs absents : D-10 (pas de null ecrit)
        EnergyMeasurement m0804 = measurement("SENSOR-001", Instant.parse("2026-09-30T08:15:04Z"),
                29.3, 16.2, 50.01, SensorState.ON);
        EnergyMeasurement jourSuivant = measurement("SENSOR-001", Instant.parse("2026-10-01T08:15:00Z"),
                28.0, 15.0, 50.0, SensorState.OFF);
        repository.saveAll(List.of(
                MeasurementByDayEntity.from(m0800),
                MeasurementByDayEntity.from(m0802),
                MeasurementByDayEntity.from(m0804),
                MeasurementByDayEntity.from(jourSuivant)));

        List<MeasurementByDayEntity> jour1 = readDay("SENSOR-001", LocalDate.parse("2026-09-30"));

        assertEquals(3, jour1.size(), "une requete par jour : seule la partition du jour est lue");
        // clustering measured_at DESC : la mesure la plus recente d'abord
        assertEquals(Instant.parse("2026-09-30T08:15:04Z"), jour1.get(0).getKey().getMeasuredAt());
        assertEquals(Instant.parse("2026-09-30T08:15:02Z"), jour1.get(1).getKey().getMeasuredAt());
        assertEquals(Instant.parse("2026-09-30T08:15:00Z"), jour1.get(2).getKey().getMeasuredAt());

        // valeurs conservees
        MeasurementByDayEntity first = jour1.get(0);
        assertEquals("SENSOR-001", first.getKey().getSensorId());
        assertEquals(LocalDate.parse("2026-09-30"), first.getKey().getDay());
        assertEquals(3.72, first.getPowerKw(), 1e-9);
        assertEquals(226.4, first.getVoltage(), 1e-9);
        assertEquals(29.3, first.getTemperature(), 1e-9);
        assertEquals("ON", first.getState());
        assertEquals(m0804.eventId(), first.getEventId(), "event_id conserve pour la tracabilite");

        // D-10 : champs optionnels absents -> relus null (colonnes omises, aucun tombstone)
        MeasurementByDayEntity sansOptionnels = jour1.get(1);
        assertEquals(m0802.eventId(), sansOptionnels.getEventId());
        assertNull(sansOptionnels.getTemperature());
        assertNull(sansOptionnels.getCurrentA());
        assertNull(sansOptionnels.getFrequencyHz());
        assertNull(sansOptionnels.getState());

        // le jour suivant n'apparait pas dans la lecture du jour 1
        List<MeasurementByDayEntity> jour2 = readDay("SENSOR-001", LocalDate.parse("2026-10-01"));
        assertEquals(1, jour2.size());
        assertEquals(Instant.parse("2026-10-01T08:15:00Z"), jour2.get(0).getKey().getMeasuredAt());

        // bornes de clustering [from, to) : from INCLUS (08:15:02 -> 2 lignes,
        // 08:15:00 exclu), puis to EXCLU (09:00 absent ici ; borne haute stricte
        // verifiee avec to = 08:15:04 -> 2 lignes, 08:15:04 exclu)
        List<MeasurementByDayEntity> depuis = repository.findByDayRange("SENSOR-001",
                LocalDate.parse("2026-09-30"),
                Instant.parse("2026-09-30T08:15:02Z"),
                Instant.parse("2026-09-30T09:00:00Z"));
        assertEquals(2, depuis.size(), "from inclus : 08:15:02 et 08:15:04");
        List<MeasurementByDayEntity> avant = repository.findByDayRange("SENSOR-001",
                LocalDate.parse("2026-09-30"),
                Instant.parse("2026-09-30T08:15:00Z"),
                Instant.parse("2026-09-30T08:15:04Z"));
        assertEquals(2, avant.size(), "to exclu : 08:15:00 et 08:15:02 seulement");
    }

    @Test
    void doublon_memeMessageRejoue_neCreeQuUneLigne() {
        EnergyMeasurement m = measurement("SENSOR-002", Instant.parse("2026-09-29T10:00:00Z"),
                25.0, 12.0, 50.0, SensorState.ON);

        repository.save(MeasurementByDayEntity.from(m));
        repository.save(MeasurementByDayEntity.from(m));   // rejeu (idempotence par cle primaire)

        List<MeasurementByDayEntity> rows = readDay("SENSOR-002", LocalDate.parse("2026-09-29"));
        assertEquals(1, rows.size(), "upsert sur la meme cle primaire : une seule ligne");
        assertEquals(m.eventId(), rows.get(0).getEventId());
    }

    @Test
    void perfLectureUnJour_sur10kLignes() {
        LocalDate jour = LocalDate.parse("2026-10-10");
        Instant base = jour.atStartOfDay(ZoneOffset.UTC).toInstant();
        int parJour = 500;                       // pas de 172,8 s : pas de 2 s -> 43 200/jour
        int jours = 4;
        int capteurs = 5;
        long stepMillis = Duration.ofDays(1).toMillis() / parJour;

        List<EnergyMeasurement> lot = new ArrayList<>(capteurs * jours * parJour);
        for (int s = 0; s < capteurs; s++) {
            String id = "SENSOR-PERF-" + s;
            for (int j = 0; j < jours * parJour; j++) {
                lot.add(measurement(id, base.plusMillis(j * stepMillis),
                        20.0 + (j % 10) * 0.1, 15.0, 50.0, SensorState.ON));
            }
        }
        long tInsert = System.nanoTime();
        for (EnergyMeasurement m : lot) {
            repository.save(MeasurementByDayEntity.from(m));
        }
        long insertMs = (System.nanoTime() - tInsert) / 1_000_000;

        String capteur = "SENSOR-PERF-0";
        for (int i = 0; i < 3; i++) {                            // chauffe (preparation de statements)
            assertEquals(parJour, readDay(capteur, jour).size());
        }
        long[] durations = new long[20];
        for (int i = 0; i < durations.length; i++) {
            long t0 = System.nanoTime();
            List<MeasurementByDayEntity> rows = readDay(capteur, jour);
            durations[i] = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(parJour, rows.size(), "lecture bornee au jour demande");
        }
        long mediane = percentile(durations, 50);
        long p95 = percentile(durations, 95);
        System.out.printf("%nPERF etape 5 : %d lignes inserees en %d ms (5 capteurs x %d jours x %d lignes)%n"
                        + "PERF etape 5 : lecture d'un jour (%d lignes) - mediane %d ms, p95 %d ms sur %d mesures%n",
                lot.size(), insertMs, jours, parJour, parJour, mediane, p95, durations.length);
        assertTrue(p95 < 1_000, "p95 = " + p95 + " ms : cible locale < 1000 ms (cible CDC : p95 < 300 ms)");
    }

    /**
     * Garde-fou du prefixe de configuration Cassandra : verifie que les proprietes sont
     * bien liees via "spring.cassandra.*" (Boot 3.5). L'ancien prefixe
     * "spring.data.cassandra.*" est deprecie depuis 3.4 : un retour en arriere silencieux
     * laisserait la session sur les valeurs par defaut (localhost:9042) sans que rien
     * n'echoue au demarrage — d'ou ce test (voir la regression de l'etape 5).
     */
    @Test
    void configurationCassandra_estLieeViaLePrefixeSpringCassandra() {
        // Boot 3.5 : getContactPoints() renvoie List<String> (plus InetSocketAddress).
        assertEquals(List.of("localhost:9042"), cassandraProps.getContactPoints(),
                "contact-points = localhost:9042");
        assertEquals("smartenergy", cassandraProps.getKeyspaceName());
        assertEquals("datacenter1", cassandraProps.getLocalDatacenter());
        assertEquals("none", env.getProperty("spring.cassandra.schema-action"),
                "schema-action doit venir du application.yml (prefixe spring.cassandra) :"
                        + " un ancien prefixe non lie laisserait cette valeur nulle");
        // session reellement connectee a la stack de l'app et voyant les bonnes colonnes
        assertEquals(List.of("4.1.12"), cassandraTemplate.select(
                "SELECT release_version FROM system.local", String.class));
        assertTrue(cassandraTemplate.select(
                        "SELECT column_name FROM system_schema.columns WHERE keyspace_name='smartenergy'"
                                + " AND table_name='measurements_by_sensor_day'", String.class)
                .containsAll(COLONNES_ATTENDUES), "toutes les colonnes de l'entite existent cote serveur");
    }

    // ------------------------------------------------------------------ etape 7

    /**
     * Critere de validation du CDC (etape 7) : une mesure envoyee au Collector est retrouvee
     * dans Cassandra. Ici on publie dans le stream EXACTEMENT comme le collector (XADD +
     * MeasurementEventCodec), le StorageStreamConsumer (auto-demare dans le contexte) la
     * consomme, le writer la persiste dans les DEUX tables, puis acquitte (ACK).
     */
    @Test
    void critereValidation_mesurePubliee_estRetrouveeDansCassandra_puisAcquittee() {
        EnergyMeasurement m = measurement("SENSOR-E2E-1", Instant.parse("2026-11-01T10:00:00Z"),
                21.5, 10.1, 50.02, SensorState.ON);
        // publication identique au collector
        redis.opsForStream().add(StringRecord.of(MeasurementEventCodec.toFields(m))
                .withStreamKey(StreamContract.STREAM));

        // le consumer persiste ; on attend la ligne dans l'historique (une requete par jour)
        LocalDate jour = LocalDate.parse("2026-11-01");
        awaitUntil(() -> !readDay("SENSOR-E2E-1", jour).isEmpty(), Duration.ofSeconds(20));

        // table principale : valeurs conservees (powerKw/voltage = sentinelles du helper,
        // eventId = discriminant reel)
        MeasurementByDayEntity row = readDay("SENSOR-E2E-1", jour).get(0);
        assertEquals(3.72, row.getPowerKw(), 1e-9);
        assertEquals("ON", row.getState());
        assertEquals(m.eventId(), row.getEventId());

        // table latest (le "cache" D-09) : la mesure est aussi la plus recente
        LatestMeasurementEntity latest = latestOf("SENSOR-E2E-1");
        assertNotNull(latest, "latest_measurement_by_sensor alimentee par le meme lot");
        assertEquals(m.eventId(), latest.getEventId());
        assertEquals(3.72, latest.getPowerKw(), 1e-9);

        // ACK : le message consomme n'est plus en attente pour storage-group
        awaitUntil(() -> pendingCount() == 0, Duration.ofSeconds(10));
    }

    /** Scenario 8 (doublon) : rejeu du meme evenement → une seule ligne, latest incoherent impossible. */
    @Test
    void writeBatch_rejoue_neCreeQuUneLigne() {
        String sensor = "SENSOR-IDEM-1";
        EnergyMeasurement m = measurement(sensor, Instant.parse("2026-11-03T09:00:00Z"),
                22.0, 11.0, 50.0, SensorState.ON);

        writer.writeBatch(List.of(m));
        writer.writeBatch(List.of(m));   // rejeu (idempotence par cle primaire + USING TIMESTAMP)

        assertEquals(1, readDay(sensor, LocalDate.parse("2026-11-03")).size(),
                "upsert idempotent : une seule ligne pour un meme evenement rejoue");
        assertEquals(m.eventId(), latestOf(sensor).getEventId());
    }

    /**
     * Decision D-09 : le timestamp d'ecriture de "latest" = temps d'evenement (µs). Une mesure
     * en retard qui arrive APRES une plus recente ne doit pas l'ecraser, alors qu'un simple
     * upsert (dernier arrivé, premier servi) l'ecraserait.
     */
    @Test
    void mesureEnRetard_nEcrasePasLaPlusRecente_usingTimestamp() {
        String sensor = "SENSOR-TS-1";
        EnergyMeasurement recente = measurement(sensor, Instant.parse("2026-11-02T12:00:10Z"),
                30.0, 15.0, 50.0, SensorState.ON);
        EnergyMeasurement ancienne = measurement(sensor, Instant.parse("2026-11-02T12:00:00Z"),
                20.0, 9.0, 49.9, SensorState.OFF);

        writer.writeBatch(List.of(recente));   // la plus recente arrive en premier
        writer.writeBatch(List.of(ancienne));  // une mesure en retard arrive ensuite

        LatestMeasurementEntity latest = latestOf(sensor);
        assertEquals(recente.eventId(), latest.getEventId(),
                "USING TIMESTAMP = temps d'evenement : la mesure en retard n'ecrase pas la recente");
        assertEquals(recente.timestamp(), latest.getMeasuredAt(),
                "la date de 'latest' est celle de la mesure la plus recente (pas celle du dernier arrivage)");

        // les deux mesures restent dans l'historique (seul "latest" resout le conflit)
        assertEquals(2, readDay(sensor, LocalDate.parse("2026-11-02")).size(),
                "historique complet : les deux lignes sont conservees");
    }

    /** Decision D-10 : champs absents → colonnes non ecrites (unset), relues null ; metriques enregistrees. */
    @Test
    void writeBatch_ometLesChampsAbsents_etEnregistreLesMetriques() {
        String sensor = "SENSOR-NULL-1";
        EnergyMeasurement sansOptionnels = measurement(sensor, Instant.parse("2026-11-04T08:00:00Z"),
                null, null, null, null);

        writer.writeBatch(List.of(sansOptionnels));

        MeasurementByDayEntity row = readDay(sensor, LocalDate.parse("2026-11-04")).get(0);
        assertNull(row.getTemperature(), "D-10 : temperature absente -> colonne non ecrite");
        assertNull(row.getCurrentA());
        assertNull(row.getFrequencyHz());
        assertNull(row.getState());
        LatestMeasurementEntity latest = latestOf(sensor);
        assertNotNull(latest);
        assertNull(latest.getTemperature(), "D-10 : idem sur la table latest");

        // metriques exposees par le writer (Timer enregistre a l'ecriture, Counter enregistre au demarrage du bean)
        assertNotNull(meterRegistry.find("energy_persist_duration_seconds").timer(),
                "Timer energy_persist_duration_seconds enregistre");
        assertNotNull(meterRegistry.find("energy_persist_errors_total").counter(),
                "Counter energy_persist_errors_total enregistre");
    }

    // ------------------------------------------------------------------ etape 8

    /**
     * Scenario 3 (CDC) au niveau API : 6 mesures publiees dans le stream (comme le collector),
     * consommees puis consultees via GET /api/measurements/{id} — bornes from/to, pagination
     * par curseur opaque sur 2 pages, ordre desc (defaut) et asc.
     */
    @Test
    void apiHistorique_fromTo_paginationCurseur_etOrdres() throws Exception {
        String sensor = "SENSOR-API-HIST";
        List<EnergyMeasurement> lot = List.of(
                measurement(sensor, Instant.parse("2026-11-05T08:00:00Z"), 20.0, 10.0, 50.0, SensorState.ON),
                measurement(sensor, Instant.parse("2026-11-05T08:00:02Z"), 20.2, 10.1, 50.0, SensorState.ON),
                measurement(sensor, Instant.parse("2026-11-05T08:00:04Z"), 20.4, 10.2, 50.1, SensorState.ON),
                measurement(sensor, Instant.parse("2026-11-06T09:00:00Z"), 21.0, 11.0, 50.0, SensorState.ON),
                measurement(sensor, Instant.parse("2026-11-06T09:00:02Z"), 21.2, 11.1, 50.0, SensorState.ON),
                measurement(sensor, Instant.parse("2026-11-06T09:00:04Z"), 21.4, 11.2, 50.1, SensorState.OFF));
        lot.forEach(m -> redis.opsForStream()
                .add(StringRecord.of(MeasurementEventCodec.toFields(m)).withStreamKey(StreamContract.STREAM)));
        awaitUntil(() -> readDay(sensor, LocalDate.parse("2026-11-05")).size() == 3
                && readDay(sensor, LocalDate.parse("2026-11-06")).size() == 3, Duration.ofSeconds(20));

        // --- bornes [from, to) sur 2 jours : les 6 items, du plus recent au plus ancien
        String body = mockMvc.perform(get("/api/measurements/{id}", sensor)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-07T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorId").value(sensor))
                .andExpect(jsonPath("$.items.length()").value(6))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        List<Instant> desc = timestamps(body);
        assertEquals(Instant.parse("2026-11-06T09:00:04Z"), desc.get(0), "desc = plus recent d'abord");
        assertEquals(Instant.parse("2026-11-05T08:00:00Z"), desc.get(5), "desc = plus ancien en dernier");

        // --- une borne exclusive : to = 2026-11-06T00:00:00Z ne voit que le jour 5
        mockMvc.perform(get("/api/measurements/{id}", sensor)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-06T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        // --- pagination : limit=4 -> 4 items + nextCursor ; page 2 -> les 2 restants
        String page1 = mockMvc.perform(get("/api/measurements/{id}", sensor)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-07T00:00:00Z")
                        .param("limit", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        List<Instant> page1Times = timestamps(page1);

        String page2 = mockMvc.perform(get("/api/measurements/{id}", sensor)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-07T00:00:00Z")
                        .param("limit", "4")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        List<Instant> page2Times = timestamps(page2);

        // continuite stricte du parcours desc : la page 2 suit la page 1 sans trou ni doublon
        assertEquals(Instant.parse("2026-11-05T08:00:02Z"), page2Times.get(0),
                "la page 2 reprend juste avant le dernier item de la page 1");
        List<Instant> all = new ArrayList<>(page1Times);
        all.addAll(page2Times);
        assertEquals(6, all.stream().distinct().count(), "6 items distincts sur les 2 pages");
        assertEquals(Instant.parse("2026-11-06T09:00:04Z"), all.get(0));
        assertEquals(Instant.parse("2026-11-05T08:00:00Z"), all.get(5));

        // --- order=asc : chronologique
        String asc = mockMvc.perform(get("/api/measurements/{id}", sensor)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-07T00:00:00Z")
                        .param("order", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(6))
                .andReturn().getResponse().getContentAsString();
        List<Instant> ascTimes = timestamps(asc);
        assertEquals(Instant.parse("2026-11-05T08:00:00Z"), ascTimes.get(0));
        assertEquals(Instant.parse("2026-11-06T09:00:04Z"), ascTimes.get(5));

        // --- capteur sans donnee : 200 et liste vide (pas de couplage sensor-service)
        mockMvc.perform(get("/api/measurements/{id}", "SENSOR-JAMAIS-VU")
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-06T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    /** GET /latest : 200 + cache-aside Redis (TTL) puis 404 MEASUREMENT_NOT_FOUND pour un capteur inconnu. */
    @Test
    void apiLatest_200_avecCacheAside_puis404PourCapteurInconnu() throws Exception {
        String sensor = "SENSOR-API-LATEST";
        EnergyMeasurement m = measurement(sensor, Instant.parse("2026-11-04T07:30:00Z"),
                25.5, 12.5, 50.05, SensorState.ON);
        redis.delete(RedisKeys.cacheLatest(sensor));   // garantit le chemin cache-miss
        redis.opsForStream().add(StringRecord.of(MeasurementEventCodec.toFields(m))
                .withStreamKey(StreamContract.STREAM));
        awaitUntil(() -> latestOf(sensor) != null, Duration.ofSeconds(20));

        String body = mockMvc.perform(get("/api/measurements/{id}/latest", sensor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.powerKw").value(3.72))
                .andExpect(jsonPath("$.state").value("ON"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(Instant.parse("2026-11-04T07:30:00Z"),
                Instant.parse((String) JsonPath.read(body, "$.timestamp")), "derniere mesure = D-09");

        // cache-aside : la cle existe avec un TTL borne (RedisKeys.CACHE_LATEST_TTL = 30 s)
        String cacheKey = RedisKeys.cacheLatest(sensor);
        assertTrue(Boolean.TRUE.equals(redis.hasKey(cacheKey)), "cle cache:latest ecrite apres lecture Cassandra");
        Long ttlSeconds = redis.getExpire(cacheKey);
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        assertTrue(ttlSeconds >= 0 && ttlSeconds <= 30,
                "TTL borne a 30 s, obtenu " + ttl);

        // 404 : code d'erreur du catalogue (application/problem+json)
        mockMvc.perform(get("/api/measurements/{id}/latest", "SENSOR-INCONNU-API"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_NOT_FOUND"));
    }

    /** Les controles metier repondent 400 avec le bon code (jamais une erreur 500). */
    @Test
    void apiParametresInvalides_repondent400_problemPlusJson() throws Exception {
        String base = "/api/measurements/SENSOR-API-HIST";

        mockMvc.perform(get(base).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mockMvc.perform(get(base).param("limit", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mockMvc.perform(get(base).param("order", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mockMvc.perform(get(base).param("cursor", "!!jeton-corrompu!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mockMvc.perform(get(base)
                        .param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-05T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        // from/to de type illisible : 400 automatique (platform-web)
        mockMvc.perform(get(base).param("from", "hier"))
                .andExpect(status().isBadRequest());
        // plage > 7 jours : RANGE_TOO_LARGE
        mockMvc.perform(get(base)
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-10T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
    }

    /** Critere de validation (etape 8) : l'API est documentee et testable depuis Swagger UI. */
    @Test
    void openApi_documenteLesRoutesDeLecture() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(docs.contains("\"/api/measurements/{sensorId}\""), "route historique documentee");
        assertTrue(docs.contains("\"/api/measurements/{sensorId}/latest\""), "route latest documentee");
        assertNotNull(JsonPath.read(docs, "$.paths['/api/measurements/{sensorId}'].get"),
                "methode GET documentee sur l'historique");
        // Swagger UI joignable (redirect ou page)
        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertTrue(status >= 200 && status < 400, "swagger-ui.html repond " + status);
    }

    /** Items d'une reponse MeasurementHistoryResponse : timestamps parses (ISO-8601). */
    private static List<Instant> timestamps(String body) {
        List<String> raw = JsonPath.read(body, "$.items[*].timestamp");
        return raw.stream().map(Instant::parse).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ utilitaires

    /** Derniere mesure d'un capteur (table latest), ou null si absente. */
    private LatestMeasurementEntity latestOf(String sensorId) {
        return cassandraTemplate.selectOne(
                SimpleStatement.newInstance(
                        "SELECT * FROM latest_measurement_by_sensor WHERE sensor_id = ?", sensorId),
                LatestMeasurementEntity.class);
    }

    /** Messages livres mais non acquittes par storage-group. */
    private long pendingCount() {
        var pending = redis.opsForStream().pending(StreamContract.STREAM, StreamContract.STORAGE_GROUP);
        return pending == null ? 0L : pending.getTotalPendingMessages();
    }

    /** Attend qu'une condition soit vraie (les traitements stream sont asynchrones). */
    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError last = new AssertionError("condition non atteinte en " + timeout);
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException | AssertionError e) {
                last = e instanceof AssertionError a ? a : new AssertionError(e);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw last;
    }

    /** Lecture d'un jour UTC complet : bornes [00:00Z, 00:00Z+1j). */
    private List<MeasurementByDayEntity> readDay(String sensorId, LocalDate day) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        return repository.findByDayRange(sensorId, day, from, from.plus(1, ChronoUnit.DAYS));
    }

    private static EnergyMeasurement measurement(String sensorId, Instant timestamp,
                                                 Double temperature, Double currentA,
                                                 Double frequencyHz, SensorState state) {
        Instant receivedAt = timestamp.plusMillis(250);
        return new EnergyMeasurement(
                MeasurementSchema.CURRENT_VERSION,
                EventIds.forMeasurement(sensorId, timestamp),
                "trace-" + sensorId + '-' + timestamp.toEpochMilli(),
                sensorId,
                timestamp,
                receivedAt,
                3.72,
                226.4,
                temperature,
                currentA,
                frequencyHz,
                state);
    }

    /** Percentile (0-100) d'un echantillon, par troncature classique. */
    private static long percentile(long[] values, int p) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /**
     * Decoupe le script CQL en instructions : commentaires "--" retires par ligne,
     * separation sur ";" (le script du projet ne contient aucun point-virgule dans
     * une chaine ni dans un commentaire — il est simple par construction).
     */
    private static List<String> cqlStatements(String script) {
        return Arrays.stream(script.split(";"))
                .map(instruction -> Arrays.stream(instruction.split("\n"))
                        .map(line -> {
                            int comment = line.indexOf("--");
                            return comment >= 0 ? line.substring(0, comment) : line;
                        })
                        .collect(Collectors.joining("\n")))
                .map(String::trim)
                .filter(instruction -> !instruction.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Verification du schema reel cote serveur : si une colonne attendue manque
     * (ancienne version de la table, CREATE IF NOT EXISTS sans effet), message d'aide.
     */
    private static void verifierColonnes(CqlSession session, String table, Set<String> attendues) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
                KEYSPACE, table));
        Set<String> colonnes = new HashSet<>();
        for (Row row : rs) {
            colonnes.add(row.getString("column_name"));
        }
        Set<String> manquantes = new HashSet<>(attendues);
        manquantes.removeAll(colonnes);
        if (!manquantes.isEmpty()) {
            throw new IllegalStateException("Schema Cassandra OBSOLETE pour " + KEYSPACE + "." + table
                    + " — colonnes manquantes : " + manquantes
                    + ". Correction : DROP KEYSPACE " + KEYSPACE + " puis reappliquer"
                    + " scripts/init-cassandra.cql (README section 3.2).");
        }
    }

    /** scripts/init-cassandra.cql : chemin depuis storage-service/ ou la racine du repo. */
    private static Path schemaPath() {
        for (String candidate : new String[]{"../scripts/init-cassandra.cql", "scripts/init-cassandra.cql"}) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("scripts/init-cassandra.cql introuvable depuis " + Path.of("").toAbsolutePath());
    }
}
