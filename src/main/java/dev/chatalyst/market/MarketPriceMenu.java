package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Ajustes de UN item del cofre, enteros dentro de la GUI: su PRECIO y su DESCRIPCION propia.
 * Aqui se pone TODO lo de cada item por separado, para que la GUI principal quede limpia con
 * solo lo del lote (venta/subasta, duracion, publicar).
 *
 * <p>El precio se ajusta a botonazos (+-1/10/100/1000) alrededor del item, sin depender de
 * ninguna tecla especial; el prompt de chat sigue disponible como atajo para cifras grandes.
 * La descripcion es opcional: vacia = se publica el item tal cual (su propio tooltip).
 *
 * <p>Layout simetrico: item al centro (13) flanqueado por los pasos de precio (menos a la
 * izquierda, mas a la derecha); fila de acciones centrada — regalar (20), descripcion (22),
 * precio exacto (24) — y volver en la esquina (18).
 */
final class MarketPriceMenu implements Menu {

    // Fila de ajuste: los decrementos a la izquierda del item y los incrementos a la derecha,
    // en el mismo orden de magnitud hacia fuera (…-100 -10 -1 [item] +1 +10 +100…).
    private static final int[] MINUS_SLOTS = {9, 10, 11, 12};
    private static final int[] PLUS_SLOTS = {14, 15, 16, 17};
    private static final double[] MINUS_STEPS = {-1000, -100, -10, -1};
    private static final double[] PLUS_STEPS = {1, 10, 100, 1000};
    private static final int SLOT_ITEM = 13;
    // Fila de acciones, simetrica alrededor del centro (22): regalar | descripcion | exacto.
    private static final int SLOT_FREE = 20;
    private static final int SLOT_DESC = 22;
    private static final int SLOT_CHAT = 24;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;   // espejo de SLOT_BACK

    private final MarketManager market;
    private final MarketPublishManager drafts;
    private final int index; // posicion del item dentro del cofre del borrador

    MarketPriceMenu(MarketManager market, MarketPublishManager drafts, int index) {
        this.market = market;
        this.drafts = drafts;
        this.index = index;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("market.price-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "market.price-title"));
    }

    @Override
    public int size() {
        return 27;
    }

    /** El item que se esta tasando, o null si el borrador cambio por debajo. */
    private MarketPublishManager.PricedItem entry(Player viewer) {
        var draft = drafts.draft(viewer.getUniqueId());
        return index >= 0 && index < draft.items.size() ? draft.items.get(index) : null;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        var entry = entry(viewer);
        if (entry == null) return; // sin item que tasar: menu vacio, el boton volver no existe
        for (int i = 0; i < MINUS_SLOTS.length; i++) {
            inv.setItem(MINUS_SLOTS[i], MenuItems.item(Material.CRYING_OBSIDIAN,
                    Lang.raw(viewer, "market.price-minus",
                            "amount", MarketManager.money(-MINUS_STEPS[i])),
                    List.of()));
        }
        for (int i = 0; i < PLUS_SLOTS.length; i++) {
            inv.setItem(PLUS_SLOTS[i], MenuItems.item(Material.AMETHYST_SHARD,
                    Lang.raw(viewer, "market.price-plus",
                            "amount", MarketManager.money(PLUS_STEPS[i])),
                    List.of()));
        }

        // El item real en el centro, con el precio vigente y su descripcion pegados al tooltip.
        var shown = entry.item().clone();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(Lang.raw(viewer, "market.price-current",
                "price", MarketManager.money(entry.price())));
        lore.add(entry.description().isEmpty()
                ? Lang.raw(viewer, "market.price-desc-none")
                : Lang.raw(viewer, "market.price-desc-current",
                        "text", MM.escape(entry.description())));
        MenuItems.appendLore(shown, lore);
        inv.setItem(SLOT_ITEM, shown);

        inv.setItem(SLOT_FREE, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "market.price-free"),
                List.of(Lang.raw(viewer, "market.price-free-hint"))));
        inv.setItem(SLOT_DESC, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "market.price-desc"),
                List.of(entry.description().isEmpty()
                                ? Lang.raw(viewer, "market.price-desc-none")
                                : Lang.raw(viewer, "market.price-desc-value",
                                        "text", MM.escape(entry.description())),
                        Lang.raw(viewer, "market.price-desc-hint"))));
        inv.setItem(SLOT_CHAT, MenuItems.item(Material.OAK_SIGN,
                Lang.raw(viewer, "market.price-chat"),
                List.of(Lang.raw(viewer, "market.price-chat-hint"))));
        inv.setItem(SLOT_BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
        inv.setItem(SLOT_CLOSE, dev.chatalyst.gui.MenuNavigation.close(viewer));
    }

    /** Repinta en sitio: reabrir disparia el cierre del menu y no hace falta. */
    private void rebuild(Player player) {
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof MenuHolder holder && holder.getMenu() == this) {
            top.clear();
            build(top, player);
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        var draft = drafts.draft(player.getUniqueId());
        if (!new MarketPublishMenu(market, drafts).ensureActive(player, draft)) return;
        var entry = entry(player);
        if (entry == null) {
            backToPublish(player);
            return;
        }
        for (int i = 0; i < MINUS_SLOTS.length; i++) {
            if (slot == MINUS_SLOTS[i]) {
                adjust(player, entry, MINUS_STEPS[i]);
                return;
            }
        }
        for (int i = 0; i < PLUS_SLOTS.length; i++) {
            if (slot == PLUS_SLOTS[i]) {
                adjust(player, entry, PLUS_STEPS[i]);
                return;
            }
        }
        switch (slot) {
            case SLOT_FREE -> adjust(player, entry, Double.NEGATIVE_INFINITY); // al suelo: 0
            case SLOT_CHAT -> beginChat(player, MarketPublishManager.Composing.PRICE,
                    "market.publish-price-prompt");
            case SLOT_DESC -> beginChat(player, MarketPublishManager.Composing.DESCRIPTION,
                    "market.publish-desc-prompt");
            case SLOT_BACK -> backToPublish(player);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    /** Arranca un prompt de chat (precio o descripcion) para ESTE item y cierra el menu. */
    private void beginChat(Player player, MarketPublishManager.Composing what, String promptKey) {
        var draft = drafts.draft(player.getUniqueId());
        draft.composing = what;
        draft.editingItem = index;
        drafts.startComposing(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(Lang.get(player, promptKey));
    }

    /** Aplica un salto de precio y repinta. Nunca baja de 0 ni desborda a infinito. */
    private void adjust(Player player, MarketPublishManager.PricedItem entry, double step) {
        double next = step == Double.NEGATIVE_INFINITY ? 0 : entry.price() + step;
        double safe = Double.isFinite(next) ? Math.max(0, next) : entry.price();
        if (drafts.updateItemDurably(player.getUniqueId(), index, safe, entry.description())) {
            rebuild(player);
        } else {
            player.sendMessage(Lang.get(player, "market.storage-error"));
        }
    }

    /**
     * Vuelve al cofre de publicacion. No toca {@code keepItems}: el cierre que dispara este
     * cambio de menu lo recibe ESTE menu, y el gancho de cierre solo actua sobre el de
     * publicacion. Marcarlo aqui dejaria la bandera puesta y el siguiente ESC de verdad no
     * devolveria el cofre al inventario.
     */
    private void backToPublish(Player player) {
        Menu.open(player, new MarketPublishMenu(market, drafts));
    }
}
