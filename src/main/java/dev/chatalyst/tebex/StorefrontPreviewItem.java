package dev.chatalyst.tebex;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One line of a preview: an icon, a name and a couple of lines of detail.
 *
 * <p>Deliberately a presentation object with no link to delivery. A rank's perks are
 * permissions and meta spread across LuckPerms and other plugins, and there is no honest way
 * to derive "what you get" from them — so the owner writes it, and what is shown is exactly
 * what they promised. For goods the preview can be generated from the product itself, because
 * there the contents ARE known.
 */
public record StorefrontPreviewItem(ItemStack icon, String name, List<String> lore) {

    private static final int MAX_LORE = 8;
    /** Sanity bound only; the preview paginates, so nothing is hidden by it. */
    private static final int MAX_ITEMS = 256;

    /**
     * The item as the menu should show it.
     *
     * <p>A null {@code name} means "keep what the item already says". Imported kit items
     * arrive with their own display name, lore, colours and trims already set by the plugin
     * that owns them; overwriting those would be the opposite of importing them.
     */
    public org.bukkit.inventory.ItemStack render() {
        if (name == null) {
            if (lore.isEmpty()) return icon.clone();
            org.bukkit.inventory.ItemStack shown = icon.clone();
            shown.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lines =
                        new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
                for (String line : lore) {
                    lines.add(dev.chatalyst.util.MM.full(line).decoration(
                            net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                meta.lore(lines);
            });
            return shown;
        }
        return dev.chatalyst.gui.MenuItems.item(icon, name, lore);
    }

    public StorefrontPreviewItem {
        lore = List.copyOf(lore);
    }

    /** Reads a {@code Shop.Preview} list; each entry is Icon + Name + Lore. */
    public static List<StorefrontPreviewItem> read(ConfigurationSection shop) {
        if (shop == null) return List.of();
        List<StorefrontPreviewItem> out = new ArrayList<>();
        for (var raw : shop.getMapList("Preview")) {
            if (out.size() >= MAX_ITEMS) break;
            Object material = raw.get("Icon");
            Material parsed = material == null ? null : Material.matchMaterial(
                    String.valueOf(material).toUpperCase(Locale.ROOT));
            if (parsed == null || !parsed.isItem() || parsed.isAir()) parsed = Material.PAPER;
            List<String> lore = new ArrayList<>();
            if (raw.get("Lore") instanceof List<?> configured) {
                for (Object line : configured) {
                    if (lore.size() >= MAX_LORE) break;
                    lore.add(bounded(String.valueOf(line), 256));
                }
            }
            String name = raw.get("Name") == null
                    ? prettify(parsed) : bounded(String.valueOf(raw.get("Name")), 160);
            out.add(new StorefrontPreviewItem(new ItemStack(parsed), name, lore));
        }
        return List.copyOf(out);
    }

    /**
     * Preview built from what a good actually delivers, so it cannot promise something the
     * delivery does not contain. Ranks have no equivalent: their perks live in other plugins.
     */
    public static List<StorefrontPreviewItem> fromProduct(
            dev.chatalyst.store.StoreProduct product, String moneyLine, String commandsLine) {
        List<StorefrontPreviewItem> out = new ArrayList<>();
        for (var spec : product.items()) {
            if (out.size() >= MAX_ITEMS) break;
            // The real ItemStack is the icon, so enchantments, model data and glint show
            // exactly as the buyer will receive them. Its own lore is carried over too:
            // replacing it with just the amount threw away what the item says about itself.
            List<String> lore = new ArrayList<>(spec.lore());
            if (spec.amount() > 1) {
                if (!lore.isEmpty()) lore.add("");
                lore.add("<#7B68A0>x" + spec.amount());
            }
            out.add(new StorefrontPreviewItem(spec.build(),
                    spec.name().isBlank() ? prettify(spec.material()) : spec.name(),
                    List.copyOf(lore)));
        }
        if (product.money() > 0d && out.size() < MAX_ITEMS) {
            out.add(new StorefrontPreviewItem(new ItemStack(Material.GOLD_INGOT),
                    moneyLine, List.of()));
        }
        if (!product.commands().isEmpty() && out.size() < MAX_ITEMS) {
            // The commands themselves are never shown: they are server internals, and one
            // could reveal a plugin or a permission node a player has no business seeing.
            out.add(new StorefrontPreviewItem(new ItemStack(Material.NETHER_STAR),
                    commandsLine, List.of()));
        }
        return List.copyOf(out);
    }

    /**
     * Preview from ItemStacks imported from another plugin. Their own display name and lore
     * are already on the item, so nothing is written over them: the buyer sees the kit
     * exactly as its owner built it, colours, trims and all.
     */
    public static List<StorefrontPreviewItem> fromItems(List<ItemStack> items) {
        List<StorefrontPreviewItem> out = new ArrayList<>();
        for (ItemStack item : items) {
            if (out.size() >= MAX_ITEMS) break;
            if (item == null || item.getType().isAir()) continue;
            out.add(new StorefrontPreviewItem(item.clone(), null,
                    item.getAmount() > 1 ? List.of("<#7B68A0>x" + item.getAmount()) : List.of()));
        }
        return List.copyOf(out);
    }

    private static String prettify(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return "<#E0AAFF>" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
