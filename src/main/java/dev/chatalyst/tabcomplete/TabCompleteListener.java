package dev.chatalyst.tabcomplete;

import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffCommandCatalog;
import dev.chatalyst.util.CommandNames;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hides commands from tab completion (TAB) so the server doesn't reveal which plugins or
 * version it runs (/plugins, /version, /about, and every namespaced /bukkit:*, /minecraft:*...).
 * Whoever has the bypass permission still sees them.
 *
 * Entries can be:
 *   - an exact command:        "version", "plugins"
 *   - a namespace wildcard:    "minecraft:*", "bukkit:*"  (hides that whole namespace)
 *
 * Con {@code Tab_Complete.Mode: whitelist} la lista se invierte: solo aparecen los comandos
 * de {@code Tab_Complete.Whitelist}, y todo lo demas se esconde aunque lo registre un plugin
 * instalado mañana. Es la forma de no tener que perseguir cada comando nuevo.
 *
 * Ademas, si Nick.Tab_Complete_Nicks esta activo, reescribe las sugerencias de argumentos
 * de CUALQUIER comando (/tpa, /msg, /tp...) cambiando el nombre real de un jugador con
 * apodo por su apodo plano, para que el /nick tambien oculte el nombre en el TAB.
 */
public final class TabCompleteListener implements Listener {

    private final JavaPlugin plugin;
    // Volatile + colecciones inmutables (swap en load()): los eventos pueden correr en otro
    // hilo (Folia: hilos de region) mientras /chatalyst reload reescribe este estado.
    private volatile boolean enabled;
    private volatile String bypass;
    private volatile Set<String> hidden = Set.of();       // exact names
    private volatile List<String> prefixes = List.of();   // "minecraft:" from "minecraft:*"
    // Modo lista blanca: se ve SOLO lo de aqui. Mismo formato de entrada que Hide.
    private volatile boolean whitelistMode;
    private volatile Set<String> allowed = Set.of();
    private volatile List<String> allowedPrefixes = List.of();
    // Nombre real -> apodo en las sugerencias de tab de todos los comandos.
    private volatile boolean nickAliases;
    private volatile String nickBypass;

    public TabCompleteListener(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Tab_Complete.Enabled", false);
        bypass = plugin.getConfig().getString("Tab_Complete.Bypass_Permission", "chatalyst.bypass.tabcomplete");
        Set<String> nextHidden = new HashSet<>();
        List<String> nextPrefixes = new ArrayList<>();
        parse(plugin.getConfig().getStringList("Tab_Complete.Hide"), nextHidden, nextPrefixes);
        hidden = Set.copyOf(nextHidden);       // publicacion atomica
        prefixes = List.copyOf(nextPrefixes);
        Set<String> nextAllowed = new HashSet<>();
        List<String> nextAllowedPrefixes = new ArrayList<>();
        parse(plugin.getConfig().getStringList("Tab_Complete.Whitelist"),
                nextAllowed, nextAllowedPrefixes);
        allowed = Set.copyOf(nextAllowed);
        allowedPrefixes = List.copyOf(nextAllowedPrefixes);
        whitelistMode = "whitelist".equalsIgnoreCase(
                plugin.getConfig().getString("Tab_Complete.Mode", "hide"));
        nickAliases = plugin.getConfig().getBoolean("Nick.Tab_Complete_Nicks", true);
        nickBypass = plugin.getConfig().getString("Nick.Tab_Complete_Bypass", "chatalyst.bypass.nicktab");
    }

    /** Reparte las entradas entre nombres exactos y comodines de namespace ("minecraft:*"). */
    static void parse(List<String> entries, Set<String> exact, List<String> namespaces) {
        for (String raw : entries) {
            if (raw == null) continue;
            String c = raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            if (c.endsWith(":*")) {
                namespaces.add(c.substring(0, c.length() - 1)); // "minecraft:*" -> "minecraft:"
            } else if (!c.isEmpty()) {
                exact.add(c);
            }
        }
    }

    /** True si el filtro no tiene nada que hacer con la configuracion actual. */
    private boolean idle() {
        // Una lista blanca VACIA no esconde todo: seria dejar a los jugadores sin un solo
        // comando por haber cambiado el modo antes de rellenar la lista.
        return !enabled || (whitelistMode
                ? allowed.isEmpty() && allowedPrefixes.isEmpty()
                : hidden.isEmpty() && prefixes.isEmpty());
    }

    /**
     * Reescribe las sugerencias de tab de cualquier comando: donde el servidor sugiere el
     * nombre REAL de un jugador conectado con apodo, se muestra su apodo plano. Como
     * Nick.Sync_Essentials refleja el apodo en Essentials y los comandos de Chatalyst usan
     * {@link dev.chatalyst.nick.NickManager#online}, la sugerencia reescrita sigue siendo
     * un argumento valido (/tpa <apodo> funciona). Evento async de Paper: solo toca el
     * cache concurrente del NickManager.
     */
    @EventHandler
    public void onAsyncTabComplete(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event) {
        if (!nickAliases) return;
        if (!(event.getSender() instanceof org.bukkit.entity.Player viewer)) return;
        if (nickBypass != null && !nickBypass.isEmpty() && viewer.hasPermission(nickBypass)) return;
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        if (pl == null || pl.getNickManager() == null) return;
        dev.chatalyst.nick.NickManager nicks = pl.getNickManager();

        List<String> completions = event.getCompletions();
        if (completions.isEmpty()) return;
        List<String> rewritten = null;
        for (int i = 0; i < completions.size(); i++) {
            String alias = nicks.tabAlias(completions.get(i));
            if (alias == null || alias.isEmpty()) continue;
            if (rewritten == null) rewritten = new ArrayList<>(completions);
            rewritten.set(i, alias);
        }
        if (rewritten != null) event.setCompletions(rewritten);
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(command -> !canSeeStaffCommand(event.getPlayer(), command));
        if (!PrivateMessages.canSpyAny(event.getPlayer())) {
            event.getCommands().removeIf(this::isSpyCommand);
        }
        if (idle()) return;
        if (event.getPlayer().isOp()
                || (bypass != null && !bypass.isEmpty()
                && event.getPlayer().hasPermission(bypass))) return;
        event.getCommands().removeIf(this::isHidden);
    }

    /**
     * Mismo criterio que {@link #onCommandSend}: true si ese comando le aparece a este jugador
     * al pulsar Tab. Lo usa el aviso de comando desconocido para no sugerir ni confirmar
     * comandos que aqui se ocultan.
     */
    public boolean isVisibleTo(org.bukkit.entity.Player player, String command) {
        if (!canSeeStaffCommand(player, command)) return false;
        if (!PrivateMessages.canSpyAny(player) && isSpyCommand(command)) return false;
        if (idle()) return true;
        if (player.isOp() || (bypass != null && !bypass.isEmpty() && player.hasPermission(bypass))) {
            return true;
        }
        return !isHidden(command);
    }

    private static boolean canSeeStaffCommand(org.bukkit.entity.Player player, String command) {
        String base = CommandNames.baseLabel(command).replaceFirst("^/", "");
        String permission = StaffCommandCatalog.permissionFor(base);
        return permission == null || StaffAccess.has(player, permission);
    }

    private boolean isSpyCommand(String cmd) {
        String base = CommandNames.baseLabel(cmd);
        return "/spy".equals(base) || "/socialspy".equals(base);
    }

    private boolean isHidden(String cmd) {
        String lc = cmd.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
        if (whitelistMode) return !listed(lc, allowed, allowedPrefixes, false);
        return listed(lc, hidden, prefixes, true);
    }

    /**
     * ¿Esta el comando en la lista?
     *
     * @param bareCoversNamespaced al ESCONDER, "plugins" tapa tambien "bukkit:plugins" (si
     *        no, el alias con namespace delata lo mismo). Al PERMITIR es al reves: "msg" no
     *        autoriza "essentials:msg", porque ese prefijo es justo el nombre del plugin que
     *        se quiere ocultar; quien lo quiera visible lo lista entero o con "essentials:*".
     */
    static boolean listed(String lc, Set<String> exact, List<String> namespaces,
                          boolean bareCoversNamespaced) {
        // Namespace wildcards (e.g. minecraft:give, bukkit:plugins).
        for (String p : namespaces) {
            if (lc.startsWith(p)) return true;
        }
        if (exact.contains(lc)) return true;
        if (!bareCoversNamespaced || !lc.contains(":")) return false;
        return exact.contains(lc.substring(lc.indexOf(':') + 1));
    }
}
