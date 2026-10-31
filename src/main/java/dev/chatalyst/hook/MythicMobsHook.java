package dev.chatalyst.hook;

import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

/**
 * Como se llama de verdad un mob de MythicMobs.
 *
 * <p>Un jefe custom casi nunca lleva puesto el nombre de Bukkit. MythicMobs guarda su
 * {@code Display} dentro de su propia instancia, y cuando encima lo dibuja ModelEngine el
 * cuerpo real va invisible y el cartel con el nombre lo pinta el modelo, asi que la entidad
 * que golpea puede no tener {@code customName} ninguno. Preguntarle solo a Bukkit devolvia
 * "Zombie" y el trabajo de nombrar al bicho se perdia justo en la linea que lee todo el
 * servidor.
 *
 * <p>Asi que si Bukkit no sabe el nombre, se le pregunta a MythicMobs. Por reflexion y sin
 * dependencia de compilacion: es un plugin de pago, su jar no esta en ningun repositorio, y
 * Chatalyst tiene que arrancar igual en un servidor que no lo tenga.
 *
 * <p>El texto que devuelve lo escribio el DUEÑO en {@code Mobs/*.yml}, no un jugador, asi que
 * se interpreta con formato: codigos legacy ({@code &2}) y, si los usa, etiquetas MiniMessage.
 * Un mob bautizado por un jugador con una placa no pasa por aqui — ese lleva {@code customName}
 * y se escapa por el camino de siempre.
 */
public final class MythicMobsHook {

    private MythicMobsHook() {
    }

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    /** null = aun sin resolver; una vez resuelto no se vuelve a mirar. */
    private static volatile Boolean present;
    private static Method instance;
    private static Method apiHelper;
    private static Method mobInstance;
    private static Method displayName;
    private static Method mobType;

    public static boolean available() {
        Boolean known = present;
        if (known != null) return known;
        synchronized (MythicMobsHook.class) {
            if (present != null) return present;
            present = resolve();
            return present;
        }
    }

    private static boolean resolve() {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) return false;
        try {
            Class<?> bukkitApi = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            instance = bukkitApi.getMethod("inst");
            apiHelper = bukkitApi.getMethod("getAPIHelper");
            Class<?> helper = Class.forName("io.lumine.mythic.bukkit.BukkitAPIHelper");
            mobInstance = helper.getMethod("getMythicMobInstance", Entity.class);
            Class<?> active = Class.forName("io.lumine.mythic.core.mobs.ActiveMob");
            displayName = active.getMethod("getDisplayName");
            mobType = active.getMethod("getMobType");
            return true;
        } catch (ReflectiveOperationException | RuntimeException changed) {
            // Otra version de MythicMobs movio la API: se sigue sin ella, no se rompe nada.
            Bukkit.getLogger().info("[Chatalyst] MythicMobs is installed but its API looks"
                    + " different, so custom mob names fall back to the vanilla species.");
            return false;
        }
    }

    /**
     * Nombre del mob de MythicMobs detras de esta entidad, o null si no lo es (o si el plugin
     * no esta). Nunca lanza: un fallo aqui solo significa quedarse con el nombre vanilla.
     */
    public static Component displayName(Entity entity) {
        if (entity == null || !available()) return null;
        try {
            Object mythic = instance.invoke(null);
            Object helper = apiHelper.invoke(mythic);
            Object active = mobInstance.invoke(helper, entity);
            if (active == null) return null;
            Component shown = formatted(displayName.invoke(active));
            // Sin Display escrito, el nombre interno ("SkeletalKnight") sigue siendo mas
            // cierto que la especie del cuerpo que MythicMobs uso por debajo.
            return shown != null ? shown : formatted(mobType.invoke(active));
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return null;
        }
    }

    /** Texto del dueño: codigos legacy y, si los hubiera, etiquetas MiniMessage.
     *  Nucleo puro y testeable: no toca MythicMobs ni Bukkit. */
    static Component formatted(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) return null;
        Component legacy = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(text.replace('§', '&'));
        String plain = PLAIN.serialize(legacy);
        int open = plain.indexOf('<');
        if (open >= 0 && plain.indexOf('>', open) > open) {
            try {
                return MM.full(plain);
            } catch (RuntimeException notMiniMessage) {
                return legacy;
            }
        }
        return legacy;
    }
}
