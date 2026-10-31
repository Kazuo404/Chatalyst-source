package dev.chatalyst.store;

import dev.chatalyst.util.MM;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One sellable good, resolved from {@code Store_Catalog} and bounded on every axis.
 *
 * <p>Only IDs present in the catalog can ever be delivered, so a mistyped or hostile store
 * command cannot invent a product, and no part of a delivery is ever taken from the command
 * line — the command carries an ID, never a material, an amount or a command to run.
 */
public record StoreProduct(String productId, String displayName, List<ItemSpec> items,
                           List<String> commands, double money) {

    private static final int MAX_ITEMS = 27;
    private static final int MAX_COMMANDS = 10;
    private static final int MAX_LORE_LINES = 12;
    private static final double MAX_MONEY = 1_000_000_000d;

    public StoreProduct {
        items = List.copyOf(items);
        commands = List.copyOf(commands);
    }

    /**
     * El techo que se le aplica a cualquier cantidad de dinero. Publico porque el sorteo pide
     * su rango a mano y tiene que recortarlo IGUAL: dos topes distintos para el mismo concepto
     * es como se acaba pudiendo pagar por un camino lo que el otro prohibe.
     */
    public static double maxMoney() {
        return MAX_MONEY;
    }

    /** True when nothing would actually be handed over; such a product is never delivered. */
    public boolean isEmpty() {
        return items.isEmpty() && commands.isEmpty() && money <= 0d;
    }

    /** Items need a player with room; money can land on an offline account. */
    public boolean requiresOnlinePlayer() {
        return !items.isEmpty() || !commands.isEmpty();
    }

    public static Optional<StoreProduct> resolve(FileConfiguration config, String rawId) {
        String productId = normalizeId(rawId);
        if (productId == null) return Optional.empty();
        ConfigurationSection catalog = config.getConfigurationSection("Store_Catalog");
        if (catalog == null) return Optional.empty();
        String actualKey = catalog.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase(productId))
                .findFirst().orElse(null);
        if (actualKey == null) return Optional.empty();
        ConfigurationSection product = catalog.getConfigurationSection(actualKey);
        return product == null ? Optional.empty() : fromSection(productId, product);
    }

    /**
     * Parses items, commands and money out of any section shaped like a catalog entry.
     *
     * <p>Exposed for the other places that hand goods over — a giveaway prize is written with
     * the same fields and must obey the same bounds. A second parser somewhere else is how a
     * material check, a stack limit or a newline guard ends up applying to purchases but not
     * to prizes.
     *
     * @return empty when the section would hand over nothing at all
     */
    public static Optional<StoreProduct> of(String productId, ConfigurationSection section) {
        String normalized = normalizeId(productId);
        return normalized == null || section == null
                ? Optional.empty() : fromSection(normalized, section);
    }

    /** Shared parsing core, so a snapshot and a catalog entry can never drift apart. */
    private static Optional<StoreProduct> fromSection(String productId,
                                                      ConfigurationSection section) {
        StoreProduct resolved = new StoreProduct(productId,
                bounded(section.getString("Name", productId), 160),
                items(section.getMapList("Items")),
                commands(section.getStringList("Commands")),
                money(section));
        return resolved.isEmpty() ? Optional.empty() : Optional.of(resolved);
    }

    /**
     * Freezes exactly what was sold, to be stored inside the claim.
     *
     * <p>A claim can outlive the catalog entry it came from: the buyer may be offline for
     * days while the owner edits, renames or reuses that product id. Delivering whatever the
     * id happens to mean later would hand them something they never bought — so the purchase
     * carries its own contents and the catalog is only a fallback for older claims.
     *
     * <p>Base64 of YAML: single line, and free of both newlines and the unit separator that
     * delimits the claim's own fields.
     */
    public String snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("Name", displayName);
        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (ItemSpec spec : items) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("Material", spec.material().name());
            row.put("Amount", spec.amount());
            if (!spec.name().isBlank()) row.put("Name", spec.name());
            if (!spec.lore().isEmpty()) row.put("Lore", spec.lore());
            if (!spec.enchantments().isEmpty()) {
                row.put("Enchantments", new java.util.LinkedHashMap<>(spec.enchantments()));
            }
            if (spec.modelData() > 0) row.put("Model_Data", spec.modelData());
            if (spec.glow()) row.put("Glow", true);
            rows.add(row);
        }
        if (!rows.isEmpty()) yaml.set("Items", rows);
        if (!commands.isEmpty()) yaml.set("Commands", commands);
        if (money > 0d) yaml.set("Money", money);
        return java.util.Base64.getEncoder().encodeToString(
                yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    /** Rebuilds a product from the snapshot stored with the purchase. */
    public static Optional<StoreProduct> fromSnapshot(String rawId, String snapshot) {
        String productId = normalizeId(rawId);
        if (productId == null || snapshot == null || snapshot.isBlank()) {
            return Optional.empty();
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(
                    java.util.Base64.getDecoder().decode(snapshot), StandardCharsets.UTF_8));
            return fromSection(productId, yaml);
        } catch (org.bukkit.configuration.InvalidConfigurationException
                 | RuntimeException unreadable) {
            // Corrupt or truncated snapshot: fall back to the catalog rather than throw on
            // a delivery path, so the purchase stays recoverable.
            return Optional.empty();
        }
    }

    public static List<String> configuredIds(FileConfiguration config) {
        ConfigurationSection catalog = config.getConfigurationSection("Store_Catalog");
        if (catalog == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (String key : catalog.getKeys(false)) {
            String normalized = normalizeId(key);
            if (normalized != null && resolve(config, normalized).isPresent()) {
                ids.add(normalized);
            }
        }
        return List.copyOf(ids);
    }

    private static List<ItemSpec> items(List<java.util.Map<?, ?>> configured) {
        List<ItemSpec> specs = new ArrayList<>();
        for (java.util.Map<?, ?> raw : configured) {
            if (specs.size() >= MAX_ITEMS) break;
            Material material = Material.matchMaterial(
                    String.valueOf(raw.get("Material")).toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem() || material.isAir()) continue;
            int amount = clampInt(raw.get("Amount"), 1, 1, material.getMaxStackSize());
            String name = raw.get("Name") == null
                    ? "" : bounded(String.valueOf(raw.get("Name")), 160);
            List<String> lore = new ArrayList<>();
            if (raw.get("Lore") instanceof List<?> configuredLore) {
                for (Object line : configuredLore) {
                    if (lore.size() >= MAX_LORE_LINES) break;
                    lore.add(bounded(String.valueOf(line), 256));
                }
            }
            java.util.Map<String, Integer> enchants = new java.util.LinkedHashMap<>();
            if (raw.get("Enchantments") instanceof java.util.Map<?, ?> configuredEnchants) {
                configuredEnchants.forEach((key, level) -> {
                    String name0 = String.valueOf(key).toLowerCase(Locale.ROOT).trim();
                    if (name0.matches("[a-z0-9_:./-]{1,64}")) {
                        enchants.put(name0, clampInt(level, 1, 1, 255));
                    }
                });
            }
            int modelData = clampInt(raw.get("Model_Data"), 0, 0, 16_777_215);
            boolean glow = Boolean.parseBoolean(String.valueOf(raw.get("Glow")));
            specs.add(new ItemSpec(material, amount, name, List.copyOf(lore),
                    java.util.Map.copyOf(enchants), modelData, glow));
        }
        return specs;
    }

    private static List<String> commands(List<String> configured) {
        List<String> safe = new ArrayList<>();
        for (String command : configured) {
            if (safe.size() >= MAX_COMMANDS) break;
            // Newlines would let one configured line become several dispatched commands.
            String bounded = bounded(command, 256);
            if (bounded.isEmpty()) continue;
            safe.add(bounded.startsWith("/") ? bounded.substring(1) : bounded);
        }
        return List.copyOf(safe);
    }

    private static double money(ConfigurationSection product) {
        double configured = product.getDouble("Money",
                product.getDouble("Amount", 0d));
        if (!Double.isFinite(configured) || configured <= 0d) return 0d;
        return Math.min(MAX_MONEY, configured);
    }

    static String normalizeId(String rawId) {
        if (rawId == null) return null;
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_\\-]{1,64}") ? normalized : null;
    }

    private static int clampInt(Object raw, int fallback, int min, int max) {
        if (!(raw instanceof Number number)) return fallback;
        return Math.max(min, Math.min(max, number.intValue()));
    }

    private static String bounded(String value, int max) {
        String safe = value == null || value.equals("null")
                ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    /** One item in a product, already validated; building it cannot fail. */
    public record ItemSpec(Material material, int amount, String name, List<String> lore,
                           java.util.Map<String, Integer> enchantments, int modelData,
                           boolean glow) {

        public ItemStack build() {
            ItemStack stack = new ItemStack(material, amount);
            stack.editMeta(meta -> {
                if (!name.isBlank()) meta.displayName(MM.full(name).decoration(
                        net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                if (!lore.isEmpty()) {
                    List<net.kyori.adventure.text.Component> rendered = new ArrayList<>();
                    for (String line : lore) {
                        rendered.add(MM.full(line).decoration(
                                net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    }
                    meta.lore(rendered);
                }
                if (modelData > 0) meta.setCustomModelData(modelData);
                if (glow) meta.setEnchantmentGlintOverride(true);
                enchantments.forEach((key, level) -> {
                    Enchantment enchantment = enchantment(key);
                    // Unrestricted: a level above the vanilla cap is the owner's deliberate
                    // choice, and silently refusing it would shortchange the buyer.
                    if (enchantment != null) meta.addEnchant(enchantment, level, true);
                });
            });
            return stack;
        }

        private static Enchantment enchantment(String key) {
            NamespacedKey parsed = key.indexOf(':') >= 0
                    ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
            return parsed == null ? null : org.bukkit.Registry.ENCHANTMENT.get(parsed);
        }
    }
}
