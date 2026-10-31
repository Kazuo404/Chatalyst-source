package dev.chatalyst.staff;

import dev.chatalyst.util.FoliaTasks;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inmovilidad fisica de un congelado: velocidad de andar y de vuelo a cero para que el
 * cliente ni siquiera acelere (sin el tiron elastico de solo re-teleportar), mientras
 * {@code FreezeListener.onMove} cancela cualquier resto de movimiento, camara incluida.
 *
 * <p>La velocidad de andar y de vuelo SE GUARDAN en el playerdata (las "abilities"), asi que
 * un relogin NO las devuelve a su valor por defecto. Quien salia congelado — expulsado, o
 * baneado por el FreezeGuard — y era liberado estando fuera (/unfreeze, desbaneo) volvia con
 * velocidad 0: sin poder andar, y sin nada que se la devolviera. Por eso la velocidad previa
 * se apunta tambien en el PDC del jugador, que viaja con su playerdata, y al entrar sin
 * congelar se restaura ({@link #restoreIfHeld}).
 */
final class FreezeHold {

    private static final float DEFAULT_WALK = 0.2F;
    private static final float DEFAULT_FLY = 0.1F;
    private static final Map<UUID, float[]> PREVIOUS = new ConcurrentHashMap<>();

    private FreezeHold() {
    }

    private static NamespacedKey key(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "freeze_speed");
    }

    static void apply(JavaPlugin plugin, Player player) {
        FoliaTasks.entity(plugin, player, current -> {
            float walk = current.getWalkSpeed();
            float fly = current.getFlySpeed();
            // La marca del PDC manda: tras un relogin (o un re-enable del plugin) la velocidad
            // actual ya es la CONGELADA, y la buena es la que se apunto al congelar.
            float[] stored = decode(current.getPersistentDataContainer()
                    .get(key(plugin), PersistentDataType.STRING));
            // NO guardar una velocidad ya CONGELADA (0) como "previa": el jugador quedaria
            // arrastrandose ("lentitud") al descongelar.
            float[] previous = stored != null ? stored : new float[]{
                    walk > 0f ? walk : DEFAULT_WALK,
                    fly > 0f ? fly : DEFAULT_FLY};
            PREVIOUS.putIfAbsent(current.getUniqueId(), previous);
            current.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING,
                    encode(PREVIOUS.get(current.getUniqueId())));
            current.setWalkSpeed(0.0F);
            current.setFlySpeed(0.0F);
            // Un caballo o bote moveria al congelado sin disparar su PlayerMoveEvent.
            if (current.getVehicle() != null) current.leaveVehicle();
        });
    }

    static void release(JavaPlugin plugin, Player player) {
        FoliaTasks.entity(plugin, player, current -> restoreNow(plugin, current));
    }

    /**
     * Al entrar SIN estar congelado: si el jugador aun lleva la marca de un freeze (lo
     * liberaron mientras estaba fuera), se le devuelve su velocidad. Sin marca no hace nada:
     * una velocidad puesta por otro plugin no se toca.
     */
    static void restoreIfHeld(JavaPlugin plugin, Player player) {
        FoliaTasks.entity(plugin, player, current -> {
            if (current.getPersistentDataContainer().has(key(plugin), PersistentDataType.STRING)) {
                restoreNow(plugin, current);
            }
        });
    }

    private static void restoreNow(JavaPlugin plugin, Player current) {
        float[] saved = PREVIOUS.remove(current.getUniqueId());
        if (saved == null) {
            saved = decode(current.getPersistentDataContainer()
                    .get(key(plugin), PersistentDataType.STRING));
        }
        current.getPersistentDataContainer().remove(key(plugin));
        // Nunca restaurar 0/negativo: si por cualquier motivo se guardo mal, se vuelve al
        // default en vez de dejar al jugador congelado o arrastrandose tras descongelar.
        float walk = (saved == null || saved[0] <= 0f) ? DEFAULT_WALK : saved[0];
        float fly = (saved == null || saved[1] <= 0f) ? DEFAULT_FLY : saved[1];
        current.setWalkSpeed(walk);
        current.setFlySpeed(fly);
    }

    /** Al desconectarse se suelta la memoria; la marca del PDC sigue en su playerdata. */
    static void forget(UUID id) {
        PREVIOUS.remove(id);
    }

    static String encode(float[] speeds) {
        return String.format(Locale.ROOT, "%s;%s", speeds[0], speeds[1]);
    }

    /** "andar;vuelo", o null si no hay marca o no se entiende (velocidades fuera de 0..1). */
    static float[] decode(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(";");
        if (parts.length != 2) return null;
        try {
            float walk = Float.parseFloat(parts[0]);
            float fly = Float.parseFloat(parts[1]);
            if (!(walk > 0f && walk <= 1f) || !(fly > 0f && fly <= 1f)) return null;
            return new float[]{walk, fly};
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
