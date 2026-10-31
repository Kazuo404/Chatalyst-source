package dev.chatalyst.staff.lookup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Que avisos de un jugador siguen activos.
 *
 * <p>Los avisos se registran en el historial de castigos; los puntos viven aparte y decaen de
 * golpe: tras {@code decay} sin infracciones vuelven a cero. Asi que un aviso esta activo si
 * no se retiro con /unwarn y pertenece a la racha que todavia cuenta: recorriendo hacia atras
 * desde la ultima infraccion, la racha se corta en el primer hueco de {@code decay} o mas.
 * Sin Bukkit, para poder fijar la regla con tests.
 */
public final class WarnLedger {

    /** Un aviso tal como esta en el historial. {@code id} se asigna al listar (1 = mas antiguo). */
    public record Warn(int id, long time, String staff, String reason) {
    }

    private WarnLedger() {
    }

    /**
     * @param warns          avisos del historial, en cualquier orden (id ignorado)
     * @param revoked        momentos de los avisos retirados con /unwarn
     * @param lastInfraction ultima infraccion que sumo puntos (0 = sin puntos)
     * @param decay          ventana de decaimiento; 0 o negativo = nunca decaen
     * @return los activos, del mas antiguo al mas reciente, numerados desde 1
     */
    public static List<Warn> active(List<Warn> warns, Set<Long> revoked, long lastInfraction,
                                    long decay, long now) {
        if (lastInfraction <= 0) return List.of();
        boolean decays = decay > 0;
        if (decays && now - lastInfraction >= decay) return List.of();
        List<Warn> newestFirst = new ArrayList<>(warns);
        newestFirst.sort(Comparator.comparingLong(Warn::time).reversed());
        List<Warn> kept = new ArrayList<>();
        long boundary = Math.max(lastInfraction, newestFirst.isEmpty()
                ? lastInfraction : newestFirst.getFirst().time());
        for (Warn warn : newestFirst) {
            if (decays && boundary - warn.time() >= decay) break; // la racha se corto aqui
            boundary = warn.time();
            if (revoked != null && revoked.contains(warn.time())) continue;
            kept.add(warn);
        }
        List<Warn> out = new ArrayList<>(kept.size());
        for (int i = kept.size() - 1, id = 1; i >= 0; i--, id++) {
            Warn warn = kept.get(i);
            out.add(new Warn(id, warn.time(), warn.staff(), warn.reason()));
        }
        return List.copyOf(out);
    }

    /**
     * El aviso que pide /unwarn: un numero de la lista de /checkwarn, o "last"/"ultimo"
     * (o nada) para el mas reciente. Null si no existe.
     */
    public static Warn select(List<Warn> active, String selector) {
        if (active.isEmpty()) return null;
        if (selector == null || selector.isBlank()
                || selector.equalsIgnoreCase("last") || selector.equalsIgnoreCase("ultimo")
                || selector.equalsIgnoreCase("último")) {
            return active.getLast();
        }
        String digits = selector.startsWith("#") ? selector.substring(1) : selector;
        try {
            int id = Integer.parseInt(digits.trim());
            for (Warn warn : active) if (warn.id() == id) return warn;
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return null;
    }
}
