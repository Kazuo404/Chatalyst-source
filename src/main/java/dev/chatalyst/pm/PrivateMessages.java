package dev.chatalyst.pm;

import dev.chatalyst.chat.ChatRenderer;
import dev.chatalyst.config.ConfigManager;
import dev.chatalyst.format.ChatFormat;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.toggle.ToggleType;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.PlayerText;
import dev.chatalyst.util.Vanish;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Mensajes privados (/msg, /r), lista de ignorados (persistente) y modo spy para staff.
 * La entrega usa los formatos pm-sender, pm-receiver y spy.
 */
public final class PrivateMessages {

    public static final String SPY_PERMISSION = "chatalyst.spy";
    /** Bypass: message players who ignore you or have PMs off, and reach vanished players. */
    public static final String REACH_PERMISSION = "chatalyst.bypass.reach";

    /**
     * Granular spy: {@code chatalyst.spy} is the umbrella (all types + the toggle), while
     * {@code chatalyst.spy.<type>} grants a single type.
     * type = pm, command, sign, book, anvil, container, combat, channel.
     */
    public static boolean canSpy(Player p, String type) {
        return p.isOp() || p.hasPermission(SPY_PERMISSION)
                || p.hasPermission(SPY_PERMISSION + "." + type);
    }

    /** True if the player can spy on at least one type (used to allow the /spy toggle). */
    public static boolean canSpyAny(Player p) {
        if (p.isOp() || p.hasPermission(SPY_PERMISSION)) return true;
        for (String t : new String[]{"pm", "command", "sign", "book", "anvil", "container",
                "combat", "channel"}) {
            if (p.hasPermission(SPY_PERMISSION + "." + t)) return true;
        }
        return false;
    }

    /** True if the sender may directly target this player while they are vanished. */
    public static boolean canReachVanished(Player sender, Player target) {
        return sender.equals(target) || sender.isOp() || !Vanish.isVanished(target)
                || sender.hasPermission(REACH_PERMISSION);
    }

    /**
     * Interruptor maestro del dueño (Private_Messages.Enabled): con false, /msg, /reply e
     * /ignore quedan bloqueados por completo. Apagado por defecto — el correo (/inbox) es
     * un sistema aparte y sigue funcionando igual.
     */
    public static boolean systemEnabled(JavaPlugin plugin) {
        return plugin.getConfig().getBoolean("Private_Messages.Enabled", false);
    }

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final MuteManager mutes;
    private final ToggleManager toggles;
    private dev.chatalyst.log.LogManager log;
    // Puente opcional /msg -> correo (Mail.Msg_Fallback). Lo inyecta el plugin cuando el
    // modulo de correo levanta; null = sin puente.
    private dev.chatalyst.mail.MailManager mail;
    private dev.chatalyst.mail.MailNotifier mailNotifier;

    public void setLog(dev.chatalyst.log.LogManager log) {
        this.log = log;
    }

    public void setMailBridge(dev.chatalyst.mail.MailManager mail,
                              dev.chatalyst.mail.MailNotifier notifier) {
        this.mail = mail;
        this.mailNotifier = notifier;
    }

    // Estado de sesion (no persiste).
    private final Map<UUID, UUID> replyTarget = new ConcurrentHashMap<>();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();

    // Ignorados: persiste en el Store "ignore" (YAML o base de datos; CSV de UUIDs).
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    private final dev.chatalyst.db.Store store;
    /** Quien apago el spy a mano teniendo la auto-activacion. Persistente: es una decision. */
    private final dev.chatalyst.db.Store spyOptOut;

    public PrivateMessages(JavaPlugin plugin, ConfigManager config, MuteManager mutes,
                           ToggleManager toggles, dev.chatalyst.db.StorageManager storage) {
        this.plugin = plugin;
        this.config = config;
        this.mutes = mutes;
        this.toggles = toggles;
        this.store = storage.store("ignore");
        this.spyOptOut = storage.store("spy_optout");
        migrateLegacy();
    }

    /** Migracion one-time del viejo ignore-data.yml al Store. */
    private void migrateLegacy() {
        File old = new File(plugin.getDataFolder(), "ignore-data.yml");
        if (!old.exists()) return;
        try {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(old);
            boolean durable = true;
            for (String key : data.getKeys(false)) {
                if (store.get(key) != null) continue;
                List<String> list = data.getStringList(key);
                if (!list.isEmpty() && !dev.chatalyst.db.StoreCas.putIfAbsentDurably(
                        store, key, String.join(",", list))) {
                    durable = false;
                    break;
                }
            }
            if (!durable) {
                plugin.getLogger().warning("Ignore migration did not reach durable storage; "
                        + "ignore-data.yml was preserved and will be retried.");
                return;
            }
            if (old.renameTo(new File(plugin.getDataFolder(), "ignore-data.yml.migrated"))) {
                plugin.getLogger().info("Migrated ignore-data.yml to the storage backend.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not migrate ignore-data.yml: " + t.getMessage());
        }
    }

    /**
     * True si el jugador esta silenciado (y se le avisa). Usado antes de enviar un MP.
     * Los soft mutes no bloquean MP: el shadow mute solo afecta al chat publico.
     */
    public boolean blockedByMute(Player sender) {
        return mutes.notifyIfMuted(sender);
    }

    // ---- Envio ---------------------------------------------------------------

    /** Envia un MP. Devuelve false si no se pudo (target offline lo maneja el comando). */
    public void send(Player sender, Player target, String rawMessage) {
        UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        UUID targetId = target.getUniqueId();
        String targetName = target.getName();
        if (log != null && log.logsPrivate()) {
            log.log("PM", senderName + " -> " + targetName, rawMessage);
        }
        // Nombres MOSTRADOS al jugador: un anonimo que manda /msg NO revela su nombre real al
        // receptor, ni quien escribe A un anonimo ve el suyo. Se muestra el alias del disfraz
        // (escapado, porque va dentro de una plantilla MiniMessage). El spy de staff, en cambio,
        // sigue viendo el nombre REAL (spyExtra), como el resto de spies del plugin.
        String senderShown = dev.chatalyst.hook.Placeholders.isAnonymous(senderId)
                ? dev.chatalyst.nick.NickManager.safeName(sender) : senderName;
        String targetShown = dev.chatalyst.hook.Placeholders.isAnonymous(targetId)
                ? dev.chatalyst.nick.NickManager.safeName(target) : targetName;
        Component msg = PlayerText.styled(sender, rawMessage);
        Map<String, String> extra = Map.of(
                "sender_name", senderShown,
                "receiver_name", targetShown
        );

        // No se entrega si el receptor ignora al emisor o desactivo los MP con /toggle pm.
        // Con chatalyst.bypass.reach el emisor (staff) alcanza igual al receptor.
        boolean reach = sender.hasPermission(REACH_PERMISSION);
        boolean ignoring = isIgnoring(targetId, senderId);
        boolean delivered = reach || (!ignoring && toggles.isEnabled(targetId, ToggleType.PM));

        ChatFormat senderFmt = config.getFormat("pm-sender");
        sender.sendMessage(ChatRenderer.assemble(
                ChatRenderer.prepare(senderFmt, sender, msg, extra), sender));

        if (delivered) {
            ChatFormat receiverFmt = config.getFormat("pm-receiver");
            var prepared = ChatRenderer.prepare(receiverFmt, sender, msg, extra);
            dev.chatalyst.util.FoliaTasks.entity(plugin, target, receiver -> {
                receiver.sendMessage(ChatRenderer.assemble(prepared, receiver));
                replyTarget.put(senderId, targetId);
                replyTarget.put(targetId, senderId);
            });
        } else if (!ignoring && mail != null
                && plugin.getConfig().getBoolean("Mail.Msg_Fallback", false)) {
            // Puente /msg -> correo (opcion del dueño): el receptor apago los MP con
            // /toggle pm, asi que el mensaje va a su buzon en vez de perderse. El correo
            // aplica sus propias reglas (privacidad, bloqueados, spam, cooldown); si algo
            // lo frena, callamos igual que hoy para no revelar nada. /ignore nunca entra
            // aqui: ignorar sigue siendo silencioso y absoluto.
            // El correo lleva el nombre MOSTRADO (alias si el emisor es anonimo), no el real,
            // igual que /inbox send. Plano (sin escapar): el buzon lo escapa al pintarlo.
            String mailFrom = dev.chatalyst.hook.Placeholders.isAnonymous(senderId)
                    ? dev.chatalyst.nick.NickManager.plainName(sender) : senderName;
            var result = mail.send(targetId, senderId, mailFrom, rawMessage);
            if (result == dev.chatalyst.mail.MailManager.SendResult.SENT
                    || result == dev.chatalyst.mail.MailManager.SendResult.SPAM) {
                if (mailNotifier != null) {
                    if (result == dev.chatalyst.mail.MailManager.SendResult.SENT) {
                        mailNotifier.notifyIfOnline(targetId, mailFrom, rawMessage);
                    } else {
                        mailNotifier.notifySpamIfOnline(targetId, mailFrom, rawMessage);
                    }
                }
                sender.sendMessage(Lang.get(sender, "pm.sent-as-mail",
                        "player", targetShown));
            } else if (result == dev.chatalyst.mail.MailManager.SendResult.STORAGE_FAILED) {
                sender.sendMessage(dev.chatalyst.lang.Lang.get(sender, "mail.storage-error"));
            } else if (result == dev.chatalyst.mail.MailManager.SendResult.TOO_LONG) {
                sender.sendMessage(dev.chatalyst.lang.Lang.get(sender, "mail.too-long",
                        "max", mail.maxMessageLength()));
            }
        }

        // Spy: staff con spy activo que no sea emisor ni receptor. A DIFERENCIA del receptor,
        // el spy ve el nombre REAL (herramienta de moderacion, como el spy de contenedores):
        // el staff resuelve identidades a proposito, no las oculta.
        Map<String, String> spyExtra = Map.of(
                "sender_name", dev.chatalyst.util.MM.escape(senderName),
                "receiver_name", dev.chatalyst.util.MM.escape(targetName)
        );
        ChatFormat spyFmt = config.getFormat("spy");
        var spyPrepared = ChatRenderer.prepare(spyFmt, sender, msg, spyExtra);
        for (UUID uuid : spies) {
            if (uuid.equals(senderId) || uuid.equals(targetId)) continue;
            Player spy = plugin.getServer().getPlayer(uuid);
            if (spy != null) dev.chatalyst.util.FoliaTasks.entity(plugin, spy, viewer -> {
                // spyWatches: con /spy <jugador> activo, solo llegan los vigilados. El MP
                // se filtra por AMBAS puntas — vigilar a alguien incluye lo que le escriben.
                if (canSpy(viewer, "pm")
                        && (spyWatches(viewer, senderName) || spyWatches(viewer, targetName))) {
                    viewer.sendMessage(ChatRenderer.assemble(spyPrepared, viewer));
                }
            });
        }
    }

    public Player getReplyTarget(Player p) {
        UUID id = replyTarget.get(p.getUniqueId());
        return id == null ? null : plugin.getServer().getPlayer(id);
    }

    // ---- Ignore --------------------------------------------------------------

    public boolean isIgnoring(Player who, Player other) {
        return isIgnoring(who.getUniqueId(), other.getUniqueId());
    }

    /** Variante por identidad para renderizadores que no pueden tocar otra Entity en Folia. */
    public boolean isIgnoring(UUID who, UUID other) {
        Set<UUID> set = ignored.get(who);
        return set != null && set.contains(other);
    }

    /** Copia de los jugadores que {@code who} ignora (vacia si no ignora a nadie). Para /perfil. */
    public Set<UUID> ignoredBy(UUID who) {
        Set<UUID> set = ignored.get(who);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    /** Devuelve true si ahora lo ignora, false si lo dejo de ignorar. */
    public boolean toggleIgnore(Player who, UUID other) {
        Set<UUID> set = ignored.computeIfAbsent(who.getUniqueId(), u -> ConcurrentHashMap.newKeySet());
        boolean nowIgnoring = set.add(other);
        if (!nowIgnoring) set.remove(other);
        if (!persistIgnore(who.getUniqueId())) {
            // The UI must never report a state that only lived in cache.
            if (nowIgnoring) set.remove(other);
            else set.add(other);
            plugin.getLogger().severe("Could not durably update ignore state for "
                    + who.getUniqueId());
            return !nowIgnoring;
        }
        return nowIgnoring;
    }

    // ---- Spy -----------------------------------------------------------------

    /** Spy on join without having to type /spy every time (chatalyst.spy.autoenable). */
    public static final String SPY_AUTOENABLE_PERMISSION = "chatalyst.spy.autoenable";

    public boolean toggleSpy(Player p) {
        boolean on = spies.add(p.getUniqueId());
        if (!on) spies.remove(p.getUniqueId());
        // Interruptor explicito: el spy pasa a ser decision suya, asi que quitar el ultimo
        // vigilado ya no debe apagarselo por su cuenta.
        spyAutoEnabled.remove(p.getUniqueId());
        rememberSpyChoice(p, on);
        return on;
    }

    /**
     * Activa el spy al entrar, si el jugador tiene el permiso de auto-activacion Y no lo
     * apago el a mano alguna vez.
     *
     * <p>El permiso decia "encendido al entrar" y punto, asi que apagarlo con /spy duraba lo
     * que durase la sesion: a la siguiente entrada volvia solo. Y no hace falta buscarselo
     * para tenerlo — {@code chatalyst.spy.autoenable} nace en {@code false}, pero un comodin
     * como {@code *} o {@code chatalyst.*} en el grupo del dueño lo concede sin que nadie lo
     * pida, asi que el sintoma tipico es "me sale el spy activado y yo no lo active".
     *
     * <p>Ahora el permiso significa "encendido POR DEFECTO": la primera vez se enciende, y si
     * lo apagas se recuerda para siempre. Volver a encenderlo a mano borra la nota y el
     * automatismo vuelve.
     */
    public void autoEnableSpy(Player p) {
        if (!p.hasPermission(SPY_AUTOENABLE_PERMISSION) || !canSpyAny(p)) return;
        if (spyOptOut.get(p.getUniqueId().toString()) != null) return;
        if (spies.add(p.getUniqueId())) {
            p.sendMessage(dev.chatalyst.lang.Lang.get(p, "pm.spy-on"));
        }
    }

    /**
     * Guarda la eleccion explicita, y solo la de quien tiene la auto-activacion: para los
     * demas el spy sigue siendo estado de sesion y no hay nada que recordar.
     */
    private void rememberSpyChoice(Player p, boolean on) {
        if (!p.hasPermission(SPY_AUTOENABLE_PERMISSION)) return;
        String id = p.getUniqueId().toString();
        if (on) {
            spyOptOut.removeDurably(id);
        } else {
            spyOptOut.putDurably(id, "true");
        }
    }

    public boolean isSpy(Player p) {
        return spies.contains(p.getUniqueId());
    }

    // ---- Objetivos del spy --------------------------------------------------------------

    /**
     * Filtro por jugador de cada espia: vacio = spy GLOBAL (ve a todo el mundo, el
     * comportamiento de siempre); con nombres = solo esos jugadores. Se guardan en
     * minusculas porque el emisor puede cambiar de mayusculas y el filtro se consulta por
     * mensaje. Estado de sesion, como el propio spy.
     */
    private final Map<UUID, Set<String>> spyTargets = new ConcurrentHashMap<>();

    /**
     * Espias cuyo spy lo encendio el PRIMER objetivo (no un /spy toggle explicito). Al
     * quitar el ultimo vigilado se les apaga, en vez de dejarlos viendo a TODO el servidor:
     * quien dice "deja de vigilar a este" no esta pidiendo vigilar a los demas.
     */
    private final Set<UUID> spyAutoEnabled = ConcurrentHashMap.newKeySet();

    /** Que paso al alternar un objetivo, para responder con el mensaje correcto. */
    public enum TargetChange {
        /** Ahora lo vigilas (y el spy quedo encendido). */
        ADDED,
        /** Lo quitaste, pero sigues vigilando a otros. */
        REMOVED,
        /** Era el ultimo y el spy lo habia encendido el propio objetivo: se apaga. */
        REMOVED_LAST_OFF,
        /** Era el ultimo, pero el spy ya estaba encendido antes: vuelve a global. */
        REMOVED_LAST_GLOBAL
    }

    /** Nombres vigilados por este espia (vacio = todos). Copia inmutable para mostrar. */
    public Set<String> spyTargets(Player spy) {
        Set<String> targets = spyTargets.get(spy.getUniqueId());
        return targets == null ? Set.of() : Set.copyOf(targets);
    }

    /**
     * Añade o quita un objetivo (toggle). El primer objetivo enciende el spy si estaba
     * apagado (pedir vigilar a alguien y no ver nada seria desconcertante) y, simetrico,
     * quitar el ultimo lo vuelve a apagar — nunca deja al espia mirando a todo el servidor
     * sin haberlo pedido.
     */
    public TargetChange toggleSpyTarget(Player spy, String targetName) {
        UUID id = spy.getUniqueId();
        String key = targetName.toLowerCase(java.util.Locale.ROOT);
        Set<String> targets = spyTargets.computeIfAbsent(id,
                ignored -> ConcurrentHashMap.newKeySet());
        if (targets.add(key)) {
            // El spy se enciende solo si hacia falta; se recuerda para poder deshacerlo.
            if (spies.add(id)) spyAutoEnabled.add(id);
            return TargetChange.ADDED;
        }
        targets.remove(key);
        if (!targets.isEmpty()) return TargetChange.REMOVED;
        spyTargets.remove(id);
        if (spyAutoEnabled.remove(id)) {
            spies.remove(id);
            return TargetChange.REMOVED_LAST_OFF;
        }
        return TargetChange.REMOVED_LAST_GLOBAL;
    }

    /** Vuelve al spy global (sin filtro). True si de verdad habia objetivos que limpiar. */
    public boolean clearSpyTargets(Player spy) {
        // Global explicito: el spy pasa a ser "suyo", ya no se apaga solo al quitar nadie.
        spyAutoEnabled.remove(spy.getUniqueId());
        return spyTargets.remove(spy.getUniqueId()) != null;
    }

    /**
     * True si este espia debe recibir una linea del jugador {@code sourceName}. Lo consultan
     * TODOS los tipos de spy (MP, comandos, carteles, libros, yunques, contenedores).
     */
    public boolean spyWatches(Player spy, String sourceName) {
        Set<String> targets = spyTargets.get(spy.getUniqueId());
        if (targets == null || targets.isEmpty()) return true; // global
        return targets.contains(sourceName.toLowerCase(java.util.Locale.ROOT));
    }

    // ---- Persistencia (Store: cache en memoria + volcado diferido) ------------

    public void loadPlayer(Player p) {
        String raw = store.get(p.getUniqueId().toString());
        if (raw == null || raw.isEmpty()) return;
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        for (String s : raw.split(",")) {
            try {
                set.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignore) {
                // entrada corrupta: la saltamos
            }
        }
        ignored.put(p.getUniqueId(), set);
    }

    /** Relee solo el estado persistente; conserva reply targets y el spy de la sesion. */
    public void reloadPlayer(Player p) {
        ignored.remove(p.getUniqueId());
        loadPlayer(p);
    }

    public void unloadPlayer(Player p) {
        replyTarget.remove(p.getUniqueId());
        spies.remove(p.getUniqueId());
        spyTargets.remove(p.getUniqueId());
        spyAutoEnabled.remove(p.getUniqueId());
        ignored.remove(p.getUniqueId());
    }

    private boolean persistIgnore(UUID who) {
        Set<UUID> set = ignored.get(who);
        if (set == null || set.isEmpty()) {
            return store.removeDurably(who.toString());
        }
        List<String> list = new ArrayList<>();
        for (UUID u : set) list.add(u.toString());
        return store.putDurably(who.toString(), String.join(",", list));
    }

    public void shutdown() {
        store.flushNow();
    }

    /** Aviso simple con MiniMessage (para errores de comando). */
    public static Component note(String mini) {
        return MM.full(mini);
    }
}
