package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motivo personalizado de una accion de moderacion escrito por chat: mientras el staff
 * tiene un prompt activo su siguiente mensaje NO va al chat, se toma como el motivo y se
 * despacha el comando correspondiente en su region. "cancel"/"cancelar" aborta.
 */
public final class ModerationPrompt implements Listener {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private record Pending(String command, String targetName,
                           java.util.function.BiConsumer<Player, String> handler, long since) {
    }

    /**
     * Cuanto espera el prompt. Sin caducidad, quien abria "motivo personalizado" y se
     * distraia convertia su siguiente mensaje de chat — media hora despues, sobre otra cosa —
     * en el motivo de un kick o un ban. Pasado este tiempo el mensaje va al chat normal.
     */
    static final long TIMEOUT_MS = 90_000L;

    static boolean expired(long since, long now) {
        return now - since > TIMEOUT_MS;
    }

    private final StaffModerationModule module;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    ModerationPrompt(StaffModerationModule module) {
        this.module = module;
    }

    /** {@code commandPrefix} ya incluye objetivo y argumentos ("mute Nombre 10m"). */
    void begin(Player staff, String commandPrefix, String targetName) {
        pending.put(staff.getUniqueId(), new Pending(commandPrefix, targetName, null,
                System.currentTimeMillis()));
        staff.closeInventory();
        staff.sendMessage(Lang.get(staff, "staff-moderation.reason-prompt",
                "player", MM.escape(targetName)));
    }

    /** Captura texto y lo entrega al handler en la region del staff (p. ej. el compositor). */
    void beginText(Player staff, String targetName,
                   java.util.function.BiConsumer<Player, String> handler) {
        pending.put(staff.getUniqueId(), new Pending(null, targetName, handler,
                System.currentTimeMillis()));
        staff.closeInventory();
        staff.sendMessage(Lang.get(staff, "staff-moderation.reason-prompt",
                "player", MM.escape(targetName)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Player staff = event.getPlayer();
        Pending pend = pending.remove(staff.getUniqueId());
        if (pend == null || expired(pend.since(), System.currentTimeMillis())) return;
        event.setCancelled(true); // el motivo no debe salir al chat publico
        String text = PLAIN.serialize(event.message()).trim();
        FoliaTasks.entity(module.plugin(), staff, current -> {
            if (text.isBlank() || text.equalsIgnoreCase("cancel")
                    || text.equalsIgnoreCase("cancelar")) {
                current.sendMessage(Lang.get(current, "staff-moderation.reason-cancelled"));
                return;
            }
            if (pend.handler() != null) {
                pend.handler().accept(current, text);
                return;
            }
            // El comando re-verifica permiso, proteccion del objetivo y region; el motivo
            // queda en la auditoria como con cualquier uso normal del comando.
            current.performCommand(pend.command() + " " + text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
