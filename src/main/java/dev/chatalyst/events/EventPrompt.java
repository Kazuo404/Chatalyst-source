package dev.chatalyst.events;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entradas de chat del editor de eventos (nombre/descripcion) y aviso de eventos proximos al
 * entrar. Mientras un admin edita un campo, su siguiente mensaje NO va al chat: se toma como el
 * valor. "cancel"/"cancelar" aborta. El nombre admite "ingles | español" para las dos versiones.
 */
public final class EventPrompt implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private record Pending(String eventId, boolean description) {
    }

    private final ChatalystPlugin plugin;
    private final EventsManager events;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public EventPrompt(ChatalystPlugin plugin, EventsManager events) {
        this.plugin = plugin;
        this.events = events;
    }

    public void begin(Player player, String eventId, boolean description) {
        if (!player.hasPermission("chatalyst.command.events.admin")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        pending.put(player.getUniqueId(), new Pending(eventId, description));
        player.closeInventory();
        player.sendMessage(Lang.get(player, description
                ? "events-gui.prompt-desc" : "events-gui.prompt-name"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending pend = pending.remove(player.getUniqueId());
        if (pend == null) return;
        event.setCancelled(true); // no mandarlo al chat normal
        String text = PLAIN.serialize(event.message()).trim();
        FoliaTasks.entity(plugin, player, current -> {
            if (!current.hasPermission("chatalyst.command.events.admin")) {
                current.sendMessage(Lang.get(current, "common.no-permission"));
                return;
            }
            FoliaTasks.global(plugin, () -> apply(current, pend, text));
        });
    }

    private void apply(Player player, Pending pend, String text) {
        ScheduledEvent e = events.byId(pend.eventId());
        if (e == null) {
            FoliaTasks.entity(plugin, player, p -> p.sendMessage(Lang.get(p, "events-gui.gone")));
            return;
        }
        boolean cancelled = text.isBlank()
                || text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("cancelar");
        if (!cancelled) {
            if (pend.description()) {
                events.update(e.withDescription(text));
            } else {
                String[] parts = text.split("\\s*\\|\\s*", 2);
                events.update(e.withName(parts[0], parts.length > 1 ? parts[1] : ""));
            }
        }
        FoliaTasks.entity(plugin, player,
                p -> Menu.open(p, new EventEditMenu(plugin, events, this, pend.eventId())));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Tras un pequeño retraso para que salga despues del MOTD y demas mensajes de entrada.
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) events.notifyOnJoin(player);
        }, null, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
