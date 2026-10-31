package dev.chatalyst.hook;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.security.CommandBlockerListener;
import dev.chatalyst.util.FoliaTasks;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Toca las clases de PacketEvents. AISLADO: solo se carga (class-load) cuando {@link
 * PacketEventsIntegration} ya confirmo que el plugin PacketEvents esta presente, para que un
 * server SIN PacketEvents nunca provoque un NoClassDefFoundError. La clase NO implementa
 * ninguna interfaz de PacketEvents (el listener es una clase interna que solo se instancia en
 * {@link #start()}), asi que cargar este puente tampoco requiere PacketEvents.
 */
final class PacketEventsBridge {

    private final JavaPlugin plugin;
    private final CommandBlockerListener blocker;
    private final dev.chatalyst.vanilla.VanillaMessages vanilla;
    private final dev.chatalyst.anonymous.AnonymousManager anonymous;
    private final dev.chatalyst.vanilla.MessageReplacements replacements;
    private final dev.chatalyst.vanilla.ServerBrand brand;
    private boolean ownsApi;
    private final java.util.List<com.github.retrooper.packetevents.event.PacketListenerCommon>
            registered = new java.util.ArrayList<>();

    PacketEventsBridge(JavaPlugin plugin, CommandBlockerListener blocker,
                       dev.chatalyst.vanilla.VanillaMessages vanilla,
                       dev.chatalyst.anonymous.AnonymousManager anonymous,
                       dev.chatalyst.vanilla.MessageReplacements replacements,
                       dev.chatalyst.vanilla.ServerBrand brand) {
        this.plugin = plugin;
        this.blocker = blocker;
        this.vanilla = vanilla;
        this.anonymous = anonymous;
        this.replacements = replacements;
        this.brand = brand;
    }

    void start() {
        if (PacketEvents.getAPI() == null) {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
            PacketEvents.getAPI().load();
            ownsApi = true;
        }
        var events = PacketEvents.getAPI().getEventManager();
        registered.add(events.registerListener(
                new PacketCommandBlockListener(), PacketListenerPriority.LOWEST));
        if (vanilla != null) {
            registered.add(events.registerListener(
                    new VanillaMessageListener(), PacketListenerPriority.NORMAL));
        }
        if (replacements != null) {
            // Despues del embellecido vanilla y antes del guardian de anonimos: lo que
            // escriba una regla del dueño tambien tiene que pasar por el.
            registered.add(events.registerListener(
                    new MessageReplacementListener(), PacketListenerPriority.NORMAL));
        }
        if (brand != null) {
            registered.add(events.registerListener(
                    new BrandListener(), PacketListenerPriority.NORMAL));
        }
        if (anonymous != null) {
            // HIGH: el ULTIMO en tocar el mensaje. Da igual quien lo escribiera ni cuantas
            // veces se haya reestilizado antes; lo que no puede salir de aqui es el nombre.
            registered.add(events.registerListener(
                    new AnonymousNameListener(), PacketListenerPriority.HIGH));
        }
        // HIGHEST: va despues de cualquier otro que toque los pasajeros del jugador.
        registered.add(events.registerListener(
                new MountedTagListener(), PacketListenerPriority.HIGHEST));
        if (ownsApi) {
            PacketEvents.getAPI().init();
        }
        resendBrand();
    }

    /**
     * La marca solo viaja al conectar. Sin reenviarla, activarla o cambiarla con un
     * /chatalyst reload no se veria hasta que cada jugador volviera a entrar.
     */
    private void resendBrand() {
        byte[] payload = brand == null ? null : brand.payload();
        if (payload == null) return;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(online,
                        new com.github.retrooper.packetevents.wrapper.play.server
                                .WrapperPlayServerPluginMessage(
                                dev.chatalyst.vanilla.ServerBrand.CHANNEL, payload));
            } catch (Throwable ignored) {
                // Un jugador a medio conectar: recibira la marca por el camino normal.
            }
        }
    }

    void stop() {
        // Los listeners se retiran SIEMPRE, sea de quien sea la API. Cuando PacketEvents va
        // como plugin aparte (lo normal) la API no es nuestra y antes aqui no se hacia nada:
        // cada /chatalyst reload dejaba otros tres listeners vivos encima de los anteriores, y
        // apagar la integracion en la config no apagaba nada.
        if (!registered.isEmpty() && PacketEvents.getAPI() != null) {
            var events = PacketEvents.getAPI().getEventManager();
            for (var listener : registered) {
                try {
                    events.unregisterListener(listener);
                } catch (Throwable ignored) {
                }
            }
        }
        registered.clear();
        if (ownsApi) {
            try {
                PacketEvents.getAPI().terminate();
            } catch (Throwable ignored) {
            }
        }
        ownsApi = false;
    }

    /**
     * Mantiene el cartel fancy en la lista de pasajeros del jugador. CosmeticsCore manda su
     * propio SET_PASSENGERS con SOLO sus cosmeticos; sin esto el cliente desmonta el cartel y
     * lo deja flotando. PacketEvents ve el paquete ya codificado, despues de ProtocolLib.
     */
    private static final class MountedTagListener implements PacketListener {
        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            if (event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS) return;
            try {
                var wrapper = new com.github.retrooper.packetevents.wrapper.play.server
                        .WrapperPlayServerSetPassengers(event);
                int[] merged = dev.chatalyst.tablist.MountedTags.merge(
                        wrapper.getEntityId(), wrapper.getPassengers());
                if (merged == null) return;
                wrapper.setPassengers(merged);
                event.markForReEncode(true);
            } catch (Throwable ignored) {
                // Un cambio de formato del paquete no debe tumbar el envio: pasa el original.
            }
        }
    }

    /**
     * Reescribe el feedback VANILLA (/tp, /xp, /kill...) con el estilo del plugin y en el
     * idioma de quien lo recibe. Solo toca mensajes de sistema TRADUCIBLES cuya clave esta
     * mapeada; cualquier otro paquete se deja intacto, incluido el chat de los jugadores.
     */
    private final class VanillaMessageListener implements PacketListener {
        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            if (event.getPacketType() != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) return;
            if (!vanilla.isEnabled() || event.getPlayer() == null) return;
            try {
                var wrapper = new com.github.retrooper.packetevents.wrapper.play.server
                        .WrapperPlayServerSystemChatMessage(event);
                if (wrapper.isOverlay()) return; // action bar: no es feedback de comando
                net.kyori.adventure.text.Component styled =
                        vanilla.rewrite((Player) event.getPlayer(), wrapper.getMessage());
                if (styled != null) {
                    wrapper.setMessage(styled);
                    event.markForReEncode(true);
                }
            } catch (Throwable ignored) {
                // Un cambio de formato del paquete no debe tumbar el envio: pasa el original.
            }
        }
    }

    /**
     * Sustituye mensajes de OTROS plugins segun las reglas de Message_Replacements. Solo
     * mensajes de sistema del chat: la action bar se repinta varias veces por segundo y un
     * titulo no es un aviso que se "traduzca".
     */
    private final class MessageReplacementListener implements PacketListener {
        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            if (event.getPacketType() != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) return;
            if (!replacements.isActive()) return;
            if (!(event.getPlayer() instanceof Player viewer)) return;
            try {
                var wrapper = new com.github.retrooper.packetevents.wrapper.play.server
                        .WrapperPlayServerSystemChatMessage(event);
                if (wrapper.isOverlay()) return;
                var outcome = replacements.rewrite(viewer.getName(), wrapper.getMessage());
                if (outcome == null) return;
                if (outcome.hide()) {
                    event.setCancelled(true);
                    return;
                }
                wrapper.setMessage(outcome.replacement());
                event.markForReEncode(true);
            } catch (Throwable ignored) {
                // Un cambio de formato del paquete no debe tumbar el envio: pasa el original.
            }
        }
    }

    /**
     * Cambia el nombre del software del servidor que el cliente enseña en F3. El servidor lo
     * manda durante la fase de configuracion (y otra vez en juego en algunos proxys), asi que
     * se corrige en las dos: tocarlo solo al entrar dejaria ver el real un instante.
     */
    private final class BrandListener implements PacketListener {
        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            var type = event.getPacketType();
            boolean configuration = type == PacketType.Configuration.Server.PLUGIN_MESSAGE;
            if (!configuration && type != PacketType.Play.Server.PLUGIN_MESSAGE) return;
            byte[] payload = brand.payload();
            if (payload == null) return;
            try {
                com.github.retrooper.packetevents.wrapper.common.server
                        .WrapperCommonServerPluginMessage<?> wrapper = configuration
                        ? new com.github.retrooper.packetevents.wrapper.configuration.server
                                .WrapperConfigServerPluginMessage(event)
                        : new com.github.retrooper.packetevents.wrapper.play.server
                                .WrapperPlayServerPluginMessage(event);
                if (!dev.chatalyst.vanilla.ServerBrand.CHANNEL.equals(wrapper.getChannelName())) {
                    return;
                }
                if (java.util.Arrays.equals(payload, wrapper.getData())) return;
                wrapper.setData(payload);
                event.markForReEncode(true);
            } catch (Throwable ignored) {
                // Un fallo cambiando la marca no puede impedir que el jugador entre.
            }
        }
    }

    /**
     * Tapa el nombre real de un anonimo en texto que Chatalyst NO escribio: la tumba que
     * anuncia de quien es, el aviso de un anticheat, el mensaje de muerte de otro plugin de
     * chat, el titulo a pantalla completa que celebra a un ganador. Se corrige al final del
     * camino porque es el unico sitio donde se puede — no hay forma de arreglar uno a uno
     * plugins que ni siquiera sabemos que habra instalados.
     *
     * <p>No basta con el chat. Un plugin que quiere que algo se NOTE no lo manda al chat: lo
     * manda como titulo, como subtitulo, como action bar o como bossbar, que es justo donde
     * mas se lee. Y el titulo de un menu ajeno ("Cofre de Kazu") delata igual aunque no sea
     * un mensaje. Todos esos caminos llevan un componente de texto y todos pasan por aqui.
     *
     * <p>El paquete TITLE antiguo (el combinado de 1.16 y anteriores) no se cubre: este
     * plugin pide {@code api-version 1.21}, asi que ningun cliente lo recibe. Los nombres de
     * los marcadores tampoco: ahi el nombre no es texto que se lee, es la CLAVE con la que el
     * cliente casa cada linea, y cambiarla romperia el marcador en vez de esconder nada.
     *
     * <p>Al staff con permiso de {@code /realname} no se le cambia nada: resolver identidades
     * ya es cosa suya, y un aviso de moderacion con el nombre cambiado le estorbaria.
     */
    private final class AnonymousNameListener implements PacketListener {
        // Leido una vez: este listener corre por cada paquete en el hilo de red, y cada
        // /chatalyst reload crea uno nuevo (stop + start), asi que el valor nunca se queda viejo.
        private final boolean guardOtherPlugins =
                plugin.getConfig().getBoolean("Anonymous.Guard_Other_Plugins", true);

        @Override
        public void onPacketSend(com.github.retrooper.packetevents.event.PacketSendEvent event) {
            var type = event.getPacketType();
            if (type != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE
                    && type != PacketType.Play.Server.SET_TITLE_TEXT
                    && type != PacketType.Play.Server.SET_TITLE_SUBTITLE
                    && type != PacketType.Play.Server.ACTION_BAR
                    && type != PacketType.Play.Server.BOSS_BAR
                    && type != PacketType.Play.Server.OPEN_WINDOW) {
                return;
            }
            if (!anonymous.anyDisguised()) return;
            if (!guardOtherPlugins) return;
            if (!(event.getPlayer() instanceof Player viewer)) return;
            if (anonymous.seesRealNames(viewer)) return;
            java.util.Map<String, String> disguised = anonymous.disguisedNames();
            if (disguised.isEmpty()) return;
            try {
                if (type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
                    var p = new com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerSystemChatMessage(event);
                    guard(event, disguised, p::getMessage, p::setMessage);
                } else if (type == PacketType.Play.Server.SET_TITLE_TEXT) {
                    var p = new com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerSetTitleText(event);
                    guard(event, disguised, p::getTitle, p::setTitle);
                } else if (type == PacketType.Play.Server.SET_TITLE_SUBTITLE) {
                    var p = new com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerSetTitleSubtitle(event);
                    guard(event, disguised, p::getSubtitle, p::setSubtitle);
                } else if (type == PacketType.Play.Server.ACTION_BAR) {
                    var p = new com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerActionBar(event);
                    guard(event, disguised, p::getActionBarText, p::setActionBarText);
                } else if (type == PacketType.Play.Server.BOSS_BAR) {
                    var p = new com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerBossBar(event);
                    // Solo dos de las seis acciones traen titulo; en las otras el campo ni
                    // siquiera se ha leido y preguntarlo no significa nada.
                    var action = p.getAction();
                    if (action == com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerBossBar.Action.ADD
                            || action == com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerBossBar.Action.UPDATE_TITLE) {
                        guard(event, disguised, p::getTitle, p::setTitle);
                    }
                } else {
                    var p = new com.github.retrooper.packetevents.wrapper.play.server
                            .WrapperPlayServerOpenWindow(event);
                    guard(event, disguised, p::getTitle, p::setTitle);
                }
            } catch (Throwable ignored) {
                // Un fallo tapando un nombre no puede impedir que el texto llegue.
            }
        }
    }

    /**
     * Lee un componente del paquete, le tapa los nombres reales y solo lo vuelve a escribir si
     * de verdad cambio algo.
     *
     * <p>La identidad del objeto es la señal de que hubo cambio: {@link
     * dev.chatalyst.anonymous.AnonymousNameGuard#rewrite} devuelve el MISMO componente cuando
     * no habia nada que tapar. Sin esa comprobacion se re-encodearia cada titulo y cada
     * bossbar del servidor por si acaso, y eso lo paga todo el mundo por unos pocos
     * disfrazados.
     */
    private static void guard(com.github.retrooper.packetevents.event.PacketSendEvent event,
                              java.util.Map<String, String> disguised,
                              java.util.function.Supplier<net.kyori.adventure.text.Component> get,
                              java.util.function.Consumer<net.kyori.adventure.text.Component> set) {
        net.kyori.adventure.text.Component original = get.get();
        if (original == null) return;
        net.kyori.adventure.text.Component safe =
                dev.chatalyst.anonymous.AnonymousNameGuard.rewrite(original, disguised);
        if (safe == original) return;
        set.accept(safe);
        event.markForReEncode(true);
    }

    private final class PacketCommandBlockListener implements PacketListener {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPlayer() == null) return;
            if (event.getPacketType() != PacketType.Play.Client.CHAT_COMMAND) return;
            Player player = event.getPlayer();
            String message;
            try {
                message = "/" + new WrapperPlayClientChatCommand(event).getCommand();
            } catch (Throwable t) {
                return;
            }
            if (!blocker.shouldBlock(player.getUniqueId(), message)) return;
            event.setCancelled(true);
            FoliaTasks.entity(plugin, player, p -> p.sendMessage(blocker.denialFor(p, message)));
        }
    }
}
