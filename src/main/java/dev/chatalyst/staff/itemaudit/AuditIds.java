package dev.chatalyst.staff.itemaudit;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Ids cortos de registro ("k7m2qx9a"): se dictan por voz o se copian del chat sin errores.
 *
 * <p>Ocho simbolos de un alfabeto sin los que se confunden (0/o, 1/l/i) dan unos 8,5·10^11
 * combinaciones: con cientos de miles de registros en la retencion, la probabilidad de que
 * dos coincidan es despreciable, y aun asi /itemaudit id ensenaria las dos filas.
 */
final class AuditIds {

    static final int LENGTH = 8;
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final RandomGenerator RANDOM = new SecureRandom();

    private AuditIds() {
    }

    static String next() {
        return next(RANDOM);
    }

    static String next(RandomGenerator random) {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    /** Lo que escribe el staff en /itemaudit id: mayusculas y espacios no deben importar. */
    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isValid(String raw) {
        String id = normalize(raw);
        if (id.length() != LENGTH) return false;
        for (int i = 0; i < id.length(); i++) {
            if (ALPHABET.indexOf(id.charAt(i)) < 0) return false;
        }
        return true;
    }
}
