package com.smartenergy.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.StreamContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test d'integration du collector (CDC section 12, scenarios 1, 2, 8, 9 + coercion + 413).
 * Redis jetable via Testcontainers ; critere de validation : une requete valide cree un
 * evenement dans le stream, une requete invalide n'en cree aucun.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollectorIntegrationTest {

    private static final String REDIS_PASSWORD = "test-pass";

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7").withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("spring.data.redis.timeout", () -> "3s");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    StringRedisTemplate redisTemplate;

    // ------------------------------------------------------------------ scenarios

    @Test
    @Order(1)
    void scenario1_mesureValide_renvoie202_etCreeUnEvenement() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long before = xlen();

        ResponseEntity<String> resp = post(
                measurementJson("SENSOR-001", iso(now), "3.72"));

        assertEquals(202, resp.getStatusCode().value());
        String eventId = resp.getHeaders().getFirst("X-Event-Id");
        assertNotNull(eventId, "en-tete X-Event-Id attendu sur un 202");
        assertNotNull(resp.getHeaders().getFirst("X-Request-Id"), "correlation X-Request-Id attendue");
        assertEquals(before + 1, xlen(), "le stream doit contenir une entree de plus");

        Map<String, String> fields = lastEntry();
        var decoded = MeasurementEventCodec.fromFields(fields);
        assertEquals("SENSOR-001", decoded.sensorId());
        assertEquals(eventId, decoded.eventId().toString(), "eventId = X-Event-Id");
        assertEquals(now, decoded.timestamp(), "timestamp conserve tronque a la milliseconde");
        assertEquals(3.72, decoded.powerKw(), 1e-9);
        assertEquals("1", fields.get("schemaVersion"));
    }

    @Test
    @Order(2)
    void scenario2_sensorIdAbsent_renvoie400_etAucunEvenement() {
        long before = xlen();

        ResponseEntity<String> resp = post(
                "{\"timestamp\":\"" + iso(Instant.now()) + "\",\"powerKw\":3.72,\"voltage\":226.4}");

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertTrue(resp.getBody().contains("VALIDATION_ERROR"), "code VALIDATION_ERROR attendu");
        assertTrue(resp.getBody().contains("sensorId"), "le champ fautif doit etre signale");
        assertEquals(before, xlen(), "aucun evenement ne doit etre cree");
    }

    @Test
    @Order(3)
    void puissanceEnChaine_renvoie400_sansCoercion() {
        long before = xlen();

        ResponseEntity<String> resp = post(
                measurementJson("SENSOR-001", iso(Instant.now()), "\"3.72\""));

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("MALFORMED_JSON"), "pas de coercition \"3.72\" -> 3.72");
        assertEquals(before, xlen());
    }

    @Test
    @Order(4)
    void mesureVieille25h_renvoie400_tooOld() {
        long before = xlen();

        ResponseEntity<String> resp = post(measurementJson("SENSOR-001",
                iso(Instant.now().minus(25, ChronoUnit.HOURS)), "3.72"));

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("MEASUREMENT_TOO_OLD"));
        assertEquals(before, xlen());
    }

    @Test
    @Order(5)
    void mesureDansLeFutur_renvoie400_inFuture() {
        long before = xlen();

        ResponseEntity<String> resp = post(measurementJson("SENSOR-001",
                iso(Instant.now().plus(6, ChronoUnit.MINUTES)), "3.72"));

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("MEASUREMENT_IN_FUTURE"));
        assertEquals(before, xlen());
    }

    @Test
    @Order(6)
    void scenario8_doublon_renvoieDeux202() {
        Instant ts = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String json = measurementJson("SENSOR-002", iso(ts), "4.10");
        long before = xlen();

        ResponseEntity<String> first = post(json);
        ResponseEntity<String> second = post(json);

        assertEquals(202, first.getStatusCode().value());
        assertEquals(202, second.getStatusCode().value(), "un doublon est accepte en entree");
        assertEquals(first.getHeaders().getFirst("X-Event-Id"), second.getHeaders().getFirst("X-Event-Id"),
                "eventId deterministe : une meme mesure -> un meme eventId");
        assertEquals(before + 2, xlen());
    }

    @Test
    @Order(7)
    void corpsSuperieurA10Ko_renvoie413() {
        String bigDecimal = "29." + "1".repeat(11_000);
        String json = "{\"sensorId\":\"SENSOR-001\",\"timestamp\":\"" + iso(Instant.now())
                + "\",\"powerKw\":3.72,\"voltage\":226.4,\"temperature\":" + bigDecimal + "}";
        long before = xlen();

        ResponseEntity<String> resp = post(json);

        assertEquals(413, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("PAYLOAD_TOO_LARGE"));
        assertEquals(before, xlen());
    }

    @Test
    @Order(99)
    void scenario9_redisArrete_renvoie503_avecRetryAfter() {
        redis.stop();

        ResponseEntity<String> resp = post(measurementJson("SENSOR-001", iso(Instant.now()), "3.72"));

        assertEquals(503, resp.getStatusCode().value());
        assertEquals("5", resp.getHeaders().getFirst("Retry-After"));
        assertTrue(resp.getBody().contains("BROKER_UNAVAILABLE"));
        // pas de redemarrage : test volontairement en derniere position
    }

    // ------------------------------------------------------------------ utilitaires

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/measurements", new HttpEntity<>(json, headers), String.class);
    }

    private long xlen() {
        Long size = redisTemplate.opsForStream().size(StreamContract.STREAM);
        return size == null ? 0L : size;
    }

    private Map<String, String> lastEntry() {
        List<MapRecord<String, Object, Object>> all =
                redisTemplate.opsForStream().range(StreamContract.STREAM, Range.unbounded());
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        all.get(all.size() - 1).getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        return fields;
    }

    private static String iso(Instant instant) {
        return instant.toString();
    }

    private static String measurementJson(String sensorId, String timestamp, String powerKw) {
        return "{\"sensorId\":\"" + sensorId + "\",\"timestamp\":\"" + timestamp
                + "\",\"powerKw\":" + powerKw + ",\"voltage\":226.4,\"temperature\":29.1}";
    }
}
