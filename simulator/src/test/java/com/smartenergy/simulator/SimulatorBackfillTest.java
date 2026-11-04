package com.smartenergy.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.web.client.RestClient;

/**
 * Mode backfill (extension 9C) : horloge virtuelle couvrant [now - N j, now].
 * Le serveur cible est un stub local qui enregistre les mesures recues.
 */
class SimulatorBackfillTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void backfill_envoie_des_timestamps_historiques_couvrent_la_fenetre() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/measurements", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        // 2 jours d'historique, 1 capteur, pas d'1 h -> 48 ticks attendus
        SimulatorProperties props = new SimulatorProperties(url, url, false, 1,
                Duration.ofHours(1), Duration.ZERO, 7L,
                3.5, 1.0, 3600, 0.1, 230.0, 25.0, 8.0, 0.0, 0.0,
                2, 2000.0,
                new SimulatorProperties.Anomaly("", 60, 10, 3.0));
        Instant avant = Instant.now().minus(Duration.ofDays(2)).minusSeconds(5);
        new SimulatorRunner(props, RestClient.builder()).run(new DefaultApplicationArguments());
        Instant apres = Instant.now();

        assertEquals(48, bodies.size(), "2 jours / pas de 1 h = 48 mesures");
        List<String> timestamps = bodies.stream()
                .map(b -> b.replaceAll(".*\"timestamp\":\"([^\"]+)\".*", "$1"))
                .sorted()
                .toList();
        Instant premier = Instant.parse(timestamps.get(0));
        Instant dernier = Instant.parse(timestamps.get(timestamps.size() - 1));
        assertTrue(premier.isAfter(avant) && premier.isBefore(apres), "premier timestamp ~ now-2j : " + premier);
        assertTrue(dernier.isAfter(avant) && dernier.isBefore(apres), "dernier timestamp ~ maintenant : " + dernier);
        assertEquals(Duration.ofHours(47), Duration.between(premier, dernier),
                "48 mesures horaires : ecart de 47 h entre la premiere (now-2j) et la derniere (now-1j)");
        assertTrue(bodies.get(0).contains("SENSOR-001"), "capteur au format SENSOR-001");
    }

    @Test
    void temps_reel_sans_backfill_nenvoie_que_pendant_la_duree() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/measurements", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        // Pas de backfill (backfillDays=0) : comportement historique, duree 2 s, pas 500 ms -> ~5 ticks
        SimulatorProperties props = new SimulatorProperties(url, url, false, 1,
                Duration.ofMillis(500), Duration.ofSeconds(2), 7L,
                3.5, 1.0, 120, 0.1, 230.0, 25.0, 8.0, 0.0, 0.0,
                0, 200.0,
                new SimulatorProperties.Anomaly("", 60, 10, 3.0));
        new SimulatorRunner(props, RestClient.builder()).run(new DefaultApplicationArguments());

        assertTrue(bodies.size() >= 3 && bodies.size() <= 6, "envois proportionnels a la duree : " + bodies.size());
        Instant ts = Instant.parse(bodies.get(0).replaceAll(".*\"timestamp\":\"([^\"]+)\".*", "$1"));
        assertTrue(ts.isAfter(Instant.now().minusSeconds(10)), "timestamp temps reel : " + ts);
    }
}
