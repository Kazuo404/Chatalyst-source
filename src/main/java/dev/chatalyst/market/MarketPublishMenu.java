package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI de publicacion tipo COFRE. Las 3 filas de arriba son el cofre: metes items clicandolos
 * en tu inventario (seguro contra duplicacion — no se arrastran), y cada item muestra su
 * tooltip real y su precio. Clic en un item abre sus AJUSTES por-item ({@link MarketPriceMenu}:
 * precio y descripcion propia); shift-clic lo saca. Abajo, SOLO lo del lote: venta/subasta,
 * duracion, publicacion programada, vista previa y publicar (un anuncio por item). El precio y
 * la descripcion NO estan aqui a proposito — son de cada item, para que esta GUI quede limpia.
 */
final class MarketPublishMenu implements Menu {

    private static final int CHEST_SLOTS = MarketPublishManager.MAX_ITEMS; // 0..26
    // Fila del lote (4), centrada y simetrica alrededor de la columna central (40):
    // tipo (38) · duracion (40) · retraso (42). El precio y la descripcion NO viven aqui:
    // son de cada item y se editan clicando el item (MarketPriceMenu), asi esta GUI queda limpia.
    private static final int SLOT_TYPE = 38;
    private static final int SLOT_DURATION = 40;
    private static final int SLOT_DELAY = 42;
    // Fila de acciones (5), simetrica alrededor de 49: info(45) preview(48) publish(50) cancel(53).
    private static final int SLOT_INFO = 45;
    private static final int SLOT_PREVIEW = 48;
    private static final int SLOT_PUBLISH = 50;
    private static final int SLOT_CANCEL = 53;

    private final MarketManager market;
    private final MarketPublishManager drafts;

    MarketPublishMenu(MarketManager market, MarketPublishManager drafts) {
        this.market = market;
        this.drafts = drafts;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("market.publish-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "market.publish-title"));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        var draft = drafts.draft(viewer.getUniqueId());
        boolean economy = VaultHook.hasEconomy();

        // Cofre: los items metidos, cada uno con su precio en el lore.
        List<MarketPublishManager.PricedItem> items = draft.items;
        for (int i = 0; i < CHEST_SLOTS; i++) {
            if (i < items.size()) {
                ItemStack shown = items.get(i).item().clone();
                List<String> extra = new ArrayList<>();
                extra.add("");
                if (economy) {
                    // La llamada a la accion VA PRIMERO y resaltada: en un item con tooltip
                    // largo, el "clic para poner precio" quedaba enterrado al final y el
                    // vendedor no lo veia — que era justo el "no sale para poner precio".
                    extra.addAll(Lang.rawList(viewer, "market.publish-item-controls"));
                    extra.add(Lang.raw(viewer, "market.publish-item-price", "price",
                            MarketManager.money(items.get(i).price())));
                } else {
                    extra.add(Lang.raw(viewer, "market.publish-item-free"));
                }
                extra.add(Lang.raw(viewer, "market.publish-item-remove"));
                MenuItems.appendLore(shown, extra);
                inv.setItem(i, shown);
            } else if (i >= items.size() && i < firstRowsForHint(items.size())) {
                // Un solo hueco guia, para no llenar de cristal las 3 filas.
                inv.setItem(i, MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE,
                        Lang.raw(viewer, "market.publish-slot-empty"),
                        List.of(Lang.raw(viewer, "market.publish-slot-empty-lore"))));
            }
        }

        for (int slot = 27; slot <= 35; slot++) {
            inv.setItem(slot, MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of()));
        }

        inv.setItem(SLOT_TYPE, MenuItems.item(
                draft.auction ? Material.AMETHYST_CLUSTER : Material.ENDER_EYE,
                Lang.raw(viewer, draft.auction ? "market.publish-type-auction" : "market.publish-type-sale"),
                List.of(Lang.raw(viewer, "market.publish-type-hint"))));
        inv.setItem(SLOT_DURATION, MenuItems.item(Material.CLOCK,
                Lang.raw(viewer, "market.publish-duration", "time", durationLabel(viewer, draft)),
                List.of(Lang.raw(viewer, "market.publish-duration-hint"))));
        inv.setItem(SLOT_DELAY, MenuItems.item(Material.COMPASS,
                Lang.raw(viewer, "market.publish-delay", "time", delayLabel(viewer, draft)),
                List.of(Lang.raw(viewer, "market.publish-delay-hint"))));

        inv.setItem(SLOT_INFO, MenuItems.item(Material.PAPER,
                Lang.raw(viewer, "market.publish-count", "count", items.size()),
                Lang.rawList(viewer, "market.publish-info")));
        inv.setItem(SLOT_PREVIEW, dev.chatalyst.gui.MenuNavigation.preview(viewer));
        inv.setItem(SLOT_PUBLISH, MenuItems.item(Material.BEACON,
                Lang.raw(viewer, "market.publish-confirm"),
                List.of(Lang.raw(viewer, "market.publish-confirm-hint"))));
        inv.setItem(SLOT_CANCEL, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, "market.publish-cancel"),
                List.of(Lang.raw(viewer, "market.publish-cancel-hint"))));
    }

    // Muestra el hueco-guia solo hasta completar la primera fila que aun tiene sitio.
    private static int firstRowsForHint(int used) {
        return Math.min(CHEST_SLOTS, ((used / 9) + 1) * 9);
    }

    /**
     * Reconstruye el inventario ABIERTO en sitio (no reabre): reabrir con Menu.open dispararia
     * InventoryCloseEvent y el gancho de cierre devolveria el cofre. Aqui solo se repinta.
     */
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
        if (!ensureActive(player, draft)) return;
        if (slot < CHEST_SLOTS) {
            if (slot >= draft.items.size()) return; // hueco vacio del cofre
            var entry = draft.items.get(slot);
            if (click.isShiftClick()) { // sacar el item del cofre (vuelve a tu inventario)
                if (!market.stashFor(player.getUniqueId(), List.of(entry.item()))
                        || drafts.removeDurably(player.getUniqueId(), slot) == null) {
                    player.sendMessage(Lang.get(player, "market.storage-error"));
                    return;
                }
                market.recoverPlayerEscrow(player);
                rebuild(player);
                return;
            }
            if (!VaultHook.hasEconomy()) return;
            // CUALQUIER click no-shift abre el editor de precio. Antes cada tipo de click
            // hacia una cosa distinta (+10 / -10 / prompt de chat) y el unico camino al
            // precio exacto era una tecla que en supervivencia ni siquiera se envia; el
            // resultado practico era que casi nadie llegaba a cambiar el precio.
            // El orden importa: Menu.open dispara el InventoryCloseEvent de este menu de forma
            // SINCRONA, asi que la bandera tiene que estar puesta antes o el gancho de cierre
            // devolveria el cofre entero al inventario al abrir el editor.
            draft.keepItems = true;
            Menu.open(player, new MarketPriceMenu(market, drafts, slot));
            return;
        }
        switch (slot) {
            case SLOT_TYPE -> {
                if (persistSettings(player, draft, !draft.auction,
                        draft.durationMinutes, draft.publishDelayMinutes)) rebuild(player);
            }
            case SLOT_DURATION -> {
                long next = cycle(MarketPublishManager.DURATION_MINUTES,
                        draft.durationMinutes, click.isRightClick());
                if (persistSettings(player, draft, draft.auction,
                        next, draft.publishDelayMinutes)) rebuild(player);
            }
            case SLOT_DELAY -> {
                long next = cycle(MarketPublishManager.DELAY_MINUTES,
                        draft.publishDelayMinutes, click.isRightClick());
                if (persistSettings(player, draft, draft.auction,
                        draft.durationMinutes, next)) rebuild(player);
            }
            case SLOT_PREVIEW -> preview(player, draft);
            case SLOT_PUBLISH -> {
                if (draft.items.isEmpty()) {
                    player.sendMessage(Lang.get(player, "market.publish-empty"));
                    return;
                }
                // El journal del borrador se conserva hasta que cada item haya alcanzado
                // durablemente un listing o el claim ledger. Los markers deduplican un
                // reinicio entre ambos pasos.
                var copy = new ArrayList<>(draft.items);
                var type = draft.auction ? MarketListing.Type.AUCTION : MarketListing.Type.SALE;
                long dur = draft.durationMinutes;
                long delay = draft.publishDelayMinutes;
                draft.keepItems = true;
                player.closeInventory();
                // La descripcion viaja DENTRO de cada PricedItem (por item), ya no como un
                // texto unico del lote.
                market.publishBatch(player, copy, type, dur, delay);
                if (!drafts.forgetDurably(player.getUniqueId())) {
                    player.sendMessage(Lang.get(player, "market.storage-error"));
                }
            }
            case SLOT_CANCEL -> {
                if (!returnAll(player, draft)) return;
                if (!drafts.forgetDurably(player.getUniqueId())) {
                    player.sendMessage(Lang.get(player, "market.storage-error"));
                    return;
                }
                market.recoverPlayerEscrow(player);
                player.closeInventory();        // ya sin items: el gancho de cierre no hace nada
                player.sendMessage(Lang.get(player, "market.publish-cancelled"));
            }
            default -> {
            }
        }
    }

    @Override
    public void onClickBottom(Player player, int slot, ClickType click) {
        var draft = drafts.draft(player.getUniqueId());
        if (!ensureActive(player, draft)) return;
        if (slot < 0 || slot >= 36) return; // solo hotbar + inventario principal (nunca armadura/borde)
        // Clic en un item de tu inventario: se mete al cofre (con un precio inicial).
        if (draft.items.size() >= MarketPublishManager.MAX_ITEMS) {
            player.sendMessage(Lang.get(player, "market.publish-chest-full"));
            return;
        }
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || item.getType().isAir()) return;
        ItemStack original = item.clone();
        ItemStack marked = market.markEscrowItem(original);
        String marker = market.escrowMarker(marked);
        player.getInventory().setItem(slot, marked.clone());
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            player.getInventory().setItem(slot, original);
            player.sendMessage(Lang.get(player, "market.inventory-save-error"));
            return;
        }
        if (!drafts.addDurably(player.getUniqueId(),
                new MarketPublishManager.PricedItem(marked, 100))) {
            player.getInventory().setItem(slot, original);
            player.saveData();
            player.sendMessage(Lang.get(player, "market.storage-error"));
            return;
        }
        if (marker != null && marker.equals(market.escrowMarker(
                player.getInventory().getItem(slot)))) {
            player.getInventory().setItem(slot, null);
        }
        player.saveData();
        rebuild(player);
    }

    private void preview(Player player, MarketPublishManager.Draft draft) {
        if (draft.items.isEmpty()) {
            player.sendMessage(Lang.get(player, "market.publish-empty"));
            return;
        }
        draft.keepItems = true; // el cierre para la vista previa NO debe devolver el cofre
        player.closeInventory();
        player.sendMessage(Lang.get(player, "market.publish-preview-header"));
        for (var entry : draft.items) {
            player.sendMessage(market.previewAnnounce(player, entry.item(),
                    draft.auction, entry.price(), draft.durationMinutes));
            // Cada item muestra su PROPIA descripcion justo debajo de su anuncio.
            if (!entry.description().isEmpty()) {
                player.sendMessage(Lang.get(player, "market.publish-preview-desc",
                        "text", MM.escape(entry.description())));
            }
        }
    }

    /** Devuelve al jugador todos los items del cofre (cancelar / cerrar sin publicar). */
    boolean returnAll(Player player, MarketPublishManager.Draft draft) {
        for (var entry : draft.items) {
            if (!market.stashFor(player.getUniqueId(), List.of(entry.item()))) return false;
        }
        return true;
    }

    /** Reautoriza una GUI ya abierta y devuelve su escrow si el rango/feature desaparecio. */
    boolean ensureActive(Player player, MarketPublishManager.Draft draft) {
        if (market.checkActive(player)) return true;
        if (!returnAll(player, draft)) {
            player.sendMessage(Lang.get(player, "market.storage-error"));
            return false;
        }
        if (!drafts.forgetDurably(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "market.storage-error"));
            return false;
        }
        market.recoverPlayerEscrow(player);
        player.closeInventory();
        return false;
    }

    private static long cycle(long[] options, long current, boolean back) {
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                idx = i;
                break;
            }
        }
        return options[Math.floorMod(idx + (back ? -1 : 1), options.length)];
    }

    private boolean persistSettings(Player player, MarketPublishManager.Draft draft,
                                    boolean auction, long duration, long delay) {
        if (drafts.updateSettingsDurably(player.getUniqueId(), auction, duration, delay)) {
            return true;
        }
        player.sendMessage(Lang.get(player, "market.storage-error"));
        return false;
    }

    private String durationLabel(Player viewer, MarketPublishManager.Draft draft) {
        long m = draft.durationMinutes > 0 ? draft.durationMinutes : 0;
        return m == 0 ? Lang.raw(viewer, "market.time-default") : humanTime(viewer, m);
    }

    private String delayLabel(Player viewer, MarketPublishManager.Draft draft) {
        return draft.publishDelayMinutes <= 0
                ? Lang.raw(viewer, "market.publish-delay-now")
                : humanTime(viewer, draft.publishDelayMinutes);
    }

    private static String humanTime(Player viewer, long minutes) {
        if (minutes < 60) return Lang.raw(viewer, "market.time-minutes", "value", minutes);
        if (minutes < 1440) return Lang.raw(viewer, "market.time-hours", "value", minutes / 60);
        return Lang.raw(viewer, "market.time-days", "value", minutes / 1440);
    }
}
