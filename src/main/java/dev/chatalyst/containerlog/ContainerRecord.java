package dev.chatalyst.containerlog;

import java.util.UUID;

/**
 * Una interaccion registrada con un contenedor o mecanismo: quien, que accion (OPEN, PLACE,
 * BREAK, USE), sobre que bloque/entidad y en que coordenadas exactas. El {@code target} es
 * el nombre crudo del material o tipo de entidad (CHEST, OAK_DOOR, CHEST_MINECART...); la
 * etiqueta amigable en el idioma del staff se resuelve al mostrarlo.
 */
public record ContainerRecord(long time, UUID playerId, String playerName, String action,
                              String target, String world, int x, int y, int z,
                              int repeats) {

    /** Registro sin repeticiones ocultas (el caso normal). */
    public ContainerRecord(long time, UUID playerId, String playerName, String action,
                           String target, String world, int x, int y, int z) {
        this(time, playerId, playerName, action, target, world, x, y, z, 0);
    }

    /** Linea estable para el archivo de log diario; se puede re-parsear con {@code parse}. */
    public String fileContent() {
        String base = action + " " + target + " @ " + world + " " + x + " " + y + " " + z;
        return repeats > 0 ? base + " +" + repeats : base;
    }

    // El nombre del mundo puede llevar espacios (mundos custom): se captura codicioso y las
    // tres ultimas cifras son las coordenadas. Con \S+ esas lineas no casaban y las
    // entradas de ese mundo desaparecian del historial por fecha.
    private static final java.util.regex.Pattern FILE_LINE = java.util.regex.Pattern.compile(
            "^(\\S+) (\\S+) @ (.+) (-?\\d+) (-?\\d+) (-?\\d+)(?: \\+(\\d+))?$");

    /**
     * Reconstruye un registro desde una linea de archivo (contenido de una entrada
     * CONTAINER del log diario), o null si el formato no coincide.
     */
    public static ContainerRecord parse(long time, String playerName, String content) {
        var m = FILE_LINE.matcher(content);
        if (!m.matches()) return null;
        try {
            return new ContainerRecord(time, null, playerName, m.group(1), m.group(2),
                    m.group(3), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)),
                    m.group(7) == null ? 0 : Integer.parseInt(m.group(7)));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
