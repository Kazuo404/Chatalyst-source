package dev.chatalyst.combatlog;

/**
 * Un golpe o una muerte entre dos jugadores: quien, a quien, con que, cuanto dano y -- lo que
 * de verdad importa para arbitrar -- <b>quien abrio el encuentro</b> y cuanto hacia de eso.
 *
 * <p>{@code action} es {@code FIRST} (el golpe que abre la pelea), {@code HIT} (los
 * siguientes) o {@code KILL}. El dano se guarda en DECIMAS de punto (45 = 4,5 puntos = dos
 * corazones y cuarto) para que la linea del archivo no arrastre decimales ni depender del
 * separador decimal del sistema.
 */
public record CombatRecord(long time, String attacker, String victim, String action,
                           String weapon, int damageTenths, String starter, long sinceMillis,
                           String world, int x, int y, int z, int repeats) {

    /** Registro sin repeticiones agrupadas (el caso normal). */
    public CombatRecord(long time, String attacker, String victim, String action, String weapon,
                        int damageTenths, String starter, long sinceMillis,
                        String world, int x, int y, int z) {
        this(time, attacker, victim, action, weapon, damageTenths, starter, sinceMillis,
                world, x, y, z, 0);
    }

    /** Marcador de "no se sabe": el archivo no admite campos vacios. */
    private static final String NONE = "-";

    /** Linea estable para el archivo de log diario; se puede re-parsear con {@link #parse}. */
    public String fileContent() {
        String base = action + " " + victim + " " + weapon + " " + damageTenths + " "
                + (starter == null || starter.isEmpty() ? NONE : starter) + " " + sinceMillis
                + " @ " + world + " " + x + " " + y + " " + z;
        return repeats > 0 ? base + " +" + repeats : base;
    }

    // El nombre del mundo puede llevar espacios (mundos custom), asi que se captura codicioso
    // y las tres ultimas cifras son las coordenadas -- misma trampa que ya costo entradas
    // perdidas en el log de contenedores. Los nombres de jugador NO llevan espacios: Floodgate
    // los sustituye por '_' (replace-spaces), asi que \S+ es seguro para atacante y victima.
    private static final java.util.regex.Pattern FILE_LINE = java.util.regex.Pattern.compile(
            "^(\\S+) (\\S+) (\\S+) (\\d+) (\\S+) (\\d+) @ (.+) (-?\\d+) (-?\\d+) (-?\\d+)"
                    + "(?: \\+(\\d+))?$");

    /**
     * Reconstruye un registro desde una linea de archivo (contenido de una entrada COMBAT del
     * log diario), o null si el formato no coincide.
     *
     * @param attacker el campo "who" de la linea del log, que es siempre quien golpeo
     */
    public static CombatRecord parse(long time, String attacker, String content) {
        var m = FILE_LINE.matcher(content);
        if (!m.matches()) return null;
        try {
            String starter = m.group(5);
            return new CombatRecord(time, attacker, m.group(2), m.group(1), m.group(3),
                    Integer.parseInt(m.group(4)),
                    NONE.equals(starter) ? "" : starter,
                    Long.parseLong(m.group(6)),
                    m.group(7), Integer.parseInt(m.group(8)), Integer.parseInt(m.group(9)),
                    Integer.parseInt(m.group(10)),
                    m.group(11) == null ? 0 : Integer.parseInt(m.group(11)));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** Dano en puntos con un decimal, para mostrarlo ("4.5"). */
    public String damageLabel() {
        return (damageTenths / 10) + "." + (damageTenths % 10);
    }

    /** True si quien pego primero fue el atacante de ESTA linea. */
    public boolean startedByAttacker() {
        return starter != null && !starter.isEmpty() && starter.equalsIgnoreCase(attacker);
    }
}
