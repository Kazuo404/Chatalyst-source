package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lo que el staff ve MIENTRAS escribe un castigo. El cliente pide sugerencias a cada tecla,
 * asi que cada peticion es tambien el momento de decirle, en la barra de accion, por que
 * argumento va y que espera ("/tempban Pepe ‹tiempo› [motivo]  » 30m, 12h, 7d…"). Y si
 * escribe el comando a secas, la chuleta de todos los castigos con clic para rellenarlos.
 */
final class PunishmentSyntax {

    /** Un hueco del comando. El id elige los textos de lang (syntax-arg.* / syntax-help.*). */
    enum Arg {
        PLAYER("player"), TARGET("target"), TIME("time"), TIME_OPTIONAL("time-opt"),
        REASON("reason"), REASON_OPTIONAL("reason-opt");

        final String id;

        Arg(String id) {
            this.id = id;
        }

        boolean isTime() {
            return this == TIME || this == TIME_OPTIONAL;
        }

        boolean isReason() {
            return this == REASON || this == REASON_OPTIONAL;
        }
    }

    static final Map<String, List<Arg>> SYNTAX = Map.of(
            "ban", List.of(Arg.PLAYER, Arg.TIME_OPTIONAL, Arg.REASON_OPTIONAL),
            "tempban", List.of(Arg.PLAYER, Arg.TIME, Arg.REASON_OPTIONAL),
            "banip", List.of(Arg.TARGET, Arg.TIME_OPTIONAL, Arg.REASON_OPTIONAL),
            "unban", List.of(Arg.PLAYER),
            "unbanip", List.of(Arg.TARGET),
            "kick", List.of(Arg.PLAYER, Arg.REASON_OPTIONAL),
            "warn", List.of(Arg.PLAYER, Arg.REASON));

    /** Orden de la chuleta: de lo mas grave a lo mas leve, y cada quitar junto a su poner. */
    static final List<String> HELP_ORDER = List.of(
            "ban", "tempban", "banip", "unban", "unbanip", "kick", "warn");

    static final List<String> DURATIONS = List.of(
            "30m", "1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d");

    private PunishmentSyntax() {
    }

    /**
     * En que hueco cae el argumento que se esta escribiendo (el ultimo de {@code args}).
     * {@code -s} no ocupa hueco. Un tiempo opcional que no parece tiempo se salta: lo que se
     * escribio ahi ya es el motivo ("/ban Pepe hacks").
     */
    static Arg current(List<Arg> syntax, String[] args) {
        List<String> tokens = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            boolean last = i == args.length - 1;
            if (!last && isSilentFlag(args[i])) continue;
            tokens.add(args[i]);
        }
        int slot = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (slot >= syntax.size()) return syntax.get(syntax.size() - 1);
            Arg arg = syntax.get(slot);
            boolean last = i == tokens.size() - 1;
            if (arg == Arg.TIME_OPTIONAL) {
                String token = tokens.get(i);
                boolean timeLike = token.isEmpty() || Character.isDigit(token.charAt(0))
                        || TimeUtil.looksLikeDuration(token) || "perm".startsWith(
                        token.toLowerCase(Locale.ROOT));
                if (!timeLike && slot + 1 < syntax.size()) {
                    slot++;
                    arg = syntax.get(slot);
                }
            }
            if (last) return arg;
            if (!arg.isReason()) slot++; // el motivo se traga el resto de palabras
        }
        return syntax.get(Math.min(slot, syntax.size() - 1));
    }

    /** Sugerencias para el hueco actual; la lista de nombres la pone quien llama. */
    static List<String> suggestions(CommandSender sender, String command, String[] args,
                                    List<String> names) {
        List<Arg> syntax = SYNTAX.get(command);
        if (syntax == null || args.length == 0) return List.of();
        String typing = args[args.length - 1];
        Arg arg = current(syntax, args);
        Set<String> out = new LinkedHashSet<>();
        switch (arg) {
            case PLAYER, TARGET -> out.addAll(names);
            case TIME -> out.addAll(DURATIONS);
            case TIME_OPTIONAL -> {
                out.addAll(DURATIONS);
                out.add("perm");
                out.addAll(reasons(sender));
            }
            case REASON, REASON_OPTIONAL -> out.addAll(reasons(sender));
        }
        if (arg != Arg.PLAYER && arg != Arg.TARGET && !hasSilentFlag(args)) out.add("-s");
        return StaffCommandSupport.prefix(typing, List.copyOf(out));
    }

    /** Barra de accion con el comando armado hasta aqui y que va en el hueco actual. */
    static void hint(CommandSender sender, String command, String label, String[] args) {
        if (!(sender instanceof Player player)) return;
        List<Arg> syntax = SYNTAX.get(command);
        if (syntax == null) return;
        Arg active = args.length == 0 ? syntax.get(0) : current(syntax, args);
        StringBuilder line = new StringBuilder(Lang.raw(player, "staff-moderation.syntax-command",
                "label", MM.escape(label)));
        for (Arg arg : syntax) {
            line.append(' ').append(Lang.raw(player, arg == active
                            ? "staff-moderation.syntax-current" : "staff-moderation.syntax-other",
                    "arg", MM.escape(Lang.raw(player, "staff-moderation.syntax-arg." + arg.id))));
        }
        if (syntax.size() > 1) {
            line.append(' ').append(Lang.raw(player, "staff-moderation.syntax-silent"));
        }
        line.append(Lang.raw(player, "staff-moderation.syntax-help-separator"))
                .append(Lang.raw(player, "staff-moderation.syntax-help." + active.id));
        player.sendActionBar(MM.full(line.toString()));
    }

    /** La chuleta: solo los castigos que este staff puede usar, cada uno con clic. */
    static void help(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "staff-moderation.punish-help.header"));
        for (String command : HELP_ORDER) {
            String permission = StaffCommandCatalog.permissionFor(command);
            if (permission != null && !StaffAccess.has(sender, permission)) continue;
            sender.sendMessage(Lang.get(sender, "staff-moderation.punish-help." + command));
        }
        for (String line : Lang.rawList(sender, "staff-moderation.punish-help.footer")) {
            sender.sendMessage(MM.full(line));
        }
    }

    private static List<String> reasons(CommandSender sender) {
        List<String> configured = Lang.rawList(sender, "staff-moderation.reason-suggestions");
        List<String> out = new ArrayList<>(configured.size());
        for (String reason : configured) {
            // Una sugerencia es UNA palabra: el tabulador del cliente no completa espacios.
            String word = MM.strip(reason).trim().replace(' ', '-');
            if (!word.isEmpty()) out.add(word);
        }
        return out;
    }

    private static boolean isSilentFlag(String arg) {
        return arg != null && (arg.equalsIgnoreCase("-s") || arg.equalsIgnoreCase("-silent"));
    }

    private static boolean hasSilentFlag(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (isSilentFlag(args[i])) return true;
        }
        return false;
    }
}
