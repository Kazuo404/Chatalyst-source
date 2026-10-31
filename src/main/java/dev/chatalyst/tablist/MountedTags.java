package dev.chatalyst.tablist;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Que carteles lleva montados cada jugador, por id de entidad, para el hilo de red.
 *
 * <p>Otros plugins de paquetes (CosmeticsCore) mandan su propio SET_PASSENGERS del jugador con
 * SOLO sus entidades falsas. El cliente lo aplica tal cual: desmonta el cartel y lo deja
 * flotando donde estaba. El listener de red usa {@link #merge} para volver a meterlo.
 */
public final class MountedTags {

    private static final Map<Integer, int[]> BY_VEHICLE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> VEHICLE_OF = new ConcurrentHashMap<>();

    private MountedTags() {
    }

    static void publish(UUID owner, int vehicleId, int[] tagIds) {
        Integer previous = VEHICLE_OF.put(owner, vehicleId);
        if (previous != null && previous != vehicleId) BY_VEHICLE.remove(previous);
        if (tagIds.length == 0) {
            BY_VEHICLE.remove(vehicleId);
        } else {
            BY_VEHICLE.put(vehicleId, tagIds.clone());
        }
    }

    static Integer vehicleOf(UUID owner) {
        return VEHICLE_OF.get(owner);
    }

    static void clear(UUID owner) {
        Integer vehicle = VEHICLE_OF.remove(owner);
        if (vehicle != null) BY_VEHICLE.remove(vehicle);
    }

    static void clearAll() {
        BY_VEHICLE.clear();
        VEHICLE_OF.clear();
    }

    /**
     * Los pasajeros de {@code vehicleId} con los carteles que falten anadidos al final, o
     * {@code null} si no hay nada que cambiar (el caso de casi todos los paquetes).
     */
    public static int[] merge(int vehicleId, int[] passengers) {
        int[] tags = BY_VEHICLE.get(vehicleId);
        if (tags == null) return null;
        int[] out = null;
        int size = passengers.length;
        for (int tag : tags) {
            if (contains(passengers, tag)) continue;
            if (out == null) out = Arrays.copyOf(passengers, passengers.length + tags.length);
            out[size++] = tag;
        }
        return out == null ? null : Arrays.copyOf(out, size);
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) if (v == value) return true;
        return false;
    }
}
