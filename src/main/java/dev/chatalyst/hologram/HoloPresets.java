package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Un preset es una lista de opciones de {@code /cartel opcion}, con los mismos nombres:
 * {@code escala: 7}, {@code fondo: none}... Asi el dueño escribe presets en holograms.yml con
 * el vocabulario que ya conoce, y no hay un segundo formato que mantener. Sin Bukkit.
 */
public final class HoloPresets {

    /** El cartel con el preset aplicado y las opciones que no se entendieron. */
    public record Applied(Hologram hologram, List<String> rejected) {
    }

    private HoloPresets() {
    }

    public static Applied apply(Hologram hologram, Map<String, String> preset) {
        Hologram current = hologram;
        List<String> rejected = new ArrayList<>();
        if (preset != null) {
            for (Map.Entry<String, String> entry : preset.entrySet()) {
                HoloOptions.Result result = HoloOptions.apply(current, entry.getKey(),
                        entry.getValue());
                if (result.ok()) current = result.hologram();
                else rejected.add(entry.getKey());
            }
        }
        return new Applied(current, List.copyOf(rejected));
    }
}
