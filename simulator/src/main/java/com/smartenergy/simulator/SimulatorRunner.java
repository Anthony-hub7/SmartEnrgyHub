package com.smartenergy.simulator;

import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
import com.smartenergy.contracts.sensor.SensorRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/** Boucle de simulation : une mesure par capteur a chaque pas de temps, puis resume en fin d'execution. */
@Component
public class SimulatorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final SimulatorProperties p;
    private final RestClient collector;
    private final RestClient sensorService;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public SimulatorRunner(SimulatorProperties properties, RestClient.Builder builder) {
        this.p = properties;
        this.collector = builder.clone().baseUrl(p.targetUrl()).build();
        this.sensorService = builder.clone().baseUrl(p.sensorServiceUrl()).build();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> ids = IntStream.rangeClosed(1, p.sensors()).mapToObj(i -> String.format("SENSOR-%03d", i)).toList();
        if (p.registerSensors()) register(ids);

        Random[] rnd = new Random[ids.size()];
        double[] base = new double[ids.size()];
        double[] phase = new double[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            rnd[i] = new Random(p.seed() + i);
            base[i] = p.pBaseKw() * (0.8 + 0.4 * rnd[i].nextDouble());
            phase[i] = 2 * Math.PI * rnd[i].nextDouble();
        }
        Map<String, Map<String, Object>> last = new HashMap<>();
        Random chaos = new Random(p.seed() + 9999);

        // Mode backfill (extension 9C) : horloge virtuelle couvrant [now - backfillDays, now],
        // avancant de `interval` par tick, envoyee aussi vite que `backfillPace` le permet.
        boolean backfill = p.backfillDays() > 0;
        long stepNanos = p.interval().toNanos();
        if (backfill && stepNanos <= 0) {
            log.error("Backfill invalide : interval doit etre strictement positif (recu {})", p.interval());
            return;
        }
        Instant virtualStart = backfill ? Instant.now().minus(p.backfillDays(), ChronoUnit.DAYS) : null;
        long totalTicks = backfill
                ? Math.max(1, (p.backfillDays() * 86_400_000_000_000L) / stepNanos) // jours en ns / pas
                : Long.MAX_VALUE;

        log.info("Simulation : {} capteur(s), pas {}, {}, cible {}", ids.size(), p.interval(),
                backfill ? "backfill " + p.backfillDays() + " jours @ " + p.backfillPace() + " ticks/s (horloge virtuelle)"
                         : "duree " + p.duration(),
                p.targetUrl());
        long start = System.nanoTime();
        long tick = 0;
        while (true) {
            if (backfill) {
                if (tick >= totalTicks) break;
            } else if (!p.duration().isZero() && (System.nanoTime() - start) / 1e9 >= p.duration().toSeconds()) {
                break;
            }
            long tickStart = System.nanoTime();
            // elapsed : temps VIRTUEL depuis le debut de la simulation ( coherent avec le timestamp
            // envoye ) ; en mode reel, le temps mur depuis le demarrage.
            double elapsed = backfill ? tick * stepNanos / 1e9 : (tickStart - start) / 1e9;

            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                double power = base[i] + p.amplitudeKw() * Math.sin(2 * Math.PI * elapsed / p.periodSeconds() + phase[i])
                        + rnd[i].nextGaussian() * p.noiseSigmaKw();
                if (p.anomaly().enabled() && id.equals(p.anomaly().sensor())
                        && elapsed >= p.anomaly().atSeconds()
                        && elapsed < p.anomaly().atSeconds() + p.anomaly().durationSeconds()) {
                    power *= p.anomaly().factor(); // pic injecte
                }
                power = Math.max(0.0, round3(power));
                double voltage = Math.max(0.0, round3(p.voltageBase() + rnd[i].nextGaussian() * 1.5));
                double temperature = round3(p.temperatureBase() + rnd[i].nextGaussian() * 0.5);

                Instant timestamp = backfill
                        ? virtualStart.plusNanos(tick * stepNanos)
                        : Instant.now().truncatedTo(ChronoUnit.MILLIS);
                EnergyMeasurementRequest m = new EnergyMeasurementRequest(id, timestamp, power, voltage, temperature,
                        null, null, null);
                Map<String, Object> body = toBody(m);
                if (chaos.nextDouble() < p.invalidRate()) corrupt(body, chaos);
                send(body);
                if (last.containsKey(id) && chaos.nextDouble() < p.duplicateRate()) send(last.get(id)); // doublon
                last.put(id, body);
            }
            tick++;
            long wait = backfill
                    ? (long) (1_000_000_000.0 / p.backfillPace()) - (System.nanoTime() - tickStart) // rythme d'envoi
                    : start + tick * stepNanos - System.nanoTime();                                  // aligne sur le pas
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
        log.info("Resume : envoyees={} acceptees(202)={} rejetees(4xx)={} erreurs={}",
                sent.get(), accepted.get(), rejected.get(), errors.get());
    }

    private static Map<String, Object> toBody(EnergyMeasurementRequest m) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sensorId", m.sensorId());
        b.put("timestamp", m.timestamp().toString());
        b.put("powerKw", m.powerKw());
        b.put("voltage", m.voltage());
        b.put("temperature", m.temperature());
        return b;
    }

    /** Rend le corps invalide de facon realiste (scenario 2 : mesure sans sensorId, puissance negative...). */
    private static void corrupt(Map<String, Object> body, Random r) {
        switch (r.nextInt(3)) {
            case 0 -> body.remove("sensorId");
            case 1 -> body.put("powerKw", -1.0);
            default -> body.put("voltage", 9999.0);
        }
    }

    private void send(Map<String, Object> body) {
        sent.incrementAndGet();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                collector.post().uri("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().toBodilessEntity();
                accepted.incrementAndGet();
                return;
            } catch (HttpClientErrorException e) { // 4xx : mesure refusee, inutile de reessayer
                rejected.incrementAndGet();
                return;
            } catch (HttpServerErrorException e) { // 503 : broker indisponible, on reessaie avec un delai
                if (attempt == 3) { errors.incrementAndGet(); return; }
                pause(500L * attempt);
            } catch (RuntimeException e) { // collector injoignable
                if (attempt == 3) { errors.incrementAndGet(); return; }
                pause(500L * attempt);
            }
        }
    }

    private void register(List<String> ids) {
        for (String id : ids) {
            SensorRequest req = new SensorRequest(id, "Capteur simule " + id, "Batiment A", 1,
                    p.maxPowerKw(), true, List.of("simulated"), null);
            try {
                sensorService.post().uri("/api/sensors").contentType(MediaType.APPLICATION_JSON)
                        .body(req).retrieve().toBodilessEntity();
                log.info("Capteur enregistre : {}", id);
            } catch (HttpClientErrorException.Conflict e) {
                log.debug("Capteur deja enregistre : {}", id);
            } catch (RuntimeException e) {
                log.warn("Enregistrement de {} impossible ({}) : la simulation continue", id, e.toString());
            }
        }
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
