package dev.chatalyst.messages;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Mensajes del servidor configurables en messages.yml: entrada, salida, muerte y MOTD.
 */
public final class ServerMessages {

    private static final String JOIN_KEY = "server-messages.join";
    private static final String QUIT_KEY = "server-messages.quit";
    private static final String DEATH_KEY = "server-messages.death";
    private static final String DEATH_WITH_ITEM_KEY = "server-messages.death-with-item";
    private static final String MOTD_FIRST_TIME_KEY = "server-messages.motd.first-time";
    private static final String MOTD_DEFAULT_KEY = "server-messages.motd.default";

    private final JavaPlugin plugin;
    // Volatile: load() sustituye la referencia entera; los eventos (join/quit/death en
    // hilos de region en Folia) ven o el YAML viejo completo o el nuevo completo.
    private volatile YamlConfiguration yaml;

    public ServerMessages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        // Carga ESTRICTA. loadConfiguration convierte un error de sintaxis en un YAML vacio, y
        // de ahi salian los valores de fabrica sin avisar: tras un reload con messages.yml
        // roto volvian las entradas por defecto y se apagaban los mensajes de muerte. Ante
        // un fichero ilegible se conserva lo que ya habia cargado.
        try {
            yaml = dev.chatalyst.util.YamlFiles.load(file);
        } catch (java.io.IOException
                 | org.bukkit.configuration.InvalidConfigurationException broken) {
            plugin.getLogger().severe("messages.yml is not valid YAML; keeping the messages"
                    + " already in memory. Fix the file and reload: " + broken.getMessage());
            if (yaml == null) yaml = YamlConfiguration.loadConfiguration(file);
        }
    }

    public boolean joinEnabled() {
        return yaml.getBoolean("Join.Enabled", true);
    }

    public boolean quitEnabled() {
        return yaml.getBoolean("Quit.Enabled", true);
    }

    public boolean deathEnabled() {
        return yaml.getBoolean("Death.Enabled", false);
    }

    public Component join(Player p) {
        return join(p, p);
    }

    public Component join(CommandSender viewer, Player joined) {
        return line(viewer, joined, "Join", "Message", JOIN_KEY);
    }

    public Component quit(Player p) {
        return quit(p, p);
    }

    public Component quit(CommandSender viewer, Player quitter) {
        return line(viewer, quitter, "Quit", "Message", QUIT_KEY);
    }

    public Component death(Player p) {
        return death(p, p);
    }

    public Component death(CommandSender viewer, Player victim) {
        return line(viewer, victim, "Death", "Message", DEATH_KEY);
    }

    /**
     * Mensaje de muerte cuando el asesino sostiene un item CON NOMBRE (p. ej. renombrado
     * con /itemname): usa Death.With_Item con {killer} y {item} (el nombre del arma como
     * Component, con su tooltip). Devuelve null si no hay formato configurado.
     */
    public Component deathWithItem(Player victim, Player killer, Component itemName) {
        return deathWithItem(victim, victim, killer, itemName);
    }

    public Component deathWithItem(CommandSender viewer, Player victim, Player killer, Component itemName) {
        String s = lineRaw(viewer, "Death", "With_Item", DEATH_WITH_ITEM_KEY);
        if (s == null || s.isEmpty()) return null;
        // Nombre MOSTRADO del asesino (apodo o disfraz de /anonymous, no el real): los
        // placeholders solo anonimizan a la VICTIMA (subject), asi que el killer se
        // resuelve aparte con el mismo criterio del resto del chat.
        String killerShown = NickManager.safeName(killer);
        String tpl = Placeholders.apply(victim, s.replace("{killer}", killerShown)
                        .replace("{killer_uuid}", killer.getUniqueId().toString()))
                .replace("{item}", "<chatalyst_item>");
        return MM.full(tpl, net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                .component("chatalyst_item", itemName));
    }

    /** Sound key played to the player on join, or "" if none. */
    public String joinSound() {
        return yaml.getString("Join.Sound", "");
    }

    /**
     * Seccion Join.Fancy (entradas fancy por rango: sonido global, rayo, title), o null.
     * Mismo volatile que el resto: tras load() todo el mundo ve la seccion nueva completa.
     */
    public org.bukkit.configuration.ConfigurationSection fancyJoin() {
        return yaml.getConfigurationSection("Join.Fancy");
    }

    /**
     * Seccion Quit.Tiers (salidas anunciadas por rango), o null. Misma forma que
     * Join.Fancy.Tiers, para que el dueño configure las dos con lo que ya conoce.
     */
    public org.bukkit.configuration.ConfigurationSection quitTiers() {
        return yaml.getConfigurationSection("Quit.Tiers");
    }

    public boolean kickEnabled() {
        return yaml.getBoolean("Kick.Enabled", false);
    }

    public Component kick(CommandSender viewer, Player kicked) {
        return line(viewer, kicked, "Kick", "Message", "server-messages.kick");
    }

    /**
     * Linea configurada para una causa de muerte (Death.Causes.<clave>), o null si no hay.
     * Claves especiales: PVP (asesino jugador) y MOB (asesino entidad); el resto mapea el
     * DamageCause de Bukkit con {@link #causeKey}.
     */
    public String deathCauseLine(String causeKey) {
        if (causeKey == null) return null;
        String line = yaml.getString("Death.Causes." + causeKey, "");
        return line == null || line.isEmpty() ? null : line;
    }

    /**
     * Linea de causa para UN espectador: con Death.Use_Player_Language, la version en su
     * idioma (lang/<locale>.yml -> server-messages.death-causes.<causa>); si falta la
     * traduccion, cae a la linea de messages.yml.
     */
    public String deathCauseLineFor(CommandSender viewer, String causeKey) {
        if (causeKey == null) return null;
        if (yaml.getBoolean("Death.Use_Player_Language", true)) {
            String key = "server-messages.death-causes." + causeKey.toLowerCase(java.util.Locale.ROOT);
            String translated = Lang.raw(viewer, key);
            if (!key.equals(translated)) return translated; // Lang devuelve la clave si falta
        }
        return deathCauseLine(causeKey);
    }

    /**
     * Clave de Death.Causes para una muerte: PVP/MOB si hay asesino, o el DamageCause
     * normalizado (FIRE_TICK->FIRE, *_EXPLOSION->EXPLOSION...). Pura y testeable.
     */
    static String causeKey(boolean killedByPlayer, boolean killedByEntity, String damageCause) {
        if (killedByPlayer) return "PVP";
        if (killedByEntity) return "MOB";
        if (damageCause == null) return null;
        return switch (damageCause) {
            case "FIRE_TICK" -> "FIRE";
            case "BLOCK_EXPLOSION", "ENTITY_EXPLOSION" -> "EXPLOSION";
            case "FLY_INTO_WALL" -> "SUFFOCATION";
            default -> damageCause;
        };
    }

    public boolean deathEffectsEnabled() {
        return yaml.getBoolean("Death.Effects.Enabled", true);
    }

    /** Valor crudo de Death.Effects.<Killer|Victim>.<Title|Subtitle|Sound>, o "". */
    public String deathEffect(String who, String what) {
        return yaml.getString("Death.Effects." + who + "." + what, "");
    }

    /**
     * Title/Subtitle del show PvP para UN jugador, en su idioma si Use_Player_Language
     * (server-messages.death-effects.<killer|victim>-<title|subtitle>); fallback messages.yml.
     * Los sonidos no se traducen: usar {@link #deathEffect}.
     */
    public String deathEffectFor(Player target, String who, String what) {
        if (yaml.getBoolean("Death.Use_Player_Language", true)) {
            String key = "server-messages.death-effects."
                    + who.toLowerCase(java.util.Locale.ROOT) + "-" + what.toLowerCase(java.util.Locale.ROOT);
            String translated = Lang.raw(target, key);
            if (!key.equals(translated)) return translated;
        }
        return deathEffect(who, what);
    }

    /**
     * Mensaje de grupo condicional para la seccion (Join/Quit/Death): primer grupo (orden
     * del YAML) cuyas condiciones pasan, un mensaje al azar de su lista. Null si no hay
     * grupos o ninguno aplica (se usa entonces el mensaje simple de siempre).
     *
     * @param killerName nombre del asesino (jugador o tipo de mob) para Require_Killer_Contains,
     *                   o null fuera de las muertes.
     */
    /** True si el jugador esta en modo anonimo (para no delatar su rango en los mensajes). */
    private static boolean isAnonymous(Player player) {
        dev.chatalyst.ChatalystPlugin pl = dev.chatalyst.ChatalystPlugin.get();
        return pl != null && pl.getAnonymousManager() != null
                && pl.getAnonymousManager().isAnonymous(player.getUniqueId());
    }

    public String groupMessage(Player subject, String section, String killerName) {
        var groups = yaml.getConfigurationSection(section + ".Groups");
        if (groups == null) return null;
        // Un anonimo no debe disparar un grupo por PERMISO: un "entro un VIP" delataria su
        // rango aunque el nombre sea falso. Se anulan SUS permisos aqui; los grupos por
        // Require_Worlds / Require_Killer_Contains siguen valiendo (no dependen del rango). Si
        // ningun grupo no-permiso aplica, cae al mensaje por defecto de la seccion.
        java.util.function.Predicate<String> perms = isAnonymous(subject)
                ? p -> false : subject::hasPermission;
        List<String> options = pickGroupMessages(groups, perms,
                subject.getWorld().getName(), killerName);
        if (options == null || options.isEmpty()) return null;
        return options.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size()));
    }

    /**
     * Seleccion pura del primer grupo aplicable (testeable): condiciones soportadas
     * Require_Permission, Require_Worlds y Require_Killer_Contains. Devuelve la lista
     * Messages del grupo elegido, o null.
     */
    static List<String> pickGroupMessages(org.bukkit.configuration.ConfigurationSection groups,
                                          java.util.function.Predicate<String> hasPermission,
                                          String world, String killerName) {
        for (String key : groups.getKeys(false)) {
            var g = groups.getConfigurationSection(key);
            if (g == null) continue;
            String perm = g.getString("Require_Permission", "");
            if (!perm.isEmpty() && !hasPermission.test(perm)) continue;
            List<String> worlds = g.getStringList("Require_Worlds");
            if (!worlds.isEmpty() && worlds.stream().noneMatch(w -> w.equalsIgnoreCase(world))) continue;
            List<String> killers = g.getStringList("Require_Killer_Contains");
            if (!killers.isEmpty()) {
                if (killerName == null) continue;
                String low = killerName.toLowerCase(java.util.Locale.ROOT);
                if (killers.stream().noneMatch(k -> low.contains(k.toLowerCase(java.util.Locale.ROOT)))) continue;
            }
            List<String> msgs = g.getStringList("Messages");
            if (!msgs.isEmpty()) return msgs;
        }
        return null;
    }

    public boolean motdEnabled() {
        return yaml.getBoolean("Motd.Enabled", true);
    }

    /** Lineas del MOTD ya resueltas para el jugador (first-time o default). */
    public List<Component> motd(Player p, boolean firstTime) {
        List<String> raw = withoutClosedShop(motdLines(p, firstTime), shopOpen());
        List<Component> out = new ArrayList<>();
        for (String s : raw) {
            out.add(MM.full(Placeholders.apply(p, s)));
        }
        return out;
    }

    private static boolean shopOpen() {
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        return plugin != null && plugin.getTebexShop() != null && plugin.getTebexShop().enabled();
    }

    /** Sin tienda activa, una linea que lleva a ella seria un boton que no abre nada. */
    static List<String> withoutClosedShop(List<String> lines, boolean shopOpen) {
        if (shopOpen) return lines;
        return lines.stream().filter(line -> !line.contains("{ranks_command}")).toList();
    }

    private List<String> motdLines(Player p, boolean firstTime) {
        if (yaml.getBoolean("Motd.Use_Player_Language", true)) {
            List<String> translated = Lang.rawList(p, firstTime ? MOTD_FIRST_TIME_KEY : MOTD_DEFAULT_KEY);
            if (translated.isEmpty() && firstTime) {
                translated = Lang.rawList(p, MOTD_DEFAULT_KEY);
            }
            if (!translated.isEmpty()) return translated;
        }
        return configuredMotdLines(firstTime);
    }

    private List<String> configuredMotdLines(boolean firstTime) {
        List<String> raw = yaml.getStringList(firstTime ? "Motd.First_Time" : "Motd.Default");
        if (raw.isEmpty() && firstTime) {
            raw = yaml.getStringList("Motd.Default");
        }
        return raw;
    }

    private Component line(CommandSender viewer, Player subject, String section, String path, String langKey) {
        return MM.full(Placeholders.apply(subject, lineRaw(viewer, section, path, langKey)));
    }

    private String lineRaw(CommandSender viewer, String section, String path, String langKey) {
        if (yaml.getBoolean(section + ".Use_Player_Language", true)) {
            return Lang.raw(viewer, langKey);
        }
        return yaml.getString(section + "." + path, "");
    }
}
