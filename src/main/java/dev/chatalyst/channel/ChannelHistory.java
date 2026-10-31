package dev.chatalyst.channel;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historial en memoria de los ultimos mensajes por canal, para reproducirlo (claramente
 * marcado como "ya enviado") al cambiar de canal o con /channel history.
 *
 * <p>Solo se registran canales GLOBAL: reproducir un canal FREQUENCY/REGION/PARTY filtraria
 * mensajes que el espectador nunca debio ver (estaba lejos, en otra region u otra party).
 * Los mensajes de jugadores con shadow mute tampoco entran (los delataria). El historial es
 * local a este servidor y vive solo en memoria (se pierde al reiniciar, a proposito).
 */
public final class ChannelHistory {

    static final int MAX_HISTORY_SIZE = 500;

    public record Entry(String messageId, UUID sender, String senderName, String text, long time) {
    }

    private final JavaPlugin plugin;
    private final Map<String, Deque<Entry>> byChannel = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile int size;
    private volatile int showOnSwitch;
    private volatile long retentionMs;

    public ChannelHistory(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        configure(plugin.getConfig().getBoolean("Channels.History.Enabled", true),
                plugin.getConfig().getInt("Channels.History.Size", 20),
                plugin.getConfig().getInt("Channels.History.Show_On_Switch", 10),
                retentionMillis(plugin.getConfig().getLong(
                        "Channels.History.Retention_Minutes", 30)));
    }

    /** Configuracion directa (usada por load() y por los tests). */
    void configure(boolean enabled, int size, int showOnSwitch, long retentionMs) {
        this.enabled = enabled;
        this.size = Math.max(1, Math.min(MAX_HISTORY_SIZE, size));
        this.showOnSwitch = Math.max(0, Math.min(this.size, showOnSwitch));
        // configure() also acts as the normalization boundary for tests and future callers.
        // One millisecond is technically positive but operationally equivalent to deleting the
        // message immediately (and made the regression timing-dependent). Match the public
        // Retention_Minutes fallback: invalid values retain one minute.
        this.retentionMs = retentionMs <= 0 ? 60_000L : retentionMs;
        if (!enabled) byChannel.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    static long retentionMillis(long minutes) {
        if (minutes <= 0) return 60_000L;
        if (minutes > Long.MAX_VALUE / 60_000L) return Long.MAX_VALUE;
        return minutes * 60_000L;
    }

    /** Cuantas entradas se reproducen automaticamente al cambiar de canal (0 = ninguna). */
    public int showOnSwitch() {
        return showOnSwitch;
    }

    public void record(String channel, String messageId, UUID sender, String senderName, String text) {
        record(channel, new Entry(messageId, sender, senderName, text, System.currentTimeMillis()));
    }

    /** Variante con timestamp explicito (tests de retencion). */
    void record(String channel, Entry entry) {
        if (!enabled || entry.text().isEmpty()) return;
        Deque<Entry> dq = byChannel.computeIfAbsent(channel.toLowerCase(Locale.ROOT), c -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(entry);
            while (dq.size() > size) dq.removeFirst();
        }
    }

    /** Ultimas {@code limit} entradas del canal (mas antigua primero), dentro de la retencion. */
    public List<Entry> recent(String channel, int limit) {
        Deque<Entry> dq = byChannel.get(channel.toLowerCase(Locale.ROOT));
        if (dq == null || limit <= 0) return List.of();
        long cutoff = System.currentTimeMillis() - retentionMs;
        List<Entry> out = new ArrayList<>();
        synchronized (dq) {
            dq.removeIf(e -> e.time < cutoff); // limpieza perezosa
            for (Entry e : dq) out.add(e);
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    /**
     * Reproduce las ultimas {@code limit} entradas al jugador (cabecera + lineas con el
     * texto escapado). Devuelve false si no habia nada que enseñar. El comando /channel y
     * la GUI comparten este render (antes cada uno tenia su copia).
     */
    public boolean replayTo(org.bukkit.entity.Player p, String channelName, int limit) {
        if (!enabled) return false;
        // Lo que en vivo llega colapsado por /ignore tampoco se repite al entrar al canal.
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        var pms = chatalyst == null ? null : chatalyst.getPrivateMessages();
        List<Entry> entries = recent(channelName, limit).stream()
                .filter(e -> pms == null || e.sender() == null
                        || !pms.isIgnoring(p.getUniqueId(), e.sender()))
                .toList();
        if (entries.isEmpty()) return false;
        p.sendMessage(dev.chatalyst.lang.Lang.get(p, "channel.history-header",
                "name", channelName, "count", entries.size()));
        for (Entry e : entries) {
            p.sendMessage(dev.chatalyst.lang.Lang.get(p, "channel.history-entry",
                    "time", ago(e.time()),
                    "player", dev.chatalyst.util.MM.escape(e.senderName()),
                    "message", dev.chatalyst.util.MM.escape(e.text())));
        }
        return true;
    }

    /** Borra del historial un mensaje concreto (la X de moderacion / removemessage). */
    public void removeByMessageId(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        for (Deque<Entry> dq : byChannel.values()) {
            synchronized (dq) {
                dq.removeIf(e -> messageId.equals(e.messageId));
            }
        }
    }

    /** Borra del historial todo lo de un jugador (acompaña a /cht purge). */
    public void purgePlayer(UUID sender) {
        for (Deque<Entry> dq : byChannel.values()) {
            synchronized (dq) {
                dq.removeIf(e -> e.sender != null && e.sender.equals(sender));
            }
        }
    }

    /** "45s", "3m", "2h": marca compacta y neutra de idioma de hace cuanto se envio. */
    public static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h";
    }
}
