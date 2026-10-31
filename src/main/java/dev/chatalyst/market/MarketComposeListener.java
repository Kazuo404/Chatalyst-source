package dev.chatalyst.market;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.gui.MenuHolder;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Captura por chat lo que el jugador escribe desde la GUI de cofre: el PRECIO de un item (clic
 * tecla de soltar) o la DESCRIPCION (boton de texto). El texto no llega al chat publico; al terminar se
 * reabre la GUI. Ademas, si el jugador se desconecta con items en el cofre (deposito), estos
 * van a su alijo (/market claim) para no perderse.
 */
public final class MarketComposeListener implements Listener {

    private final JavaPlugin plugin;
    private final MarketManager market;
    private final MarketPublishManager drafts;

    public MarketComposeListener(JavaPlugin plugin, MarketManager market, MarketPublishManager drafts) {
        this.plugin = plugin;
        this.market = market;
        this.drafts = drafts;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!drafts.isComposing(player.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        drafts.endComposing(player.getUniqueId());
        FoliaTasks.entity(plugin, player, p -> {
            var draft = drafts.draft(p.getUniqueId());
            MarketPublishMenu publishMenu = new MarketPublishMenu(market, drafts);
            if (!publishMenu.ensureActive(p, draft)) return;
            boolean cancel = Lang.rawList(p, "market.compose-cancel-words").stream()
                    .anyMatch(word -> word.equalsIgnoreCase(text));
            int index = draft.editingItem;
            if (!cancel && index >= 0 && index < draft.items.size()) {
                var entry = draft.items.get(index);
                if (draft.composing == MarketPublishManager.Composing.DESCRIPTION) {
                    // Sin invisibles; se escapa al mostrar (MM.escape en la GUI/anuncio).
                    String description = dev.chatalyst.util.Sanitize.limit(
                            dev.chatalyst.util.Sanitize.stripInvisible(text),
                            MarketManager.MAX_DESCRIPTION_LENGTH);
                    if (!drafts.updateItemDurably(p.getUniqueId(), index,
                            entry.price(), description)) {
                        p.sendMessage(Lang.get(p, "market.storage-error"));
                    }
                } else { // PRICE
                    applyPrice(p, index, entry, text);
                }
            }
            draft.composing = null;
            draft.editingItem = -1;
            // Se vuelve al menu del ITEM que se estaba editando (no a la GUI principal): asi
            // el vendedor sigue afinando ese item. Si el item ya no existe, a la GUI principal.
            if (index >= 0 && index < draft.items.size()) {
                Menu.open(p, new MarketPriceMenu(market, drafts, index));
            } else {
                Menu.open(p, new MarketPublishMenu(market, drafts));
            }
        });
    }

    private void applyPrice(Player p, int index, MarketPublishManager.PricedItem entry,
                            String text) {
        try {
            double value = Double.parseDouble(text.replace(",", "."));
            if (value < 0 || !Double.isFinite(value)) {
                p.sendMessage(Lang.get(p, "market.invalid-price"));
            } else if (!drafts.updateItemDurably(p.getUniqueId(), index,
                    value, entry.description())) {
                p.sendMessage(Lang.get(p, "market.storage-error"));
            }
        } catch (NumberFormatException ex) {
            p.sendMessage(Lang.get(p, "market.invalid-price"));
        }
    }

    /**
     * Al cerrar la GUI de cofre (ESC), los items del cofre vuelven al inventario — no se dejan
     * en deposito. Excepto si el jugador esta escribiendo por chat (precio/descripcion) o es la
     * vista previa: en esos casos la GUI se reabre y el cofre debe conservarse.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        if (!(holder.getMenu() instanceof MarketPublishMenu)) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        MarketPublishManager.Draft draft = drafts.peek(p.getUniqueId());
        if (draft == null) return;
        if (drafts.isComposing(p.getUniqueId()) || draft.keepItems) {
            draft.keepItems = false;
            return;
        }
        if (draft.items.isEmpty()) return;
        for (var e : draft.items) {
            if (!market.stashFor(p.getUniqueId(), List.of(e.item()))) return;
        }
        if (drafts.forgetDurably(p.getUniqueId())) {
            market.recoverPlayerEscrow(p);
        } else {
            p.sendMessage(Lang.get(p, "market.storage-error"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        MarketPublishManager.Draft draft = drafts.peek(id);
        if (draft != null && !draft.items.isEmpty()) {
            // Items del cofre en deposito: al alijo para no perderlos (se recuperan con /market claim).
            List<ItemStack> items = new ArrayList<>();
            for (var e : draft.items) items.add(e.item());
            boolean durable = true;
            for (ItemStack item : items) {
                if (!market.stashFor(id, List.of(item))) {
                    durable = false;
                    break;
                }
            }
            if (durable) drafts.forgetDurably(id);
        }
        drafts.forget(id); // solo memoria; el journal queda si no alcanzo el claim ledger
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        drafts.recover(item -> market.hasListingMarker(item)
                || market.stashFor(id, List.of(item)), id);
        market.recoverPlayerEscrow(player);
    }
}
