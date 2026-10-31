package dev.chatalyst.announce;

import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.SoundUtil;
import dev.chatalyst.util.TimeUtil;
import dev.chatalyst.util.ToastUtil;
import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.proxy.NetworkPacketCodec;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado y envio de los anuncios del /announce (GUI de administracion). Cada admin tiene
 * un borrador (display, sonido, estilo, mensaje); la GUI lo edita y este manager lo envia
 * (vista previa solo al admin, o broadcast a todos con auditoria).
 */
public final class AnnounceDraftManager {

    static final int MAX_DRAFT_MESSAGE_LENGTH = 2_048;

    /** Borrador de anuncio de un admin. */
    public static final class Draft {
        public int display;   // indice en DISPLAYS
        public int sound;     // indice en SOUNDS
        public int style;     // indice en STYLES
        public int anim;      // indice en ANIMATIONS
        public boolean network; // false = backend actual; true = toda la red del proxy
        public String message = "";
    }

    /** Displays disponibles; "epic" = title + chat + bossbar + sonido, el modo espectaculo. */
    public static final List<String> DISPLAYS = List.of("epic", "chat", "title", "actionbar", "bossbar", "toast");

    /** Animaciones (ver {@link AnnounceAnimator}); solo aplican a title/actionbar/bossbar. */
    public static final List<String> ANIMATIONS = List.of(
            "none", "typewriter", "expand", "glitch", "wave", "frames",
            "pulse", "flash", "marquee", "countdown");

    /** Presets de sonido ("" = sin sonido). */
    public static final List<String> SOUNDS = List.of(
            "minecraft:ui.toast.challenge_complete",
            "minecraft:entity.ender_dragon.growl",
            "minecraft:entity.lightning_bolt.thunder",
            "minecraft:block.bell.use",
            "minecraft:entity.player.levelup",
            "");

    /** Un estilo de anuncio: id, etiqueta visible (null = usar clave lang), material del icono
     *  y plantilla MiniMessage con {msg}. */
    public record Style(String id, String label, org.bukkit.Material material, String template) {
    }

    /**
     * Estilos disponibles: "plain" y "rainbow" fijos + TODA la paleta de /chatcolor y
     * /nickcolor (colores vanilla, colores custom y gradientes de Chat_Color en config.yml).
     * Se recalcula al vuelo, asi un gradiente nuevo en la config aparece sin reiniciar.
     */
    public List<Style> styles() {
        List<Style> out = new java.util.ArrayList<>();
        out.add(new Style("plain", null, org.bukkit.Material.PAPER, "{msg}"));
        out.add(new Style("rainbow", null, org.bukkit.Material.AMETHYST_SHARD,
                "<rainbow><bold>{msg}</bold></rainbow>"));
        for (var option : dev.chatalyst.gui.ChatColorPalette.options(plugin)) {
            out.add(new Style(option.id(), option.label(), option.material(),
                    option.tag() + "<bold>{msg}"));
        }
        return List.copyOf(out);
    }

    /** Estilo actual del borrador (indice normalizado por si la paleta cambio de tamaño). */
    public Style style(Draft draft) {
        List<Style> all = styles();
        return all.get(Math.floorMod(draft.style, all.size()));
    }

    private final ChatalystPlugin plugin;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    // Admins escribiendo el mensaje del anuncio por chat (la GUI se reabre al terminar).
    private final Map<UUID, Boolean> composing = new ConcurrentHashMap<>();
    private static final int MAX_SEEN_NETWORK_ANNOUNCEMENTS = 1_000;
    // LinkedHashMap + lock: dedupe, expiracion y expulsion forman una sola operacion.
    private final Map<UUID, Long> seenNetworkAnnouncements = new LinkedHashMap<>();

    public AnnounceDraftManager(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public Draft draft(UUID id) {
        return drafts.computeIfAbsent(id, u -> new Draft());
    }

    public void startComposing(UUID id) {
        composing.put(id, Boolean.TRUE);
    }

    public boolean isComposing(UUID id) {
        return composing.containsKey(id);
    }

    public void endComposing(UUID id) {
        composing.remove(id);
    }

    public void forget(UUID id) {
        drafts.remove(id);
        composing.remove(id);
    }

    /** Componente del mensaje ya estilizado con el preset del borrador. */
    public Component styled(Draft draft) {
        String template = style(draft).template();
        return MM.full(template.replace("{msg}", dev.chatalyst.util.Legacy.toMini(draft.message)));
    }

    /**
     * Envia el anuncio. {@code preview} = solo al propio admin (para verlo antes);
     * si no, a todos los jugadores + consola, con auditoria.
     */
    public boolean send(Player admin, boolean preview) {
        // La GUI puede seguir abierta cuando LuckPerms retira el rango. La autorizacion
        // decisiva vive aqui, no solo en el comando que abrio el compositor.
        if (!canUse(admin)) return false;
        Draft draft = draft(admin.getUniqueId());
        if (draft.message.isBlank() || !validMessage(draft.message)) return false;
        Component styled = styled(draft);
        String display = DISPLAYS.get(draft.display);
        String soundKey = SOUNDS.get(draft.sound);
        String anim = ANIMATIONS.get(Math.floorMod(draft.anim, ANIMATIONS.size()));
        String template = style(draft).template();
        String message = draft.message;

        if (preview) {
            deliver(List.of(admin), display, soundKey, anim, template, message, styled);
            return true;
        } else {
            if (draft.network) {
                if (!admin.hasPermission("chatalyst.admin.network.announce")
                        || !plugin.getConfig().getBoolean("Network_Admin.Announcements.Enabled", true)
                        || plugin.getProxyManager() == null || !plugin.getProxyManager().isEnabled()) {
                    return false;
                }
                int max = Math.max(32, Math.min(512, plugin.getConfig().getInt(
                        "Network_Admin.Announcements.Max_Message_Length", 512)));
                if (message.length() > max) return false;
                NetworkPacketCodec.Announcement packet = new NetworkPacketCodec.Announcement(
                        UUID.randomUUID(), System.currentTimeMillis(), networkServerId(), admin.getName(),
                        display, soundKey, anim, template, message);
                if (!plugin.getProxyManager().forwardAnnouncement(packet)) return false;
            }
            // /announce nace en el entity scheduler del admin. La audiencia global se captura
            // en el global scheduler y desde ahi cada envio vuelve al scheduler de su jugador.
            FoliaTasks.global(plugin, () -> {
                List<? extends Player> targets = List.copyOf(plugin.getServer().getOnlinePlayers());
                deliver(targets, display, soundKey, anim, template, message, styled);
                plugin.getServer().getConsoleSender().sendMessage(styled);
            });
            var pl = dev.chatalyst.ChatalystPlugin.get();
            if (pl != null && pl.getStaffSecurityListener() != null) {
                boolean animate = !"none".equals(anim);
                pl.getStaffSecurityListener().audit(admin,
                        draft.network ? "network-announce" : "announce",
                        (draft.network ? "network+" : "local+") + display
                                + (animate ? "+" + anim : "")
                                + (soundKey.isEmpty() ? "" : "+sound") + ": " + message);
            }
            return true;
        }
    }

    /** Recibe un anuncio ya autorizado por otro backend de la misma red. Nunca lo reenvia. */
    public void receiveNetwork(NetworkPacketCodec.Announcement packet) {
        if (!plugin.getConfig().getBoolean("Network_Admin.Announcements.Enabled", true)) return;
        long now = System.currentTimeMillis();
        if (!TimeUtil.withinDistance(now, packet.timestamp(), 300_000L)) return;
        if (!markNetworkAnnouncement(packet.eventId(), now)) return;
        try {
            Component styled = MM.full(packet.template().replace("{msg}",
                    dev.chatalyst.util.Legacy.toMini(packet.message())));
            FoliaTasks.global(plugin, () -> {
                List<? extends Player> targets = List.copyOf(plugin.getServer().getOnlinePlayers());
                deliver(targets, packet.display(), packet.sound(), packet.animation(),
                        packet.template(), packet.message(), styled);
                plugin.getServer().getConsoleSender().sendMessage(MM.full(
                        "<#7B68A0>[Network " + MM.escape(packet.serverId()) + "] <#C0B3D9>"
                                + MM.escape(packet.sender()) + ": ").append(styled));
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Dropped malformed network announcement: " + ex.getMessage());
        }
    }

    private boolean markNetworkAnnouncement(UUID id, long now) {
        if (id == null) return false;
        synchronized (seenNetworkAnnouncements) {
            if (seenNetworkAnnouncements.containsKey(id)) return false;
            var expired = seenNetworkAnnouncements.entrySet().iterator();
            while (expired.hasNext()) {
                if (expired.next().getValue() >= now - 600_000L) break;
                expired.remove();
            }
            while (seenNetworkAnnouncements.size() >= MAX_SEEN_NETWORK_ANNOUNCEMENTS) {
                var iterator = seenNetworkAnnouncements.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            seenNetworkAnnouncements.put(id, now);
            return true;
        }
    }

    public boolean networkAvailable(Player player) {
        return player.hasPermission("chatalyst.admin.network.announce")
                && plugin.getConfig().getBoolean("Network_Admin.Announcements.Enabled", true)
                && plugin.getProxyManager() != null && plugin.getProxyManager().isEnabled();
    }

    public boolean canUse(Player player) {
        return player != null && player.hasPermission("chatalyst.command.announce");
    }

    static boolean validMessage(String message) {
        return message != null && message.codePointCount(0, message.length())
                <= MAX_DRAFT_MESSAGE_LENGTH;
    }

    private String networkServerId() {
        if (plugin.getNetworkMonitor() != null) return plugin.getNetworkMonitor().serverId();
        String configured = dev.chatalyst.util.StableServerIdentity.sanitize(
                plugin.getConfig().getString("Database.Server_Id", ""));
        return configured.isEmpty() ? "server-" + plugin.getServer().getPort() : configured;
    }

    private void deliver(List<? extends Player> targets, String display, String soundKey,
                         String anim, String template, String message, Component styled) {
        boolean animate = !"none".equals(anim);

        // Un anuncio nuevo sustituye al anterior en la misma superficie para cada jugador.
        // Esto tambien evita que una vista previa personal interrumpa al resto del servidor.
        AnnounceAnimator.cancel(plugin, targets, display);

        switch (display) {
            case "chat" -> sendChat(targets, styled);
            case "title" -> {
                if (animate) AnnounceAnimator.title(plugin, targets, anim, template, message);
                else sendTitle(targets, styled);
            }
            case "actionbar" -> {
                if (animate) AnnounceAnimator.actionbar(plugin, targets, anim, template, message);
                else targets.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.sendActionBar(styled)));
            }
            case "bossbar" -> {
                if (animate) AnnounceAnimator.bossbar(plugin, targets, anim, template, message);
                else sendBossbar(targets, styled);
            }
            case "toast" -> ToastUtil.show(plugin, targets,
                    MM.strip(plugin.getConfig().getString("Announcer.Toast_Title", "Announcement")),
                    MM.strip(dev.chatalyst.util.Legacy.toMini(message)));
            default -> { // epic: todo a la vez, el modo espectaculo
                if (animate) {
                    AnnounceAnimator.title(plugin, targets, anim, template, message);
                    AnnounceAnimator.bossbar(plugin, targets, anim, template, message);
                } else {
                    sendTitle(targets, styled);
                    sendBossbar(targets, styled);
                }
                sendChat(targets, styled);
            }
        }
        if (!soundKey.isEmpty()) {
            var sound = SoundUtil.sound(soundKey, soundKey, 1.0f, 1.0f);
            targets.forEach(p -> SoundUtil.play(plugin, p, sound));
        }
    }

    private void sendChat(List<? extends Player> targets, Component styled) {
        // Enmarcado para que destaque en el chat sin depender del estilo elegido.
        Component frame = MM.full("<#7B68A0><strikethrough>                                        ");
        for (Player p : targets) {
            FoliaTasks.entity(plugin, p, pl -> {
                pl.sendMessage(frame);
                pl.sendMessage(Component.text("   ").append(styled));
                pl.sendMessage(frame);
            });
        }
    }

    private void sendTitle(List<? extends Player> targets, Component styled) {
        var times = net.kyori.adventure.title.Title.Times.times(
                Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1));
        var title = net.kyori.adventure.title.Title.title(styled, Component.empty(), times);
        targets.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.showTitle(title)));
    }

    private void sendBossbar(List<? extends Player> targets, Component styled) {
        dev.chatalyst.util.Displays.bossbarTimed(plugin, targets, styled,
                net.kyori.adventure.bossbar.BossBar.Color.PURPLE);
    }
}
