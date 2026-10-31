package dev.chatalyst.staff.tools;

import dev.chatalyst.announce.AnnounceAnimator;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.proxy.NetworkPacketCodec;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * El anuncio de un castigo: "Ana ha baneado a Pepe durante 7d: X-Ray".
 *
 * <p>Sin {@code -s} lo ve toda la red; con {@code -s} (silencioso) solo el staff con
 * {@code chatalyst.notify.silent}, con la marca [Silencioso] delante, igual que el
 * {@code silent_prefix}. Quien lo puso no lo recibe: ya tiene su propia
 * confirmacion, y duplicarla solo ensucia su chat.
 *
 * <p>Los bans pueden ademas sonar y salir como titulo animado en pantalla. Todo se elige
 * desde {@code /banstyle} (GUI) o a mano en moderation.yml:
 * <pre>
 * Staff_Moderation:
 *   Broadcast:
 *     Enabled: true            # false = nunca publico, solo el aviso al staff
 *     Silent_Permission: chatalyst.notify.silent
 *     Network: true            # tambien en los demas servidores del proxy
 *     Style: linea             # linea | bloque | epico | alerta (textos en lang: broadcast-style.*)
 *     Sound: ''                # p. ej. entity.lightning_bolt.thunder, solo en los bans
 *     Sound_Volume: 1.0
 *     Sound_Pitch: 1.0
 *     Title: false             # titulo en pantalla para todos en los bans
 *     Animation: glitch        # none | glitch | pulse | flash | typewriter | expand | wave
 *     Types:                   # apagar un tipo concreto
 *       Ban: true
 *       Tempban: true
 *       Unban: true
 *       Kick: true
 *       Warn: true
 *       Banip: true
 * </pre>
 */
public final class PunishmentBroadcast {

    public static final String DEFAULT_SILENT_PERMISSION = "chatalyst.notify.silent";
    static final String ROOT = "Staff_Moderation.Broadcast.";

    /** Sonidos que ofrece la GUI, en orden de ciclo. "" = sin sonido. */
    static final List<String> SOUND_CHOICES = List.of("",
            "entity.lightning_bolt.thunder", "entity.wither.spawn", "entity.ender_dragon.growl",
            "entity.elder_guardian.curse", "block.anvil.land", "block.bell.use");

    /** Animaciones del titulo que ofrece la GUI (las de los anuncios que encajan con un ban). */
    static final List<String> ANIMATION_CHOICES = List.of(
            "none", "glitch", "pulse", "flash", "typewriter", "expand", "wave");

    /** Tipo de castigo anunciado; su nombre elige la clave de idioma y el interruptor. */
    public enum Kind {
        BAN("Ban"), TEMPBAN("Tempban"), UNBAN("Unban"), KICK("Kick"), WARN("Warn"),
        BANIP("Banip"), TEMPBANIP("Banip");

        private final String configKey;

        Kind(String configKey) {
            this.configKey = configKey;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        String langKey() {
            return "staff-tools.broadcast-" + id();
        }

        /** Los que suenan y salen en pantalla: un aviso o un kick no merecen el trueno. */
        boolean isBan() {
            return this == BAN || this == TEMPBAN || this == BANIP || this == TEMPBANIP;
        }
    }

    /** Diseno del mensaje en el chat. Cada uno es una plantilla en lang (broadcast-style.*). */
    public enum Style {
        LINEA, BLOQUE, EPICO, ALERTA;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Style from(String raw) {
            if (raw != null) {
                for (Style style : values()) {
                    if (style.id().equalsIgnoreCase(raw.trim())) return style;
                }
            }
            return LINEA;
        }
    }

    /** A quien llega el anuncio. */
    public enum Audience { EVERYONE, STAFF_ONLY }

    /** Los argumentos sin el flag, y si venia. */
    public record Parsed(boolean silent, String[] args) {
    }

    /** Lo que se anuncia, ya resuelto (nombres visibles, motivo guardado). */
    private record Notice(Kind kind, String staffName, String target, String reason,
                          String duration, long id, boolean silent) {
    }

    private PunishmentBroadcast() {
    }

    /**
     * Quita {@code -s} de los argumentos, este donde este: lo habitual es ponerlo delante del
     * jugador ({@code /ban -s Pepe}), pero el staff lo escribe tambien al final y no tiene
     * sentido que entonces acabe dentro del motivo. Solo el token exacto: "-spam" no cuenta.
     */
    public static Parsed parse(String[] args) {
        List<String> kept = new ArrayList<>(args.length);
        boolean silent = false;
        for (String arg : args) {
            if (arg != null && (arg.equalsIgnoreCase("-s") || arg.equalsIgnoreCase("-silent"))) {
                silent = true;
            } else {
                kept.add(arg);
            }
        }
        return new Parsed(silent, kept.toArray(String[]::new));
    }

    /** Decision pura: publico solo con el anuncio encendido, ese tipo encendido y sin -s. */
    public static Audience audience(boolean enabled, boolean typeEnabled, boolean silent) {
        return enabled && typeEnabled && !silent ? Audience.EVERYONE : Audience.STAFF_ONLY;
    }

    /**
     * Anuncia el castigo. Llamar despues de que haya quedado aplicado.
     *
     * @param storedReason motivo tal como se persistio (o "" en un desbaneo)
     * @param duration     texto de la duracion para los temporales ("" si no aplica)
     * @param id           numero del castigo, 0 si no tiene
     */
    public static void announce(StaffModerationModule module, Kind kind, CommandSender actor,
                                UUID targetId, String targetName, String storedReason,
                                String duration, long id, boolean silent) {
        UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
        String staffName = actor instanceof Player player ? NickManager.plainName(player) : null;
        String shownTarget = targetId == null ? targetName
                : MM.strip(NickManager.shownName(targetId.toString(), targetName));
        Notice notice = new Notice(kind, staffName, shownTarget,
                storedReason == null ? "" : storedReason, duration == null ? "" : duration,
                id, silent);

        show(module, notice, actorId);

        // Los bans son de red (base compartida): el anuncio tambien. Cada backend lo pinta
        // con su idioma y decide con SU moderation.yml si lo muestra, a quien y como.
        var proxy = module.plugin().getProxyManager();
        if (proxy != null && module.plugin().getConfig().getBoolean(ROOT + "Network", true)) {
            proxy.forwardPunishment(UUID.randomUUID(), kind.name(), staffName, shownTarget,
                    notice.reason(), notice.duration(), id, silent);
        }
    }

    /** Pinta aqui un castigo que se puso en otro servidor de la red. */
    public static void receiveNetwork(StaffModerationModule module,
                                      NetworkPacketCodec.Punishment packet) {
        if (!module.plugin().getConfig().getBoolean(ROOT + "Network", true)) return;
        Kind kind;
        try {
            kind = Kind.valueOf(packet.kind());
        } catch (IllegalArgumentException unknown) {
            return; // un backend mas nuevo con un tipo que este no conoce
        }
        show(module, new Notice(kind, packet.staff().isEmpty() ? null : packet.staff(),
                packet.target(), packet.reason(), packet.duration(), packet.id(),
                packet.silent()), null);
    }

    /**
     * Vista previa para la GUI: un ban temporal de ejemplo, con el estilo, sonido y titulo
     * configurados ahora mismo, solo para quien la pide.
     */
    public static void preview(StaffModerationModule module, Player viewer) {
        Notice sample = new Notice(Kind.TEMPBAN, viewer.getName(),
                Lang.raw(viewer, "staff-tools.broadcast-preview-player"),
                Lang.raw(viewer, "staff-tools.broadcast-preview-reason"),
                Lang.raw(viewer, "staff-tools.broadcast-preview-duration"), 1234L, false);
        viewer.sendMessage(line(module, viewer, sample));
        Sound sound = sound(module);
        if (sound != null) viewer.playSound(sound, Sound.Emitter.self());
        if (module.plugin().getConfig().getBoolean(ROOT + "Title", false)) {
            screen(module, List.of(viewer), sample);
        }
    }

    private static void show(StaffModerationModule module, Notice notice, UUID actorId) {
        var config = module.plugin().getConfig();
        Audience audience = audience(config.getBoolean(ROOT + "Enabled", true),
                config.getBoolean(ROOT + "Types." + notice.kind().configKey, true),
                notice.silent());
        String configured = config.getString(ROOT + "Silent_Permission",
                DEFAULT_SILENT_PERMISSION);
        String permission = configured == null || configured.isBlank()
                ? DEFAULT_SILENT_PERMISSION : configured;
        boolean loud = audience == Audience.EVERYONE && notice.kind().isBan();
        Sound sound = loud ? sound(module) : null;

        FoliaTasks.onlinePlayers(module.plugin(),
                viewer -> !viewer.getUniqueId().equals(actorId)
                        && (audience == Audience.EVERYONE || StaffAccess.has(viewer, permission)),
                viewer -> {
                    viewer.sendMessage(line(module, viewer, notice));
                    if (sound != null) viewer.playSound(sound, Sound.Emitter.self());
                });
        if (loud && config.getBoolean(ROOT + "Title", false)) {
            screen(module, List.copyOf(module.plugin().getServer().getOnlinePlayers()), notice);
        }
        // La consola siempre: es el rastro de quien castigo a quien, tambien en silencio.
        FoliaTasks.global(module.plugin(), () -> {
            CommandSender console = module.plugin().getServer().getConsoleSender();
            console.sendMessage(line(module, console, notice));
        });
    }

    /**
     * Titulo animado en pantalla. Se agrupa por texto ya traducido: un ticker por idioma, no
     * uno por jugador, y todos los que leen lo mismo lo ven en el mismo fotograma.
     */
    private static void screen(StaffModerationModule module, List<? extends Player> viewers,
                               Notice notice) {
        String animation = animation(module);
        Map<List<String>, List<Player>> groups = new LinkedHashMap<>();
        for (Player viewer : viewers) {
            String kindTitle = Lang.raw(viewer, "staff-tools.broadcast-title." + notice.kind().id());
            String template = Lang.raw(viewer, "staff-tools.broadcast-screen.title",
                    "accent", accent(viewer, notice.kind()));
            String subtitle = Lang.raw(viewer, "staff-tools.broadcast-screen.subtitle",
                    "accent", accent(viewer, notice.kind()),
                    "player", MM.escape(notice.target()),
                    "staff", MM.escape(staffName(viewer, notice)));
            groups.computeIfAbsent(List.of(template, kindTitle, subtitle),
                    ignored -> new ArrayList<>()).add(viewer);
        }
        groups.forEach((texts, players) -> {
            AnnounceAnimator.cancel(module.plugin(), players, "title");
            AnnounceAnimator.title(module.plugin(), players, animation, texts.get(0),
                    texts.get(1), MM.full(texts.get(2)));
        });
    }

    static String animation(StaffModerationModule module) {
        String raw = module.plugin().getConfig().getString(ROOT + "Animation", "glitch");
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return ANIMATION_CHOICES.contains(id) ? id : "none";
    }

    static Style style(StaffModerationModule module) {
        return Style.from(module.plugin().getConfig().getString(ROOT + "Style", "linea"));
    }

    /** Broadcast.Sound (p. ej. entity.lightning_bolt.thunder); vacio o invalido = sin sonido. */
    private static Sound sound(StaffModerationModule module) {
        var config = module.plugin().getConfig();
        String name = config.getString(ROOT + "Sound", "");
        if (name == null || name.isBlank()) return null;
        Key key;
        try {
            key = Key.key(name.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            return null;
        }
        float volume = (float) config.getDouble(ROOT + "Sound_Volume", 1.0);
        float pitch = (float) config.getDouble(ROOT + "Sound_Pitch", 1.0);
        return Sound.sound(key, Sound.Source.MASTER, volume, pitch);
    }

    private static String staffName(CommandSender viewer, Notice notice) {
        return notice.staffName() == null ? Lang.raw(viewer, "staff-tools.console-name")
                : notice.staffName();
    }

    private static String accent(CommandSender viewer, Kind kind) {
        return Lang.raw(viewer, "staff-tools.broadcast-accent." + kind.id());
    }

    private static Component line(StaffModerationModule module, CommandSender viewer,
                                  Notice notice) {
        Component body = body(module, viewer, notice, style(module));
        if (!notice.silent()) return body;
        String prefix = Lang.raw(viewer, "staff-tools.silent-prefix");
        if (prefix.equals("staff-tools.silent-prefix")) return body;
        return MM.full(prefix).append(body);
    }

    private static Component body(StaffModerationModule module, CommandSender viewer,
                                  Notice notice, Style style) {
        String staff = MM.escape(staffName(viewer, notice));
        String player = MM.escape(notice.target());
        String duration = MM.escape(notice.duration());
        String id = PunishmentIds.label(notice.id());
        if (style != Style.LINEA) {
            String base = "staff-tools.broadcast-style." + style.id();
            String format = base + ".format";
            // Un lang del host anterior a los estilos no tiene la plantilla: se cae a la linea.
            if (!Lang.raw(viewer, format).equals(format)) {
                String durationLine = notice.duration().isBlank() ? ""
                        : Lang.raw(viewer, base + ".duration-line", "duration", duration,
                        "accent", accent(viewer, notice.kind()));
                String reasonLine = notice.reason().isBlank() ? ""
                        : Lang.raw(viewer, base + ".reason-line",
                        "accent", accent(viewer, notice.kind()));
                String idLine = notice.id() <= PunishmentIds.NONE ? ""
                        : Lang.raw(viewer, base + ".id-line", "id", id);
                return module.formatter().feedback(viewer, format, notice.reason(),
                        "title", Lang.raw(viewer,
                                "staff-tools.broadcast-title." + notice.kind().id()),
                        "accent", accent(viewer, notice.kind()),
                        "staff", staff, "player", player, "duration", duration, "id", id,
                        "duration-line", durationLine, "reason-line", reasonLine,
                        "id-line", idLine);
            }
        }
        return module.formatter().feedback(viewer, notice.kind().langKey(), notice.reason(),
                "staff", staff, "player", player, "duration", duration, "id", id);
    }
}
