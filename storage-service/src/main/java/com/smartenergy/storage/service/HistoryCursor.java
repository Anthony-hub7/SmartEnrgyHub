package com.smartenergy.storage.service;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Curseur de pagination opaque de l'historique (etape 8, CDC section 5.4 : nextCursor est un
 * jeton opaque, null en fin de liste).
 *
 * <p>Pourquoi pas le "paging state" du pilote Cassandra ? L'historique peut traverser PLUSIEURS
 * partitions (une par jour, decision D-08) : le paging state ne couvre qu'une partition lue.
 * Un curseur maison "(jour, dernier measured_at)" indique exactement ou reprendre le parcours,
 * partition par partition, dans l'ordre demande (asc ou desc).
 *
 * <p>Encodage : base64 URL-safe (sans padding) de "yyyy-MM-dd|epochMillis". Le jeton est opaque
 * pour le client : son format interne peut evoluer sans casser l'API. Un jeton corrompu ou
 * malforme produce {@code ApiException(INVALID_PARAMETER)} (400), jamais une erreur 500.
 */
public record HistoryCursor(LocalDate day, Instant lastMeasuredAt) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = day + SEPARATOR + lastMeasuredAt.toEpochMilli();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws ApiException(INVALID_PARAMETER) si le jeton n'est pas un curseur valide
     */
    public static HistoryCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\" + SEPARATOR, -1);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new IllegalArgumentException("format attendu : jour|epochMillis");
            }
            return new HistoryCursor(LocalDate.parse(parts[0]),
                    Instant.ofEpochMilli(Long.parseLong(parts[1])));
        } catch (RuntimeException e) {
            throw ApiException.of(ErrorCode.INVALID_PARAMETER,
                    "Cursor de pagination invalide : " + cursor);
        }
    }
}
