package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and presents the YAML-defined, player-facing Tebex rank storefront. */
public final class TebexShopService {

    private final ChatalystPlugin plugin;
    private final TebexEntitlements entitlements;
    private final KitImport kits;

    public TebexShopService(ChatalystPlugin plugin, TebexEntitlements entitlements) {
        this.plugin = plugin;
        this.entitlements = entitlements;
        this.kits = new KitImport(plugin);
    }

    /** Called on reload so an edited kit shows its new contents. */
    public void invalidateKitCache() {
        kits.invalidate();
        loadAcronyms();
    }

    /** Acronimos propios del servidor, para que "ely" no acabe escrito "Ely". */
    public void loadAcronyms() {
        RankNames.learn(plugin.getConfig().getStringList("Tebex.Shop.Acronyms"));
    }

    /**
     * What this player already owns, as LuckPerms group to absolute expiry (0 = permanent).
     *
     * <p>Resolved once per menu build, not once per card: the ledger scan is cheap but doing
     * it twenty-eight times per page for no reason is not.
     */
    public Map<String, Long> ownedGroups(Player player) {
        if (entitlements == null || player == null) return Map.of();
        return TebexEntitlements.desiredGroups(
                entitlements.of(player.getUniqueId()), System.currentTimeMillis());
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("Tebex.Enabled", false)
                && plugin.getConfig().getBoolean("Tebex.Shop.Enabled", false);
    }

    public void open(Player player) {
        if (!allowed(player)) return;
        if (!enabled()) {
            player.sendMessage(Lang.get(player, "tebex-shop.disabled"));
            return;
        }
        Menu.open(player, new TebexShopMenu(plugin, this, 0));
    }

    /** Everything on sale this player may see: ranks and goods in one list. */
    public List<StorefrontEntry> visibleEntries(Player player) {
        List<StorefrontEntry> entries = new ArrayList<>();
        ConfigurationSection catalog =
                plugin.getConfig().getConfigurationSection("Rank_Catalog");
        if (catalog != null) {
            for (String rankId : catalog.getKeys(false)) {
                if (entries.size() >= 512) break;
                TebexShopEntry.resolve(plugin.getConfig(), player, rankId)
                        .filter(entry -> allowedToSee(player, entry))
                        .ifPresent(entries::add);
            }
        }
        for (String productId : StoreShopEntry.listedIds(plugin.getConfig(), player)) {
            if (entries.size() >= 512) break;
            StoreShopEntry.resolve(plugin.getConfig(), player, productId)
                    .filter(entry -> allowedToSee(player, entry))
                    .ifPresent(entries::add);
        }
        entries.sort(Comparator.comparingInt(StorefrontEntry::order)
                .thenComparing(StorefrontEntry::id));
        return List.copyOf(entries);
    }

    /** Entries of one section, using the same visibility rules. */
    public List<StorefrontEntry> visibleEntries(Player player, String categoryId) {
        List<StorefrontCategory> categories = categories(player);
        List<StorefrontEntry> out = new ArrayList<>();
        for (StorefrontEntry entry : visibleEntries(player)) {
            if (StorefrontCategory.resolveFor(entry, categories).equals(categoryId)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Every visible entry grouped by section, resolved in ONE pass.
     *
     * <p>The categories view needs a count per section. Asking for each section separately
     * re-parsed the whole catalog — both catalogs, every URL validated — once per section,
     * on the main thread, every time the menu opened.
     */
    public java.util.Map<String, List<StorefrontEntry>> entriesByCategory(Player player) {
        List<StorefrontCategory> categories = categories(player);
        java.util.Map<String, List<StorefrontEntry>> grouped = new LinkedHashMap<>();
        for (StorefrontEntry entry : visibleEntries(player)) {
            grouped.computeIfAbsent(StorefrontCategory.resolveFor(entry, categories),
                    ignored -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * What this entry gives, for the preview menu.
     *
     * <p>A rank's perks live in LuckPerms and other plugins, so there is nothing honest to
     * derive: the owner writes them in {@code Shop.Preview}. A good's contents ARE known, so
     * its preview is generated from the product and cannot promise what delivery would not
     * hand over — unless the owner overrode it explicitly.
     */
    public List<StorefrontPreviewItem> previewOf(StorefrontEntry entry, Player viewer) {
        // Resolved case-insensitively: the entry itself was found that way, so rebuilding
        // the path from its lowercased id missed the Shop block of any catalog key written
        // with capitals — and the preview came back empty with no explanation.
        var catalog = TebexShopEntry.section(plugin.getConfig(),
                entry instanceof StoreShopEntry ? "Store_Catalog" : "Rank_Catalog",
                entry.id());
        var section = catalog == null ? null : catalog.getConfigurationSection("Shop");
        List<StorefrontPreviewItem> written = StorefrontPreviewItem.read(section);
        if (!written.isEmpty()) return written; // an explicit preview always wins

        // Import the real kit from whichever kits plugin the server already uses, so the
        // owner does not describe the same kit twice and it cannot drift once they edit it.
        String kitName = section == null ? null : section.getString("Preview_Kit");
        if (kitName != null && !kitName.isBlank()) {
            List<StorefrontPreviewItem> imported =
                    StorefrontPreviewItem.fromItems(kits.items(kitName));
            if (!imported.isEmpty()) return imported;
        }
        if (!(entry instanceof StoreShopEntry)) return List.of();

        return dev.chatalyst.store.StoreProduct.resolve(plugin.getConfig(), entry.id())
                .map(product -> StorefrontPreviewItem.fromProduct(product,
                        dev.chatalyst.lang.Lang.raw(viewer, "tebex-shop.preview-money",
                                "amount", dev.chatalyst.hook.VaultHook.format(product.money())),
                        dev.chatalyst.lang.Lang.raw(viewer, "tebex-shop.preview-extra",
                                "count", product.commands().size())))
                .orElse(List.of());
    }

    /**
     * The purchase button of the preview, styled from config rather than from code.
     *
     * <p>The chat button an owner receives was already configurable while this one was fixed
     * in the jar, which is exactly the sort of inconsistency that makes a shop look unfinished
     * and leaves no way to fix it.
     *
     * <p>The price is put on the button itself: the buyer is about to leave the game for a
     * browser, and the last thing they see should be what it costs.
     */
    public org.bukkit.inventory.ItemStack buyButton(StorefrontEntry entry, Player viewer) {
        String icon = plugin.getConfig().getString("Tebex.Shop.Buy_Button.Icon", "EMERALD");
        Material material = Material.matchMaterial(
                icon == null ? "" : icon.trim().toUpperCase(java.util.Locale.ROOT));
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.EMERALD;
        }
        String name = dev.chatalyst.lang.LocalizedConfig.string(
                plugin.getConfig().getConfigurationSection("Tebex.Shop.Buy_Button"),
                "Name", viewer, Lang.raw(viewer, "tebex-shop.preview-buy"));
        List<String> lore = new ArrayList<>();
        if (!entry.price().isBlank()) {
            lore.add(Lang.raw(viewer, "tebex-shop.price", "price", MM.escape(entry.price())));
            lore.add("");
        }
        List<String> configured = dev.chatalyst.lang.LocalizedConfig.list(
                plugin.getConfig().getConfigurationSection("Tebex.Shop.Buy_Button"),
                "Lore", viewer);
        lore.addAll(configured.isEmpty()
                ? Lang.rawList(viewer, "tebex-shop.preview-buy-lore") : configured);

        var button = dev.chatalyst.gui.MenuItems.item(material,
                render(name, entry, viewer),
                lore.stream().map(line -> render(line, entry, viewer)).toList());
        if (plugin.getConfig().getBoolean("Tebex.Shop.Buy_Button.Glow", true)) {
            button.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return button;
    }

    public List<StorefrontCategory> categories(Player viewer) {
        return StorefrontCategory.all(plugin.getConfig(), viewer);
    }

    /** Sections are opt-in; without them the storefront stays the flat list it was. */
    public boolean sectioned() {
        return StorefrontCategory.enabled(plugin.getConfig())
                && !StorefrontCategory.all(plugin.getConfig(), null).isEmpty();
    }

    private static boolean allowedToSee(Player player, StorefrontEntry entry) {
        return entry.viewPermission().isEmpty()
                || player.hasPermission(entry.viewPermission());
    }

    /**
     * Resolves an id back to its entry, whichever catalog owns it, in the viewer's language.
     */
    public java.util.Optional<StorefrontEntry> entry(String id, Player viewer) {
        java.util.Optional<StorefrontEntry> rank =
                TebexShopEntry.resolve(plugin.getConfig(), viewer, id).map(e -> e);
        return rank.isPresent() ? rank
                : StoreShopEntry.resolve(plugin.getConfig(), viewer, id).map(e -> e);
    }

    public void select(Player player, String rankId) {
        if (!allowed(player) || !enabled()) {
            player.sendMessage(Lang.get(player, "tebex-shop.disabled"));
            return;
        }
        Optional<StorefrontEntry> resolved = entry(rankId, player);
        if (resolved.isEmpty()) {
            player.sendMessage(Lang.get(player, "tebex-shop.unavailable"));
            return;
        }
        StorefrontEntry entry = resolved.get();
        if (!entry.viewPermission().isEmpty()
                && !player.hasPermission(entry.viewPermission())) {
            player.sendMessage(Lang.get(player, "tebex-shop.unavailable"));
            return;
        }
        Optional<String> purchaseUrl = entry.purchaseUrl(
                player.getName(), player.getUniqueId());
        if (purchaseUrl.isEmpty()) {
            player.sendMessage(Lang.get(player, "tebex-shop.invalid-link"));
            return;
        }
        if (plugin.getConfig().getBoolean("Tebex.Shop.Close_After_Select", true)) {
            player.closeInventory();
        }
        String selection = render(entry.selectionMessage(), entry, player);
        if (!selection.isBlank()) player.sendMessage(component(selection));

        String url = purchaseUrl.get();
        Component button = component(render(entry.buttonText(), entry, player))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(
                        component(render(entry.buttonHover(), entry, player))));
        player.sendMessage(button);
        if (plugin.getConfig().getBoolean("Tebex.Shop.Show_Raw_Link", false)) {
            player.sendMessage(Component.text(url).clickEvent(ClickEvent.openUrl(url)));
        }
    }

    public void sendHome(Player player) {
        String template = plugin.getConfig().getString("Tebex.Shop.Home_Url", "");
        Optional<String> url = TebexShopEntry.resolvePurchaseUrl(template, "store",
                player.getName(), player.getUniqueId());
        if (url.isEmpty()) {
            player.sendMessage(Lang.get(player, "tebex-shop.invalid-link"));
            return;
        }
        String link = url.get();
        Component button = component(bound(dev.chatalyst.lang.LocalizedConfig.string(plugin.getConfig().getConfigurationSection("Tebex.Shop"), "Home_Button_Text", player,
                        "<gradient:#B56CFF:#E4A8FF><bold>OPEN STORE</bold></gradient>"),
                        256))
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(
                        Lang.get(player, "tebex-shop.home-hover")));
        player.closeInventory();
        player.sendMessage(button);
    }

    public Component title(Player player) {
        String configured = dev.chatalyst.lang.LocalizedConfig.string(plugin.getConfig().getConfigurationSection("Tebex.Shop"), "Title", player,
                "<gradient:#240046:#9D4EDD:#E0AAFF><bold>Rank Store</bold></gradient>");
        return component(renderPlayer(bound(configured, 160), player));
    }

    public Material infoIcon() {
        String configured = plugin.getConfig().getString(
                "Tebex.Shop.Info.Icon", "NETHER_STAR");
        Material material = Material.matchMaterial(configured);
        return material != null && material.isItem() && !material.isAir()
                ? material : Material.NETHER_STAR;
    }

    public String infoName(Player player) {
        return renderPlayer(bound(dev.chatalyst.lang.LocalizedConfig.string(
                plugin.getConfig().getConfigurationSection("Tebex.Shop.Info"), "Name", player,
                "<gradient:#B56CFF:#E4A8FF><bold>Server ranks</bold></gradient>"),
                160), player);
    }

    public List<String> infoLore(Player player) {
        return dev.chatalyst.lang.LocalizedConfig.list(
                plugin.getConfig().getConfigurationSection("Tebex.Shop.Info"),
                "Description", player).stream()
                .limit(12)
                .map(line -> renderPlayer(bound(line, 256), player))
                .toList();
    }

    public boolean hasHomeUrl(Player player) {
        return TebexShopEntry.resolvePurchaseUrl(
                plugin.getConfig().getString("Tebex.Shop.Home_Url", ""),
                "store", player.getName(), player.getUniqueId()).isPresent();
    }

    public String render(String template, StorefrontEntry entry, Player player) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("{player}", MM.escape(player.getName()));
        // El id es para maquinas ("mvplusplus"); lo que se enseña es un nombre ("MVP++"), y
        // para eso se pregunta primero al servidor (LuckPerms) antes de adivinar nada.
        // La URL de compra sigue usando el id crudo — eso identifica el paquete, no al ojo.
        base.put("{rank_id}", MM.escape(RankNames.of(entry.ownedGroup(), entry.id())));
        base.put("{rank_key}", MM.escape(entry.id()));
        base.put("{group}", MM.escape(entry.ownedGroup()));
        base.put("{duration}", MM.escape(entry.duration()));
        base.put("{price}", MM.sanitizeInteraction(entry.price()));

        Map<String, String> full = new LinkedHashMap<>(base);
        full.put("{rank}", MM.sanitizeInteraction(
                substitute(entry.displayName(), base)));
        return dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(
                substitute(template, full));
    }

    private String renderPlayer(String template, Player player) {
        String name = player == null ? "" : MM.escape(player.getName());
        return dev.chatalyst.hook.ItemsAdderHook.replaceEmojisUnchecked(
                substitute(template, Map.of("{player}", name)));
    }

    /**
     * Resolves every token in ONE pass, so an inserted value is never rescanned.
     *
     * <p>Chained {@code replace} calls would expand a token that happened to appear inside a
     * value substituted earlier. Nothing reachable today can exploit that — names carry no
     * braces and rank IDs are regex-validated — but {@code Lang.applyReplacements} and
     * {@code DiscordBot.renderDiscordFormat} were both rewritten this way on purpose, and
     * leaving one sequential exception is how the next player-controlled placeholder becomes
     * an injection.
     */
    static String substitute(String template, Map<String, String> values) {
        if (template == null || template.isEmpty() || template.indexOf('{') < 0) {
            return template == null ? "" : template;
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int from = 0;
        while (true) {
            int open = template.indexOf('{', from);
            if (open < 0) break;
            int close = template.indexOf('}', open + 1);
            if (close < 0) break;
            String replacement = values.get(template.substring(open, close + 1));
            if (replacement != null) {
                out.append(template, from, open).append(replacement);
                from = close + 1;
            } else {
                // Not one of ours (a stray brace, another plugin's token): copied verbatim.
                out.append(template, from, open + 1);
                from = open + 1;
            }
        }
        return out.append(template, from, template.length()).toString();
    }

    private static Component component(String miniMessage) {
        try {
            return MM.full(MM.sanitizeInteraction(miniMessage));
        } catch (RuntimeException invalid) {
            return Component.text(MM.strip(miniMessage));
        }
    }

    private static String bound(String value, int max) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static boolean allowed(Player player) {
        if (player.hasPermission("chatalyst.command.ranks")) return true;
        player.sendMessage(Lang.get(player, "common.no-permission"));
        return false;
    }
}
