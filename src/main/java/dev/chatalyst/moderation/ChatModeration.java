package dev.chatalyst.moderation;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de moderacion del chat en vivo (no persiste; se ajusta con comandos durante la
 * sesion): bloqueo global del chat y slowmode (cooldown global entre mensajes).
 */
public final class ChatModeration {

    public static final String BYPASS = "chatalyst.chat.bypass";

    private volatile boolean locked = false;
    private volatile int slowmodeSeconds = 0;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    // ---- Bloqueo global ------------------------------------------------------

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    // ---- Slowmode ------------------------------------------------------------

    public int getSlowmode() {
        return slowmodeSeconds;
    }

    public void setSlowmode(int seconds) {
        this.slowmodeSeconds = Math.max(0, seconds);
        if (slowmodeSeconds == 0) lastMessage.clear();
    }

    /** @return segundos que faltan para poder hablar (0 = puede hablar ya). */
    public long slowmodeRemaining(Player p) {
        if (slowmodeSeconds <= 0 || p.isOp() || p.hasPermission(BYPASS)) return 0;
        Long last = lastMessage.get(p.getUniqueId());
        if (last == null) return 0;
        long elapsed = (System.currentTimeMillis() - last) / 1000;
        long remaining = slowmodeSeconds - elapsed;
        return Math.max(0, remaining);
    }

    /** Registra que el jugador acaba de hablar (para el slowmode). */
    public void markSpoke(Player p) {
        if (slowmodeSeconds > 0) {
            lastMessage.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    public void clear(UUID id) {
        lastMessage.remove(id);
    }
}
