package dev.chatalyst.hologram;

import java.util.Locale;

/**
 * Como ve un jugador de Bedrock (Geyser) un cartel.
 *
 * <ul>
 *   <li>{@link #GEYSER}: lo traduce Geyser. Un TextDisplay llega a Bedrock como un nombre
 *       flotante: se lee, con colores, pero sin escala, fondo ni orientacion. La cara de atras
 *       de un cartel de doble cara se le oculta, que si no veria el texto dos veces.</li>
 *   <li>{@link #STANDS}: respaldo. Se le ocultan los TextDisplay y ve en su lugar un soporte de
 *       armadura invisible con nombre por linea, solo para el (los de Java no lo ven). Mejor
 *       separacion entre lineas que la traduccion de Geyser.</li>
 *   <li>{@link #HIDDEN}: el cartel no existe para Bedrock.</li>
 * </ul>
 */
public enum BedrockMode {
    GEYSER, STANDS, HIDDEN;

    public static BedrockMode parse(String raw) {
        if (raw == null) return null;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "geyser", "auto", "normal", "default" -> GEYSER;
            case "respaldo", "stands", "armorstand", "soportes", "fallback" -> STANDS;
            case "ocultar", "oculto", "hidden", "hide", "off" -> HIDDEN;
            default -> null;
        };
    }
}
