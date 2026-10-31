package dev.chatalyst.containerlog;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.log.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /containerlog [jugador...] [cantidad|fecha] — historial de contenedores y mecanismos con
 * coordenadas exactas y botones [Ir] / [TP]. Sin fecha lee el buffer reciente en memoria;
 * con fecha (hoy, ayer o YYYY-MM-DD) relee las entradas CONTAINER del archivo diario.
 */
public final class ContainerLogCommand implements CommandExecutor, TabCompleter {

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 100;

    private final JavaPlugin plugin;
    private final ContainerLogManager manager;
    private final LogManager log;

    public ContainerLogCommand(JavaPlugin plugin, ContainerLogManager manager, LogManager log) {
        this.plugin = plugin;
        this.manager = manager;
        this.log = log;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, "chatalyst.command.containerlog")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (!manager.containersEnabled() && !manager.mechanismsEnabled()) {
            sender.sendMessage(Lang.get(sender, "containerlog.disabled"));
            return true;
        }

        List<String> parts = new ArrayList<>(java.util.Arrays.asList(args));
        java.time.LocalDate date = null;
        int count = DEFAULT_COUNT;
        if (!parts.isEmpty()) {
            String last = parts.get(parts.size() - 1);
            java.time.LocalDate parsed = parseDate(last);
            if (parsed != null) {
                date = parsed;
                parts.remove(parts.size() - 1);
            } else if (last.matches("\\d{1,9}")) {
                count = Math.max(1, Math.min(MAX_COUNT, Integer.parseInt(last)));
                parts.remove(parts.size() - 1);
            }
        }
        Set<String> players = parts.isEmpty() ? null : new LinkedHashSet<>(parts);

        if (date != null) {
            readDay(sender, date, players);
            return true;
        }
        send(sender, manager.recent(players, count));
        return true;
    }

    // Dia concreto: reutiliza el lector asincrono del LogManager y re-parsea las lineas
    // CONTAINER a registros estructurados para recuperar los botones de teletransporte.
    private void readDay(CommandSender sender, java.time.LocalDate date, Set<String> players) {
        // El filtro de tipo va DENTRO del recorrido: si no, el limite se lo comen las
        // lineas de chat del dia y la consulta sale vacia aunque haya registros.
        log.readDayAsync(date, players, java.util.Set.of("CONTAINER"), MAX_COUNT)
                .whenComplete((entries, failure) ->
                dispatchResponse(sender, () -> {
                    if (failure != null) {
                        plugin.getLogger().warning("Could not read container log: "
                                + failure.getMessage());
                        sender.sendMessage(Lang.get(sender, "log.read-error"));
                        return;
                    }
                    List<ContainerRecord> records = new ArrayList<>();
                    for (LogManager.Entry entry : entries) {
                        ContainerRecord record = ContainerRecord.parse(
                                parseTime(entry.time()), entry.who(), entry.content());
                        if (record != null) records.add(record);
                    }
                    send(sender, records);
                }));
    }

    private void send(CommandSender sender, List<ContainerRecord> records) {
        if (records.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "containerlog.empty"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "containerlog.header", "count", records.size()));
        for (ContainerRecord record : records) {
            sender.sendMessage(ContainerLogManager.render(sender, record, "containerlog.entry"));
        }
    }

    // Respuesta en el hilo correcto (region del staff o global para consola), como /chatlog.
    private void dispatchResponse(CommandSender sender, Runnable response) {
        if (sender instanceof Player player) {
            try {
                player.getScheduler().run(plugin, task -> {
                    if (player.isOnline()) response.run();
                }, null);
            } catch (Throwable ignored) {
                // El staff se desconecto o el plugin se esta apagando.
            }
        } else {
            try {
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> response.run());
            } catch (Throwable ignored) {
                // Apagado: no responder desde el hilo async.
            }
        }
    }

    private static long parseTime(String stamp) {
        try {
            return java.time.LocalDateTime.parse(stamp,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception malformed) {
            return System.currentTimeMillis();
        }
    }

    private static java.time.LocalDate parseDate(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        if (low.equals("today") || low.equals("hoy")) return java.time.LocalDate.now();
        if (low.equals("yesterday") || low.equals("ayer")) {
            return java.time.LocalDate.now().minusDays(1);
        }
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception notDate) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender,
                "chatalyst.command.containerlog")) return List.of();
        String pre = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (dev.chatalyst.util.Vanish.isVanished(p)
                    && !dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) {
                continue;
            }
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
        }
        if (args.length >= 2) {
            for (String d : List.of("today", "yesterday", "hoy", "ayer")) {
                if (d.startsWith(pre)) out.add(d);
            }
        }
        return out;
    }
}
