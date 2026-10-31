package dev.chatalyst.tagfx;

import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animated rank tags.
 *
 * A tag is a set of ItemsAdder font glyphs, one per frame. This class picks
 * which frame is showing right now and hands it out through
 * {@code %chatalyst_fx_<name>%}, or {@code %chatalyst_fx_auto%} for the
 * viewer's own rank.
 *
 * <p>The frame comes from the wall clock, never from a per-player counter, so
 * every player sees the same frame at the same instant. With a counter the tab
 * list would look ragged: each row animating on its own beat.
 *
 * <p><b>Where this shows up.</b> Only on surfaces the client repaints — the tab
 * list, the name above the head, the scoreboard, the boss bar and menus. A chat
 * message is drawn once and never redrawn, so a tag in chat cannot animate. That
 * is the game, not the plugin: use a single static glyph there.
 */
public final class TagEffects {

    /**
     * One configured animation.
     *
     * <p>{@code frames} holds what to actually send, already resolved. Two ways
     * to write it, and the difference matters more than it looks:
     *
     * <ul>
     *   <li><b>Chars</b> — the real font characters. These work <i>everywhere</i>.
     *   <li><b>Glyph + Frames</b> — the {@code :name:} shorthand. Only works where
     *       ItemsAdder gets to filter the text, which is chat and its own menus.
     *       TAB writes straight to the client, so there the shorthand shows up as
     *       literal text: {@code :tag_quasar_1:}. Learned the hard way.
     * </ul>
     */
    public record Effect(List<String> frames, long intervalMs, boolean shorthand) {

        /** What to show at {@code nowMs}. */
        public String frameAt(long nowMs) {
            int index = (int) (Math.floorDiv(nowMs, Math.max(1L, intervalMs)) % frames.size());
            return frames.get(index);
        }
    }

    private final JavaPlugin plugin;
    private final File file;

    // Reemplazado entero en load(), como el resto de managers: /chatalyst reload
    // puede correr mientras un placeholder se esta resolviendo en otro hilo.
    private volatile Map<String, Effect> effects = Map.of();

    // Un nombre mal escrito se resolveria a vacio en silencio y costaria horas de
    // encontrar. Se avisa, pero una sola vez: esto corre en cada refresco de TAB.
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public TagEffects(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tagfx.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("tagfx.yml", false);
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlFiles.load(file);
        } catch (Exception broken) {
            // Carga estricta, igual que books.yml: un fichero roto no vacia lo ya
            // cargado, porque dejaria los tags animados en blanco de golpe.
            plugin.getLogger().severe("tagfx.yml has a syntax error; keeping the effects"
                    + " already in memory. Fix the file and reload: " + broken.getMessage());
            return;
        }
        effects = read(yaml);
        warned.clear();
    }

    private Map<String, Effect> read(YamlConfiguration yaml) {
        Map<String, Effect> next = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("Effects");
        if (root == null) return Map.copyOf(next);

        for (String name : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(name);
            if (sec == null) continue;

            long interval = sec.getLong("Interval_Ms", 80L);
            if (interval < 20L) {
                // Por debajo de un tick el cliente no lo aprecia y solo gasta paquetes.
                plugin.getLogger().warning("tagfx.yml: effect '" + name
                        + "' had Interval_Ms below 20; raised to 20.");
                interval = 20L;
            }

            List<String> chars = sec.getStringList("Chars");
            List<String> frames = new ArrayList<>();
            boolean shorthand = false;

            if (!chars.isEmpty()) {
                frames.addAll(chars);
            } else {
                String glyph = sec.getString("Glyph", "tag_" + name);
                int count = sec.getInt("Frames", 0);
                for (int i = 1; i <= count; i++) frames.add(":" + glyph + "_" + i + ":");
                shorthand = true;
            }

            if (frames.size() < 2) {
                plugin.getLogger().warning("tagfx.yml: effect '" + name + "' needs at least two"
                        + " frames to animate (use Chars, or Glyph + Frames); ignored.");
                continue;
            }
            if (shorthand) {
                plugin.getLogger().warning("tagfx.yml: effect '" + name + "' uses the :name:"
                        + " shorthand. That only renders where ItemsAdder filters the text"
                        + " (chat, its own menus). In TAB it will show as literal text —"
                        + " use 'Chars' with the real font characters instead.");
            }
            next.put(name.toLowerCase(Locale.ROOT), new Effect(List.copyOf(frames), interval, shorthand));
        }
        return Map.copyOf(next);
    }

    /** The frame for {@code name} right now, or an empty string if it is not configured. */
    public String frame(String name) {
        Effect effect = effects.get(name.toLowerCase(Locale.ROOT));
        if (effect == null) {
            if (warned.add(name)) {
                plugin.getLogger().warning("Unknown tag effect '" + name
                        + "'. Add it to tagfx.yml or fix the placeholder.");
            }
            return "";
        }
        return effect.frameAt(System.currentTimeMillis());
    }

    /** True when {@code group} has an animation configured. */
    public boolean has(String group) {
        return group != null && effects.containsKey(group.toLowerCase(Locale.ROOT));
    }

    public Set<String> names() {
        return effects.keySet();
    }
}
