package dev.chatalyst.security;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Pattern;

/**
 * First line of chat security: sanitizes the message BEFORE any other filter sees it
 * (priority LOWEST). Removes:
 *   - control / non-printable characters,
 *   - zero-width and bidirectional-control characters (used to evade the swear/rules
 *     filters, e.g. "p<zwsp>u<zwsp>t<zwsp>o", and for RTL spoofing),
 *   - the section sign (color-code injection),
 * and caps the length. This way the swear filter and rules see the real text.
 */
public final class ChatSanitizerListener implements Listener {

    // Detects deliberate invisible/control characters (a filter-evasion attempt).
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u206F\\uFEFF\\u180E\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]");

    private final JavaPlugin plugin;
    // Volatile: onChat corre en el hilo del chat async mientras /reload los reescribe.
    private volatile boolean enabled;
    private volatile int maxLength;
    private volatile boolean stripControl;
    private volatile boolean stripInvisible;
    private volatile boolean notifyStaff;
    private volatile String bypass;

    public ChatSanitizerListener(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Chat_Security.Enabled", true);
        maxLength = normalizeMaxLength(
                plugin.getConfig().getInt("Chat_Security.Max_Length", 256));
        stripControl = plugin.getConfig().getBoolean("Chat_Security.Strip_Control_Chars", true);
        stripInvisible = plugin.getConfig().getBoolean("Chat_Security.Strip_Invisible", true);
        notifyStaff = plugin.getConfig().getBoolean("Chat_Security.Notify_Staff", true);
        bypass = plugin.getConfig().getString("Chat_Security.Bypass_Permission", "chatalyst.bypass.security");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled) return;
        Player p = event.getPlayer();
        if (p.isOp()) return;
        if (bypass != null && !bypass.isEmpty() && p.hasPermission(bypass)) return;

        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        String clean = sanitize(text);
        if (!clean.equals(text)) {
            event.message(Component.text(clean));
            // If invisible/control chars were used, it's a deliberate evasion attempt: alert staff.
            if (notifyStaff && INVISIBLE.matcher(text).find()) {
                alertStaff(p, clean);
            }
        }
    }

    private void alertStaff(Player who, String cleaned) {
        // Se renderiza POR ESPECTADOR (cada staff en su idioma). El texto limpio del jugador va
        // como placeholder unparsed: nunca inyecta MiniMessage.
        java.util.function.Function<CommandSender, Component> render = viewer -> MM.full(
                Lang.raw(viewer, "security.filter-bypass", "player", who.getName()),
                Placeholder.unparsed("chatalyst_msg", cleaned));
        // Corre en el hilo async del chat: iterar getOnlinePlayers() aqui no es seguro
        // (Folia); FoliaTasks lo despacha al hilo correcto por jugador.
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                staff -> staff.hasPermission("chatalyst.notify.security"),
                staff -> staff.sendMessage(render.apply(staff)));
        CommandSender console = plugin.getServer().getConsoleSender();
        dev.chatalyst.util.FoliaTasks.global(plugin, () -> console.sendMessage(render.apply(console)));
        // El espejo a Discord (canal unico) va en el idioma por defecto. Guard de null:
        // este camino corre en el hilo async del chat y puede cruzarse con el apagado.
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst != null && chatalyst.getStaffNotifier() != null) {
            chatalyst.getStaffNotifier()
                    .alert(PlainTextComponentSerializer.plainText().serialize(render.apply(console)));
        }
    }

    private String sanitize(String text) {
        return sanitize(text, stripControl, stripInvisible, maxLength);
    }

    static String sanitize(String text, boolean stripControl, boolean stripInvisible,
                           int maxLength) {
        String out = text;
        // Section sign: internal color code, never allowed from players.
        out = out.replace('§', ' ');
        if (stripInvisible) out = dev.chatalyst.util.Sanitize.stripFormattingControls(out);
        if (stripControl) out = dev.chatalyst.util.Sanitize.stripControlCharacters(out);
        if (maxLength > 0 && out.length() > maxLength) {
            out = out.substring(0, maxLength);
        }
        return out;
    }

    static int normalizeMaxLength(int configured) {
        if (configured <= 0) return 256;
        return Math.min(1_024, configured);
    }
}
