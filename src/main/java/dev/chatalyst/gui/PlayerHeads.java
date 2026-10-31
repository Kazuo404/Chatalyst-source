package dev.chatalyst.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Skins de las cabezas de jugador en TODAS las GUIs del plugin. El patron viejo
 * ({@code setOwningPlayer(getOfflinePlayer(uuid))}) pierde la skin en servidores detras de
 * un proxy (backends en offline-mode: el perfil offline no lleva texturas). Dos fases:
 *
 * <ul>
 *   <li>{@link #apply}: lo mejor disponible sin bloquear ni salir de la region — la cache
 *       si ya se resolvio antes, y si no la cabeza del OfflinePlayer (best-effort), que SI
 *       puede salir a la red en segundo plano. Nunca para un UUID de Bedrock.</li>
 *   <li>{@link #completeLater}: para jugadores online obtiene el perfil en su entity
 *       scheduler; para jugadores offline que SI jugaron aqui lo completa en segundo plano.
 *       El resultado queda en un cache LRU acotado y
 *       refresca ese slot si el espectador sigue con el menu abierto. A quien nunca entro
 *       no se le consulta (decision vieja del correo: no preguntar a Mojang por extraños).</li>
 * </ul>
 */
public final class PlayerHeads {

    private static final int MAX_COMPLETED = 2_048;
    private static final int MAX_REQUESTED = 4_096;
    // LRU acotados: balance top, correo y paneles de staff pueden recorrer jugadores
    // historicos. Cachearlos "de por vida" retenia perfiles/texturas sin limite.
    private static final Map<UUID, PlayerProfile> COMPLETED = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, PlayerProfile> eldest) {
                    return size() > MAX_COMPLETED;
                }
            });
    // UUIDs ya consultados (en curso o fallidos): LRU mayor que el cache positivo para no
    // golpear Mojang en cada apertura, pero tambien con limite duro.
    private static final Map<UUID, Boolean> REQUESTED = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > MAX_REQUESTED;
                }
            });

    private PlayerHeads() {
    }

    /**
     * Olvida la skin cacheada de un jugador. Necesario cuando su skin cambia EN VIVO
     * (/anonymous pone o quita el disfraz): sin esto, las cabezas de las GUIs seguirian
     * mostrando la skin vieja — o peor, la real de alguien anonimo.
     */
    public static void invalidate(UUID id) {
        if (id == null) return;
        COMPLETED.remove(id);
        REQUESTED.remove(id);
    }

    /** Aplica la mejor skin disponible ahora mismo (sin red). */
    public static void apply(SkullMeta meta, UUID id) {
        if (id == null) return;
        // Modo anonimo primero: setOwningPlayer haria que el CLIENTE resolviera la skin
        // real del jugador, delatando el disfraz en cada GUI con cabezas.
        PlayerProfile disguised = disguise(id);
        if (disguised != null) {
            meta.setPlayerProfile(disguised);
            return;
        }
        PlayerProfile cached = COMPLETED.get(id);
        if (cached != null) {
            meta.setPlayerProfile(cached);
            return;
        }
        // A Mojang NO se le pregunta por cuentas de Bedrock (ver isBedrock). setOwningPlayer
        // sobre un perfil sin texturas hace que el SERVIDOR salga a la red por ese UUID, y para
        // uno de Floodgate esa consulta esta condenada de antemano. Un menu de cabezas son
        // decenas de fallos de golpe, y Mojang responde HTTP 429 dejando limitado al servidor
        // entero un rato: despues fallan tambien las consultas legitimas de los jugadores de
        // Java, y todo el mundo ve cabezas grises. La skin de Bedrock la sirve Geyser, asi que
        // por no preguntar no se pierde nada.
        if (isBedrock(id)) return;
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
    }

    /**
     * Como {@link #apply}, pero SIN el respaldo que sale a la red.
     *
     * <p>{@code setOwningPlayer} sobre un perfil sin texturas hace que el servidor pregunte a
     * Mojang por ese UUID en segundo plano. En un menu normal eso es una consulta y ya. En uno
     * que redibuja las mismas cabezas muchas veces por segundo (la ruleta del sorteo) son
     * cientos en pocos segundos, y hay dos formas de que acaben mal:
     *
     * <ul>
     *   <li>Los jugadores de BEDROCK entran con UUID de Floodgate
     *       ({@code 00000000-0000-0000-0009-...}), que Mojang no conoce ni conocera: cada
     *       intento falla, y se reintenta en cada fotograma.</li>
     *   <li>Mojang responde {@code HTTP 429} y deja al servidor ENTERO limitado un rato, asi
     *       que despues fallan tambien las consultas legitimas de otros menus.</li>
     * </ul>
     *
     * <p>Aqui una cabeza generica es una perdida cosmetica minima; el rate limit no lo es. Lo
     * que si esta cacheado (o {@link #warm calentado} antes de empezar) se sigue usando.
     */
    public static void applyCached(SkullMeta meta, UUID id) {
        if (id == null) return;
        PlayerProfile disguised = disguise(id);
        if (disguised != null) {
            meta.setPlayerProfile(disguised);
            return;
        }
        PlayerProfile cached = COMPLETED.get(id);
        if (cached != null) meta.setPlayerProfile(cached);
        // Sin cache: cabeza generica. NUNCA setOwningPlayer por este camino.
    }

    /**
     * Cachea la textura VIVA de un jugador conectado sin tocar ningun slot de menu.
     *
     * <p>Para GUIs que redibujan las mismas cabezas muchas veces y en slots que CAMBIAN entre
     * fotogramas (la ruleta del sorteo): ahi {@link #completeLater} no sirve, porque refresca
     * un slot concreto y para cuando llegara la textura ese slot ya muestra a otra persona.
     * Preparando el cache antes de empezar, cada {@link #apply} posterior sale ya con la skin
     * real en vez de la generica del perfil offline.
     *
     * <p>Respeta el modo anonimo igual que el resto: de un disfrazado no se resuelve — ni se
     * cachea — jamas su perfil real.
     */
    public static void warm(Player online) {
        if (online == null) return;
        UUID id = online.getUniqueId();
        if (disguise(id) != null || COMPLETED.containsKey(id)) return;
        ChatalystPlugin plugin = ChatalystPlugin.get();
        if (plugin == null) return;
        // getPlayerProfile lee estado propiedad de la entidad: se pide en SU hilo de region.
        FoliaTasks.entity(plugin, online, target -> {
            try {
                PlayerProfile profile = target.getPlayerProfile();
                if (profile.hasTextures()) COMPLETED.put(id, profile);
            } catch (RuntimeException unavailable) {
                // Sin perfil: se queda la cabeza generica, que es lo que habia antes.
            }
        });
    }

    /**
     * Cuenta de Bedrock entrando por Floodgate/Geyser.
     *
     * <p>Floodgate fabrica su UUID con los 8 primeros bytes a cero
     * ({@code 00000000-0000-0000-0009-01f7f785559e}), porque no es una cuenta de Mojang y
     * nunca lo sera. Preguntar por ella a la API de sesiones falla SIEMPRE — y falla caro:
     * en un menu con muchas cabezas (una tabla de dinero, el correo, la ruleta del sorteo)
     * son decenas de consultas condenadas de golpe, y Mojang responde {@code HTTP 429}
     * dejando limitado al SERVIDOR ENTERO un rato, con lo que despues fallan tambien las
     * consultas legitimas de los jugadores de Java.
     *
     * <p>Su skin la sirve Geyser, no Mojang, asi que no se pierde nada por no preguntar.
     */
    static boolean isBedrock(UUID id) {
        return id != null && id.getMostSignificantBits() == 0L;
    }

    /** Perfil de incognito si el jugador esta en modo anonimo, o null. */
    private static PlayerProfile disguise(UUID id) {
        ChatalystPlugin plugin = ChatalystPlugin.get();
        return plugin == null || plugin.getAnonymousManager() == null
                ? null : plugin.getAnonymousManager().disguisedProfile(id);
    }

    /**
     * Completa la textura de un jugador OFFLINE en segundo plano y refresca el slot del menu
     * (nombre y lore intactos) si {@code viewer} sigue mirando ese inventario. No hace nada
     * para jugadores conectados (ya salen bien de {@link #apply}) ni repite consultas.
     */
    public static void completeLater(Player viewer, Inventory inv, int slot, UUID id) {
        if (id == null) return;
        // Nunca se resuelve el perfil REAL de un jugador anonimo (ni se cachea).
        if (disguise(id) != null) return;
        // A Mojang NO se le pregunta por cuentas que no son suyas (ver isBedrock).
        if (isBedrock(id)) return;
        if (COMPLETED.containsKey(id)) return;
        synchronized (REQUESTED) {
            if (REQUESTED.putIfAbsent(id, Boolean.TRUE) != null) return;
        }
        ChatalystPlugin plugin = ChatalystPlugin.get();
        if (plugin == null) {
            REQUESTED.remove(id);
            return;
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            // getPlayerProfile reads entity-owned state. Fetch it from that player's region,
            // then update the viewer from the viewer's region.
            FoliaTasks.entity(plugin, online, target -> {
                try {
                    PlayerProfile profile = target.getPlayerProfile();
                    if (!profile.hasTextures()) return;
                    COMPLETED.put(id, profile);
                    refresh(viewer, inv, slot, profile, plugin);
                } catch (RuntimeException failure) {
                    REQUESTED.remove(id);
                }
            }, () -> REQUESTED.remove(id));
            return;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        if (!offline.hasPlayedBefore()) {
            REQUESTED.remove(id);
            return;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    PlayerProfile profile = offline.getPlayerProfile();
                    if (!profile.complete(true) || !profile.hasTextures()) return;
                    COMPLETED.put(id, profile);
                    refresh(viewer, inv, slot, profile, plugin);
                } catch (Throwable ex) {
                    // sin red o sin perfil: la cabeza generica se queda
                }
            });
        } catch (Throwable rejected) {
            // No dejar una marca REQUESTED eterna si el plugin se apaga entre el click y
            // el envio al scheduler; una instancia futura podra reintentarlo.
            REQUESTED.remove(id);
        }
    }

    private static void refresh(Player viewer, Inventory inv, int slot, PlayerProfile profile,
                                ChatalystPlugin plugin) {
        // El refresco corre en el hilo del espectador (Folia-safe) y solo si ese
        // inventario concreto sigue siendo el que tiene abierto.
        FoliaTasks.entity(plugin, viewer, p -> {
            if (p.getOpenInventory().getTopInventory() != inv) return;
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.PLAYER_HEAD) return;
            item.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(profile));
            inv.setItem(slot, item);
        });
    }
}
