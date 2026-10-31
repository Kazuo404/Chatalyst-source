package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Si el jugador esta editando un formato, su siguiente mensaje de chat se toma como el nuevo
 * texto de la parte (no se envia al chat). Escribir "cancelar" aborta la edicion.
 */
public final class FormatEditChatListener implements Listener {

    private final JavaPlugin plugin;
    private final FormatEditManager editor;

    public FormatEditChatListener(JavaPlugin plugin, FormatEditManager editor) {
        this.plugin = plugin;
        this.editor = editor;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        FormatEditManager.Session session = editor.getSession(p.getUniqueId());
        if (session == null) return;

        event.setCancelled(true); // no mandar al chat normal
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        editor.endEdit(p.getUniqueId());

        // La sesion puede sobrevivir a que LuckPerms retire el rango. Se reautoriza en el
        // scheduler de la entidad antes de tocar el YAML; el comando inicial no basta.
        FoliaTasks.entity(plugin, p, player -> {
            if (!player.hasPermission("chatalyst.command.editor")) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
                return;
            }
            if (text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Lang.get(player, "editor.cancelled"));
                return;
            }
            if (!FormatEditManager.validMessage(text)) {
                player.sendMessage(Lang.get(player, "editor.too-long",
                        "max", FormatEditManager.MAX_MESSAGE_LENGTH));
                return;
            }
            // Guardar YAML y recargar la configuracion se serializa en el scheduler global.
            FoliaTasks.global(plugin, () -> {
                boolean ok = editor.saveMessage(session.format(), session.part(), text);
                FoliaTasks.entity(plugin, player, current -> current.sendMessage(ok
                        ? Lang.get(current, "editor.saved", "part", session.part())
                        : Lang.get(current, "editor.error")));
            });
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        editor.endEdit(event.getPlayer().getUniqueId());
    }
}
