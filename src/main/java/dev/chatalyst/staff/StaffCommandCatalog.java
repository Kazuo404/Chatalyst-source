package dev.chatalyst.staff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fuente unica de las etiquetas de moderacion y del permiso que permite verlas.
 * El registro runtime y el filtro del arbol de comandos consumen este catalogo para
 * que un alias nuevo no quede expuesto a jugadores sin permiso.
 */
public final class StaffCommandCatalog {

    public record Spec(String name, String usage, String description,
                       List<String> aliases, String permission) {

        public Spec {
            aliases = List.copyOf(aliases);
        }
    }

    /** Alias en ambos idiomas: cada herramienta se puede invocar en ingles y espanol. */
    private static final List<Spec> DYNAMIC_COMMANDS = List.of(
            new Spec("staff", "/staff [menu|on|off|players]",
                    "Standalone staff mode and symmetric moderation dashboard",
                    List.of("staffmode", "modmode"), "chatalyst.command.staff"),
            new Spec("kick", "/kick <player> [reason]",
                    "Fancy kick with colors, gradients and named styles", List.of("expulsar"),
                    "chatalyst.command.kick"),
            new Spec("warn", "/warn <player> <reason>",
                    "Warn a player: adds warning points and records history",
                    List.of("amonestar", "advertir"), "chatalyst.command.warn"),
            new Spec("note", "/note <player> <text>",
                    "Attach a staff note to a player (works offline)", List.of("nota"),
                    "chatalyst.staff.notes"),
            new Spec("ban", "/ban <player> [reason]",
                    "Permanently ban a player with a fancy screen", List.of("banear"),
                    "chatalyst.command.ban"),
            new Spec("tempban", "/tempban <player> <time> [reason]",
                    "Temporarily ban a player with a fancy screen", List.of("bantemporal"),
                    "chatalyst.command.ban"),
            new Spec("unban", "/unban <player>",
                    "Remove a Chatalyst or native profile ban",
                    List.of("pardon", "desbanear"), "chatalyst.command.ban"),
            new Spec("banip", "/banip <player|ip> [time] [reason]",
                    "Ban the account and its connection (blocks alts)",
                    List.of("ipban", "banearip"), "chatalyst.command.banip"),
            new Spec("unbanip", "/unbanip <player|ip>",
                    "Remove a connection ban", List.of("ipunban", "desbanearip"),
                    "chatalyst.command.banip"),
            new Spec("freeze", "/freeze <player> [reason]",
                    "Freeze a player for a staff review", List.of("congelar"),
                    "chatalyst.command.freeze"),
            new Spec("unfreeze", "/unfreeze <player>",
                    "Release a player from a staff review", List.of("descongelar"),
                    "chatalyst.command.freeze"),
            new Spec("vanish", "/vanish [player] [on|off]",
                    "Toggle staff vanish", List.of("v", "esfumarse"),
                    "chatalyst.command.vanish"),
            new Spec("invsee", "/invsee <player>",
                    "Review a player's inventory", List.of("verinv"),
                    "chatalyst.command.invsee"),
            new Spec("echest", "/echest <player>",
                    // Sin "ec": es el /ec de EssentialsX que usan los rangos (cofre de ender propio).
                    // Con Command_Priority se lo quitabamos y a un Nova le decia que no existe (2026-10-16).
                    "Review a player's ender chest", List.of("verender"),
                    "chatalyst.command.echest"),
            new Spec("vaultsee", "/vaultsee <player> [number]",
                    "Review a player's PlayerVaultsX vaults, online or offline",
                    List.of("vervaults", "vermochila", "vermochilas"),
                    "chatalyst.command.vaultsee"),
            new Spec("seen", "/seen <player>",
                    "Show when a player was last seen", List.of("visto", "lastseen"),
                    "chatalyst.command.seen"),
            new Spec("check", "/check <player>",
                    "Punishment and status summary for a player", List.of("checar"),
                    "chatalyst.command.check"),
            new Spec("tpto", "/tpto <player>",
                    "Teleport yourself to a player", List.of("goto", "irhacia"),
                    "chatalyst.command.tp"),
            new Spec("clearinventory", "/clearinventory <player> [all|ender]",
                    "Clear a player's inventory, equipped armor and off hand",
                    List.of("clearinv", "ci", "limpiarinv"),
                    "chatalyst.command.clearinventory"),
            new Spec("chestview", "/chestview [on|off]",
                    "Inspect any container read-only (nothing can be taken out)",
                    List.of("vercofre", "inspeccionar"), "chatalyst.command.chestview"),
            new Spec("tphere", "/tphere <player>",
                    "Bring a player to you", List.of("bring", "traer"),
                    "chatalyst.command.tp"),
            // ---- 2026-10-03: consultas e historial de staff (staff/lookup y staff/tools) ----
            new Spec("dupeip", "/dupeip <player|ip>",
                    "Every account that joined from the same connections, online or not",
                    List.of("alts", "cuentas", "multicuentas"), "chatalyst.command.dupeip"),
            new Spec("ipreport", "/ipreport",
                    "Online players sharing a connection", List.of("reporteip"),
                    "chatalyst.command.ipreport"),
            new Spec("iphistory", "/iphistory <player>",
                    "Connections used by an account", List.of("historialip"),
                    "chatalyst.command.iphistory"),
            new Spec("namehistory", "/namehistory <player>",
                    "Names used by an account", List.of("historialnombres", "nombres"),
                    "chatalyst.command.namehistory"),
            new Spec("lastuuid", "/lastuuid <name>",
                    "Last UUID seen with a name", List.of("ultimauuid"),
                    "chatalyst.command.lastuuid"),
            new Spec("checkban", "/checkban <player>",
                    "Active ban of a player", List.of("verban"), "chatalyst.command.checkban"),
            new Spec("checkmute", "/checkmute <player>",
                    "Active mute of a player", List.of("vermute"), "chatalyst.command.checkmute"),
            new Spec("checkwarn", "/checkwarn <player>",
                    "Active warnings of a player", List.of("veravisos"),
                    "chatalyst.command.checkwarn"),
            new Spec("banlist", "/banlist [page]",
                    "Active bans", List.of("listabans", "baneados"), "chatalyst.command.banlist"),
            new Spec("mutelist", "/mutelist [page]",
                    "Active mutes", List.of("listamutes", "silenciados"),
                    "chatalyst.command.mutelist"),
            new Spec("warnlist", "/warnlist [page]",
                    "Active warnings", List.of("listaavisos"), "chatalyst.command.warnlist"),
            new Spec("ipmute", "/ipmute <player|ip> [time] [reason]",
                    "Mute every account of a connection", List.of("silenciarip", "muteip"),
                    "chatalyst.command.ipmute"),
            new Spec("unipmute", "/unipmute <player|ip>",
                    "Remove a connection mute", List.of("desilenciarip", "unmuteip"),
                    "chatalyst.command.ipmute"),
            new Spec("unwarn", "/unwarn <player> [id|last]",
                    "Remove a warning and its points", List.of("quitaraviso", "desamonestar"),
                    "chatalyst.command.unwarn"),
            new Spec("history", "/history <player> [page]",
                    "Punishment history in chat", List.of("historial", "hist"),
                    "chatalyst.command.history"),
            new Spec("staffhistory", "/staffhistory <staff> [page]",
                    "Punishments issued by a staff member", List.of("historialstaff"),
                    "chatalyst.command.staffhistory"),
            new Spec("staffrollback", "/staffrollback <staff> <time> [confirm]",
                    "Undo the punishments a staff member issued in a time window",
                    List.of("revertirstaff"), "chatalyst.command.staffrollback"),
            new Spec("prunehistory", "/prunehistory <player> [time]",
                    "Delete a player's punishment history", List.of("borrarhistorial"),
                    "chatalyst.command.prunehistory"),
            new Spec("lockdown", "/lockdown <reason> | /lockdown end",
                    "Close the network to everyone but staff", List.of("cierre", "cerrarred"),
                    "chatalyst.command.lockdown"),
            new Spec("banstyle", "/banstyle [preview]",
                    "Choose how bans are announced: design, sound, on-screen title and animation",
                    List.of("estiloban", "disenoban"), "chatalyst.command.banstyle"));

    private static final Map<String, String> PERMISSIONS = buildPermissions();

    /**
     * Herramientas que NO se pueden usar sin estar dentro del servidor.
     *
     * <p>No es una lista de "peligrosas", es una lista de imposibles: abren un inventario
     * delante de quien mira, o teletransportan a alguien "hacia ti" — y desde Discord no hay
     * ningun "ti". Ejecutarlas desde fuera no seria arriesgado, seria absurdo.
     *
     * <p>Todo lo demas (banear, expulsar, congelar, silenciar, consultar) le habla al servidor
     * sobre otra persona, y eso la consola lo sabe hacer sola.
     */
    private static final java.util.Set<String> IN_GAME_ONLY = java.util.Set.of(
            "staff", "staffmode", "modmode",
            "vanish", "v", "esfumarse",
            "invsee", "verinv",
            "echest", "verender",
            "vaultsee", "vervaults", "vermochila", "vermochilas",
            "tpto", "goto", "irhacia",
            "tphere", "bring", "traer",
            "chestview", "vercofre", "inspeccionar",
            "banstyle", "estiloban", "disenoban");

    /**
     * Lo que si se puede hacer desde fuera, pero no se puede deshacer.
     *
     * <p>Esta lista existe por una razon distinta a la de arriba, y por eso no se mezclan. Un
     * ban se levanta, un mute caduca, un freeze se suelta: cualquiera de ellos puesto por error
     * desde Discord se corrige con otro mensaje. Vaciar un inventario no — la armadura puesta,
     * la segunda mano y el cofre de ender no vuelven, y no hay comando que los traiga.
     *
     * <p>Una accion irreversible pedida desde un chat, contra alguien a quien no estas viendo,
     * es donde un nombre mal escrito o una cuenta de Discord robada dejan de costar una
     * disculpa. Se hace dentro, mirando.
     */
    private static final java.util.Set<String> IRREVERSIBLE = java.util.Set.of(
            "clearinventory", "clearinv", "ci", "limpiarinv",
            // Borrar historial no se deshace (no hay papelera), y un rollback levanta bans y
            // borra filas de golpe: desde una cuenta de Discord robada seria el arma perfecta.
            "prunehistory", "borrarhistorial",
            "staffrollback", "revertirstaff");

    private StaffCommandCatalog() {
    }

    /** Por que una etiqueta puede o no pedirse sin estar dentro del juego. */
    public enum FromOutside {
        /** Adelante: le habla al servidor sobre otra persona y se puede deshacer. */
        ALLOWED,
        /** No es de la suite de staff; aqui no se decide nada sobre ella. */
        UNKNOWN,
        /** Necesita un "tu" dentro del mundo: abre algo delante de ti, o te mueve. */
        NEEDS_A_PLAYER,
        /** Se podria, pero no se deshace. */
        IRREVERSIBLE
    }

    /** Si esta etiqueta pertenece a la suite de staff (por nombre o por alias). */
    public static boolean isStaffCommand(String label) {
        return permissionFor(label) != null;
    }

    /**
     * Si esto se puede ejecutar sin estar dentro del juego — desde la consola o desde Discord —
     * y, cuando no, por que no.
     *
     * @return {@link FromOutside#UNKNOWN} para lo que no es un comando de staff: quien pregunte
     *         por una etiqueta desconocida no debe recibir un "adelante".
     */
    public static FromOutside fromOutside(String label) {
        if (!isStaffCommand(label)) return FromOutside.UNKNOWN;
        String key = label.toLowerCase(Locale.ROOT);
        if (IN_GAME_ONLY.contains(key)) return FromOutside.NEEDS_A_PLAYER;
        if (IRREVERSIBLE.contains(key)) return FromOutside.IRREVERSIBLE;
        return FromOutside.ALLOWED;
    }

    /** Atajo de {@link #fromOutside(String)} para quien solo necesita el si o el no. */
    public static boolean consoleSafe(String label) {
        return fromOutside(label) == FromOutside.ALLOWED;
    }

    /** Solo para tests: que etiquetas se consideran imposibles fuera del juego. */
    static java.util.Set<String> inGameOnly() {
        return IN_GAME_ONLY;
    }

    /** Solo para tests: que etiquetas se niegan por no poder deshacerse. */
    static java.util.Set<String> irreversible() {
        return IRREVERSIBLE;
    }

    static List<Spec> dynamicCommands() {
        return DYNAMIC_COMMANDS;
    }

    /** Devuelve null para una etiqueta que no pertenece a la suite de staff. */
    public static String permissionFor(String label) {
        if (label == null) return null;
        return PERMISSIONS.get(label.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> buildPermissions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Spec spec : DYNAMIC_COMMANDS) {
            putUnique(out, spec.name(), spec.permission());
            for (String alias : spec.aliases()) {
                putUnique(out, alias, spec.permission());
            }
        }
        // Comandos de staff declarados fuera del registro dinamico.
        add(out, "chatalyst.command.players", "players", "playerlist", "list", "jugadores");
        add(out, "chatalyst.command.log", "chatlog", "clog");
        add(out, "chatalyst.command.containerlog", "containerlog", "contlog", "cofres");
        add(out, "chatalyst.command.combatlog", "combatlog", "pvplog", "combate");
        add(out, "chatalyst.command.mute", "mute", "mutear", "silenciar",
                "unmute", "desmutear", "desilenciar");
        return Map.copyOf(out);
    }

    private static void add(Map<String, String> out, String permission, String... labels) {
        for (String label : labels) putUnique(out, label, permission);
    }

    private static void putUnique(Map<String, String> out, String label, String permission) {
        String previous = out.putIfAbsent(label.toLowerCase(Locale.ROOT), permission);
        if (previous != null && !previous.equals(permission)) {
            throw new IllegalStateException("Conflicting staff command permission for " + label);
        }
    }
}
