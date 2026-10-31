package dev.chatalyst.nick;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.rules.RuleEngine;
import dev.chatalyst.rules.RuleResult;
import dev.chatalyst.rules.RuleContext;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;


/**
 * /nick <apodo>            Ponerte un apodo.
 * /nick off                Quitarte el apodo.
 * /nick <jugador> <apodo>  (staff) Cambiar el apodo de otro.
 *
 * Con permiso chatalyst.nick.color el apodo admite MiniMessage; sin el, se limpia a texto.
 */
public final class NickCommand implements CommandExecutor {

    private final NickManager nicks;
    private final int maxLength;
    private final RuleEngine rules;
    private final dev.chatalyst.swear.SwearFilter swear;

    public NickCommand(NickManager nicks, int maxLength, RuleEngine rules,
                       dev.chatalyst.swear.SwearFilter swear) {
        this.nicks = nicks;
        this.maxLength = Math.max(1, maxLength);
        this.rules = rules;
        this.swear = swear;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Lang.get(sender, "nick.usage"));
            return true;
        }

        // Forma de staff: /nick <jugador> <apodo...> (acepta nombre real o apodo actual).
        // El apodo son TODAS las palabras restantes, no solo la primera.
        if (args.length >= 2 && sender.hasPermission("chatalyst.nick.others")) {
            Player target = NickManager.online(args[0]);
            if (target != null) {
                if (locked(sender, target)) return true;
                applyOther(sender, target, NickText.joinArguments(args, 1));
                return true;
            }
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "nick.console"));
            return true;
        }
        if (!p.hasPermission("chatalyst.nick")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return true;
        }

        if (locked(p, p)) return true;
        // Un apodo puede llevar espacios ("El Rey"), asi que se unen todas las palabras.
        String requested = NickText.joinArguments(args, 0);
        if (requested.isEmpty()) {
            p.sendMessage(Lang.get(p, "nick.usage"));
            return true;
        }
        // Se compara contra el apodo COMPLETO, no contra la primera palabra: asi
        // "/nick off rey" pide ese apodo en vez de borrar el que ya se tenia.
        if (requested.equalsIgnoreCase("off") || requested.equalsIgnoreCase("reset")) {
            p.sendMessage(nicks.removeNick(p.getUniqueId())
                    ? Lang.get(p, "nick.removed")
                    : storageError(p));
            return true;
        }
        ApplyResult result = apply(p, requested, p.hasPermission("chatalyst.nick.color"),
                p.hasPermission("chatalyst.nick.impersonate"));
        respond(p, p.getName(), result, false);
        return true;
    }

    /**
     * El apodo de quien esta en modo anonimo es su DISFRAZ: tocarlo desde /nick lo rompe.
     * En particular {@code /nick off} borraba el alias aleatorio y dejaba su nombre real a la
     * vista mientras seguia "anonimo" — desenmascarado con un comando que un streamer teclea
     * por costumbre. Se responde con el aviso y se manda a /anonymous off.
     */
    /** Se lee en cada uso para que un reload valga sin reiniciar; ProtectedNames cachea. */
    private static java.util.List<String> protectedNames() {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        return plugin == null ? java.util.List.of()
                : plugin.getConfig().getStringList("Nick.Protected_Names");
    }

    private boolean locked(CommandSender sender, Player target) {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin == null || plugin.getAnonymousManager() == null
                || !plugin.getAnonymousManager().isAnonymous(target.getUniqueId())) {
            return false;
        }
        sender.sendMessage(Lang.get(sender, sender == target
                ? "nick.anonymous-self" : "nick.anonymous-other"));
        return true;
    }

    private ApplyResult apply(Player target, String rawNick, boolean allowColor,
                              boolean canImpersonate) {
        // Anti-spoofing: strip invisible/control characters from the nickname first.
        rawNick = dev.chatalyst.util.Sanitize.stripInvisible(rawNick);
        // Emojis/imagenes de ItemsAdder (:nombre: -> glifo del resource pack), con los
        // permisos por emoji de IA del dueño del nick. Al quedar GUARDADO como glifo, se ve
        // igual en chat, tab, nametag fancy e inbox sin trabajo extra.
        rawNick = dev.chatalyst.hook.ItemsAdderHook.replaceEmojis(target, rawNick);
        // La politica de color y la forma leida del nick viven en NickText: el limite de
        // longitud y el anti-suplantacion de mas abajo se miden sobre 'plain' a proposito.
        NickText text = NickText.of(rawNick, allowColor);
        String nick = text.stored();
        String plain = text.plain();
        if (text.longerThan(maxLength)) {
            return new ApplyResult(ApplyStatus.TOO_LONG, plain);
        }
        // Un apodo hecho solo de etiquetas ("<bold>") se guarda pero no se LEE: el jugador
        // pasaba a hablar sin nombre en el chat, que es peor que suplantar a alguien porque
        // ni siquiera hay a quien reportar.
        if (plain.isBlank()) {
            return new ApplyResult(ApplyStatus.BLOCKED, plain);
        }
        // Reglas (anti-publicidad, palabras prohibidas) sobre el nick, si estan activas.
        if (rules != null && rules.appliesToNicks() && target instanceof Player tp) {
            RuleResult res = rules.check(tp, plain, RuleContext.NICK);
            if (res.cancelled) {
                return new ApplyResult(ApplyStatus.BLOCKED, plain);
            }
        }
        // Filtro de insultos anti-evasion sobre el nick.
        if (swear != null && swear.isEnabled() && swear.apply(plain).matched()) {
            return new ApplyResult(ApplyStatus.BLOCKED, plain);
        }
        // Anti-suplantacion: no dejar usar el nombre real de OTRO jugador, conectado o no.
        // El chequeo offline usa el cache del servidor (sin lookup bloqueante): sin el, se
        // podia tomar el nombre exacto de alguien desconectado (incluso staff) y recibir sus
        // /trade, /mail o acciones de moderacion mientras el real no estuviera.
        // Se prueba la forma leida Y su clave de choque (ver NickText.spoofKey): comparar
        // solo la primera dejaba pasar "Notch " o el ancho completo "Ｎｏｔｃｈ", que se leen
        // como el nombre de otro sin ser la misma cadena. Ambas consultas de Bukkit ignoran
        // mayusculas, asi que la clave en minusculas encuentra igual al jugador real.
        if (!canImpersonate) {
            for (String candidate : java.util.List.of(plain, text.spoofKey())) {
                if (candidate.isEmpty()) continue;
                Player conflict = Bukkit.getPlayerExact(candidate);
                if (conflict != null && !conflict.getUniqueId().equals(target.getUniqueId())) {
                    return new ApplyResult(ApplyStatus.IMPERSONATION, plain);
                }
                org.bukkit.OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(candidate);
                if (known != null && !known.getUniqueId().equals(target.getUniqueId())) {
                    return new ApplyResult(ApplyStatus.IMPERSONATION, plain);
                }
                // Y contra el APODO de los demas, no solo contra su nombre real. Las dos
                // consultas de arriba solo conocen nombres de cuenta, asi que sin esto dos
                // jugadores podian llamarse igual en el chat: el segundo copiaba el apodo
                // del primero y ninguna de las dos preguntas se enteraba. findByDisplay ya
                // compara tambien sin espacios, de modo que "El Rey" cubre "ElRey".
                java.util.UUID sameName = nicks.findByDisplay(candidate);
                if (sameName != null && !sameName.equals(target.getUniqueId())) {
                    return new ApplyResult(ApplyStatus.IMPERSONATION, plain);
                }
                // El usercache del servidor caduca (unas mil entradas, treinta dias): pasado
                // ese plazo el nombre de un jugador de siempre volvia a estar libre para
                // cualquiera. El indice propio no caduca y es el mismo con el que el staff
                // sanciona por nombre a esa gente.
                java.util.UUID remembered = OfflineNameIndex.find(candidate);
                if (remembered != null && !remembered.equals(target.getUniqueId())) {
                    return new ApplyResult(ApplyStatus.IMPERSONATION, plain);
                }
            }
            // Nombres reservados de la config (streamers, staff, marcas). Cubren el hueco
            // que las consultas de Bukkit no pueden: quien nunca ha entrado al servidor no
            // esta en el usercache y hasta ahora se podia suplantar libremente.
            // Menos a su dueño: el Ibai de verdad tiene que poder llamarse Ibai, y el dueño
            // del servidor que se añade a la lista no puede quedarse sin su propio nombre.
            if (ProtectedNames.blocks(protectedNames(), text.spoofKey(), target.getName())) {
                return new ApplyResult(ApplyStatus.IMPERSONATION, plain);
            }
        }
        return new ApplyResult(nicks.setNick(target.getUniqueId(), nick)
                ? ApplyStatus.SUCCESS : ApplyStatus.STORAGE_FAILED, plain);
    }

    private void applyOther(CommandSender actor, Player target, String rawNick) {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        if (plugin == null) {
            actor.sendMessage(storageError(actor));
            return;
        }
        boolean canImpersonate = actor.hasPermission("chatalyst.nick.impersonate");
        String actorName = actor.getName();
        Runnable offline = () -> deliver(plugin, actor,
                current -> current.sendMessage(Lang.get(current, "common.player-offline")));
        FoliaTasks.entity(plugin, target, selected -> {
            String targetName = selected.getName();
            ApplyResult result = apply(selected, rawNick, true, canImpersonate);
            deliver(plugin, actor, current -> {
                respond(current, targetName, result, true);
                if (result.status() == ApplyStatus.SUCCESS
                        && plugin.getStaffSecurityListener() != null
                        && !actorName.equalsIgnoreCase(targetName)) {
                    plugin.getStaffSecurityListener().audit(current, "nick",
                            targetName + " -> " + result.plain());
                }
            });
        }, offline);
    }

    private void respond(CommandSender actor, String targetName, ApplyResult result,
                         boolean other) {
        switch (result.status()) {
            case SUCCESS -> actor.sendMessage(other
                    ? Lang.get(actor, "nick.updated-other", "player", targetName)
                    : Lang.get(actor, "nick.updated"));
            case TOO_LONG -> actor.sendMessage(Lang.get(actor, "nick.too-long", "max", maxLength));
            case BLOCKED -> actor.sendMessage(Lang.get(actor, "nick.blocked"));
            case IMPERSONATION -> actor.sendMessage(Lang.get(actor, "nick.impersonation",
                    "name", result.plain()));
            case STORAGE_FAILED -> actor.sendMessage(storageError(actor));
        }
    }

    private static void deliver(dev.chatalyst.ChatalystPlugin plugin, CommandSender actor,
                                java.util.function.Consumer<CommandSender> action) {
        if (actor instanceof Player player) {
            FoliaTasks.entity(plugin, player, action::accept);
        } else {
            FoliaTasks.global(plugin, () -> action.accept(actor));
        }
    }

    private static net.kyori.adventure.text.Component storageError(CommandSender sender) {
        return Lang.get(sender, "plugin.storage-unavailable");
    }

    private enum ApplyStatus { SUCCESS, TOO_LONG, BLOCKED, IMPERSONATION, STORAGE_FAILED }

    private record ApplyResult(ApplyStatus status, String plain) {
    }
}
