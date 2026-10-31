package dev.chatalyst.staff.lookup;

import java.util.regex.Pattern;

/**
 * Como se escribe y como se reconoce una conexion en las consultas del staff. Sin Bukkit.
 *
 * <p>La llave de todo es el hash de la IP; la IP en claro es solo una etiqueta que se enseña a
 * quien tiene permiso. Las conexiones registradas antes de guardar IPs no la tienen, y para
 * ellas se sigue enseñando el id corto aunque el espectador pudiera ver IPs.
 */
public final class ConnectionLabel {

    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private ConnectionLabel() {
    }

    /** IP si se puede ver y se conoce; si no, el identificador corto del hash. */
    public static String of(String hash, String ip, boolean showIp) {
        if (showIp && ip != null && !ip.isBlank()) return ip;
        return SharedConnections.shortId(hash);
    }

    /**
     * Si lo que escribio el staff es una IP literal (y no un nombre). Un nombre de Minecraft
     * nunca lleva ':' ni tiene forma de cuatro numeros con puntos, asi que no hay ambiguedad.
     */
    public static boolean isIpLiteral(String input) {
        if (input == null) return false;
        String value = input.trim();
        if (IPV4.matcher(value).matches()) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) return false;
            }
            return true;
        }
        return value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
    }
}
