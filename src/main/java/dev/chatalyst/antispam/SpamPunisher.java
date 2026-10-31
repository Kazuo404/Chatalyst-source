package dev.chatalyst.antispam;

import dev.chatalyst.chat.MessageDeleteManager;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escalating auto-punishment for spam: each spam message adds a warning; after enough
 * warnings the player is auto-muted, and each successive mute lasts longer (15m, 30m, 1h,
 * 5h... from the Escalation list). Warnings reset after a quiet period, and the escalation
 * level decays after a longer quiet period (so a one-off offender isn't punished forever).
 *
 * <p>Escalation state is per server session (in memory).
 */
public final class SpamPunisher {

    private final JavaPlugin plugin;
    private final MuteManager mutes;
    private final MessageDeleteManager deletes;

    // Volatile + lista inmutable reemplazada entera en load(): onSpam() corre en el hilo del
    // chat async mientras /reload recarga. Con la lista mutable, un clear() a mitad de un
    // escalation.get() lanzaba IndexOutOfBoundsException y el spammer se libraba del mute.
    private volatile boolean enabled;
    private volatile int warningsBeforeMute;
    private volatile long warningResetMs;
    private volatile long escalationResetMs;
    private volatile boolean deleteMessages;
    private volatile int deleteCount;
    private volatile boolean clearChat;
    private volatile List<Long> escalation = List.of();

    private final Map<UUID, Integer> warnings = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> muteLevel = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMute = new ConcurrentHashMap<>();
    // Estos mapas solo decaen cuando el jugador vuelve a spamear; sin poda, los de quien se
    // fue quedan para siempre. Se podan por antiguedad cuando crecen (solo lo ya caducado).
    private static final int SOFT_CAP = 5000;

    public SpamPunisher(JavaPlugin plugin, MuteManager mutes, MessageDeleteManager deletes) {
        this.plugin = plugin;
        this.mutes = mutes;
        this.deletes = deletes;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Anti_Spam.Auto_Mute.Enabled", true);
        warningsBeforeMute = Math.max(1, plugin.getConfig().getInt("Anti_Spam.Auto_Mute.Warnings_Before_Mute", 4));
        warningResetMs = Math.max(1, plugin.getConfig().getInt("Anti_Spam.Auto_Mute.Warning_Reset", 60)) * 1000L;
        escalationResetMs = TimeUtil.parseDuration(
                plugin.getConfig().getString("Anti_Spam.Auto_Mute.Escalation_Reset", "6h"));
        deleteMessages = plugin.getConfig().getBoolean("Anti_Spam.Auto_Mute.Delete_Messages", true);
        deleteCount = Math.max(1, plugin.getConfig().getInt("Anti_Spam.Auto_Mute.Delete_Count", 10));
        clearChat = plugin.getConfig().getBoolean("Anti_Spam.Auto_Mute.Clear_Chat", false);
        List<Long> nextEscalation = new ArrayList<>();
        for (String d : plugin.getConfig().getStringList("Anti_Spam.Auto_Mute.Escalation")) {
            long ms = TimeUtil.parseDuration(d);
            if (ms > 0) nextEscalation.add(ms);
        }
        if (nextEscalation.isEmpty()) {
            nextEscalation.add(TimeUtil.parseDuration("15m"));
            nextEscalation.add(TimeUtil.parseDuration("30m"));
            nextEscalation.add(TimeUtil.parseDuration("1h"));
        }
        escalation = List.copyOf(nextEscalation); // publicacion atomica (nunca queda vacia)
    }

    /** Called when a spam message is detected. Warns and, if the threshold is crossed, mutes. */
    public void onSpam(Player p) {
        if (!enabled) return;
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        pruneStale(now);

        // Reset the warning counter after a quiet period.
        Long lw = lastWarn.get(id);
        if (lw != null && now - lw > warningResetMs) warnings.remove(id);
        // Decay the escalation level after a longer quiet period.
        Long lm = lastMute.get(id);
        if (lm != null && escalationResetMs > 0 && now - lm > escalationResetMs) {
            muteLevel.remove(id);
            lastMute.remove(id);
        }

        int w = warnings.merge(id, 1, Integer::sum);
        lastWarn.put(id, now);

        if (w >= warningsBeforeMute) {
            warnings.remove(id);
            int level = muteLevel.getOrDefault(id, 0);
            List<Long> steps = escalation; // una sola lectura del volatile: get y size coherentes
            long duration = steps.get(Math.min(level, steps.size() - 1));
            if (!mutes.mute(p, duration, "Auto-mute: spam", "AntiSpam")) return;
            muteLevel.put(id, level + 1);
            lastMute.put(id, now);
            // Clean up the spammer's recent messages from everyone's chat.
            // deleteRecentFrom retracts every line for which Paper supplied a usable signature;
            // this is independent from a simple premium/offline classification. clearChat is
            // the fallback for unsigned/system lines, but wipes the whole visible chat.
            if (deleteMessages && deletes != null) {
                deletes.deleteRecentFrom(id, deleteCount);
            }
            if (clearChat) {
                net.kyori.adventure.text.Component blank = net.kyori.adventure.text.Component.empty();
                // onSpam corre en el hilo del chat async: iterar getOnlinePlayers() aqui no es
                // seguro (Folia). FoliaTasks lo despacha al hilo/region correcta por jugador.
                dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                        online -> !online.hasPermission("chatalyst.chat.bypass"), // keep staff's chat
                        online -> {
                            for (int i = 0; i < 80; i++) online.sendMessage(blank);
                        });
            }
            String durStr = TimeUtil.formatRemaining(TimeUtil.deadline(now, duration));
            p.sendMessage(Lang.get(p, "antispam.auto-muted", "duration", durStr));
        } else {
            p.sendMessage(Lang.get(p, "antispam.warning", "left", warningsBeforeMute - w));
        }
    }

    public void clear(UUID id) {
        warnings.remove(id);
        lastWarn.remove(id);
        // muteLevel is kept while online so escalation persists across their session.
    }

    /**
     * Poda entradas cuya ventana de reset ya paso, para que los mapas no crezcan sin limite
     * con jugadores que ya se fueron. Solo actua cuando crecen; nunca borra algo que aun
     * podria decaer, y respeta el decay de escalacion desactivado (escalationResetMs &lt;= 0).
     */
    private void pruneStale(long now) {
        if (warnings.size() + muteLevel.size() < SOFT_CAP) return;
        long warnCut = now - Math.max(warningResetMs, 60_000L);
        lastWarn.entrySet().removeIf(e -> {
            if (e.getValue() < warnCut) {
                warnings.remove(e.getKey());
                return true;
            }
            return false;
        });
        if (escalationResetMs > 0) {
            long escCut = now - Math.max(escalationResetMs, 60_000L);
            lastMute.entrySet().removeIf(e -> {
                if (e.getValue() < escCut) {
                    muteLevel.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }
    }
}
