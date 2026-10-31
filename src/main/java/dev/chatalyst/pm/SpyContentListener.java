package dev.chatalyst.pm;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Amplia el spy a contenido: los espias con /spy activo y el permiso granular adecuado ven
 * carteles, libros y nombres de yunque. Configurable por seccion en config.yml.
 */
public final class SpyContentListener implements Listener {

    private static final int MAX_CONTENT_LENGTH = 500;

    private final JavaPlugin plugin;
    private final PrivateMessages pm;

    public SpyContentListener(JavaPlugin plugin, PrivateMessages pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!plugin.getConfig().getBoolean("Spy.Signs.Enabled", true)) return;
        StringBuilder content = new StringBuilder();
        for (Component line : event.lines()) {
            String text = PlainTextComponentSerializer.plainText().serialize(line).trim();
            if (!text.isEmpty()) {
                if (content.length() > 0) content.append(" | ");
                content.append(text);
            }
        }
        if (content.length() == 0) return;
        broadcastToSpies(event.getPlayer(), "spy.sign",
                "player", event.getPlayer().getName(), "content", spyContent(content.toString())
                        + " " + MM.escape(dev.chatalyst.log.LogListener.signWhere(event)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!plugin.getConfig().getBoolean("Spy.Books.Enabled", true)) return;
        List<Component> pages = event.getNewBookMeta().pages();
        if (pages.isEmpty()) return;
        StringBuilder content = new StringBuilder();
        String title = dev.chatalyst.log.LogListener.signedTitle(event);
        if (title != null) content.append("«").append(title).append("» ");
        for (Component page : pages) {
            String text = PlainTextComponentSerializer.plainText().serialize(page).replace('\n', ' ').trim();
            if (!text.isEmpty()) {
                if (content.length() > 0) content.append(" // ");
                content.append(text);
            }
        }
        if (content.length() == 0) return;
        broadcastToSpies(event.getPlayer(), "spy.book",
                "player", event.getPlayer().getName(),
                "pages", pages.size(),
                "content", spyContent(content.toString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent event) {
        if (!plugin.getConfig().getBoolean("Spy.Anvil.Enabled", true)) return;
        if (!(event.getInventory() instanceof AnvilInventory)) return;
        if (event.getRawSlot() != 2) return; // slot de resultado
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || !result.hasItemMeta() || !result.getItemMeta().hasDisplayName()) return;
        String name = PlainTextComponentSerializer.plainText()
                .serialize(result.getItemMeta().displayName()).trim();
        if (name.isEmpty()) return;
        broadcastToSpies(p, "spy.anvil", "player", p.getName(), "content", spyContent(name));
    }

    // Construye el mensaje por-espia para que cada uno lo vea en su idioma.
    // La key es "spy.<tipo>", asi que el tipo (sign/book/anvil) sale tras el punto.
    private void broadcastToSpies(Player source, String key, Object... replacements) {
        String type = key.substring(key.indexOf('.') + 1);
        // Despacho por entidad (Folia): sign/book/anvil corren en la region del emisor;
        // tocar a los espias directamente no es seguro.
        java.util.UUID sourceId = source.getUniqueId();
        String sourceName = source.getName();
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                spy -> !spy.getUniqueId().equals(sourceId)
                        && pm.isSpy(spy) && PrivateMessages.canSpy(spy, type)
                        && pm.spyWatches(spy, sourceName),
                spy -> spy.sendMessage(Lang.get(spy, key, replacements)));
    }

    private static String spyContent(String content) {
        if (content.length() <= MAX_CONTENT_LENGTH) return MM.escape(content);
        return MM.escape(content.substring(0, MAX_CONTENT_LENGTH) + "...");
    }
}
