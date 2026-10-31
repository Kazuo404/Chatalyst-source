package dev.chatalyst.chestlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * El candado de un contenedor: dueno y gente de confianza.
 *
 * <p>Se guarda DENTRO del bloque (su PersistentDataContainer), no en una base de datos aparte:
 * si el bloque desaparece, el candado desaparece con el, y no hay tabla que se desincronice con
 * el mundo (un rollback, un WorldEdit o una copia del mapa se llevan el candado puesto).
 */
public record Lock(UUID owner, String ownerName, Set<UUID> trusted) {

    public Lock {
        trusted = Set.copyOf(trusted);
    }

    public boolean canAccess(UUID player) {
        return owner.equals(player) || trusted.contains(player);
    }

    public Lock withTrusted(UUID player) {
        Set<UUID> t = new LinkedHashSet<>(trusted);
        t.add(player);
        return new Lock(owner, ownerName, t);
    }

    public Lock withoutTrusted(UUID player) {
        Set<UUID> t = new LinkedHashSet<>(trusted);
        t.remove(player);
        return new Lock(owner, ownerName, t);
    }

    /** {@code uuid,uuid,...}; lo que no sea un UUID se ignora. */
    static String encodeTrusted(Set<UUID> trusted) {
        return String.join(",", trusted.stream().map(UUID::toString).toList());
    }

    static Set<UUID> decodeTrusted(String raw) {
        Set<UUID> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            try {
                out.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // un trozo roto no tumba el resto del candado
            }
        }
        return out;
    }

    List<UUID> trustedList() {
        return new ArrayList<>(trusted);
    }
}
