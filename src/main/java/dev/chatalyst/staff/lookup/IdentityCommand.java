package dev.chatalyst.staff.lookup;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.AltImport;
import dev.chatalyst.staff.AltTracker;
import dev.chatalyst.staff.IpBanManager;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identidad y multicuentas: /dupeip (alias /alts, /cuentas,
 * /multicuentas), /ipreport, /iphistory, /namehistory y /lastuuid.
 *
 * <p>Funciona este o no conectado el jugador, y vale toda la red: el registro de conexiones
 * vive en el Store compartido, asi que una cuenta vista solo en el Lobby aparece igual
 * consultando desde el Survival. La IP en claro solo la ve quien tiene
 * {@code chatalyst.staff.ip} (y solo el puede pasar una IP a /dupeip); el resto ve cada
 * conexion como un identificador corto de su hash.
 */
public final class IdentityCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    public IdentityCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!LookupSupport.allowed(module, sender, name)) return true;
        if (name.equals("ipreport")) {
            ipReport(sender);
            return true;
        }
        if (args.length < 1) {
            LookupSupport.usage(sender, label, name.equals("lastuuid") ? "<nombre>"
                    : name.equals("dupeip") && LookupSupport.seesIps(sender)
                    ? "<jugador|ip>" : "<jugador>");
            return true;
        }
        switch (name) {
            case "dupeip" -> {
                if (args[0].equalsIgnoreCase("importar") || args[0].equalsIgnoreCase("import")) {
                    importOld(sender);
                } else {
                    boolean deep = false;
                    boolean text = false;
                    for (int i = 1; i < args.length; i++) {
                        String word = args[i].toLowerCase(Locale.ROOT);
                        if (DEEP_WORDS.contains(word)) deep = true;
                        if (TEXT_WORDS.contains(word)) text = true;
                    }
                    // Un jugador ve la ficha en un menu; la consola (o "chat") el texto de siempre.
                    if (sender instanceof Player player && !text
                            && !ConnectionLabel.isIpLiteral(args[0])) {
                        openMenu(player, args[0], deep);
                    } else {
                        dupeIp(sender, args[0], deep);
                    }
                }
            }
            case "iphistory" -> ipHistory(sender, args[0]);
            case "namehistory" -> nameHistory(sender, args[0]);
            case "lastuuid" -> lastUuid(sender, args[0]);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- /dupeip ---------------------------------------------------------------------------

    /** Segunda palabra que pide seguir tambien las cuentas de las cuentas. */
    private static final java.util.Set<String> DEEP_WORDS =
            java.util.Set.of("profundo", "deep", "-d", "todo", "all");
    /** Palabras que piden la respuesta en el chat en vez del menu. */
    private static final java.util.Set<String> TEXT_WORDS =
            java.util.Set.of("chat", "texto", "text", "-t");

    private void openMenu(Player viewer, String input, boolean deep) {
        LookupSupport.async(module, () -> {
            LookupSupport.Target target = LookupSupport.resolve(module, viewer, input);
            if (target == null) {
                unknown(viewer, input);
                return;
            }
            IdentityMenu.open(module, viewer, target.id(), target.name(), deep, null);
        });
    }

    /** Tope del modo profundo: una conexion publica (cibercafe, datos moviles) lo dispararia. */
    private static final int DEEP_MAX_ACCOUNTS = 60;

    private void dupeIp(CommandSender sender, String input, boolean deep) {
        if (ConnectionLabel.isIpLiteral(input)) {
            dupeIpByAddress(sender, input.trim());
            return;
        }
        LookupSupport.async(module, () -> {
            LookupSupport.Target target = LookupSupport.resolve(module, sender, input);
            if (target == null) {
                unknown(sender, input);
                return;
            }
            AltTracker alts = module.alts();
            var connections = alts.connections(target.id());
            Map<String, IpBanManager.IpBan> ipBanCache = new ConcurrentHashMap<>();
            List<AccountView.Facts> facts = new ArrayList<>();
            Map<UUID, AltTracker.Account> found = new LinkedHashMap<>();
            for (AltTracker.Account account : alts.accountsSharingWith(target.id())) {
                found.putIfAbsent(account.id(), account);
            }
            if (deep) {
                // Segundo grado: las cuentas que comparten conexion con las cuentas encontradas.
                for (UUID first : List.copyOf(found.keySet())) {
                    if (first.equals(target.id())) continue;
                    for (AltTracker.Account account : alts.accountsSharingWith(first)) {
                        if (found.size() >= DEEP_MAX_ACCOUNTS) break;
                        found.putIfAbsent(account.id(), account);
                    }
                }
            }
            for (AltTracker.Account account : found.values()) {
                facts.add(AccountView.gather(module, account, ipBanCache));
            }
            boolean showIps = LookupSupport.seesIps(sender);
            List<String> ids = connections.stream().limit(5)
                    .map(seen -> ConnectionLabel.of(seen.key(),
                            showIps ? alts.ipOf(seen.key()) : null, showIps))
                    .toList();
            LookupSupport.reply(module, sender, viewer -> {
                String shownName = MM.escape(target.name());
                viewer.sendMessage(connections.isEmpty()
                        ? Lang.get(viewer, "staff-lookup.dupeip-start-no-connection",
                                "player", shownName)
                        : Lang.get(viewer, "staff-lookup.dupeip-start", "player", shownName,
                                "connections", connections.size(),
                                "ids", MM.escape(String.join(", ", ids))
                                        + (connections.size() > ids.size() ? "…" : "")));
                sendAccounts(viewer, facts, target.id());
                viewer.sendMessage(facts.size() <= 1
                        ? Lang.get(viewer, "staff-lookup.dupeip-alone", "player", shownName)
                        : Lang.get(viewer, deep ? "staff-lookup.dupeip-deep-footer"
                                : "staff-lookup.dupeip-footer", "count", facts.size()));
            });
        });
    }

    /**
     * /dupeip importar: vuelve a pasar los logs y Essentials de ESTE servidor al registro.
     * Solo con permiso de ver IPs, porque lo que mete son IPs. Al arrancar ya se hace solo
     * una vez por servidor; esto es para repetirlo (p. ej. tras traer logs viejos).
     */
    private void importOld(CommandSender sender) {
        if (!LookupSupport.seesIps(sender)) {
            sender.sendMessage(Lang.get(sender, "staff-lookup.ip-no-permission"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "staff-lookup.import-start"));
        LookupSupport.async(module, () -> {
            try {
                AltImport.Result r = module.alts().importHistory(true);
                module.plugin().getLogger().info(sender.getName() + " imported old connections: "
                        + r.logins() + " logins, " + r.accounts() + " accounts");
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-lookup.import-done", "logins", r.logins(), "files", r.files(),
                        "accounts", r.accounts(), "connections", r.connections())));
            } catch (RuntimeException failed) {
                LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(Lang.get(viewer,
                        "staff-lookup.import-failed", "error", MM.escape(String.valueOf(failed.getMessage())))));
            }
        });
    }

    /**
     * /dupeip &lt;ip&gt;: cuentas vistas desde esa IP. Solo con permiso de ver IPs — si no,
     * escribir IPs a ciegas serviria para averiguar la de cualquiera. Se busca por el hash, asi
     * que tambien encuentra las cuentas registradas antes de guardar IPs.
     */
    private void dupeIpByAddress(CommandSender sender, String ip) {
        if (!LookupSupport.seesIps(sender)) {
            sender.sendMessage(Lang.get(sender, "staff-lookup.ip-no-permission"));
            return;
        }
        String hash = AltTracker.hashIp(ip);
        LookupSupport.async(module, () -> {
            Map<String, IpBanManager.IpBan> ipBanCache = new ConcurrentHashMap<>();
            List<AccountView.Facts> facts = new ArrayList<>();
            for (AltTracker.Account account : module.alts().accountsOn(hash)) {
                facts.add(AccountView.gather(module, account, ipBanCache));
            }
            LookupSupport.reply(module, sender, viewer -> {
                String shownIp = MM.escape(ip);
                if (facts.isEmpty()) {
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.dupeip-ip-none",
                            "ip", shownIp));
                    return;
                }
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.dupeip-start-ip",
                        "ip", shownIp, "id", SharedConnections.shortId(hash)));
                sendAccounts(viewer, facts, null);
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.dupeip-footer",
                        "count", facts.size()));
            });
        });
    }

    /** Leyenda y lista coloreada de cuentas, la consultada primero. Hilo de quien mira. */
    private void sendAccounts(CommandSender viewer, List<AccountView.Facts> facts, UUID target) {
        List<AccountState.Row> rows = new ArrayList<>();
        Map<UUID, AccountView.Facts> byId = new HashMap<>();
        for (AccountView.Facts fact : facts) {
            byId.put(fact.id(), fact);
            rows.add(new AccountState.Row(fact.id(), fact.name(),
                    AccountView.state(viewer, fact), fact.lastSeen()));
        }
        viewer.sendMessage(Lang.get(viewer, "staff-lookup.legend"));
        List<Component> names = new ArrayList<>();
        for (AccountState.Row row : AccountState.sorted(rows, target)) {
            names.add(AccountView.render(module, viewer, byId.get(row.id())));
        }
        viewer.sendMessage(AccountView.join(viewer, names));
    }

    // ---- /ipreport -------------------------------------------------------------------------

    /**
     * Conectados que comparten conexion con otras cuentas. Lo que se ve de los conectados se
     * decide aqui, en el hilo de quien pregunta: un vanish o un anonimo que no puede ver no
     * entra en el informe (ni como linea, ni como "conectado" dentro de otra).
     */
    private void ipReport(CommandSender sender) {
        Map<UUID, String> online = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!LookupSupport.visibleOnline(sender, player.getUniqueId())) continue;
            InetSocketAddress address = player.getAddress();
            online.put(player.getUniqueId(), address == null || address.getAddress() == null
                    ? null : AltTracker.hashIp(address.getAddress().getHostAddress()));
            names.put(player.getUniqueId(), player.getName());
        }
        int scanned = online.size();
        LookupSupport.async(module, () -> {
            Map<String, List<UUID>> accountsOn = new HashMap<>();
            Map<UUID, AltTracker.Account> accounts = new HashMap<>();
            for (String hash : new java.util.HashSet<>(online.values())) {
                if (hash == null) continue;
                List<UUID> ids = new ArrayList<>();
                for (AltTracker.Account account : module.alts().accountsOn(hash)) {
                    ids.add(account.id());
                    accounts.merge(account.id(), account, (a, b) -> a);
                }
                accountsOn.put(hash, ids);
            }
            List<SharedConnections.Report> reports =
                    SharedConnections.report(online, names, accountsOn);
            Map<String, IpBanManager.IpBan> ipBanCache = new ConcurrentHashMap<>();
            Map<UUID, AccountView.Facts> facts = new HashMap<>();
            boolean showIps = LookupSupport.seesIps(sender);
            Map<String, String> ipByHash = new HashMap<>();
            for (SharedConnections.Report report : reports) {
                if (showIps) {
                    ipByHash.computeIfAbsent(report.hash(), module.alts()::ipOf);
                }
                for (UUID other : report.others()) {
                    facts.computeIfAbsent(other, id -> AccountView.gather(module,
                            accounts.get(id), ipBanCache));
                }
            }
            LookupSupport.reply(module, sender, viewer -> {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.ipreport-start",
                        "count", scanned));
                if (reports.isEmpty()) {
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.ipreport-none"));
                    return;
                }
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.legend"));
                for (SharedConnections.Report report : reports) {
                    List<Component> others = new ArrayList<>();
                    for (UUID other : report.others()) {
                        others.add(AccountView.render(module, viewer, facts.get(other)));
                    }
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.ipreport-entry",
                                    "player", MM.escape(report.name()),
                                    "id", LookupSupport.connection(viewer, report.hash(),
                                            ipByHash.get(report.hash())))
                            .clickEvent(ClickEvent.runCommand(
                                    "/chatalyst:dupeip " + report.name()))
                            .append(AccountView.join(viewer, others)));
                }
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.ipreport-footer",
                        "players", reports.size(),
                        "groups", SharedConnections.groups(reports)));
            });
        });
    }

    // ---- /iphistory ------------------------------------------------------------------------

    private void ipHistory(CommandSender sender, String input) {
        LookupSupport.async(module, () -> {
            LookupSupport.Target target = LookupSupport.resolve(module, sender, input);
            if (target == null) {
                unknown(sender, input);
                return;
            }
            AltTracker alts = module.alts();
            var connections = alts.connections(target.id());
            String current = alts.lastHash(target.id());
            List<int[]> counts = new ArrayList<>(); // [cuentas, baneada, silenciada]
            var mutes = module.plugin().getMuteManager();
            boolean showIps = LookupSupport.seesIps(sender);
            List<String> ips = new ArrayList<>();
            for (var connection : connections) {
                ips.add(showIps ? alts.ipOf(connection.key()) : null);
                counts.add(new int[]{alts.accountsOn(connection.key()).size(),
                        module.ipBans() != null && module.ipBans().get(connection.key()) != null
                                ? 1 : 0,
                        mutes != null && mutes.ipMuteOf(connection.key()) != null ? 1 : 0});
            }
            LookupSupport.reply(module, sender, viewer -> {
                String shownName = MM.escape(target.name());
                if (connections.isEmpty()) {
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.no-connection",
                            "player", shownName));
                    return;
                }
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.iphistory-start",
                        "player", shownName, "count", connections.size()));
                for (int i = 0; i < connections.size(); i++) {
                    var connection = connections.get(i);
                    int[] info = counts.get(i);
                    StringBuilder flags = new StringBuilder();
                    if (connection.key().equals(current)) {
                        flags.append(Lang.raw(viewer, "staff-lookup.iphistory-current"));
                    }
                    if (info[1] == 1) flags.append(Lang.raw(viewer, "staff-lookup.iphistory-banned"));
                    if (info[2] == 1) flags.append(Lang.raw(viewer, "staff-lookup.iphistory-muted"));
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.iphistory-entry",
                                    "id", LookupSupport.connection(viewer, connection.key(),
                                            ips.get(i)),
                                    "first", LookupSupport.dateOrUnknown(viewer, connection.first()),
                                    "last", LookupSupport.dateOrUnknown(viewer, connection.last()),
                                    "accounts", info[0], "flags", flags.toString())
                            .hoverEvent(HoverEvent.showText(Lang.get(viewer,
                                    "staff-lookup.iphistory-hover", "player", shownName)))
                            .clickEvent(ClickEvent.runCommand(
                                    "/chatalyst:dupeip " + target.name())));
                }
            });
        });
    }

    // ---- /namehistory y /lastuuid ----------------------------------------------------------

    private void nameHistory(CommandSender sender, String input) {
        LookupSupport.async(module, () -> {
            LookupSupport.Target target = LookupSupport.resolve(module, sender, input);
            if (target == null) {
                unknown(sender, input);
                return;
            }
            var names = module.alts().nameHistory(target.id());
            LookupSupport.reply(module, sender, viewer -> {
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.namehistory-start",
                        "player", MM.escape(target.name()), "uuid", target.id().toString()));
                if (names.isEmpty()) {
                    // Cuenta anterior al historial de nombres: solo se sabe el de ahora.
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.namehistory-only-current",
                            "name", MM.escape(target.name())));
                    return;
                }
                for (var name : names) {
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.namehistory-entry",
                            "name", MM.escape(name.key()),
                            "first", LookupSupport.dateOrUnknown(viewer, name.first()),
                            "last", LookupSupport.dateOrUnknown(viewer, name.last())));
                }
            });
        });
    }

    private void lastUuid(CommandSender sender, String input) {
        LookupSupport.async(module, () -> {
            UUID id = module.alts().uuidOf(input);
            if (id == null) {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
                if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
                    id = cached.getUniqueId();
                }
            }
            UUID found = id;
            LookupSupport.reply(module, sender, viewer -> {
                if (found == null) {
                    viewer.sendMessage(Lang.get(viewer, "staff-lookup.lastuuid-none",
                            "name", MM.escape(input)));
                    return;
                }
                viewer.sendMessage(Lang.get(viewer, "staff-lookup.lastuuid-result",
                                "name", MM.escape(input), "uuid", found.toString())
                        .hoverEvent(HoverEvent.showText(
                                Lang.get(viewer, "staff-lookup.lastuuid-copy")))
                        .clickEvent(ClickEvent.copyToClipboard(found.toString())));
            });
        });
    }

    private void unknown(CommandSender sender, String input) {
        LookupSupport.reply(module, sender, viewer -> viewer.sendMessage(
                Lang.get(viewer, "common.unknown-player", "player", MM.escape(input))));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("ipreport")) return List.of();
        if (!StaffAccess.has(sender, "chatalyst.command."
                + command.getName().toLowerCase(Locale.ROOT))) return List.of();
        boolean dupe = command.getName().equalsIgnoreCase("dupeip");
        if (args.length == 2 && dupe) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.of("profundo", "chat").filter(w -> w.startsWith(p)).toList();
        }
        if (args.length != 1) return List.of();
        // Tambien desconectados: estos comandos sirven igual con quien no esta en linea.
        List<String> out = new ArrayList<>(LookupSupport.knownNames(sender, args[0]));
        if (dupe && "importar".startsWith(args[0].toLowerCase(Locale.ROOT))
                && LookupSupport.seesIps(sender)) out.add(0, "importar");
        return out;
    }
}
