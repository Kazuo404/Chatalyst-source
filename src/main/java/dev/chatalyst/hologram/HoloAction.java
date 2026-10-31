package dev.chatalyst.hologram;

import java.util.Locale;

/**
 * Lo que pasa al hacer clic en un cartel. Un solo tipo por cartel; para "abrir un menu" se
 * usa {@link Type#PLAYER} con el comando del menu (/ranks, /warps...), que es como lo abre
 * el propio jugador.
 *
 * @param value    comando sin barra (PLAYER/CONSOLE) o texto MiniMessage (MESSAGE);
 *                 {player} se cambia por el nombre de quien hace clic
 * @param cooldown segundos entre clics del mismo jugador
 */
public record HoloAction(Type type, String value, int cooldown) {

    public enum Type { NONE, PLAYER, CONSOLE, MESSAGE }

    public static final HoloAction NONE = new HoloAction(Type.NONE, "", 2);

    public HoloAction {
        type = type == null ? Type.NONE : type;
        value = value == null ? "" : value.strip();
        if (type == Type.PLAYER || type == Type.CONSOLE) {
            while (value.startsWith("/")) value = value.substring(1);
        }
        if (value.length() > 512) value = value.substring(0, 512);
        cooldown = (int) HoloLook.clamp(cooldown, 0, 3600);
        if (type != Type.NONE && value.isEmpty()) type = Type.NONE;
    }

    public boolean active() {
        return type != Type.NONE;
    }

    public HoloAction withCooldown(int seconds) {
        return new HoloAction(type, value, seconds);
    }

    /** Nombre escrito por el staff (es o en) al tipo, o null si no es ninguno. */
    public static Type parseType(String raw) {
        if (raw == null) return null;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "ninguna", "ninguno", "none", "off", "quitar" -> Type.NONE;
            case "jugador", "player", "comando", "command", "menu", "menú" -> Type.PLAYER;
            case "consola", "console" -> Type.CONSOLE;
            case "mensaje", "message", "msg" -> Type.MESSAGE;
            default -> null;
        };
    }

    /** Sustituye {player} en el valor: el nombre de Minecraft nunca lleva espacios ni llaves. */
    public String resolved(String playerName) {
        return value.replace("{player}", playerName == null ? "" : playerName);
    }
}
