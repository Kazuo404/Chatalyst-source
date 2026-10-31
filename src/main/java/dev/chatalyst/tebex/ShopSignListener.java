package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.List;
import java.util.Locale;

/**
 * A physical shop entrance: a sign you place at spawn and players click.
 *
 * <p>A command is a poor front door for a store — it has to be remembered, advertised and
 * spelled right, and every good label is already owned by some other plugin. A sign is seen,
 * not memorised: it stands where players already are and says what it is.
 *
 * <p>Creating one needs a permission, and the sign is stamped with persistent data when it is
 * created. Clicks are only honoured on a stamped sign, so a player cannot mimic the entrance
 * by typing the same text on their own sign.
 */
public final class ShopSignListener implements Listener {

    private final ChatalystPlugin plugin;
    private final TebexShopService shop;
    private final NamespacedKey marker;

    public ShopSignListener(ChatalystPlugin plugin, TebexShopService shop) {
        this.plugin = plugin;
        this.shop = shop;
        this.marker = new NamespacedKey(plugin, "shop_sign");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("Tebex.Shop.Sign.Enabled", true);
    }

    private String tag() {
        String configured = plugin.getConfig().getString("Tebex.Shop.Sign.Tag", "[ranks]");
        return configured == null || configured.isBlank()
                ? "[ranks]" : configured.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Turns a freshly written tag into the styled entrance and stamps it as ours.
     *
     * <p>HIGHEST, not MONITOR: this REWRITES the lines, and MONITOR is contractually
     * observe-only — anything watching there would have been shown text that is about to
     * change. FancySignListener also runs at HIGHEST but leaves the tag alone, because it
     * only reformats lines that carry one of its style tags.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreate(SignChangeEvent event) {
        if (!enabled()) return;
        String first = MM.strip(net.kyori.adventure.text.serializer.plain
                .PlainTextComponentSerializer.plainText().serialize(event.line(0) == null
                        ? net.kyori.adventure.text.Component.empty() : event.line(0)))
                .trim().toLowerCase(Locale.ROOT);
        if (!first.equals(tag())) return;

        if (!event.getPlayer().hasPermission("chatalyst.sign.shop")) {
            // Left exactly as typed: no styling, no stamp, so it is an ordinary sign.
            event.getPlayer().sendMessage(Lang.get(event.getPlayer(), "common.no-permission"));
            return;
        }
        List<String> lines = Lang.rawList(event.getPlayer(), "tebex-shop.sign-lines");
        for (int i = 0; i < 4; i++) {
            event.line(i, i < lines.size() ? MM.full(lines.get(i))
                    : net.kyori.adventure.text.Component.empty());
        }
        if (event.getBlock().getState() instanceof Sign sign) {
            sign.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            sign.update();
        }
        event.getPlayer().sendMessage(Lang.get(event.getPlayer(), "tebex-shop.sign-created"));
    }

    /**
     * Opens the storefront when a stamped sign is clicked.
     *
     * <p>Escucha tambien los clics ya cancelados: el cartel va en el spawn, y ahi WorldGuard
     * le niega la interaccion con el bloque a cualquier jugador sin permisos de la region.
     * Con ignoreCancelled el cartel no abria nada justo para quien tenia que abrirlo. No hay
     * riesgo en saltarse esa negativa: solo cuenta un cartel sellado por este plugin y lo
     * unico que hace es abrir un menu de solo lectura.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(PlayerInteractEvent event) {
        if (!enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) return;
        // Only a sign this plugin stamped counts, so copying the text is not enough.
        if (!sign.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        shop.open(event.getPlayer());
    }
}
