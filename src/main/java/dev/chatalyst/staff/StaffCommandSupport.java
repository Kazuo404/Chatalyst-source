package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Vanish;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Shared, side-effect-light command parsing helpers. */
final class StaffCommandSupport {

    private StaffCommandSupport() {
    }

    static boolean available(StaffModerationModule module, CommandSender sender) {
        if (module.enabled()) return true;
        sender.sendMessage(Lang.get(sender, "staff-moderation.disabled"));
        return false;
    }

    static boolean require(CommandSender sender, String permission) {
        if (StaffAccess.has(sender, permission)) return true;
        sender.sendMessage(Lang.get(sender, "common.no-permission"));
        return false;
    }

    static boolean unknown(CommandSender sender, String input) {
        sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", MM.escape(input)));
        return true;
    }

    static String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    static OfflinePlayer resolveBanTarget(StaffModerationModule module, String input) {
        OfflinePlayer resolved = NickManager.resolveOffline(input);
        if (resolved != null) return resolved;
        for (Map.Entry<UUID, BanRecord> entry : module.bans().snapshot().entrySet()) {
            if (entry.getValue().playerName().equalsIgnoreCase(input)) {
                return Bukkit.getOfflinePlayer(entry.getKey());
            }
        }
        return null;
    }

    /**
     * Nombres para autocompletar el objetivo. A un anonimo se le ofrece por su alias (los
     * comandos de sancion resuelven apodos), y su cuenta solo a quien tiene /realname: el
     * completer era la forma mas rapida de desenmascarar a alguien escribiendo "/kick ".
     */
    static List<String> onlineNames(CommandSender viewer) {
        List<String> names = new ArrayList<>();
        boolean realNames = StaffAccess.has(viewer, "chatalyst.command.realname");
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        var anonymous = plugin == null ? null : plugin.getAnonymousManager();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (Vanish.isVanished(online) && !StaffAccess.canSeeVanished(viewer)) continue;
            if (!realNames && anonymous != null && anonymous.isAnonymous(online.getUniqueId())) {
                String alias = NickManager.suggestedName(online);
                if (alias != null && !alias.isBlank()) names.add(alias);
                continue;
            }
            names.add(online.getName());
        }
        return names;
    }

    /**
     * Tiempo restante LOCALIZADO para mostrar al viewer: {@code TimeUtil.formatRemaining}
     * devuelve "permanente"/"expirado" fijos y un cliente en ingles los veria en español.
     */
    static String remaining(CommandSender viewer, long until) {
        if (until == dev.chatalyst.util.TimeUtil.PERMANENT) {
            return Lang.raw(viewer, "staff-moderation.duration-permanent");
        }
        long ms = until - System.currentTimeMillis();
        if (ms <= 0) return Lang.raw(viewer, "staff-moderation.remaining-expired");
        return dev.chatalyst.util.TimeUtil.formatElapsed(ms);
    }

    static List<String> prefix(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
