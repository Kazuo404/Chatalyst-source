package dev.chatalyst.log;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /chatlog [player] [count]        Recent history from the in-memory buffer.
 * /chatlog [player] [YYYY-MM-DD]    That day's history from the log file (today/yesterday too).
 *
 * Lets staff review what a player said, either recently or on a specific day, without
 * opening the log files — the core tool for investigating a report.
 */
public final class ChatLogCommand implements CommandExecutor, TabCompleter {

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 100;
    /** Tipos de entrada reconocidos como filtro ("/chatlog swear"). */
    private static final java.util.Set<String> KNOWN_TYPES = java.util.Set.of(
            "CHAT", "CMD", "PM", "MAIL", "SIGN", "BOOK", "ANVIL", "RULE", "SWEAR", "DISCORD",
            "FREEZE", "CONTAINER");

    private final JavaPlugin plugin;
    private final LogManager log;

    public ChatLogCommand(JavaPlugin plugin, LogManager log) {
        this.plugin = plugin;
        this.log = log;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, "chatalyst.command.log")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (!log.isEnabled()) {
            sender.sendMessage(Lang.get(sender, "log.disabled"));
            return true;
        }

        // Parseo: /chatlog <player...> [tipo...] [count|date]. El ultimo token, si es fecha
        // o numero, es ese filtro; los tokens que coinciden con un TIPO de entrada (SWEAR,
        // PM, SIGN...) filtran por tipo; el resto son jugadores (conversacion entre ellos).
        java.util.List<String> parts = new java.util.ArrayList<>(java.util.Arrays.asList(args));
        java.time.LocalDate date = null;
        int count = DEFAULT_COUNT;
        if (!parts.isEmpty()) {
            String last = parts.get(parts.size() - 1);
            java.time.LocalDate d = parseDate(last);
            if (d != null) {
                date = d;
                parts.remove(parts.size() - 1);
            } else if (last.matches("\\d{1,9}")) {
                // Tope de 9 digitos: "\d+" dejaba pasar numeros que desbordan el int y
                // parseInt lanzaba NumberFormatException sin capturar.
                count = Math.max(1, Math.min(MAX_COUNT, Integer.parseInt(last)));
                parts.remove(parts.size() - 1);
            }
        }
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        parts.removeIf(token -> {
            String upper = token.toUpperCase(Locale.ROOT);
            if (KNOWN_TYPES.contains(upper)) {
                types.add(upper);
                return true;
            }
            return false;
        });
        java.util.Set<String> players = parts.isEmpty()
                ? null : new java.util.LinkedHashSet<>(parts);

        if (date != null) {
            java.time.LocalDate requestedDate = date;
            java.util.Set<String> requestedPlayers = players == null
                    ? null : java.util.Collections.unmodifiableSet(
                            new java.util.LinkedHashSet<>(players));
            // Los tipos se filtran DENTRO del recorrido del archivo: filtrarlos despues
            // gastaba el limite en lineas de otros tipos y "/chatlog <fecha> swear" podia
            // salir vacio en un dia con mucho chat.
            log.readDayAsync(requestedDate, requestedPlayers,
                            types.isEmpty() ? null : java.util.Set.copyOf(types), MAX_COUNT)
                    .whenComplete((entries, failure) -> dispatchResponse(sender, () -> {
                        if (failure != null) {
                            plugin.getLogger().warning("Could not read requested chat log: "
                                    + failure.getMessage());
                            sender.sendMessage(Lang.get(sender, "log.read-error"));
                        } else {
                            sendEntries(sender, requestedDate, requestedPlayers,
                                    filterTypes(entries, types, MAX_COUNT));
                        }
                    }));
            return true;
        }

        // Con filtro de tipo se piden mas lineas del buffer: filtrar despues dejaria menos.
        List<LogManager.Entry> entries = log.recent(players, types.isEmpty() ? count : MAX_COUNT);
        sendEntries(sender, null, players, filterTypes(entries, types, count));
        return true;
    }

    // Deja solo los tipos pedidos, conservando las ULTIMAS count entradas (son cronologicas).
    private static List<LogManager.Entry> filterTypes(List<LogManager.Entry> entries,
                                                      java.util.Set<String> types, int count) {
        if (types.isEmpty()) return entries;
        List<LogManager.Entry> out = new ArrayList<>();
        for (LogManager.Entry e : entries) {
            if (types.contains(e.type().toUpperCase(Locale.ROOT))) out.add(e);
        }
        return out.size() > count ? out.subList(out.size() - count, out.size()) : out;
    }

    private void sendEntries(CommandSender sender, java.time.LocalDate date,
                             java.util.Set<String> players, List<LogManager.Entry> entries) {
        if (entries.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "log.empty"));
            return;
        }

        String whoLabel = players == null ? "" : " <#C0B3D9>· <#FBF2FF>" + String.join("<#C0B3D9>, <#FBF2FF>", players);
        sender.sendMessage(MM.full("<#7B68A0>» <#C0B3D9>"
                + (date != null ? "Log for " : "Recent log")
                + (date != null ? "<#FBF2FF>" + date : "")
                + whoLabel
                + " <#7B68A0>(" + entries.size() + ")"));
        for (LogManager.Entry e : entries) {
            // Hora corta (HH:mm:ss). Si el timestamp del archivo viniera corrupto/mas corto,
            // usamos lo que haya en vez de reventar con un substring fuera de rango.
            String time = e.time();
            String shortTime = time.length() >= 11 ? time.substring(11) : time;
            // El contenido se inserta como texto plano (no interpreta MiniMessage del jugador).
            sender.sendMessage(MM.full(
                    "<#7B68A0>[" + shortTime + "] <#C0B3D9>[" + typeColor(e.type())
                            + "<#C0B3D9>] <#FBF2FF>" + e.who() + "<#7B68A0>: <#C0B3D9><chatalyst_line>",
                    Placeholder.unparsed("chatalyst_line", e.content())));
        }
    }

    private void dispatchResponse(CommandSender sender, Runnable response) {
        if (sender instanceof Player player) {
            try {
                player.getScheduler().run(plugin, task -> {
                    if (player.isOnline()) response.run();
                }, null);
            } catch (Throwable ignored) {
                // El staff se desconecto o el plugin esta apagandose: ya no hay destinatario.
            }
        } else {
            try {
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> response.run());
            } catch (Throwable ignored) {
                // Apagado: no intentar responder desde el hilo async.
            }
        }
    }

    // "today" / "yesterday" / "YYYY-MM-DD" -> fecha, o null si es un numero/otra cosa.
    private static java.time.LocalDate parseDate(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        if (low.equals("today") || low.equals("hoy")) return java.time.LocalDate.now();
        if (low.equals("yesterday") || low.equals("ayer")) return java.time.LocalDate.now().minusDays(1);
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String typeColor(String type) {
        return switch (type) {
            case "CHAT" -> "<#3DFFA8>CHAT";
            case "CMD" -> "<#9D4EDD>CMD";
            case "PM" -> "<#B56CFF>PM";
            case "MAIL" -> "<#C77DFF>MAIL";
            case "SIGN" -> "<#E0AAFF>SIGN";
            case "BOOK" -> "<#7B2CBF>BOOK";
            case "ANVIL" -> "<dark_aqua>ANVIL";
            case "RULE" -> "<#FF4D6D>RULE";
            case "SWEAR" -> "<dark_red>SWEAR";
            case "DISCORD" -> "<#5865F2>DISCORD";
            case "FREEZE" -> "<#B56CFF>FREEZE";
            case "CONTAINER" -> "<#D8B4FE>CONTAINER";
            default -> "<#C0B3D9>" + type;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!dev.chatalyst.staff.StaffAccess.has(sender, "chatalyst.command.log")) {
            return List.of();
        }
        // Sugerimos jugadores en cada posicion (varios participantes) y, a partir del 2º,
        // tambien las opciones de fecha.
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
            for (String d : List.of("today", "yesterday")) {
                if (d.startsWith(pre)) out.add(d);
            }
        }
        for (String type : KNOWN_TYPES) {
            if (type.toLowerCase(Locale.ROOT).startsWith(pre)) {
                out.add(type.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
