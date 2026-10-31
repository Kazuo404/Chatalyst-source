package dev.chatalyst.tablist;

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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captura por chat para poner el titulo/iconos del nametag DESDE una GUI: tras pulsar el boton,
 * el siguiente mensaje del jugador se toma como el valor del campo (no se envia al chat).
 * "cancelar" aborta. Reusa {@link NametagTitleCommand#applyFromEditor} para no duplicar las
 * defensas (sanitizado, permiso de color, reglas, filtro de insultos, almacenamiento).
 */
public final class NametagTitleEditor implements Listener {

    private final JavaPlugin plugin;
    private final NametagTitleCommand command;
    // La captura caduca: sin esto, quien pulsaba el boton y se iba sin escribir perdia su
    // siguiente mensaje de chat, horas despues, convertido en titulo sobre su cabeza.
    private static final long TIMEOUT_MILLIS = 120_000L;

    private record Pending(int field, long deadline) {
    }

    // Jugador -> campo pendiente (TITLE / ICON_LEFT / ICON_RIGHT) y hasta cuando vale.
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public NametagTitleEditor(JavaPlugin plugin, NametagTitleCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    /** Arranca la captura: el proximo mensaje del jugador sera el valor de {@code field}. */
    public void begin(Player player, int field) {
        pending.put(player.getUniqueId(),
                new Pending(field, System.currentTimeMillis() + TIMEOUT_MILLIS));
        player.sendMessage(Lang.get(player, "nametag-title.editor-prompt",
                "field", Lang.raw(player, "nametag-title.field-" + fieldKey(field))));
    }

    /** Aplica un valor directo (ej. "off") sin pasar por el chat; usado por el boton derecho. */
    public void applyDirect(Player player, int field, String value) {
        pending.remove(player.getUniqueId());
        command.applyFromEditor(player, field, value);
    }

    // Prioridad baja para tomar el mensaje antes que el resto del pipeline de chat.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Pending capture = pending.remove(event.getPlayer().getUniqueId());
        if (capture == null || System.currentTimeMillis() > capture.deadline()) return;
        int field = capture.field();
        event.setCancelled(true); // no mandar el valor al chat normal
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        // Reautoriza y aplica en el scheduler de la entidad: el permiso pudo cambiar y el
        // comando toca APIs de jugador.
        FoliaTasks.entity(plugin, event.getPlayer(), player -> {
            if (text.equalsIgnoreCase("cancelar") || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Lang.get(player, "nametag-title.editor-cancelled"));
                return;
            }
            command.applyFromEditor(player, field, text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private static String fieldKey(int field) {
        return switch (field) {
            case NametagTitleManager.ICON_LEFT -> "iconleft";
            case NametagTitleManager.ICON_RIGHT -> "iconright";
            default -> "title";
        };
    }
}
