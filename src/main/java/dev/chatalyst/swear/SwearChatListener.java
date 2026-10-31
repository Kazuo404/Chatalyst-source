package dev.chatalyst.swear;

import dev.chatalyst.util.MM;
import dev.chatalyst.warnings.WarningPoints;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Aplica el filtro de insultos al chat. Prioridad LOW (junto a anti-spam y reglas, antes de
 * formatear): censura el mensaje o lo cancela segun la config.
 */
public final class SwearChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SwearFilter filter;
    private final WarningPoints warnings;

    public SwearChatListener(SwearFilter filter, WarningPoints warnings) {
        this.filter = filter;
        this.warnings = warnings;
    }

    /** El texto como lo vera el resto: codigos &, &#hex y tags MiniMessage fuera. */
    static String visibleText(String raw) {
        if (raw == null || (raw.indexOf('&') < 0 && raw.indexOf('<') < 0 && raw.indexOf('§') < 0)) {
            return raw == null ? "" : raw;
        }
        return MM.strip(dev.chatalyst.util.Legacy.toMini(raw));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!filter.isEnabled()) return;
        Player p = event.getPlayer();
        if (filter.hasBypass(p)) return;

        String text = PLAIN.serialize(event.message());
        SwearFilter.Result result = filter.apply(text);
        if (!result.matched()) {
            // Quien tiene permiso de color escribe "pu&cta" o "pu<red>ta": en pantalla sale el
            // insulto entero, pero el filtro veia letras sueltas separadas por codigos. Se mira
            // tambien el texto TAL COMO SE VERA. Si solo casa asi, la version censurada pierde
            // los colores del infractor (no hay forma limpia de censurar dentro de las tags).
            String visible = visibleText(text);
            if (!visible.equals(text)) {
                SwearFilter.Result shown = filter.apply(visible);
                if (shown.matched()) result = shown;
            }
        }
        if (!result.matched()) return;

        // Auditoria con el texto ORIGINAL: el log de chat (MONITOR) solo ve la version ya
        // censurada, y un mensaje bloqueado no llega a verlo nadie. Acceso perezoso porque
        // el LogManager se crea despues que este listener (y guard de null por si el
        // plugin aun esta a medio arrancar).
        dev.chatalyst.ChatalystPlugin chatalyst = dev.chatalyst.ChatalystPlugin.get();
        dev.chatalyst.log.LogManager log = chatalyst == null ? null : chatalyst.getLogManager();
        if (log != null && log.logsSwear()) {
            log.log("SWEAR", p.getName(),
                    (filter.getAction() == SwearFilter.Action.DENY ? "[denied] " : "[censored] ") + text);
        }

        String warn = filter.getWarn(p);
        if (warn != null && !warn.isEmpty()) {
            p.sendMessage(MM.full(warn));
        }
        if (filter.getPoints() > 0 && warnings != null) {
            warnings.add(p, filter.getPoints());
        }

        if (filter.getAction() == SwearFilter.Action.DENY) {
            event.setCancelled(true);
        } else {
            // Reemplaza el mensaje por la version censurada (conserva los codigos & del jugador).
            event.message(Component.text(result.text()));
        }
    }
}
