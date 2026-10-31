package dev.chatalyst.giveaway;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.util.TimeUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Lo que el staff decide EN EL JUEGO sobre los rangos sorteables (/sorteo rango): cuanto dura
 * cada uno y si se ofrece o no. Vive en el Store compartido, asi que vale para toda la red sin
 * tocar giveaway.yml ni recargar. Lo que no se ha tocado sigue la config
 * ({@code Giveaway.Ranks.Duration}).
 *
 * <p>Claves: {@code dur:<grupo>} = duracion tal como se escribio ("30d", "permanente") y
 * {@code off:<grupo>} = "1" si el rango no se ofrece.
 */
public final class GiveawayRankSettings {

    private static volatile GiveawayRankSettings instance;

    private final Store store;

    GiveawayRankSettings(Store store) {
        this.store = store;
    }

    static GiveawayRankSettings open(StorageManager storage) {
        GiveawayRankSettings settings = new GiveawayRankSettings(storage.store("giveaway_ranks"));
        instance = settings;
        return settings;
    }

    static Optional<GiveawayRankSettings> get() {
        return Optional.ofNullable(instance);
    }

    static String key(String group) {
        return group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    }

    /** Duracion propia del rango ("30d", "permanente"), o null si sigue la de la config. */
    public String duration(String group) {
        String raw = store.get("dur:" + key(group));
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Guarda la duracion (null = volver a la de la config). Devuelve false si no se entiende. */
    public boolean setDuration(String group, String raw) {
        if (raw == null) {
            store.remove("dur:" + key(group));
            return true;
        }
        if (parse(raw) == 0L) return false;
        store.put("dur:" + key(group), raw.trim().toLowerCase(Locale.ROOT));
        return true;
    }

    public boolean enabled(String group) {
        return store.get("off:" + key(group)) == null;
    }

    public void setEnabled(String group, boolean on) {
        if (on) store.remove("off:" + key(group));
        else store.put("off:" + key(group), "1");
    }

    /**
     * Duracion en ms de un texto: {@link TimeUtil#PERMANENT} para "permanente", 0 si no se
     * entiende. Acepta lo de TimeUtil (30d, 12h, 1w...) y las palabras en espanol.
     */
    static long parse(String raw) {
        if (raw == null) return 0L;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("permanente") || value.equals("perm") || value.equals("siempre")
                || value.equals("permanent")) {
            return TimeUtil.PERMANENT;
        }
        return TimeUtil.parseDuration(value);
    }

    /**
     * Los grupos que se ofrecen como premio, en el orden de LuckPerms (logica pura).
     *
     * @param all      todos los grupos cargados
     * @param wanted   lista {@code Names} de la config; vacia o con "*" = todos
     * @param never    nunca (incluye "default" y lo que ponga la config)
     * @param staff    grupos de staff, detectados por permisos
     * @param disabled apagados desde el juego
     */
    static List<String> selectGroups(List<String> all, Set<String> wanted, Set<String> never,
                                     Set<String> staff, Predicate<String> disabled) {
        boolean everything = wanted.isEmpty() || wanted.contains("*");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String group : all) {
            String name = group == null ? "" : group.trim();
            String k = key(name);
            if (name.isEmpty() || k.equals("default") || never.contains(k) || staff.contains(k)) continue;
            if (!everything && !wanted.contains(k)) continue;
            if (disabled.test(k) || !seen.add(k)) continue;
            out.add(name);
        }
        return out;
    }
}
