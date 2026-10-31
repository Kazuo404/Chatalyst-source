package dev.chatalyst.discord;

import java.util.List;
import java.util.Locale;

/**
 * Un boton de Discord, descrito sin depender de Discord.
 *
 * <p>Mismo motivo que {@link DiscordEmbed}: quien decide QUE botones lleva un reporte no
 * deberia tener que conocer JDA, y asi el identificador que viaja dentro del boton se puede
 * probar sin levantar un bot.
 */
public record DiscordButton(String id, String label, Style style) {

    /** Prefijo propio: un boton de otro plugin en el mismo canal no es cosa nuestra. */
    public static final String PREFIX = "cht:rep:";

    /** Discord corta por aqui; pasarse invalida el mensaje entero, no solo el boton. */
    private static final int MAX_ID = 100;
    private static final int MAX_LABEL = 80;

    public enum Style { PRIMARY, SECONDARY, SUCCESS, DANGER }

    public DiscordButton {
        id = id == null ? "" : id.substring(0, Math.min(id.length(), MAX_ID));
        label = label == null ? "" : label.substring(0, Math.min(label.length(), MAX_LABEL));
        style = style == null ? Style.SECONDARY : style;
    }

    /** El identificador que viaja dentro del boton: {@code cht:rep:<accion>:<id>}. */
    public static DiscordButton forReport(String action, int reportId, String label,
                                          Style style) {
        return new DiscordButton(PREFIX + action + ":" + reportId, label, style);
    }

    /**
     * Que accion y que reporte pedia este boton, o null si no es nuestro ni tiene forma.
     *
     * <p>Se valida en vez de confiar: el identificador vuelve del cliente, y aunque Discord
     * no deje inventarselo a mano, un boton viejo de otra version puede llegar con cualquier
     * cosa dentro.
     */
    public static Action parse(String customId) {
        if (customId == null || !customId.startsWith(PREFIX)) return null;
        String[] parts = customId.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) return null;
        String action = parts[0].toLowerCase(Locale.ROOT);
        if (!List.of("claim", "reply", "resolve", "reject", "reopen").contains(action)) {
            return null;
        }
        try {
            return new Action(action, Integer.parseInt(parts[1]));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Las acciones que abren una ventana para escribir en vez de ejecutarse al pulsar. */
    public static boolean needsText(String action) {
        return List.of("reply", "resolve", "reject").contains(action);
    }

    public record Action(String action, int reportId) {
    }
}
