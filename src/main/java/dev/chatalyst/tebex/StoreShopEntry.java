package dev.chatalyst.tebex;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A one-shot good from {@code Store_Catalog}, shown in the same storefront as ranks.
 *
 * <p>Only the presentation lives here. What is actually delivered stays in
 * {@link dev.chatalyst.store.StoreProduct}, and the {@code Shop} block below merely decides
 * whether and how the product is advertised. A product with no {@code Shop} block is still
 * deliverable — plenty of goods are sold outside the in-game menu — it simply is not listed.
 */
public record StoreShopEntry(
        String productId,
        String category,
        String displayName,
        List<String> description,
        String icon,
        String price,
        String purchaseUrlTemplate,
        int order,
        boolean featured,
        String viewPermission,
        String selectionMessage,
        String buttonText,
        String buttonHover) implements StorefrontEntry {

    private static final int MAX_DESCRIPTION_LINES = 12;

    public StoreShopEntry {
        description = List.copyOf(description);
    }

    public static Optional<StoreShopEntry> resolve(FileConfiguration config, CommandSender viewer, String rawId) {
        String productId = normalizeId(rawId);
        if (productId == null) return Optional.empty();
        ConfigurationSection catalog = config.getConfigurationSection("Store_Catalog");
        if (catalog == null) return Optional.empty();
        String actualKey = catalog.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase(productId))
                .findFirst().orElse(null);
        if (actualKey == null) return Optional.empty();
        ConfigurationSection product = catalog.getConfigurationSection(actualKey);
        if (product == null) return Optional.empty();
        ConfigurationSection shop = product.getConfigurationSection("Shop");
        if (shop == null || !shop.getBoolean("Visible", false)) return Optional.empty();

        String urlTemplate = text(shop, "Purchase_Url", config.getString("Tebex.Shop.Rank_Url_Template", ""), 512, viewer);
        // Same rule as ranks: a card that cannot be bought is worse than no card at all.
        if (TebexShopEntry.resolvePurchaseUrl(urlTemplate, productId, "Player",
                new UUID(0L, 0L)).isEmpty()) {
            return Optional.empty();
        }

        String permission = text(shop, "View_Permission", "", 128, viewer);
        if (!permission.isEmpty() && !permission.matches("[a-z0-9_.\\-*]{1,128}")) {
            return Optional.empty();
        }
        return Optional.of(new StoreShopEntry(
                productId,
                text(shop, "Category", "", 64, viewer).toLowerCase(Locale.ROOT),
                text(shop, "Display_Name",
                        bounded(product.getString("Name", productId), 160), 160, viewer),
                description(shop, config, viewer),
                icon(text(shop, "Icon", "CHEST", 128, viewer)),
                text(shop, "Price", "", 64, viewer),
                urlTemplate,
                Math.max(-10_000, Math.min(10_000, shop.getInt("Order", 0))),
                shop.getBoolean("Featured", false),
                permission,
                text(shop, "Selection_Message", dev.chatalyst.lang.LocalizedConfig.string(config.getConfigurationSection("Tebex.Shop"), "Selection_Message", viewer,
                                "<#C0B3D9>You selected {rank}."), 1_024, viewer),
                text(shop, "Button_Text", dev.chatalyst.lang.LocalizedConfig.string(config.getConfigurationSection("Tebex.Shop"), "Button_Text", viewer,
                                "<gradient:#B56CFF:#E4A8FF><bold>BUY {rank_id}</bold>"
                                        + "</gradient>"), 256, viewer),
                text(shop, "Button_Hover", dev.chatalyst.lang.LocalizedConfig.string(config.getConfigurationSection("Tebex.Shop"), "Button_Hover", viewer,
                                "<#FBF2FF>Open the secure purchase page for {rank_id}"), 256, viewer)));
    }

    /** Every listed product id, for the storefront to walk. */
    public static List<String> listedIds(FileConfiguration config, CommandSender viewer) {
        ConfigurationSection catalog = config.getConfigurationSection("Store_Catalog");
        if (catalog == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (String key : catalog.getKeys(false)) {
            String id = normalizeId(key);
            if (id != null && resolve(config, viewer, id).isPresent()) ids.add(id);
        }
        return List.copyOf(ids);
    }

    @Override
    public String id() {
        return productId;
    }

    /** Goods never expire, so no duration line is shown. */
    @Override
    public String duration() {
        return "";
    }

    /**
     * Empty on purpose: a good is not "owned" in a way the storefront can check, and buying a
     * second kit is legitimate. Showing "you already have this" would be both wrong and a
     * reason not to buy.
     */
    @Override
    public String ownedGroup() {
        return "";
    }

    @Override
    public ItemStack iconItem() {
        if (icon.indexOf(':') >= 0) {
            ItemStack custom = dev.chatalyst.hook.ItemsAdderHook.item(icon);
            if (custom != null && !custom.getType().isAir()) return custom;
        }
        Material parsed = Material.matchMaterial(icon);
        return new ItemStack(parsed != null && parsed.isItem() && !parsed.isAir()
                ? parsed : Material.CHEST);
    }

    @Override
    public Optional<String> purchaseUrl(String playerName, UUID playerId) {
        return TebexShopEntry.resolvePurchaseUrl(
                purchaseUrlTemplate, productId, playerName, playerId);
    }

    private static List<String> description(ConfigurationSection shop,
                                            FileConfiguration config,
                                            CommandSender viewer) {
        List<String> configured = dev.chatalyst.lang.LocalizedConfig.list(
                shop, "Description", viewer);
        if (configured.isEmpty()) {
            configured = dev.chatalyst.lang.LocalizedConfig.list(
                    config.getConfigurationSection("Tebex.Shop"),
                    "Default_Description", viewer);
        }
        List<String> safe = new ArrayList<>();
        for (String line : configured) {
            if (safe.size() >= MAX_DESCRIPTION_LINES) break;
            safe.add(bounded(line, 256));
        }
        return List.copyOf(safe);
    }

    /**
     * Owner text in the viewer's language: a parallel {@code _es} key wins for a Spanish
     * client, exactly as announcements and auto-replies already do.
     */
    private static String text(ConfigurationSection section, String path,
                               String fallback, int max, CommandSender viewer) {
        return bounded(dev.chatalyst.lang.LocalizedConfig.string(
                section, path, viewer, fallback), max);
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String icon(String raw) {
        String namespaced = raw.toLowerCase(Locale.ROOT);
        if (namespaced.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) return namespaced;
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}") || normalized.endsWith("AIR")) {
            return "CHEST";
        }
        return normalized;
    }

    static String normalizeId(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_\\-]{1,64}") ? normalized : null;
    }
}
