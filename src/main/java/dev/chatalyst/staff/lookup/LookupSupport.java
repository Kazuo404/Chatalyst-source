package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lo comun de las consultas del staff: permisos, resolver a un jugador este o no conectado
 * (en cualquier servidor de la red), sacar la E/S del hilo del servidor y volver a el para
 * contestar, y la regla de cuando se puede decir que alguien "esta conectado".
 */
public final class LookupSupport {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    /** Una cuenta resuelta: su UUID y el nombre con el que se la conoce ahora. */
    record Target(UUID id, String name) {
    }

    private LookupSupport() {
    }

    /** Suite activa y permiso del comando; si falla, ya se le dijo al staff por que. */
    static boolean allowed(StaffModerationModule module, CommandSender sender, String command) {
        if (!module.enabled()) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.disabled"));
            return false;
        }
        if (StaffAccess.has(sender, "chatalyst.command." + command)) return true;
        sender.sendMessage(Lang.get(sender, "common.no-permission"));
        return false;
    }

    static void usage(CommandSender sender, String label, String args) {
        sender.sendMessage(Lang.get(sender, "staff-lookup.usage",
                "command", MM.escape(label), "args", MM.escape(args)));
    }

    /** Trabajo con E/S (Store compartido, MySQL) fuera del hilo del servidor. */
    static void async(StaffModerationModule module, Runnable work) {
        try {
            module.plugin().getServer().getAsyncScheduler().runNow(module.plugin(), task -> {
                try {
                    work.run();
                } catch (RuntimeException failure) {
                    // Sin esto el staff se quedaba sin respuesta y sin saber por que.
                    module.plugin().getLogger().log(java.util.logging.Level.WARNING,
                            "Staff lookup failed", failure);
                }
            });
        } catch (RuntimeException rejected) {
            // El plugin se esta apagando: no hay hilo al que volver.
        }
    }

    /** Vuelve al hilo de quien pregunto (su region en Folia, o el global para la consola). */
    static void reply(StaffModerationModule module, CommandSender sender,
                      Consumer<CommandSender> action) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(module.plugin(), player, action::accept);
        } else {
            FoliaTasks.global(module.plugin(), () -> action.accept(sender));
        }
    }

    /**
     * Resuelve nombre, apodo de un conectado o UUID escrita. Llamar FUERA del hilo del
     * servidor: el indice de nombres de la red vive en el Store.
     *
     * <p>Un apodo de /anonymous no lleva a la cuenta real para quien no tiene
     * {@code chatalyst.command.realname}: si no, cualquier consulta seria un desenmascarador.
     */
    static Target resolve(StaffModerationModule module, CommandSender viewer, String input) {
        if (input == null || input.isBlank()) return null;
        try {
            UUID literal = UUID.fromString(input.trim());
            String name = module.alts().lastKnownName(literal);
            if (name != null) return new Target(literal, name);
        } catch (IllegalArgumentException notAUuid) {
            // nombre normal
        }
        Player online = NickManager.online(input);
        if (online != null) {
            boolean byAlias = !online.getName().equalsIgnoreCase(input);
            if (byAlias && anonymous(online.getUniqueId())
                    && !StaffAccess.has(viewer, "chatalyst.command.realname")) {
                return null;
            }
            return new Target(online.getUniqueId(), online.getName());
        }
        UUID indexed = module.alts().uuidOf(input);
        if (indexed != null) {
            String name = module.alts().lastKnownName(indexed);
            return new Target(indexed, name == null ? input : name);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            return new Target(cached.getUniqueId(),
                    cached.getName() == null ? input : cached.getName());
        }
        return null;
    }

    /** Nombre de una cuenta para listas: el ultimo visto en la red, o un trozo de su UUID. */
    static String nameOf(StaffModerationModule module, UUID id) {
        String name = module.alts().lastKnownName(id);
        return name == null ? id.toString().substring(0, 8) : name;
    }

    /**
     * Si este espectador puede saber que la cuenta esta conectada. Un vanish o un anonimo se
     * cuentan como desconectados para quien no tiene permiso: una consulta de multicuentas no
     * puede ser la rendija por la que se ve quien esta dentro.
     */
    static boolean visibleOnline(CommandSender viewer, UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online == null) return false;
        if (dev.chatalyst.util.Vanish.isVanished(online) && !StaffAccess.canSeeVanished(viewer)) {
            return false;
        }
        return !anonymous(id) || StaffAccess.has(viewer, "chatalyst.command.realname");
    }

    static boolean anonymous(UUID id) {
        return dev.chatalyst.hook.Placeholders.isAnonymous(id);
    }

    /** Ver IPs en claro. Sin el, cada conexion es el identificador corto de su hash. */
    static final String IP_PERMISSION = "chatalyst.staff.ip";

    static boolean seesIps(CommandSender viewer) {
        return StaffAccess.has(viewer, IP_PERMISSION);
    }

    /**
     * Como se nombra una conexion ante este espectador: su IP si tiene permiso y se conoce
     * (las registradas antes de guardar IPs solo tienen hash), si no el id corto. Escapado.
     */
    static String connection(CommandSender viewer, String hash, String ip) {
        return MM.escape(ConnectionLabel.of(hash, ip, seesIps(viewer)));
    }

    static String date(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /** "dd/MM/yyyy HH:mm", o "desconocida" para datos anteriores al registro con fechas. */
    static String dateOrUnknown(CommandSender viewer, long epochMillis) {
        return epochMillis <= 0 ? Lang.raw(viewer, "staff-lookup.unknown-date") : date(epochMillis);
    }

    static String ago(CommandSender viewer, long epochMillis) {
        if (epochMillis <= 0) return Lang.raw(viewer, "staff-lookup.unknown-date");
        return TimeUtil.formatElapsed(System.currentTimeMillis() - epochMillis);
    }

    /** Tiempo que le queda a un castigo, localizado ("permanente" / "expirado"). */
    static String remaining(CommandSender viewer, long until) {
        if (until == TimeUtil.PERMANENT) {
            return Lang.raw(viewer, "staff-moderation.duration-permanent");
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) return Lang.raw(viewer, "staff-moderation.remaining-expired");
        return TimeUtil.formatElapsed(left);
    }

    static String expires(CommandSender viewer, long until) {
        return until == TimeUtil.PERMANENT
                ? Lang.raw(viewer, "staff-moderation.expires-never") : date(until);
    }

    /** Motivo guardado, en texto plano y escapado (token default en el idioma del lector). */
    static String reason(StaffModerationModule module, CommandSender viewer, String stored) {
        if (stored == null || stored.isBlank()) return "-";
        return MM.escape(module.formatter().plainReason(viewer, stored));
    }

    /** Nombres de los conectados para autocompletar, con las mismas reglas que /check. */
    /** Tope de sugerencias: el cliente no pinta mas y la lista de vistos puede ser larga. */
    private static final int MAX_SUGGESTIONS = 40;

    /**
     * Conectados (como en {@link #onlineNames}) y despues los que ya jugaron alguna vez, para
     * los comandos que funcionan igual sin que el jugador este en linea. Un anonimo conectado
     * nunca sale por su nombre real ni siquiera en la parte de desconectados.
     */
    public static List<String> knownNames(CommandSender viewer, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(onlineNames(viewer, prefix));
        if (lower.isEmpty() || out.size() >= MAX_SUGGESTIONS) return out;
        java.util.Set<String> taken = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        taken.addAll(out);
        boolean realNames = StaffAccess.has(viewer, "chatalyst.command.realname");
        List<String> offline = new ArrayList<>();
        for (OfflinePlayer known : Bukkit.getOfflinePlayers()) {
            String name = known.getName();
            if (name == null || !name.toLowerCase(Locale.ROOT).startsWith(lower)) continue;
            if (known.isOnline()) continue; // los conectados ya se decidieron arriba
            if (!realNames && anonymous(known.getUniqueId())) continue;
            if (taken.add(name)) offline.add(name);
        }
        offline.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : offline) {
            if (out.size() >= MAX_SUGGESTIONS) break;
            out.add(name);
        }
        return out;
    }

    static List<String> onlineNames(CommandSender viewer, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        boolean realNames = StaffAccess.has(viewer, "chatalyst.command.realname");
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (dev.chatalyst.util.Vanish.isVanished(online)
                    && !StaffAccess.canSeeVanished(viewer)) continue;
            String shown = !realNames && anonymous(online.getUniqueId())
                    ? NickManager.suggestedName(online) : online.getName();
            if (shown != null && shown.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(shown);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
