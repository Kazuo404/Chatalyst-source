package dev.chatalyst.newcomer;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Da la bienvenida a los jugadores que entran por primera vez: anuncia su llegada a todos y
 * ejecuta comandos de consola (dar kit, permisos de inicio, etc.).
 */
public final class NewcomerListener implements Listener {

    private final JavaPlugin plugin;
    // Volatile + lista inmutable: el join corre en otro hilo (Folia: hilo de region)
    // mientras /chatalyst reload reescribe este estado.
    private volatile boolean enabled;
    private volatile boolean localizedBroadcast;
    private volatile String broadcast;
    private volatile List<String> commands = List.of();

    public NewcomerListener(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Newcomer.Enabled", false);
        localizedBroadcast = plugin.getConfig().getBoolean("Newcomer.Localized_Broadcast", true);
        broadcast = dev.chatalyst.config.ConfigFiles.string(
                plugin.getConfig(), "Newcomer.Welcome_Broadcast", "");
        commands = List.copyOf(plugin.getConfig().getStringList("Newcomer.Commands"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player p = event.getPlayer();
        if (p.hasPlayedBefore()) return; // solo la primera vez

        if (broadcast != null && !broadcast.isEmpty()) {
            sendWelcomeBroadcast(p);
        }
        for (String cmd : commands) {
            String resolved = Placeholders.apply(p, cmd);
            // Los comandos se ejecutan en el hilo principal (por region del jugador).
            plugin.getServer().getGlobalRegionScheduler().run(plugin,
                    t -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), resolved));
        }
    }

    private void sendWelcomeBroadcast(Player newcomer) {
        if (!localizedBroadcast) {
            FoliaTasks.broadcast(plugin, MM.full(Placeholders.apply(newcomer, broadcast)));
            return;
        }
        String playerName = newcomer.getName();
        String world = newcomer.getWorld().getName();
        String ping = String.valueOf(newcomer.getPing());
        FoliaTasks.onlinePlayers(plugin, viewer -> viewer.sendMessage(Lang.get(viewer,
                "newcomer.welcome-broadcast",
                "player_name", playerName,
                "player", playerName,
                "world", world,
                "ping", ping)));
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(Lang.get(
                "newcomer.welcome-broadcast",
                "player_name", playerName,
                "player", playerName,
                "world", world,
                "ping", ping)));
    }
}
