package dev.chatalyst.pm;

import dev.chatalyst.util.CommandNames;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Muestra comandos a los espias con /spy activo y permiso chatalyst.spy o
 * chatalyst.spy.command. Los comandos de autenticacion se excluyen siempre:
 * la configuracion solo puede anadir exclusiones, nunca quitar las criticas.
 */
public final class CommandSpyListener implements Listener {

    /** Default español de versiones anteriores; se trata como "sin personalizar". */
    private static final String LEGACY_FORMAT =
            "<#7B68A0>[<#C44FE0>Spy<#7B68A0>] <#FBF2FF>{player_name} <#7B68A0>ejecuto: <#C0B3D9>{command}";

    private final JavaPlugin plugin;
    private final PrivateMessages pm;

    public CommandSpyListener(JavaPlugin plugin, PrivateMessages pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("Spy.Commands.Enabled", false)) return;

        String message = event.getMessage();               // ej: "/warp spawn"
        List<String> ignore = plugin.getConfig().getStringList("Spy.Commands.Ignore");
        if (!shouldSpyCommand(message, ignore)) return;

        Player sender = event.getPlayer();
        // Formato: el del dueño si lo personalizo, y si no la plantilla del idioma de CADA
        // espia (como el resto de tipos de spy). Asi un staff con el cliente en ingles no
        // recibe la linea en español.
        String override = customFormat(plugin.getConfig().getString("Spy.Commands.Format", ""));

        // Despacho por entidad (Folia): el evento corre en la region del emisor, tocar a
        // otros jugadores directamente no es seguro.
        UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                staff -> !staff.getUniqueId().equals(senderId)
                        && pm.isSpy(staff) && PrivateMessages.canSpy(staff, "command")
                        && pm.spyWatches(staff, senderName),
                staff -> staff.sendMessage(line(staff, override, senderName, message)));
    }

    /** Linea del spy para UN espia, en su idioma (o con el formato fijo del dueño). */
    private static Component line(Player spy, String override, String senderName,
                                  String command) {
        String template = override != null ? override
                : dev.chatalyst.lang.Lang.raw(spy, "spy.command");
        // El comando se inserta como Component de texto plano (nunca como tags): lo escribe
        // un jugador y no debe poder inyectar formato ni botones en el chat del staff.
        String tpl = template.replace("{player_name}", dev.chatalyst.util.MM.escape(senderName))
                .replace("{command}", "<chatalyst_cmd>");
        return MM.full(tpl, Placeholder.component("chatalyst_cmd", Component.text(command)));
    }

    /**
     * Formato personalizado del dueño, o null para usar el idioma del espia. El texto por
     * defecto ANTIGUO (que venia solo en español) cuenta como "sin personalizar", para que
     * los servidores que ya lo tienen guardado pasen al modo bilingue sin tocar nada.
     */
    static String customFormat(String configured) {
        if (configured == null || configured.isBlank()) return null;
        return configured.equals(LEGACY_FORMAT) ? null : configured;
    }

    /**
     * The mandatory set protects credentials even when an administrator replaces the
     * configurable ignore list. Kept package-private for focused unit tests.
     */
    static boolean shouldSpyCommand(String message, List<String> configuredIgnored) {
        return !dev.chatalyst.log.LogManager.isAlwaysSensitiveCommand(message)
                && !CommandNames.matches(message,
                configuredIgnored != null ? configuredIgnored : List.of());
    }
}
