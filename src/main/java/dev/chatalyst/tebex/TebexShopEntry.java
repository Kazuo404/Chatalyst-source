package dev.chatalyst.tebex;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Immutable, bounded view of one player-facing rank card in the Tebex shop. */
public record TebexShopEntry(
        String rankId,
        String group,
        String duration,
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
        String buttonHover,
        String category) implements StorefrontEntry {

    private static final int MAX_DESCRIPTION_LINES = 12;

    public TebexShopEntry {
        description = List.copyOf(description);
    }

    public static Optional<TebexShopEntry> resolve(FileConfiguration config, CommandSender viewer, String rawRankId) {
        String rankId = normalizeRank(rawRankId);
        if (rankId == null) return Optional.empty();
        // Case-insensitive like TebexRankService and StoreShopEntry already were. Looking
        // up the lowercased id directly meant a catalog written "Vip:" was deliverable by
        // command but invisible in the shop, with nothing anywhere to say why.
        ConfigurationSection rank = section(config, "Rank_Catalog", rankId);
        if (rank == null || !rank.getBoolean("Tebex_Enabled", false)) {
            return Optional.empty();
        }
        ConfigurationSection shop = rank.getConfigurationSection("Shop");
        if (shop == null || !shop.getBoolean("Visible", false)) {
            return Optional.empty();
        }

        String urlTemplate = text(shop, "Purchase_Url", config.getString("Tebex.Shop.Rank_Url_Template", ""), 512, viewer);
        if (resolvePurchaseUrl(urlTemplate, rankId, "Player",
                new UUID(0L, 0L)).isEmpty()) {
            return Optional.empty();
        }

        // Lowercased like TebexRankService and TebexEntitlements do. Without this a catalog
        // written as "VIP" produced a card whose group never matched the stored entitlement,
        // so the shop silently failed to notice the player already owned that rank.
        String group = bounded(rank.getString("LuckPerms_Group", rankId), 64)
                .toLowerCase(Locale.ROOT);
        String duration = bounded(rank.getString("Tebex_Duration", "permanent"), 64);
        String name = text(shop, "Display_Name", "<gradient:#B56CFF:#E4A8FF><bold>{rank_id}</bold></gradient>", 160, viewer);
        List<String> description = description(shop, config, viewer);
        String icon = icon(text(shop, "Icon", "EMERALD", 128, viewer));
        String price = text(shop, "Price", "", 64, viewer);
        int order = Math.max(-10_000, Math.min(10_000, shop.getInt("Order", 0)));
        boolean featured = shop.getBoolean("Featured", false);
        String permission = text(shop, "View_Permission", "", 128, viewer);
        if (!permission.isEmpty()
                && !permission.matches("[a-z0-9_.\\-*]{1,128}")) {
            return Optional.empty();
        }
        String selection = text(shop, "Selection_Message", dev.chatalyst.lang.LocalizedConfig.string(
                        config.getConfigurationSection("Tebex.Shop"), "Selection_Message", viewer,
                        "<#C0B3D9>You selected {rank}. Use the secure button below."), 1_024, viewer);
        String button = text(shop, "Button_Text", dev.chatalyst.lang.LocalizedConfig.string(
                        config.getConfigurationSection("Tebex.Shop"), "Button_Text", viewer,
                        "<gradient:#B56CFF:#E4A8FF><bold>BUY {rank_id}</bold></gradient>"), 256, viewer);
        String hover = text(shop, "Button_Hover", dev.chatalyst.lang.LocalizedConfig.string(
                        config.getConfigurationSection("Tebex.Shop"), "Button_Hover", viewer,
                        "<#FBF2FF>Open the secure purchase page for {rank_id}"), 256, viewer);
        // Blank category is fine: the menu drops it into the default section rather than
        // hiding it, so a typo never removes something that is on sale.
        String category = text(shop, "Category", "", 64, viewer).toLowerCase(Locale.ROOT);
        return Optional.of(new TebexShopEntry(rankId, group, duration, name,
                description, icon, price, urlTemplate, order, featured, permission,
                selection, button, hover, category));
    }

    @Override
    public String id() {
        return rankId;
    }

    /** A rank IS ownable, so the storefront can say "you already have this". */
    @Override
    public String ownedGroup() {
        return group;
    }

    /** The catalog entry whose key matches {@code id}, whatever case it was written in. */
    static ConfigurationSection section(FileConfiguration config, String catalog, String id) {
        ConfigurationSection root = config.getConfigurationSection(catalog);
        if (root == null) return null;
        String actual = root.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase(id))
                .findFirst().orElse(null);
        return actual == null ? null : root.getConfigurationSection(actual);
    }

    @Override
    public ItemStack iconItem() {
        if (icon.indexOf(':') >= 0) {
            ItemStack custom = dev.chatalyst.hook.ItemsAdderHook.item(icon);
            if (custom != null && !custom.getType().isAir()) return custom;
        }
        Material parsed = Material.matchMaterial(icon);
        return new ItemStack(parsed != null && parsed.isItem() && !parsed.isAir()
                ? parsed : Material.EMERALD);
    }

    public Optional<String> purchaseUrl(String playerName, UUID playerId) {
        return resolvePurchaseUrl(purchaseUrlTemplate, rankId, playerName, playerId);
    }

    public static Optional<String> resolvePurchaseUrl(String template, String rankId,
                                                      String playerName, UUID playerId) {
        if (template == null || template.isBlank() || template.length() > 512) {
            return Optional.empty();
        }
        String resolved = template.trim()
                .replace("{rank_id}", encode(normalizeRank(rankId) == null ? "" : rankId))
                .replace("{player}", encode(playerName == null ? "" : playerName))
                .replace("{uuid}", encode(playerId == null ? "" : playerId.toString()));
        return validHttpsUrl(resolved) ? Optional.of(resolved) : Optional.empty();
    }

    public static boolean validHttpsUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
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
        if (namespaced.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) {
            return namespaced;
        }
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}")
                || normalized.equals("AIR")
                || normalized.equals("CAVE_AIR")
                || normalized.equals("VOID_AIR")) {
            return "EMERALD";
        }
        return normalized;
    }

    private static String normalizeRank(String rankId) {
        if (rankId == null) return null;
        String normalized = rankId.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_\\-]{1,64}") ? normalized : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
