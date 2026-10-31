package dev.chatalyst.chat;

import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.hook.VaultHook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Overrides por grupo de permisos (seccion Groups de config.yml): formato de chat y retardo
 * anti-spam distintos por rango. El grupo primario sale de LuckPerms (o Vault si no hay LP).
 * Solo los grupos listados se ven afectados; el resto usa la configuracion global.
 */
public final class GroupOverrides {

    /** Overrides de un grupo. {@code format} vacio o {@code delaySeconds} negativo = sin override. */
    public record Override(String format, double delaySeconds) {
    }

    private final JavaPlugin plugin;
    private volatile boolean enabled;
    private volatile Map<String, Override> byGroup = Map.of();

    public GroupOverrides(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Groups.Enabled", true);
        byGroup = parse(plugin.getConfig().getConfigurationSection("Groups.List"));
    }

    /** Parseo puro y testeable de la seccion Groups.List. */
    static Map<String, Override> parse(ConfigurationSection list) {
        if (list == null) return Map.of();
        Map<String, Override> next = new LinkedHashMap<>();
        for (String group : list.getKeys(false)) {
            ConfigurationSection sec = list.getConfigurationSection(group);
            if (sec == null) continue;
            String format = sec.getString("Format", "");
            double delay = sec.getDouble("Chat_Delay_Seconds", -1.0);
            if (!Double.isFinite(delay)) delay = -1.0;
            if ((format != null && !format.isEmpty()) || delay >= 0) {
                next.put(group.toLowerCase(Locale.ROOT),
                        new Override(format == null ? "" : format, delay));
            }
        }
        return Map.copyOf(next);
    }

    /** Override del grupo primario del jugador, o null si no hay ninguno configurado. */
    public Override forPlayer(Player p) {
        if (!enabled || byGroup.isEmpty()) return null;
        // Modo anonimo: el formato de chat POR GRUPO (colores VIP...) delata el rango
        // aunque el prefijo este anulado. Un anonimo chatea con el formato por defecto.
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl != null && pl.getAnonymousManager() != null
                && pl.getAnonymousManager().isAnonymous(p.getUniqueId())) {
            return null;
        }
        String group = LuckPermsHook.isAvailable()
                ? LuckPermsHook.group(p.getUniqueId())
                : (VaultHook.isAvailable() ? VaultHook.group(p) : "");
        if (group == null || group.isEmpty()) return null;
        return byGroup.get(group.toLowerCase(Locale.ROOT));
    }
}
