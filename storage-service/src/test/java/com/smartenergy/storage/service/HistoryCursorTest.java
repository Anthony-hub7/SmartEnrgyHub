package com.smartenergy.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Curseur de pagination : aller-retour sans perte et rejet des jetons corrompus (etape 8). */
class HistoryCursorTest {

    @Test
    void allerRetour_preserveJourEtInstant() {
        HistoryCursor original = new HistoryCursor(LocalDate.parse("2026-10-27"),
                Instant.parse("2026-10-27T11:59:58.123Z"));

        HistoryCursor decode = HistoryCursor.decode(original.encode());

        assertEquals(original, decode, "encode/decode est exact (jour + dernier measured_at)");
        assertEquals(LocalDate.parse("2026-10-27"), decode.day());
        assertEquals(Instant.parse("2026-10-27T11:59:58.123Z"), decode.lastMeasuredAt());
    }

    @Test
    void encode_estUrlSafe_sansPadding() {
        String cursor = new HistoryCursor(LocalDate.parse("2026-01-02"),
                Instant.parse("2026-01-02T03:04:05.006Z")).encode();

        assertNotEquals("=", cursor.substring(cursor.length() - 1), "pas de padding '='");
        // base64 URL-safe : ni '+' ni '/' (le jeton passe dans une query string)
        org.junit.jupiter.api.Assertions.assertFalse(cursor.contains("+"));
        org.junit.jupiter.api.Assertions.assertFalse(cursor.contains("/"));
    }

    @Test
    void jetonCorrompu_produisInvalidParameter() {
        // texte non base64, base64 valide mais format faux, base64 de format partiel
        for (String bogus : new String[]{"!!!pas-base64!!!", "", "YWJjZA==", "MjAyNi0xMC0yNw"}) {
            ApiException ex = assertThrows(ApiException.class, () -> HistoryCursor.decode(bogus),
                    "jeton invalide attendu : " + bogus);
            assertEquals(ErrorCode.INVALID_PARAMETER, ex.code());
        }
    }

    @Test
    void deuxCurseursDistincts_sontDistincts() {
        HistoryCursor a = new HistoryCursor(LocalDate.parse("2026-10-27"),
                Instant.parse("2026-10-27T10:00:00Z"));
        HistoryCursor b = new HistoryCursor(LocalDate.parse("2026-10-27"),
                Instant.parse("2026-10-27T10:00:01Z"));
        assertNotEquals(a.encode(), b.encode(), "1 seconde d'ecart = jeton different");
    }
}
