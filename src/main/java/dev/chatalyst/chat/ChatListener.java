package dev.chatalyst.chat;

import dev.chatalyst.channel.Channel;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.config.ConfigManager;
import dev.chatalyst.format.ChatFormat;
import dev.chatalyst.proxy.ProxyManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;

/**
 * Aplica el formato de Chatalyst al chat usando el renderer moderno de Paper
 * (AsyncChatEvent#renderer). Si los canales estan activos, enruta el mensaje al
 * canal de escritura del jugador y filtra quien lo recibe.
 */
public final class ChatListener implements Listener {

    private final ConfigManager config;
    private final ChannelManager channels;
    private final ProxyManager proxy;
    private final MessageDeleteManager deletes;
    private final dev.chatalyst.moderation.MuteManager mutes;
    private final dev.chatalyst.channel.ChannelHistory history;
    private final GroupOverrides groups;
    private final dev.chatalyst.network.NetworkMonitor networkMonitor;
    private final dev.chatalyst.pm.PrivateMessages pm;
    private final dev.chatalyst.translate.TranslationService translation;
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, String> locales =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ChatListener(ConfigManager config, ChannelManager channels, ProxyManager proxy,
                         MessageDeleteManager deletes, dev.chatalyst.moderation.MuteManager mutes,
                         dev.chatalyst.channel.ChannelHistory history, GroupOverrides groups,
                         dev.chatalyst.network.NetworkMonitor networkMonitor,
                         dev.chatalyst.pm.PrivateMessages pm,
                         dev.chatalyst.translate.TranslationService translation) {
        this.config = config;
        this.channels = channels;
        this.proxy = proxy;
        this.deletes = deletes;
        this.mutes = mutes;
        this.history = history;
        this.groups = groups;
        this.networkMonitor = networkMonitor;
        this.pm = pm;
        this.translation = translation;
    }

    // Mensajes cuya entrega ASUMIO el traductor: el evento queda cancelado (la entrega vanilla
    // no sirve, es por-espectador y asincrona) pero el mensaje SI llega a los jugadores. Los
    // listeners MONITOR (log, Discord, menciones, AFK, announcer) deben tratarlos como chat
    // vivo, no como chat bloqueado por mute/anti-spam. Weak: se limpia solo al morir el evento.
    private static final java.util.Set<AsyncChatEvent> TRANSLATED_DELIVERIES =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** True si este evento fue cancelado por la traduccion en vivo pero SI se entrego. */
    public static boolean deliveredByTranslation(AsyncChatEvent event) {
        return TRANSLATED_DELIVERIES.contains(event);
    }

    /** Entrega el mensaje: traduccion en vivo por espectador si esta activa, o el render normal. */
    private void deliver(AsyncChatEvent event, org.bukkit.entity.Player sender,
            dev.chatalyst.format.ChatFormat fmt, Map<String, String> extra,
            java.util.List<ChatRenderer.RenderedPart> prepared, String messageId, boolean hide) {
        if (translateAndDeliver(event, sender, fmt, extra, prepared, messageId, hide)) return;
        java.util.UUID senderId = sender.getUniqueId();
        // Nombre MOSTRADO (apodo o alias de /anonymous), no el real: va en el marcador de
        // "mensaje ignorado", y con el real ese marcador desenmascaraba a un anonimo.
        String senderName = dev.chatalyst.nick.NickManager.safeName(sender);
        event.renderer((source, dn, message, viewer) ->
                renderFor(prepared, senderId, senderName, viewer, messageId, hide));
    }

    /**
     * Traduccion en vivo: cada espectador ve el mensaje en el idioma de su cliente. Si esta
     * disponible y al menos un espectador la activo, CANCELA el evento y entrega de forma
     * asincrona (el texto se traduce una vez por idioma, con cache), respetando el idioma del
     * emisor, el ignore y la preferencia opt-in por jugador. Fail-open: si el backend falla,
     * cada quien ve el original.
     */
    private boolean translateAndDeliver(AsyncChatEvent event, org.bukkit.entity.Player sender,
            dev.chatalyst.format.ChatFormat fmt, Map<String, String> extra,
            java.util.List<ChatRenderer.RenderedPart> prepared, String messageId, boolean hide) {
        if (translation == null || !translation.enabled()) return false;
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(event.message());
        if (text.length() < translation.minLength()) return false;
        java.util.List<org.bukkit.entity.Player> viewers = new java.util.ArrayList<>();
        java.util.List<net.kyori.adventure.audience.Audience> others = new java.util.ArrayList<>();
        for (var a : event.viewers()) {
            if (a instanceof org.bukkit.entity.Player p) viewers.add(p);
            else others.add(a); // consola u otros sinks: reciben el original
        }
        if (viewers.isEmpty()) return false;

        java.util.UUID senderId = sender.getUniqueId();
        // Nombre MOSTRADO (apodo o alias de /anonymous), no el real: va en el marcador de
        // "mensaje ignorado", y con el real ese marcador desenmascaraba a un anonimo.
        String senderName = dev.chatalyst.nick.NickManager.safeName(sender);
        String senderLang = rememberLocale(sender);
        java.util.Map<java.util.UUID, String> targetByViewer = new java.util.HashMap<>();
        for (org.bukkit.entity.Player v : viewers) {
            java.util.UUID viewerId = v.getUniqueId();
            String lang = translateOn(viewerId)
                    ? locales.getOrDefault(viewerId, "en") : null; // null = original
            targetByViewer.put(viewerId, lang);
        }
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (String lang : targetByViewer.values()) {
            if (lang != null && !lang.equals(senderLang)) targets.add(lang);
        }
        // Nadie eligio una traduccion necesaria (audiencia monolingue o sin opt-ins):
        // conserva el pipeline normal sin demora ni coste.
        if (targets.isEmpty()) return false;

        java.util.Map<String, java.util.concurrent.CompletableFuture<String>> futures =
                new java.util.HashMap<>();
        try {
            // Preparar TODAS las operaciones antes de cancelar el evento. Aunque el servicio
            // esta disenado fail-open, una excepcion inesperada aqui debe dejar que Paper
            // entregue el mensaje original por su pipeline normal.
            for (String lang : targets) futures.put(lang, translation.translate(text, lang));
        } catch (RuntimeException failure) {
            return false;
        }

        event.setCancelled(true); // las futures existen: ya podemos tomar control de la entrega
        TRANSLATED_DELIVERIES.add(event); // cancelado pero entregado: los MONITOR deben verlo

        net.kyori.adventure.text.Component original = event.message();
        java.util.concurrent.CompletableFuture
                .allOf(futures.values().toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((ignored, err) -> {
                    var plugin = pluginOf();
                    // Placeholders/condiciones del emisor se resuelven exclusivamente en su
                    // EntityScheduler. Si se desconecta mientras traduce, entregamos el
                    // render original ya preparado en vez de tragarnos el mensaje cancelado.
                    dev.chatalyst.util.FoliaTasks.entity(plugin, sender, current -> {
                        java.util.Map<String, java.util.List<ChatRenderer.RenderedPart>> byLang =
                                new java.util.HashMap<>();
                        byLang.put(senderLang, prepared);
                        for (var e : futures.entrySet()) {
                            String translated = e.getValue().getNow(text);
                            byLang.put(e.getKey(), ChatRenderer.prepare(fmt, current,
                                    net.kyori.adventure.text.Component.text(translated), extra));
                        }
                        deliverTranslated(viewers, others, targetByViewer, byLang, senderId,
                                senderName, senderLang, messageId, hide);
                    }, () -> deliverTranslated(viewers, others, targetByViewer,
                            java.util.Map.of(senderLang, prepared), senderId, senderName,
                            senderLang, messageId, hide));
                });
        return true;
    }

    private void deliverTranslated(java.util.List<Player> viewers,
            java.util.List<net.kyori.adventure.audience.Audience> others,
            java.util.Map<java.util.UUID, String> targetByViewer,
            java.util.Map<String, java.util.List<ChatRenderer.RenderedPart>> byLang,
            java.util.UUID senderId, String senderName, String senderLang, String messageId,
            boolean hide) {
        var originalPrepared = byLang.get(senderLang);
        for (Player viewer : viewers) {
            String lang = targetByViewer.get(viewer.getUniqueId());
            var chosen = lang == null || lang.equals(senderLang)
                    ? originalPrepared : byLang.getOrDefault(lang, originalPrepared);
            dev.chatalyst.util.FoliaTasks.entity(pluginOf(), viewer,
                    current -> current.sendMessage(
                            renderFor(chosen, senderId, senderName, current, messageId, hide)));
        }
        dev.chatalyst.util.FoliaTasks.global(pluginOf(), () -> {
            for (net.kyori.adventure.audience.Audience other : others) {
                other.sendMessage(renderFor(originalPrepared, senderId, senderName, other,
                        messageId, hide));
            }
        });
    }

    private static org.bukkit.plugin.java.JavaPlugin pluginOf() {
        return dev.chatalyst.ChatalystPlugin.get();
    }

    private String rememberLocale(org.bukkit.entity.Player p) {
        try {
            String lang = p.locale().getLanguage();
            String normalized = normalizeLocale(lang);
            locales.put(p.getUniqueId(), normalized);
            return normalized;
        } catch (Throwable t) {
            return "en";
        }
    }

    private static String normalizeLocale(String lang) {
        return lang == null || lang.isBlank()
                ? "en" : lang.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean translateOn(java.util.UUID viewerId) {
        var pl = dev.chatalyst.ChatalystPlugin.get();
        var toggles = pl == null ? null : pl.getToggleManager();
        // Fail-safe de privacidad/preferencia: sin manager cargado no se asume consentimiento.
        return toggles != null
                && toggles.isEnabled(viewerId, dev.chatalyst.toggle.ToggleType.TRANSLATE);
    }

    /**
     * Componente que ve un espectador: el mensaje normal, o — si el espectador IGNORA al emisor
     * y {@code Chat.Ignore.Hide_In_Chat} esta activo — un marcador colapsado con boton para verlo
     * igual ("<#C0B3D9>[ignorado] X · [ver]"). El mensaje real se guarda para el boton de revelar.
     */
    private net.kyori.adventure.text.Component renderFor(
            java.util.List<ChatRenderer.RenderedPart> prepared, java.util.UUID senderId,
            String senderName,
            net.kyori.adventure.audience.Audience viewerAud, String messageId, boolean hideIgnored) {
        org.bukkit.entity.Player viewer = viewerAud instanceof org.bukkit.entity.Player p ? p : null;
        net.kyori.adventure.text.Component shown;
        if (hideIgnored && viewer != null && pm != null
                && !viewer.getUniqueId().equals(senderId)
                && pm.isIgnoring(viewer.getUniqueId(), senderId)) {
            deletes.rememberReveal(messageId, viewer.getUniqueId(),
                    ChatRenderer.assemble(prepared, viewer));
            shown = dev.chatalyst.lang.Lang.get(viewer, "chat.ignored-hidden",
                    "player", senderName, "id", messageId);
        } else {
            shown = ChatRenderer.assemble(prepared, viewer);
        }
        // Lo que ve cada jugador, para poder borrar el mensaje aunque llegue sin firma.
        if (viewer != null) deletes.recordShown(messageId, viewer.getUniqueId(), shown);
        return shown;
    }

    /** Party y clanes son privados para los jugadores, no para el staff con spy de canales. */
    private void notifyChannelSpies(Player sender, Channel channel,
                                    net.kyori.adventure.text.Component message) {
        var pm = dev.chatalyst.ChatalystPlugin.get() == null ? null
                : dev.chatalyst.ChatalystPlugin.get().getPrivateMessages();
        if (pm == null) return;
        java.util.UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(message);
        dev.chatalyst.util.FoliaTasks.onlinePlayers(pluginOf(),
                spy -> !spy.getUniqueId().equals(senderId)
                        && !channels.canSee(spy, senderId, channel)
                        && pm.isSpy(spy)
                        && dev.chatalyst.pm.PrivateMessages.canSpy(spy, "channel")
                        && pm.spyWatches(spy, senderName),
                spy -> spy.sendMessage(dev.chatalyst.util.MM.full(
                        dev.chatalyst.lang.Lang.raw(spy, "spy.channel")
                                .replace("{player_name}", dev.chatalyst.util.MM.escape(senderName))
                                .replace("{channel}", dev.chatalyst.util.MM.escape(channel.name))
                                .replace("{message}", "<chatalyst_msg>"),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                                "chatalyst_msg", net.kyori.adventure.text.Component.text(text)))));
    }

    private boolean hideIgnored() {
        var pl = dev.chatalyst.ChatalystPlugin.get();
        return pl != null && pl.getConfig().getBoolean("Chat.Ignore.Hide_In_Chat", true);
    }

    /**
     * Formato efectivo para el jugador: el del grupo (Groups.List.<grupo>.Format) cuando el
     * canal usa el formato base de chat; los formatos especiales (staff, trade...) no se tocan.
     */
    private ChatFormat formatFor(Player sender, String channelFormat) {
        if (groups != null && channelFormat.equalsIgnoreCase(config.getDefaultFormatName())) {
            var override = groups.forPlayer(sender);
            if (override != null && !override.format().isEmpty()) {
                return config.getFormat(override.format());
            }
        }
        return config.getFormat(channelFormat);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.isChatEnabled()) {
            return;
        }
        Player sender = event.getPlayer();
        // Id para poder borrar el mensaje luego (la X de moderacion / limpieza por spam),
        // con firma o, en servidores no premium, sin ella.
        String messageId = deletes.track(event.signedMessage(), sender.getUniqueId());
        boolean hide = hideIgnored(); // ocultar a quien ignora al emisor (con boton de revelar)

        if (channels.isEnabled()) {
            // El router (LOW) puede haber redirigido ESTE mensaje a otro canal (ej. una oferta
            // de venta escrita en el global sale por el canal trade) sin cambiar el canal activo.
            Channel routed = channels.consumeRoutedMessage(sender);
            Channel channel = routed != null ? routed : channels.getWriteChannel(sender);
            if (channel != null) {
                // Quitar de los destinatarios a quien no deba ver este canal.
                event.viewers().removeIf(v -> v instanceof Player p && !channels.canSee(p, sender, channel));
                ChatFormat fmt = formatFor(sender, channel.format);
                Map<String, String> extra = Map.of("channel", channel.name, "message_uuid", messageId);
                // Se pre-renderiza una vez; cada espectador solo filtra por permisos de vista.
                var prepared = ChatRenderer.prepare(fmt, sender, event.message(), extra);

                // API publica: otros plugins pueden observar o cancelar el mensaje del canal.
                var apiEvent = new dev.chatalyst.api.ChannelMessageEvent(sender, channel.name,
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(event.message()));
                sender.getServer().getPluginManager().callEvent(apiEvent);
                if (apiEvent.isCancelled()) {
                    event.setCancelled(true);
                    return;
                }

                boolean softMuted = mutes != null && mutes.isSoftMuted(sender.getUniqueId());
                if (!softMuted && networkMonitor != null) {
                    networkMonitor.recordLocal(sender, channel.name, event.message());
                }
                // Marca el canal real del mensaje para los relays en MONITOR (Discord por canal).
                channels.markDelivered(sender, channel);
                // Historial del canal (replay al cambiar de canal / /channel history). Solo
                // canales GLOBAL (los de proximidad/party filtrarian lo que no debiste ver)
                // y nunca con shadow mute (el replay delataria u ocultaria el mute).
                if (history != null && channel.type == dev.chatalyst.channel.ChannelType.GLOBAL
                        && !softMuted) {
                    history.record(channel.name, messageId, sender.getUniqueId(),
                            dev.chatalyst.nick.NickManager.suggestedName(sender),
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                    .plainText().serialize(event.message()));
                }

                // Canal cross-server: reenviar una version formateada a los demas servers.
                // Nunca con shadow mute: el mensaje no debe salir de la vista del muteado.
                if (channel.proxy && proxy.isEnabled() && !softMuted) {
                    proxy.forward(channel.name, ChatRenderer.assemble(prepared, null));
                }
                deliver(event, sender, fmt, extra, prepared, messageId, hide);
                if (channel.isPrivateGroup() && !softMuted) {
                    notifyChannelSpies(sender, channel, event.message());
                }
                return;
            }
        }

        // Sin canales: formato por defecto para todos (con override por grupo si lo hay).
        channels.markDeliveredNone(sender); // que Discord no reutilice un canal viejo
        ChatFormat fmt = formatFor(sender, config.getDefaultFormatName());
        if (fmt == null) return;
        Map<String, String> extra = Map.of("message_uuid", messageId);
        var prepared = ChatRenderer.prepare(fmt, sender, event.message(), extra);
        if ((mutes == null || !mutes.isSoftMuted(sender.getUniqueId())) && networkMonitor != null) {
            networkMonitor.recordLocal(sender, "global", event.message());
        }
        deliver(event, sender, fmt, extra, prepared, messageId, hide);
    }

    // SIN ignoreCancelled a proposito: si el router marco una redireccion en LOW y luego
    // otro filtro (anti-spam, reglas) cancelo el mensaje, aqui se drena la marca. Si no,
    // quedaria pendiente y mandaria el SIGUIENTE mensaje del jugador al canal equivocado.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatDrainRouting(AsyncChatEvent event) {
        if (event.isCancelled()) {
            channels.consumeRoutedMessage(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rememberLocale(event.getPlayer());
        // Entrada real: aplica Channels.On_Join (por defecto, todos empiezan en el general).
        channels.loadPlayer(event.getPlayer(), true);
    }

    @EventHandler
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        locales.put(event.getPlayer().getUniqueId(),
                normalizeLocale(event.locale().getLanguage()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        locales.remove(event.getPlayer().getUniqueId());
        channels.unloadPlayer(event.getPlayer());
        // Libera el historial de firmas del jugador para que el mapa no crezca sin fin.
        // Con margen: /cht purge debe funcionar contra quien acaba de desconectarse.
        deletes.forgetLater(event.getPlayer().getUniqueId());
    }
}
