package dev.chatalyst.staff.lookup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agrupacion de /ipreport: jugadores conectados que comparten conexion con otras cuentas.
 * Recibe ya resuelto, para cada conectado, su conexion actual y las cuentas conocidas de
 * esa conexion; aqui solo se decide quien sale y en que orden. Sin Bukkit.
 */
public final class SharedConnections {

    /** Un conectado y las OTRAS cuentas vistas desde su conexion actual. */
    public record Report(UUID player, String name, String hash, List<UUID> others) {
    }

    private SharedConnections() {
    }

    /**
     * @param online       conectado -> hash de su conexion actual (null si no se pudo leer)
     * @param names        conectado -> nombre a mostrar
     * @param accountsOn   hash -> cuentas conocidas de esa conexion (de toda la red)
     * @return solo los que comparten con alguien, agrupados por conexion y por nombre
     */
    public static List<Report> report(Map<UUID, String> online, Map<UUID, String> names,
                                      Map<String, List<UUID>> accountsOn) {
        List<Report> out = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : online.entrySet()) {
            String hash = entry.getValue();
            if (hash == null) continue;
            List<UUID> others = new ArrayList<>();
            for (UUID account : accountsOn.getOrDefault(hash, List.of())) {
                if (!account.equals(entry.getKey()) && !others.contains(account)) {
                    others.add(account);
                }
            }
            if (others.isEmpty()) continue;
            out.add(new Report(entry.getKey(), names.getOrDefault(entry.getKey(), "?"), hash,
                    List.copyOf(others)));
        }
        // Los de una misma conexion quedan juntos: asi se ven los grupos de un vistazo.
        out.sort(Comparator.comparing(Report::hash)
                .thenComparing(Report::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    /** Cuantos grupos distintos (conexiones) hay en el informe. */
    public static int groups(List<Report> reports) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Report report : reports) seen.put(report.hash(), true);
        return seen.size();
    }

    /** Identificador corto y estable de una conexion: los primeros caracteres del hash. */
    public static String shortId(String hash) {
        if (hash == null || hash.isBlank()) return "??????";
        return hash.length() <= 6 ? hash : hash.substring(0, 6);
    }
}
