package dev.chatalyst.channel;

import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Regiones de chat propias de Chatalyst (cajas 3D definidas con /cht region), para que el
 * canal REGION funcione sin WorldGuard. Se persisten en regions.yml. Con WorldGuard
 * instalado ambas fuentes conviven: el tracker mezcla las regiones de los dos.
 */
public final class ChatRegions {

    /**
     * Caja 3D con esquinas normalizadas (min &lt;= max en cada eje). {@code channel} opcional:
     * si esta definido, entrar en la region te cambia a ese canal automaticamente (y salir
     * lo restaura).
     */
    public record Region(String name, String world,
                         double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ,
                         String channel) {

        /** Crea la region normalizando las dos esquinas en cualquier orden. */
        public static Region of(String name, String world,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2) {
            return of(name, world, x1, y1, z1, x2, y2, z2, "");
        }

        public static Region of(String name, String world,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2, String channel) {
            return new Region(name, world,
                    Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                    channel == null ? "" : channel.toLowerCase(Locale.ROOT));
        }

        public boolean contains(String world, double x, double y, double z) {
            return this.world.equals(world)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    private final JavaPlugin plugin;
    private volatile List<Region> regions = List.of();

    public ChatRegions(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "regions.yml");
        if (!file.exists()) {
            regions = List.of();
            return;
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (Exception broken) {
            // Carga estricta: un regions.yml roto NO se trata como vacio — save() reescribe
            // el archivo completo y borraria todas las regiones. Se conserva lo que hay en
            // memoria hasta que el owner arregle el archivo y recargue.
            plugin.getLogger().severe("regions.yml has a syntax error; keeping the regions"
                    + " already in memory. Fix the file and reload: " + broken.getMessage());
            return;
        }
        List<Region> next = new ArrayList<>();
        var section = yaml.getConfigurationSection("Regions");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                var sec = section.getConfigurationSection(name);
                if (sec == null) continue;
                String world = sec.getString("World", "");
                if (world.isEmpty()) continue;
                next.add(Region.of(name.toLowerCase(Locale.ROOT), world,
                        sec.getDouble("X1"), sec.getDouble("Y1"), sec.getDouble("Z1"),
                        sec.getDouble("X2"), sec.getDouble("Y2"), sec.getDouble("Z2"),
                        sec.getString("Channel", "")));
            }
        }
        regions = List.copyOf(next);
    }

    public boolean isEmpty() {
        return regions.isEmpty();
    }

    public List<Region> all() {
        return regions;
    }

    /** Primera region con canal de auto-switch que contiene el punto, o null. */
    public Region autoSwitchRegionAt(String world, double x, double y, double z) {
        for (Region r : regions) {
            if (!r.channel().isEmpty() && r.contains(world, x, y, z)) return r;
        }
        return null;
    }

    /** Nombres de las regiones que contienen el punto (con prefijo, listos para la cache). */
    public List<String> namesAt(String world, double x, double y, double z) {
        List<Region> current = regions;
        if (current.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(2);
        for (Region r : current) {
            if (r.contains(world, x, y, z)) {
                // Prefijo para no chocar jamas con un id de region de WorldGuard.
                out.add("chatalyst:" + r.name());
            }
        }
        return out;
    }

    /** Añade (o reemplaza) una region y persiste. */
    public void add(Region region) throws IOException {
        List<Region> next = new ArrayList<>(regions);
        next.removeIf(r -> r.name().equals(region.name()));
        next.add(region);
        regions = List.copyOf(next);
        save();
    }

    /** Elimina una region por nombre y persiste. Devuelve true si existia. */
    public boolean remove(String name) throws IOException {
        String low = name.toLowerCase(Locale.ROOT);
        List<Region> next = new ArrayList<>(regions);
        boolean had = next.removeIf(r -> r.name().equals(low));
        if (had) {
            regions = List.copyOf(next);
            save();
        }
        return had;
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Chatalyst chat regions (REGION channels without WorldGuard).",
                "Managed with /cht region; edit by hand only with the server stopped."));
        for (Region r : regions) {
            String base = "Regions." + r.name() + ".";
            yaml.set(base + "World", r.world());
            yaml.set(base + "X1", r.minX());
            yaml.set(base + "Y1", r.minY());
            yaml.set(base + "Z1", r.minZ());
            yaml.set(base + "X2", r.maxX());
            yaml.set(base + "Y2", r.maxY());
            yaml.set(base + "Z2", r.maxZ());
            if (!r.channel().isEmpty()) {
                yaml.set(base + "Channel", r.channel());
            }
        }
        YamlFiles.saveAtomic(yaml, new File(plugin.getDataFolder(), "regions.yml"));
    }
}
