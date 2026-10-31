package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.staff.AltTracker;
import dev.chatalyst.staff.BanRecord;
import dev.chatalyst.staff.IpBanManager;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Una cuenta tal como la ve el staff en /dupeip e /ipreport: el nombre coloreado por su
 * estado, un hover con todo lo que importa (cuando entro, si esta baneada o silenciada y por
 * que) y un clic que abre su /check.
 *
 * <p>Se construye en dos tiempos a proposito: {@link #gather} lee el Store (fuera del hilo del
 * servidor) y {@link #render} decide "conectado" en el hilo de quien mira, que es donde se
 * sabe si puede ver un vanish o un anonimo.
 */
final class AccountView {

    /** Lo que se sabe de una cuenta, leido del Store compartido. */
    record Facts(UUID id, String name, long firstSeen, long lastSeen, BanRecord ban,
                 IpBanManager.IpBan ipBan, Mute mute, int sharedConnections) {
    }

    private AccountView() {
    }

    /**
     * Lee bans, baneos de conexion y mutes de la cuenta. {@code ipBanCache} evita releer el
     * mismo hash cuando varias cuentas comparten conexion. Llamar fuera del hilo del servidor.
     */
    static Facts gather(StaffModerationModule module, AltTracker.Account account,
                        Map<String, IpBanManager.IpBan> ipBanCache) {
        BanRecord ban = module.bans().get(account.id());
        IpBanManager.IpBan ipBan = null;
        if (module.ipBans() != null) {
            for (var connection : module.alts().connections(account.id())) {
                IpBanManager.IpBan found = ipBanCache.computeIfAbsent(connection.key(),
                        hash -> module.ipBans().get(hash));
                if (found != null) {
                    ipBan = found;
                    break;
                }
            }
        }
        var mutes = module.plugin().getMuteManager();
        Mute mute = mutes == null ? null : mutes.getMute(account.id());
        String name = account.name() == null || account.name().isBlank()
                ? LookupSupport.nameOf(module, account.id()) : account.name();
        return new Facts(account.id(), name, account.firstSeen(), account.lastSeen(), ban,
                ipBan, mute, account.sharedHashes().size());
    }

    static AccountState state(CommandSender viewer, Facts facts) {
        return AccountState.of(LookupSupport.visibleOnline(viewer, facts.id()),
                facts.ban() != null, facts.ipBan() != null);
    }

    /** El nombre coloreado, con hover y clic a /check. Hilo de quien mira. */
    static Component render(StaffModerationModule module, CommandSender viewer, Facts facts) {
        AccountState state = state(viewer, facts);
        String key = switch (state) {
            case ONLINE -> "staff-lookup.name-online";
            case OFFLINE -> "staff-lookup.name-offline";
            case BANNED -> "staff-lookup.name-banned";
            case IP_BANNED -> "staff-lookup.name-ipbanned";
        };
        String safeName = MM.escape(facts.name());
        return Lang.get(viewer, key, "name", safeName)
                .hoverEvent(HoverEvent.showText(hover(module, viewer, facts, state)))
                .clickEvent(ClickEvent.runCommand("/chatalyst:check " + facts.name()));
    }

    private static Component hover(StaffModerationModule module, CommandSender viewer,
                                   Facts facts, AccountState state) {
        List<Component> lines = new ArrayList<>(lines(module, viewer, facts, state));
        lines.add(Lang.get(viewer, "staff-lookup.hover-click", "name", MM.escape(facts.name())));
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    }

    /** Lo que se sabe de la cuenta, linea a linea (hover del chat y lore del menu). */
    static List<Component> lines(StaffModerationModule module, CommandSender viewer,
                                 Facts facts, AccountState state) {
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.get(viewer, "staff-lookup.hover-name", "name", MM.escape(facts.name())));
        lines.add(state == AccountState.ONLINE || (state == AccountState.BANNED
                && LookupSupport.visibleOnline(viewer, facts.id()))
                ? Lang.get(viewer, "staff-lookup.hover-online")
                : Lang.get(viewer, "staff-lookup.hover-last-seen",
                        "ago", LookupSupport.ago(viewer, facts.lastSeen())));
        if (facts.firstSeen() > 0) {
            lines.add(Lang.get(viewer, "staff-lookup.hover-first-seen",
                    "date", LookupSupport.date(facts.firstSeen())));
        }
        if (facts.ban() != null) {
            lines.add(Lang.get(viewer, "staff-lookup.hover-banned",
                    "staff", MM.escape(facts.ban().staff()),
                    "remaining", LookupSupport.remaining(viewer, facts.ban().until()),
                    "reason", LookupSupport.reason(module, viewer, facts.ban().reason())));
        }
        if (facts.ipBan() != null) {
            lines.add(Lang.get(viewer, "staff-lookup.hover-ipbanned",
                    "staff", MM.escape(facts.ipBan().staff()),
                    "remaining", LookupSupport.remaining(viewer, facts.ipBan().until()),
                    "reason", LookupSupport.reason(module, viewer, facts.ipBan().reason())));
        }
        if (facts.mute() != null) {
            lines.add(Lang.get(viewer, "staff-lookup.hover-muted",
                    "staff", MM.escape(facts.mute().by),
                    "remaining", LookupSupport.remaining(viewer, facts.mute().until),
                    "reason", LookupSupport.reason(module, viewer, facts.mute().reason)));
        }
        if (facts.sharedConnections() > 1) {
            lines.add(Lang.get(viewer, "staff-lookup.hover-shared",
                    "count", facts.sharedConnections()));
        }
        return lines;
    }

    /** Une componentes con el separador de la lista (", "). */
    static Component join(CommandSender viewer, List<Component> parts) {
        return Component.join(net.kyori.adventure.text.JoinConfiguration.separator(
                Lang.get(viewer, "staff-lookup.separator")), parts);
    }
}
