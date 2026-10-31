package dev.chatalyst.tebex;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a kit's real contents from whichever kits plugin the server already uses.
 *
 * <p>The point is that the owner configures nothing twice. Their kits already exist, with the
 * right items, names, colours, lore and armour trims; retyping all of that into a storefront
 * preview would be tedious and would drift out of date the first time they edit a kit.
 *
 * <p>These plugins store kits as <b>Bukkit-serialised ItemStacks</b>, so the items are read
 * back with Bukkit's own deserialiser rather than with any of their code. Nothing is linked
 * against, no API is required, and a plugin that is absent or has changed its layout simply
 * yields nothing — the storefront then falls back to whatever the owner wrote by hand.
 */
public final class KitImport {

    /** Plugin folder -> path template of the file holding one kit's items. */
    private static final Map<String, String> SOURCES = Map.of(
            "AdvancedKits", "kititems/%s.yml",
            "PlayerKits2", "kits/%s.yml",
            "PlayerKits", "kits/%s.yml");

    /**
     * Not a display limit — the preview paginates, so a kit shows in full however big it is.
     * This is only a sanity bound so a corrupt or hostile file cannot make the server read an
     * unbounded number of items into memory. A double chest is 54; nobody ships more.
     */
    private static final int MAX_ITEMS = 256;

    private final JavaPlugin plugin;
    /** Parsed kits, so opening the storefront does not read files on the main thread. */
    private final Map<String, List<ItemStack>> cache = new ConcurrentHashMap<>();

    public KitImport(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Dropped on reload so an edited kit shows its new contents. */
    public void invalidate() {
        cache.clear();
    }

    /**
     * Items of {@code kitName}, or empty when no supported plugin holds it.
     *
     * @param kitName as written in the kits plugin, e.g. {@code Vip}
     */
    public List<ItemStack> items(String kitName) {
        String kit = normalize(kitName);
        if (kit == null) return List.of();
        List<ItemStack> cached = cache.get(kit);
        if (cached != null) return cached;
        List<ItemStack> read = readFromDisk(kit);
        cache.put(kit, read);
        return read;
    }

    private List<ItemStack> readFromDisk(String kit) {
        for (var source : SOURCES.entrySet()) {
            var owner = plugin.getServer().getPluginManager().getPlugin(source.getKey());
            if (owner == null) continue;
            File file = new File(owner.getDataFolder(),
                    String.format(source.getValue(), kit));
            if (!file.isFile()) {
                // Kit names are case-sensitive on disk; try the exact folder listing before
                // giving up, so "vip" still finds "Vip.yml".
                file = findIgnoringCase(new File(owner.getDataFolder(),
                        source.getValue().substring(0, source.getValue().indexOf('/'))), kit);
                if (file == null) continue;
            }
            try {
                List<ItemStack> items = parse(YamlConfiguration.loadConfiguration(file));
                if (!items.isEmpty()) {
                    plugin.getLogger().info("Storefront preview: imported " + items.size()
                            + " item(s) for kit '" + kit + "' from " + source.getKey() + ".");
                    return items;
                }
            } catch (Exception unreadable) {
                // Another plugin's file changed shape or is corrupt. Not our problem to solve
                // and never a reason to break the shop: fall back to the written preview.
                plugin.getLogger().warning("Could not read kit '" + kit + "' from "
                        + source.getKey() + " (" + unreadable + "); using the configured"
                        + " preview instead.");
            }
        }
        return List.of();
    }

    /**
     * Kits are stored as nested sections of serialised ItemStacks — typically level, then
     * slot. Rather than assume that shape, every ItemStack found anywhere in the file is
     * collected in slot order, which survives a layout change that only moves nesting around.
     */
    static List<ItemStack> parse(ConfigurationSection root) {
        List<ItemStack> items = new ArrayList<>();
        collect(root, items);
        return List.copyOf(items.size() <= MAX_ITEMS ? items : items.subList(0, MAX_ITEMS));
    }

    private static void collect(ConfigurationSection section, List<ItemStack> into) {
        List<String> keys = new ArrayList<>(section.getKeys(false));
        // Numeric keys are slots or levels; ordering them numerically keeps armour and
        // weapons in the order the owner arranged them.
        keys.sort(Comparator.comparingInt(KitImport::asNumber).thenComparing(k -> k));
        for (String key : keys) {
            if (into.size() >= MAX_ITEMS) return;
            ItemStack item = null;
            try {
                item = section.getItemStack(key);
            } catch (RuntimeException undeserialisable) {
                // One item written by a newer server, or a material that no longer exists,
                // must not cost the buyer the whole preview. Skip it and keep going.
                continue;
            }
            if (item != null && !item.getType().isAir()) {
                into.add(item);
                continue;
            }
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) collect(child, into);
        }
    }

    private static int asNumber(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException notANumber) {
            return Integer.MAX_VALUE;
        }
    }

    private static File findIgnoringCase(File folder, String kit) {
        File[] files = folder.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().equalsIgnoreCase(kit + ".yml")) return file;
        }
        return null;
    }

    /** Refuses anything that could escape the kits folder. */
    static String normalize(String raw) {
        if (raw == null) return null;
        String kit = raw.trim();
        return kit.matches("[A-Za-z0-9_\\-]{1,64}") ? kit : null;
    }

    /** Plugin names this can import from, for documentation and diagnostics. */
    public static List<String> supportedPlugins() {
        return SOURCES.keySet().stream().sorted()
                .map(name -> name.toLowerCase(Locale.ROOT)).toList();
    }
}
