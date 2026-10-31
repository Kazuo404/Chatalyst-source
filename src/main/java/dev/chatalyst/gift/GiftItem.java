package dev.chatalyst.gift;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * La caja tal y como se ve en el inventario.
 *
 * <p>Que caja es va en el PersistentDataContainer: una marca que el
 * jugador no puede escribir es una que no puede falsificar con un yunque. El lore, en cambio,
 * es puro escaparate y se REGENERA cada vez que se fabrica una caja — nunca se lee para
 * decidir nada. Por eso una caja vieja en un cofre sigue valiendo aunque se le cambie el
 * texto, y por eso el texto puede decir la verdad sobre la tabla de premios de HOY.
 *
 * <p>El perfil de la cabeza lleva un UUID derivado del id de la caja y no uno al azar. Dos
 * cabezas con perfiles distintos NO apilan, asi que un UUID aleatorio convertiria diez cajas
 * de oro en diez huecos del inventario.
 */
public final class GiftItem {

    private final JavaPlugin plugin;
    private final NamespacedKey key;

    public GiftItem(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "gift_box");
    }

    /** Que caja es este objeto, o null si no es una caja nuestra. */
    public String idOf(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
    }

    public boolean isGift(ItemStack item) {
        return idOf(item) != null;
    }

    /**
     * Fabrica {@code amount} cajas de este tipo.
     *
     * @param viewer para quien se escribe el texto; el mismo objeto se ve en su idioma.
     */
    public ItemStack create(GiftTier tier, FileConfiguration config, CommandSender viewer,
                            int amount) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD,
                Math.max(1, Math.min(64, amount)));
        String name = tier.displayName(config, viewer);
        List<Component> lore = lore(tier, config, viewer);

        item.editMeta(meta -> {
            meta.displayName(MM.full("<b><" + tier.color() + ">✦ " + name + " ✦</" + tier.color() + "></b>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tier.id());
            if (tier.glow()) meta.setEnchantmentGlintOverride(true);
            if (meta instanceof SkullMeta skull && !tier.texture().isBlank()) {
                skull.setPlayerProfile(profile(tier));
            }
        });
        return item;
    }

    /**
     * El escaparate: que es, como se abre y que puede caer.
     *
     * <p>Los premios se listan de mas probable a menos y con su porcentaje real, calculado
     * contra la suma de pesos de ESTA caja. Un porcentaje escrito a mano en el fichero se
     * queda desactualizado en cuanto alguien anade una linea a la tabla; este no puede.
     */
    private List<Component> lore(GiftTier tier, FileConfiguration config, CommandSender viewer) {
        List<Component> out = new ArrayList<>();
        for (String line : tier.description(config, viewer)) {
            out.add(MM.full(line).decoration(TextDecoration.ITALIC, false));
        }

        boolean showContents = config.getBoolean("Gifts.Show_Contents", true);
        int total = tier.totalWeight();
        if (showContents && total > 0) {
            if (!out.isEmpty()) out.add(Component.empty());
            out.add(MM.full("<" + tier.color() + ">" + Lang.raw(viewer, "gift.lore-contains"))
                    .decoration(TextDecoration.ITALIC, false));

            List<GiftReward> sorted = new ArrayList<>(tier.rewards());
            sorted.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
            int max = Math.max(1, config.getInt("Gifts.Max_Listed", 8));
            int shown = 0;
            for (GiftReward reward : sorted) {
                if (reward.weight() <= 0) continue;
                if (shown >= max) {
                    out.add(MM.full("<dark_gray>  " + Lang.raw(viewer, "gift.lore-and-more",
                            "count", String.valueOf(countRemaining(sorted, max))))
                            .decoration(TextDecoration.ITALIC, false));
                    break;
                }
                String amount = reward.amountText();
                out.add(MM.full("<dark_gray>  ‣ <gray>" + percent(reward.weight(), total)
                        + "%  <white>" + reward.label(viewer)
                        + (amount.isEmpty() ? "" : " <gray>" + amount))
                        .decoration(TextDecoration.ITALIC, false));
                shown++;
            }
        }

        if (!out.isEmpty()) out.add(Component.empty());
        out.add(MM.full("<" + tier.color() + ">" + Lang.raw(viewer, "gift.lore-open"))
                .decoration(TextDecoration.ITALIC, false));
        return out;
    }

    private static int countRemaining(List<GiftReward> sorted, int shown) {
        int remaining = 0;
        for (int i = shown; i < sorted.size(); i++) {
            if (sorted.get(i).weight() > 0) remaining++;
        }
        return remaining;
    }

    /** Un decimal solo cuando hace falta: "12.5%" se lee, "12.0%" estorba. */
    static String percent(int weight, int total) {
        double value = weight * 100d / total;
        return value == Math.rint(value)
                ? String.valueOf((int) value)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private PlayerProfile profile(GiftTier tier) {
        UUID id = UUID.nameUUIDFromBytes(
                ("chatalyst-gift-" + tier.id()).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(id, null);
        profile.setProperty(new ProfileProperty("textures", tier.texture()));
        return profile;
    }

    /** Para que otras piezas (el exportador) marquen un objeto como caja sin rehacerlo. */
    public NamespacedKey key() {
        return key;
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
