package dev.chatalyst.channel;

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
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ciclo de vida de la frecuencia del jugador: la carga a la cache al entrar y la libera al
 * salir; ademas captura la frecuencia que el jugador escribe por chat desde la GUI (boton de
 * escribir), sin que llegue al chat publico.
 */
public final class FrequencyListener implements Listener {

    private final JavaPlugin plugin;
    private final FrequencyManager frequencies;

    public FrequencyListener(JavaPlugin plugin, FrequencyManager frequencies) {
        this.plugin = plugin;
        this.frequencies = frequencies;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        frequencies.loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        frequencies.unloadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!frequencies.isComposing(player.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        frequencies.endComposing(player.getUniqueId());
        FoliaTasks.entity(plugin, player, p -> {
            boolean cancel = text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("cancelar");
            if (!cancel) {
                Integer cents = FrequencyManager.parse(text);
                if (cents == null) {
                    p.sendMessage(Lang.get(p, "frequency.invalid"));
                } else {
                    if (frequencies.set(p.getUniqueId(), cents)) {
                        p.sendMessage(Lang.get(p, "frequency.tuned",
                                "freq", FrequencyManager.format(cents)));
                    } else {
                        p.sendMessage(Lang.get(p, "plugin.storage-unavailable"));
                    }
                }
            }
            Menu.open(p, new FrequencyMenu(frequencies));
        });
    }
}
