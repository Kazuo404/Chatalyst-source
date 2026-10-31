package dev.chatalyst.autoreply;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Envio de las auto-respuestas: escucha el chat en MONITOR (no toca el mensaje; si un mute,
 * regla o anti-spam lo cancelo antes, el bot no responde) y entrega las lineas configuradas
 * con un pequeño retardo para que se lea natural.
 *
 * <p>Bilingue por cliente: {@code Reply} (ingles) / {@code Reply_es} (español) se eligen con
 * el idioma del CLIENTE de cada receptor ({@link Lang#isSpanish}) — con {@code To: player}
 * el del que pregunto; con {@code To: everyone} cada espectador ve su propio idioma (la
 * consola recibe la version inglesa).
 */
public final class AutoReplyListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;
    private final AutoResponder responder;
    private final Capabilities capabilities;

    public AutoReplyListener(JavaPlugin plugin, AutoResponder responder) {
        this.plugin = plugin;
        this.responder = responder;
        this.capabilities = new Capabilities(plugin);
    }

    // LOW (no MONITOR): en modo privado cancelamos el mensaje ANTES de que ChatListener (NORMAL)
    // lo registre en historial/monitor/proxy. ignoreCancelled=true respeta mute/reglas/anti-spam,
    // que corren en LOWEST/LOW antes que este listener (registrado despues del router).
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!responder.isEnabled()) return;
        Player p = event.getPlayer();
        String bypass = responder.bypassPermission();
        if (bypass != null && !bypass.isEmpty() && p.hasPermission(bypass)) return;

        String text = PLAIN.serialize(event.message());
        AutoResponder.Match match =
                responder.match(p.getUniqueId(), text, System.currentTimeMillis());
        if (match == null) return;
        AutoResponder.Response response = match.response();
        // Modo privado: la pregunta NO va al chat general; solo el que pregunto la ve, junto con
        // la respuesta del bot, en su idioma. El idioma se captura AHORA (leer p.locale() aqui es
        // seguro); tras el delay el envio corre en otros hilos y con el jugador quiza desconectado.
        // Solo se oculta si el bot esta seguro (Private_Questions_Mode): si caso por deteccion
        // difusa, el mensaje sale al chat como siempre y la respuesta va en privado igualmente.
        // Ambas salen del MISMO snapshot de configuracion que eligio la respuesta (un /reload a
        // mitad no puede ocultar la pregunta y a la vez difundir la respuesta a todos).
        boolean privateMode = match.privateMode();
        boolean hide = match.hide();
        if (hide) event.setCancelled(true);
        audit(p.getName(), response.name(), match, hide, text);
        deliver(p.getUniqueId(), NickManager.safeName(p), Lang.isSpanish(p), response,
                privateMode, hide ? text : null);
    }

    /**
     * Cada respuesta del bot queda en el log de chat ({@code [BOT]}) con la regla, si fue un
     * trigger o deteccion difusa (con su nota) y si se oculto la pregunta: con eso se afina bot.yml
     * mirando lo que de verdad contesto en vez de adivinar.
     */
    private void audit(String player, String rule, AutoResponder.Match match, boolean hidden,
            String text) {
        if (!(plugin instanceof dev.chatalyst.ChatalystPlugin cp) || cp.getLogManager() == null) {
            return;
        }
        String how = match.explicit() ? "trigger" : "keywords " + match.score();
        String shown = text.length() > 120 ? text.substring(0, 120) + "..." : text;
        cp.getLogManager().log("BOT", player,
                rule + " (" + how + (hidden ? ", oculta" : "") + "): " + shown);
    }

    private void deliver(UUID askerId, String askerName, boolean askerSpanish,
            AutoResponder.Response response, boolean privateMode, String echoQuestion) {
        // El bot compone la respuesta segun el estado REAL del servidor (comandos registrados,
        // plugins presentes, features encendidas). Se evalua al enviar, no al arrancar, para
        // reflejar cambios en caliente (/reload, plugins cargados).
        java.util.function.Predicate<String> capable = capabilities::has;
        boolean auto = responder.autoLanguage();
        boolean fixedEs = responder.fixedSpanish();
        Runnable send = () -> {
            // En modo privado SIEMPRE se responde solo al que pregunto, aunque la respuesta sea
            // "To: everyone" (y su pregunta se le repite solo si se oculto del chat general).
            if (!privateMode && response.toEveryone()) {
                if (auto) {
                    // Auto-lenguaje: renderizado UNA vez por idioma; cada espectador ve el suyo.
                    List<Component> english = render(response.replies(false, capable), askerName);
                    List<Component> spanish = render(response.replies(true, capable), askerName);
                    FoliaTasks.onlinePlayers(plugin, viewer -> {
                        for (Component line : Lang.isSpanish(viewer) ? spanish : english) {
                            viewer.sendMessage(line);
                        }
                    });
                    sendToConsole(english);
                } else {
                    // Idioma fijo para todos.
                    List<Component> lines = render(response.replies(fixedEs, capable), askerName);
                    FoliaTasks.onlinePlayers(plugin, viewer -> {
                        for (Component line : lines) viewer.sendMessage(line);
                    });
                    sendToConsole(lines);
                }
                return;
            }
            boolean spanish = auto ? askerSpanish : fixedEs;
            List<Component> lines = render(response.replies(spanish, capable), askerName);
            Player asker = plugin.getServer().getPlayer(askerId);
            if (asker == null) return; // se fue antes del delay: nada que responder
            FoliaTasks.entity(plugin, asker, viewer -> {
                // En privado, primero se le muestra SU pregunta (escapada: nunca inyecta MM).
                if (echoQuestion != null) {
                    viewer.sendMessage(Lang.get(viewer, "auto-reply.your-question",
                            "message", dev.chatalyst.util.MM.escape(echoQuestion)));
                }
                for (Component line : lines) {
                    viewer.sendMessage(line);
                }
            });
        };
        if (response.delayTicks() > 0) {
            FoliaTasks.globalLater(plugin, send, response.delayTicks());
        } else {
            FoliaTasks.global(plugin, send);
        }
    }

    private void sendToConsole(List<Component> lines) {
        FoliaTasks.global(plugin, () -> {
            for (Component line : lines) plugin.getServer().getConsoleSender().sendMessage(line);
        });
    }

    private List<Component> render(List<String> lines, String askerName) {
        // El comando de la tienda de rangos lo elige el dueño (Tebex.Shop.Command), asi que
        // se resuelve en vivo: escribirlo a mano en bot.yml haria que el bot mandara a los
        // jugadores a un comando que dejo de existir en cuanto se renombre.
        String ranks = plugin.getConfig().getString("Tebex.Shop.Command", "ranks");
        String ranksCommand = "/" + (ranks == null || ranks.isBlank()
                ? "ranks" : ranks.trim().toLowerCase(java.util.Locale.ROOT));
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            // {player} = quien pregunto, ya anonimizado y escapado antes del delay. El texto
            // de la pregunta NUNCA se interpola en la respuesta.
            out.add(MM.full(line.replace("{player}", askerName)
                    .replace("{ranks_command}", ranksCommand)));
        }
        return out;
    }
}
