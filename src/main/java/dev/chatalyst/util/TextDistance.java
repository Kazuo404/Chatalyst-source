package dev.chatalyst.util;

/**
 * Distancia de edicion clasica, compartida por los dos sitios que la necesitaban.
 *
 * <p>Deliberadamente sin tope propio: cada llamante acota sus operandos como le conviene.
 * AntiSpam compara mensajes que escribe un jugador, asi que descarta antes los pares de
 * longitudes muy distintas; ConfigDoctor compara claves de configuracion, cortas y de
 * arranque, donde ese descarte solo perderia sugerencias utiles.
 */
public final class TextDistance {

    private TextDistance() {
    }

    /** Numero minimo de inserciones, borrados y sustituciones para pasar de {@code a} a {@code b}. */
    public static int levenshtein(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        int[] prev = new int[right.length() + 1];
        int[] curr = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) prev[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[right.length()];
    }
}
