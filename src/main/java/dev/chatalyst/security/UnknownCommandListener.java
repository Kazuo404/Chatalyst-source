package dev.chatalyst.security;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.tabcomplete.TabCompleteListener;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sustituye el error de Brigadier ("Comando desconocido o incompleto. Revisa el error a
 * continuacion: plugins&lt;--[AQUI]") por un mensaje con el estilo del plugin.
 *
 * <p>Paper lanza {@link UnknownCommandEvent} para CUALQUIER error de sintaxis de un comando
 * (desconocido, sin permiso, incompleto o con un argumento mal escrito) con el mensaje vanilla ya
 * armado: {@code [error, salto de linea, contexto con <--[AQUI]]} (medido en
 * {@code net.minecraft.commands.Commands#performCommand} de Purpur 26.2). Aqui se distinguen dos
 * casos:
 * <ul>
 *   <li>el comando existe y este jugador lo ve: esta incompleto o mal escrito. Se conserva el error
 *       vanilla, que el cliente traduce ("Se esperaba un numero entero"), y se ofrece reescribirlo;
 *   <li>no existe, no tiene permiso, esta oculto del Tab o bloqueado: el MISMO mensaje en los tres
 *       casos, para no revelar que existe, con el comando visible mas parecido como sugerencia.
 * </ul>
 * No usa paquetes, asi que funciona sin PacketEvents.
 */
public final class UnknownCommandListener implements Listener {

    /** Etiquetas que se pueden meter en un click sin romper el MiniMessage del lang. */
    private static final Pattern SAFE_LABEL = Pattern.compile("[a-z0-9_.\\-]{1,48}");
    private static final Pattern SAFE_LINE = Pattern.compile("[A-Za-z0-9_ .:@~^=,+#/\\-]{1,120}");

    private final JavaPlugin plugin;
    private final TabCompleteListener tabComplete;
    private final CommandBlockerListener blocker;
    private volatile boolean enabled;
    private volatile boolean suggest;

    public UnknownCommandListener(JavaPlugin plugin, TabCompleteListener tabComplete,
                                  CommandBlockerListener blocker) {
        this.plugin = plugin;
        this.tabComplete = tabComplete;
        this.blocker = blocker;
        load();
    }

    /** UnknownCommandEvent es de Paper; en Spigot no existe y el listener no se registra. */
    public static boolean supported() {
        try {
            Class.forName("org.bukkit.event.command.UnknownCommandEvent");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Unknown_Command.Enabled", true);
        suggest = plugin.getConfig().getBoolean("Unknown_Command.Suggest", true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUnknownCommand(UnknownCommandEvent event) {
        if (!enabled || !(event.getSender() instanceof Player player)) return;
        // null = spigot.yml con unknown-command vacio u otro plugin ya lo silencio: se respeta
        if (event.message() == null) return;
        String line = event.getCommandLine() == null ? "" : event.getCommandLine().strip();
        if (line.startsWith("/")) line = line.substring(1).strip();
        if (line.isEmpty()) {
            event.message(Lang.get(player, "unknown-command.empty"));
            return;
        }
        String label = line.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        String shown = MM.escape(label.length() > 32 ? label.substring(0, 32) + "…" : label);

        // Si Brigadier marca el error desde el PRINCIPIO de la linea, no pudo ni leer el nombre del
        // comando para este jugador: es desconocido aunque exista (visto en el juego con /plugins:
        // salia "Revisa /plugins" porque el comando existe y el jugador pasa el permiso de Bukkit,
        // pero Brigadier no se lo deja ejecutar). Solo es incompleto si el error va DESPUES.
        if (!errorStartsAtRoot(event.message(), line) && isVisible(player, label)) {
            // lo que va dentro del click tiene que ser texto seguro para el MiniMessage del lang
            String retype = SAFE_LINE.matcher(line).matches() ? line
                    : SAFE_LABEL.matcher(label).matches() ? label : "";
            Component detail = errorDetail(event.message());
            Component main = MM.full(Lang.raw(player, "unknown-command.incomplete",
                    "command", shown, "line", retype), Placeholder.component("error", detail));
            event.message(main.append(Component.newline())
                    .append(Lang.get(player, "unknown-command.incomplete-hint")));
            return;
        }

        Component message = Lang.get(player, "unknown-command.unknown", "command", shown);
        String best = suggest && SAFE_LABEL.matcher(label).matches() ? closest(label, visibleCommands(player)) : null;
        message = message.append(Component.newline()).append(best != null
                ? Lang.get(player, "unknown-command.suggestion", "suggestion", best)
                : Lang.get(player, "unknown-command.no-suggestion"));
        event.message(message);
    }

    /**
     * El error vanilla sin la linea de contexto: Paper lo arma como un componente con hijos
     * {@code [error, salto, contexto]}; el primero es el texto traducible del error.
     */
    private static Component errorDetail(Component vanilla) {
        List<Component> children = vanilla.children();
        return children.isEmpty() ? vanilla : children.get(0);
    }

    /**
     * True si la parte subrayada del contexto vanilla ({@code input.substring(cursor)}, en rojo y
     * subrayada) es la linea entera, o sea, cursor 0: Brigadier no reconocio ni la primera palabra.
     * Sin contexto (no hay parte subrayada) no se puede saber y se devuelve false.
     */
    static boolean errorStartsAtRoot(Component vanilla, String line) {
        String underlined = underlinedText(vanilla);
        return underlined != null && underlined.strip().equalsIgnoreCase(line.strip());
    }

    private static String underlinedText(Component component) {
        if (component.decoration(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                == net.kyori.adventure.text.format.TextDecoration.State.TRUE
                && component instanceof net.kyori.adventure.text.TextComponent text) {
            return text.content();
        }
        for (Component child : component.children()) {
            String found = underlinedText(child);
            if (found != null) return found;
        }
        return null;
    }

    private boolean isVisible(Player player, String label) {
        Command command = plugin.getServer().getCommandMap().getCommand(label);
        return command != null && isVisible(player, label, command);
    }

    private boolean isVisible(Player player, String label, Command command) {
        if (!command.testPermissionSilent(player)) return false;
        if (tabComplete != null && !tabComplete.isVisibleTo(player, label)) return false;
        return blocker == null || !blocker.shouldBlock(player, "/" + label);
    }

    /** Comandos que este jugador ve al pulsar Tab, sin los alias con namespace. */
    private List<String> visibleCommands(Player player) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Command> entry : plugin.getServer().getCommandMap().getKnownCommands().entrySet()) {
            String label = entry.getKey().toLowerCase(Locale.ROOT);
            if (label.indexOf(':') >= 0 || !SAFE_LABEL.matcher(label).matches()) continue;
            if (isVisible(player, label, entry.getValue())) out.add(label);
        }
        return out;
    }

    /**
     * El comando mas parecido a lo escrito, o null si ninguno esta cerca. Un comando que empieza
     * por lo escrito (3+ letras) cuenta como coincidencia exacta: "/gamem" es "/gamemode". El
     * margen crece con la longitud: 1 cambio hasta 4 letras, 2 hasta 8, 3 despues. Empates: el
     * mas corto y luego el alfabetico, para que la sugerencia sea estable.
     */
    static String closest(String typed, Collection<String> candidates) {
        String input = typed.toLowerCase(Locale.ROOT);
        if (input.length() < 2) return null;
        int limit = input.length() <= 4 ? 1 : input.length() <= 8 ? 2 : 3;
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate.equals(input)) continue;
            int score = input.length() >= 3 && candidate.startsWith(input) ? 0 : distance(input, candidate, limit);
            if (score > limit) continue;
            if (best == null || score < bestScore
                    || (score == bestScore && (candidate.length() < best.length()
                    || (candidate.length() == best.length() && candidate.compareTo(best) < 0)))) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Distancia de edicion con transposicion (una letra cambiada de sitio cuenta como un solo
     * error: "spwan" -> "spawn"). Devuelve Integer.MAX_VALUE en cuanto supera {@code limit}.
     */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return Integer.MAX_VALUE;
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    value = Math.min(value, d[i - 2][j - 2] + 1);
                }
                d[i][j] = value;
                rowMin = Math.min(rowMin, value);
            }
            if (rowMin > limit) return Integer.MAX_VALUE;
        }
        return d[a.length()][b.length()] > limit ? Integer.MAX_VALUE : d[a.length()][b.length()];
    }
}
