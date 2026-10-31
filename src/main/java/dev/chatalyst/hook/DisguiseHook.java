package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Detecta si un jugador esta DISFRAZADO por Lib's Disguises (o por algo que lo use, como
 * Morphy). Sirve para callar todo lo que delataria al disfrazado: el nametag flotante, el
 * nombre sobre la cabeza y cualquier cartel que lleve su rango.
 *
 * <p>Por reflexion a proposito: Lib's Disguises es opcional y su API cambia entre versiones
 * mayores. Enlazar contra ella obligaria a tenerla instalada y a recompilar Chatalyst cada
 * vez que saquen una nueva. Si no esta, o si su API cambia, {@link #isDisguised} devuelve
 * false y todo sigue funcionando como antes.
 */
public final class DisguiseHook {

    private static Boolean available;
    private static Method isDisguised;

    private DisguiseHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = resolve();
        }
        return available;
    }

    private static boolean resolve() {
        if (Bukkit.getPluginManager().getPlugin("LibsDisguises") == null) return false;
        try {
            Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            isDisguised = api.getMethod("isDisguised", org.bukkit.entity.Entity.class);
            return true;
        } catch (Throwable notThere) {
            isDisguised = null;
            return false;
        }
    }

    /** True si este jugador esta disfrazado ahora mismo. False si no, o si LD no esta. */
    public static boolean isDisguised(Player player) {
        if (player == null || !isAvailable() || isDisguised == null) return false;
        try {
            Object result = isDisguised.invoke(null, player);
            return result instanceof Boolean flag && flag;
        } catch (Throwable failure) {
            // Un cambio de API no puede tumbar el render del chat ni de los nametags:
            // se degrada a "no disfrazado", que es el comportamiento de siempre.
            return false;
        }
    }

    /**
     * Avisa en cuanto un jugador se disfraza o deja de estarlo, para poder repintar su
     * nametag al instante.
     *
     * <p>Sin esto el disfraz solo se nota en el siguiente refresco periodico, y el nametag
     * clasico corre cada 30s por defecto: medio minuto con el rango encima de la cabeza de
     * un jugador que cree estar oculto. Los eventos son cancelables y se lanzan ANTES de
     * aplicarse, asi que se escucha en MONITOR (cuando ya nadie va a cancelarlos) y se
     * repinta un tick despues, cuando DisguiseAPI ya refleja el estado nuevo.
     *
     * @return true si se pudo enganchar; false si LD no esta o cambio su API.
     */
    public static boolean listen(org.bukkit.plugin.java.JavaPlugin plugin,
                                 java.util.function.Consumer<Player> onChange) {
        if (!isAvailable()) return false;
        boolean any = false;
        for (String name : new String[]{"DisguiseEvent", "UndisguiseEvent"}) {
            try {
                Class<?> raw = Class.forName("me.libraryaddict.disguise.events." + name);
                if (!org.bukkit.event.Event.class.isAssignableFrom(raw)) continue;
                @SuppressWarnings("unchecked")
                Class<? extends org.bukkit.event.Event> type =
                        (Class<? extends org.bukkit.event.Event>) raw;
                Method getEntity = raw.getMethod("getEntity");
                Bukkit.getPluginManager().registerEvent(type, new org.bukkit.event.Listener() {
                }, org.bukkit.event.EventPriority.MONITOR, (listener, event) -> {
                    if (!type.isInstance(event)) return;
                    try {
                        if (getEntity.invoke(event) instanceof Player disguised) {
                            dev.chatalyst.util.FoliaTasks.globalLater(
                                    plugin, () -> onChange.accept(disguised), 1L);
                        }
                    } catch (Throwable ignored) {
                        // Un cambio de API no puede tumbar el pipeline de eventos del server.
                    }
                }, plugin, true);
                any = true;
            } catch (Throwable notThere) {
                // Ese evento no existe en esta version: se sigue con el otro.
            }
        }
        return any;
    }

    /** Se vuelve a resolver tras un /cht reload o si LD se carga mas tarde. */
    public static void reset() {
        available = null;
        isDisguised = null;
    }
}
