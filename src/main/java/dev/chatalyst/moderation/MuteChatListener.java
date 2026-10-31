package dev.chatalyst.moderation;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Impide chatear a los jugadores silenciados. Prioridad LOWEST para cortar antes de
 * cualquier procesamiento (reglas, formato).
 */
public final class MuteChatListener implements Listener {

    private final MuteManager mutes;

    public MuteChatListener(MuteManager mutes) {
        this.mutes = mutes;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // OP is the server's unconditional superuser; permission negations never mute it.
        if (event.getPlayer().isOp() && dev.chatalyst.staff.StaffAccess.exemptsOps()) return;
        // Mute propio de Chatalyst.
        Mute mute = mutes.getMute(event.getPlayer().getUniqueId());
        if (mute != null) {
            if (mute.soft) {
                // Shadow mute: el mensaje se renderiza normal, pero solo lo reciben el propio
                // jugador, la consola (queda en el log) y el staff con permiso de spy.
                var sender = event.getPlayer();
                event.viewers().removeIf(v -> v instanceof org.bukkit.entity.Player p
                        && !p.equals(sender) && !p.isOp()
                        && !p.hasPermission("chatalyst.spy"));
                return;
            }
            event.setCancelled(true);
            mutes.notifyIfMuted(event.getPlayer());
        }
    }
}
