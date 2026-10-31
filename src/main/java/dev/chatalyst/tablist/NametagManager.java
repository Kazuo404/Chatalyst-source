package dev.chatalyst.tablist;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;


/**
 * Muestra el prefijo/sufijo (con rango) sobre la cabeza de cada jugador usando teams del
 * scoreboard principal. Activalo solo si no usas otro plugin de nametags para no chocar.
 */
public final class NametagManager {

    private final JavaPlugin plugin;
    private volatile boolean enabled;
    private String prefixFormat;
    private String suffixFormat;
    private String nameColorFormat;
    private int intervalSeconds;
    private ScheduledTask task;
    /** Invalida resoluciones de entidad pendientes cuando cambia la configuracion. */
    private volatile long generation;

    public NametagManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        stop();
        enabled = plugin.getConfig().getBoolean("Nametag.Enabled", false);
        prefixFormat = plugin.getConfig().getString("Nametag.Prefix", "{player_prefix}");
        suffixFormat = plugin.getConfig().getString("Nametag.Suffix", "{player_suffix}");
        nameColorFormat = dev.chatalyst.config.ConfigFiles.string(
                plugin.getConfig(), "Nametag.Name_Color", "");
        intervalSeconds = Math.max(5, plugin.getConfig().getInt("Nametag.Update_Interval", 30));
    }

    public void start() {
        if (!enabled) return;
        // El nametag fancy (TextDisplay) sustituye a este: un jugador solo puede estar en
        // un team, asi que no pueden convivir.
        if (plugin.getConfig().getBoolean("Nametag.Fancy.Enabled", false)) {
            plugin.getLogger().warning("Nametag is enabled but Nametag.Fancy is too: skipping "
                    + "the classic team nametags (the fancy display replaces them).");
            return;
        }
        // TAB (NEZNAMY) tambien usa scoreboard teams para los nametags: si esta, no chocamos.
        if (dev.chatalyst.hook.TabHook.isAvailable()) {
            plugin.getLogger().warning("Nametag is enabled but TAB is installed: skipping the "
                    + "Chatalyst nametags to avoid team conflicts. Configure nametags in TAB.");
            return;
        }
        long runGeneration = ++generation;
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            if (generation != runGeneration) return;
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                FoliaTasks.entity(plugin, p, current -> updateNow(current, runGeneration));
            }
        }, 20L, intervalSeconds * 20L);
        FoliaTasks.onlinePlayers(plugin, p -> updateNow(p, runGeneration));
    }

    public void stop() {
        enabled = false;
        ++generation;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void update(Player player) {
        if (!enabled) return;
        long expectedGeneration = generation;
        FoliaTasks.entity(plugin, player, current -> updateNow(current, expectedGeneration));
    }

    private void updateNow(Player player, long expectedGeneration) {
        if (!enabled || generation != expectedGeneration) return;
        UUID id = player.getUniqueId();
        String entry = player.getName();
        net.kyori.adventure.text.Component prefix;
        net.kyori.adventure.text.Component suffix;
        // Disfrazado (Morphy / Lib's Disguises): sin prefijo ni sufijo. Un [MVP++] flotando
        // sobre una vaca delata al jugador igual de bien que su propio nombre.
        if (dev.chatalyst.hook.DisguiseHook.isDisguised(player)) {
            prefix = net.kyori.adventure.text.Component.empty();
            suffix = net.kyori.adventure.text.Component.empty();
        } else {
            prefix = MM.full(Legacy.toMini(Placeholders.apply(player, prefixFormat)));
            suffix = MM.full(Legacy.toMini(Placeholders.apply(player, suffixFormat)));
        }
        // Color del nombre sobre la cabeza (los teams solo aceptan los 16 colores vanilla;
        // hex y gradientes se redondean al vanilla mas cercano). Si el formato queda vacio
        // (ej. "{nick_color}" sin apodo coloreado) se restaura el blanco por defecto.
        NamedTextColor parsedColor = nameColorFormat != null && !nameColorFormat.isEmpty()
                ? parseColor(Placeholders.apply(player, nameColorFormat)) : null;
        FoliaTasks.global(plugin, () -> {
            if (!enabled || generation != expectedGeneration) return;
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Team team = board.getTeam(teamName(id));
            if (team == null) team = board.registerNewTeam(teamName(id));
            // Cada setter del team se reenvia a TODOS los jugadores aunque el valor sea el
            // mismo: con 200 jugadores, 40 000 paquetes por refresco sin cambiar nada.
            Applied last = appliedTeams.get(team.getName());
            Applied now = new Applied(
                    setIfChanged(team, prefix, last == null ? null : last.prefix(), true),
                    setIfChanged(team, suffix, last == null ? null : last.suffix(), false));
            appliedTeams.put(team.getName(), now);
            if (nameColorFormat != null && !nameColorFormat.isEmpty()) {
                NamedTextColor wanted = parsedColor != null ? parsedColor : NamedTextColor.WHITE;
                if (!team.hasColor() || !wanted.equals(team.color())) team.color(wanted);
            }
            if (!team.hasEntry(entry)) team.addEntry(entry);
        });
    }

    /**
     * Lo que pusimos en el prefijo/sufijo de cada team y lo que el team devolvio justo despues.
     * Comparar tambien lo leido hace que, si otro plugin o un comando cambia el team, se
     * vuelva a poner el nuestro igual que antes.
     */
    private record Shown(net.kyori.adventure.text.Component sent,
                         net.kyori.adventure.text.Component observed) {
    }

    private record Applied(Shown prefix, Shown suffix) {
    }

    /** Solo se toca desde el hilo global, pero el mapa es concurrente por si acaso. */
    private final java.util.Map<String, Applied> appliedTeams =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Shown setIfChanged(Team team, net.kyori.adventure.text.Component wanted,
                                      Shown last, boolean prefix) {
        net.kyori.adventure.text.Component current = prefix ? team.prefix() : team.suffix();
        if (last != null && last.sent().equals(wanted)
                && java.util.Objects.equals(last.observed(), current)) return last;
        if (prefix) team.prefix(wanted);
        else team.suffix(wanted);
        return new Shown(wanted, prefix ? team.prefix() : team.suffix());
    }

    // Acepta "<#FF4D6D>", "&c", "red", "<#RRGGBB>", "&#RRGGBB" o un gradiente, y devuelve el
    // color vanilla mas cercano (los teams no aceptan RGB), o null si no hay color.
    private static NamedTextColor parseColor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String name = raw.replaceAll("[<>&§]", "").trim().toLowerCase();
        NamedTextColor named = NamedTextColor.NAMES.value(name);
        if (named != null) return named;
        // Hex o gradiente: parsear como MiniMessage (con una letra que porte el color) y
        // quedarse con el primer color visible, redondeado al vanilla mas cercano.
        net.kyori.adventure.text.format.TextColor color =
                MM.firstColor(Legacy.toMini(raw.trim()) + "x");
        return color != null ? NamedTextColor.nearestTo(color) : null;
    }

    public void remove(Player player) {
        String name = teamName(player.getUniqueId());
        FoliaTasks.global(plugin, () -> removeTeam(name));
    }

    /** Quita todos los teams que creamos (al apagar o recargar). */
    public void clearAll() {
        if (plugin.isEnabled()) {
            FoliaTasks.global(plugin, this::clearAllNow);
        } else if (!FoliaTasks.isFolia()) {
            clearAllNow();
        }
    }

    private void clearAllNow() {
        Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
        for (Team t : board.getTeams()) {
            if (t.getName().startsWith("ct_")) {
                t.unregister();
            }
        }
        appliedTeams.clear();
    }

    private void removeTeam(String name) {
        Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(name);
        if (team != null) team.unregister();
        appliedTeams.remove(name);
    }

    // Nombre de team POR UUID (13 primeros hex, sin guiones), no por nombre de jugador. El
    // nombre se truncaba a 13 chars (limite de 16 menos el prefijo "ct_"), asi que dos
    // jugadores con los mismos 13 primeros caracteres compartian team: el segundo pisaba el
    // prefix/sufijo/color del rango del primero y ambos mostraban el rango equivocado sobre
    // la cabeza (y al salir uno, unregister se llevaba el del otro). El UUID es unico y estable
    // y el prefijo "ct_" sigue permitiendo limpiarlos luego.
    private String teamName(UUID id) {
        return "ct_" + id.toString().replace("-", "").substring(0, 13);
    }
}
