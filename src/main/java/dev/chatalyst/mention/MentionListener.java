package dev.chatalyst.mention;

import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.toggle.ToggleType;
import dev.chatalyst.util.SoundUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Reproduce un sonido a los jugadores mencionados en el chat. Solo avisa a quienes
 * realmente reciben el mensaje (respeta la lista de destinatarios que otros plugins o
 * los canales dejaron) y que no hayan desactivado las menciones.
 */
public final class MentionListener implements Listener {

    private static final String DEFAULT_SOUND = "minecraft:block.note_block.pling";

    private final JavaPlugin plugin;
    private final ToggleManager toggles;
    // Ultimo aviso por mencionado: con Cooldown_Seconds evita el spameo de pings.
    private final java.util.Map<java.util.UUID, Long> lastPing = new java.util.concurrent.ConcurrentHashMap<>();

    public MentionListener(JavaPlugin plugin, ToggleManager toggles) {
        this.plugin = plugin;
        this.toggles = toggles;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("Mentions.Enabled", true);
    }

    // Sin ignoreCancelled: las menciones deben sonar tambien cuando la TRADUCCION en vivo
    // asumio la entrega (el evento queda cancelado pero los espectadores SI ven el mensaje).
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()
                && !dev.chatalyst.chat.ChatListener.deliveredByTranslation(event)) return;
        if (!enabled()) return;
        boolean requireAt = plugin.getConfig().getBoolean("Mentions.Require_At", false);
        Sound sound = SoundUtil.sound(plugin.getConfig().getString("Mentions.Sound", DEFAULT_SOUND),
                DEFAULT_SOUND, 1f, 1f);

        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        String lower = text.toLowerCase(Locale.ROOT);

        long cooldownMs = cooldownMillis(
                plugin.getConfig().getLong("Mentions.Cooldown_Seconds", 0));
        long now = System.currentTimeMillis();
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        var pms = chatalyst == null ? null : chatalyst.getPrivateMessages();
        java.util.UUID senderId = event.getPlayer().getUniqueId();
        for (Audience a : event.viewers()) {
            if (!(a instanceof Player viewer)) continue;
            if (viewer.equals(event.getPlayer())) continue;
            if (!toggles.isEnabled(viewer, ToggleType.MENTIONS)) continue;
            // Quien ignora al emisor recibe el marcador colapsado, no el mensaje: que le
            // suene la mencion era la forma de seguir molestandole a pesar del /ignore.
            if (pms != null && pms.isIgnoring(viewer.getUniqueId(), senderId)) continue;
            // Nombre real O apodo: con /nick puesto, el apodo es el unico nombre que los
            // demas ven, asi que llamar a alguien por el debe sonarle igual. Vale tambien
            // para el alias de /anonymous (quien escribe ya solo conoce ese nombre).
            String realName = viewer.getName().toLowerCase(Locale.ROOT);
            String bare = bareName(realName);
            if (mentioned(lower, realName, requireAt)
                    || (!bare.equals(realName) && mentioned(lower, bare, requireAt))
                    || mentionedByNick(lower, viewer, requireAt)) {
                if (cooldownMs > 0) {
                    Long last = lastPing.get(viewer.getUniqueId());
                    if (last != null && now - last < cooldownMs) continue;
                    lastPing.put(viewer.getUniqueId(), now);
                }
                SoundUtil.play(plugin, viewer, sound);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPing.remove(event.getPlayer().getUniqueId());
    }

    /**
     * El nombre sin el prefijo que Floodgate pone a los jugadores de Bedrock (".Steve"). Nadie
     * escribe el punto al nombrar a alguien, asi que sin esto las menciones a jugadores de
     * Bedrock no sonaban ni se resaltaban nunca.
     */
    public static String bareName(String name) {
        if (name == null) return "";
        int i = 0;
        while (i < name.length() && !Character.isLetterOrDigit(name.charAt(i))
                && name.charAt(i) != '_') i++;
        return i == 0 || i == name.length() ? name : name.substring(i);
    }

    static long cooldownMillis(long configuredSeconds) {
        // Es configuracion administrativa, pero aun debe fallar de forma segura: un long
        // enorme antes desbordaba al multiplicar y anulaba silenciosamente el cooldown.
        long seconds = Math.max(0L, Math.min(3600L, configuredSeconds));
        return seconds * 1000L;
    }

    /**
     * Igual que {@link #mentioned} pero contra el APODO plano del jugador. Devuelve false si
     * no tiene apodo o si coincide con su nombre real (ya comprobado por el llamador).
     */
    static boolean mentionedByNick(String messageLower, Player viewer, boolean requireAt) {
        // suggestedName solo hace el strip de MiniMessage cuando el jugador TIENE apodo; para
        // el resto devuelve el nombre tal cual. Importa: esto corre por espectador y por
        // mensaje, y un parse de MiniMessage por cada uno se nota en el hilo del chat.
        String plain = dev.chatalyst.nick.NickManager.suggestedName(viewer);
        if (plain == null || plain.isEmpty()) return false;
        String lower = plain.toLowerCase(Locale.ROOT);
        // Sin apodo, suggestedName ya devolvio el nombre real y el llamador lo comprobo.
        if (lower.equals(viewer.getName().toLowerCase(Locale.ROOT))) return false;
        return mentioned(messageLower, lower, requireAt);
    }

    /**
     * Nombre como palabra completa; admite @nombre y puntuacion alrededor (@Notch. cuenta).
     * Escaneo manual en vez de regex: la version anterior hacia Pattern.compile por CADA
     * espectador en CADA mensaje (100 jugadores = 100 compilaciones por linea de chat).
     * Equivale a {@code (?<![\w])@?nombre(?![\w])} (o con @ obligatoria si requireAt).
     */
    static boolean mentioned(String messageLower, String nameLower, boolean requireAt) {
        int nameLen = nameLower.length();
        if (nameLen == 0) return false;
        int from = 0;
        while (true) {
            int i = messageLower.indexOf(nameLower, from);
            if (i < 0) return false;
            int end = i + nameLen;
            boolean afterOk = end >= messageLower.length() || !isWordChar(messageLower.charAt(end));
            if (afterOk) {
                char before = i > 0 ? messageLower.charAt(i - 1) : 0;
                if (requireAt) {
                    // Necesita '@' pegada al nombre y limite de palabra antes de la '@'.
                    if (before == '@' && (i - 1 == 0 || !isWordChar(messageLower.charAt(i - 2)))) {
                        return true;
                    }
                } else if (i == 0 || !isWordChar(before)) {
                    return true; // '@' previa tambien pasa: no es caracter de palabra
                }
            }
            from = i + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
