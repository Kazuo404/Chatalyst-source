package dev.chatalyst.swear;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Function;

/**
 * Avisa al staff (chatalyst.notify.security) cuando entra un jugador cuyo NOMBRE de cuenta
 * dispara el filtro de groserias. No expulsa ni bloquea: un falso positivo en un nombre
 * significaria vetar a un jugador legitimo, asi que la decision queda en manos del staff.
 */
public final class SwearNameAlertListener implements Listener {

    private final JavaPlugin plugin;
    private final SwearFilter filter;

    public SwearNameAlertListener(JavaPlugin plugin, SwearFilter filter) {
        this.plugin = plugin;
        this.filter = filter;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!filter.isEnabled() || !filter.isNameAlertEnabled()) return;
        Player who = event.getPlayer();
        if (!filter.apply(who.getName()).matched()) return;

        // Render POR ESPECTADOR (cada staff en su idioma). El nombre se ESCAPA: en servidores
        // offline/cracked una cuenta puede llamarse "<#FF4D6D>x" o meter una etiqueta clicable, y
        // sin escapar MM.full() la interpretaria (inyeccion MiniMessage al chat del staff).
        String safeName = MM.escape(who.getName());
        Function<CommandSender, Component> render = viewer -> MM.full(
                Lang.raw(viewer, "swear.name-alert", "player", safeName));
        FoliaTasks.onlinePlayers(plugin,
                staff -> staff.hasPermission("chatalyst.notify.security"),
                staff -> staff.sendMessage(render.apply(staff)));
        CommandSender console = plugin.getServer().getConsoleSender();
        FoliaTasks.global(plugin, () -> console.sendMessage(render.apply(console)));
        // Espejo a Discord (canal de staff), como las alertas del sanitizador: si no hay
        // staff conectado cuando entra el alt, el aviso no se pierde.
        dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst != null && chatalyst.getStaffNotifier() != null) {
            chatalyst.getStaffNotifier().alert(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(render.apply(console)));
        }
    }
}
