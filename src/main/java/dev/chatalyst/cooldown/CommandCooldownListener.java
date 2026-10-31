package dev.chatalyst.cooldown;

import dev.chatalyst.lang.Lang;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-command cooldowns: makes a player wait N seconds between uses of a configured command
 * (e.g. /kit, /home). Configured in Command_Cooldowns. Bypassable with a permission.
 */
public final class CommandCooldownListener implements Listener {

    private final JavaPlugin plugin;
    // Mapa inmutable reemplazado entero en load() + bypass volatile: los comandos corren en
    // otro hilo (Folia: hilos de region) mientras /chatalyst reload reescribe este estado.
    private volatile Map<String, Integer> cooldowns = Map.of();        // command -> seconds
    private final Map<UUID, Map<String, Long>> lastUse = new ConcurrentHashMap<>();
    private volatile String bypass;

    public CommandCooldownListener(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        bypass = plugin.getConfig().getString("Command_Cooldowns.Bypass_Permission", "chatalyst.bypass.cooldown");
        Map<String, Integer> next = new HashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("Command_Cooldowns.Commands");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (next.size() >= 256) break;
                String normalized = normalize(key);
                if (normalized.length() <= 65) {
                    next.put(normalized, Math.max(0, Math.min(86_400, sec.getInt(key))));
                }
            }
        }
        cooldowns = Map.copyOf(next); // publicacion atomica
    }

    private String normalize(String cmd) {
        String c = cmd.toLowerCase(Locale.ROOT);
        return c.startsWith("/") ? c : "/" + c;
    }

    /** "/plugin:comando" -> "/comando" (los namespaces validos son [a-z0-9_.-]). */
    public static String stripNamespace(String cmd) {
        int colon = cmd.indexOf(':');
        if (colon <= 1 || !cmd.startsWith("/")) return cmd;
        String ns = cmd.substring(1, colon);
        for (int i = 0; i < ns.length(); i++) {
            char c = ns.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_' && c != '.' && c != '-') {
                return cmd; // no es un namespace: no tocar
            }
        }
        return "/" + cmd.substring(colon + 1);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (cooldowns.isEmpty()) return;
        String cmd = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        // Quitar el namespace ("/essentials:kit" -> "/kit"): sin esto el cooldown se evadia
        // simplemente anteponiendo el prefijo del plugin al comando configurado.
        cmd = stripNamespace(cmd);
        Integer seconds = cooldowns.get(cmd);
        if (seconds == null || seconds <= 0) return;

        Player p = event.getPlayer();
        if (p.isOp()) return;
        if (bypass != null && !bypass.isEmpty() && p.hasPermission(bypass)) return;

        long now = System.currentTimeMillis();
        Map<String, Long> map = lastUse.computeIfAbsent(p.getUniqueId(), u -> new ConcurrentHashMap<>());
        Long last = map.get(cmd);
        if (last != null && now - last < seconds * 1000L) {
            long remaining = (seconds * 1000L - (now - last) + 999) / 1000;
            event.setCancelled(true);
            p.sendMessage(Lang.get(p, "cooldown.wait", "seconds", remaining));
            return;
        }
        map.put(cmd, now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }
}
