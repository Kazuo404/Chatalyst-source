package dev.chatalyst.vanilla;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * Reescribe con el estilo del plugin los mensajes VANILLA del servidor — el feedback plano de
 * /tp, /xp, /gamemode, /give, /kill, /effect, /time... — y los entrega en el idioma del
 * cliente que los recibe.
 *
 * <p>Minecraft manda esos mensajes como componentes <i>traducibles</i>: una clave
 * ({@code commands.teleport.success.entity.single}) mas sus argumentos, y es el cliente quien
 * pone el texto. Por eso ya son multi-idioma, pero son grises y sin formato. Aqui se detecta
 * la clave, se extraen los argumentos como {0}, {1}, {2}... y se sustituye el mensaje por la
 * plantilla de {@code lang/<idioma>.yml}, que cada dueño puede editar.
 *
 * <p>Esta clase NO depende de PacketEvents (la intercepcion vive en el puente aislado): aqui
 * solo esta la traduccion clave -> mensaje, para poder probarla y reutilizarla.
 */
public final class VanillaMessages {

    /** Clave vanilla -> clave de lang (bajo {@code vanilla.}). Ampliable sin tocar codigo. */
    private static final Map<String, String> KEYS = Map.ofEntries(
            // Teletransporte
            Map.entry("commands.teleport.success.entity.single", "teleport-entity"),
            Map.entry("commands.teleport.success.location.single", "teleport-location"),
            Map.entry("commands.teleport.success.entity.multiple", "teleport-entity-multi"),
            Map.entry("commands.teleport.success.location.multiple", "teleport-location-multi"),
            // Experiencia
            Map.entry("commands.experience.query.points", "xp-query-points"),
            Map.entry("commands.experience.query.levels", "xp-query-levels"),
            Map.entry("commands.experience.add.points.success.single", "xp-add-points"),
            Map.entry("commands.experience.add.levels.success.single", "xp-add-levels"),
            Map.entry("commands.experience.set.points.success.single", "xp-set-points"),
            Map.entry("commands.experience.set.levels.success.single", "xp-set-levels"),
            // Modo de juego y estado
            Map.entry("commands.gamemode.success.self", "gamemode-self"),
            Map.entry("commands.gamemode.success.other", "gamemode-other"),
            Map.entry("gameMode.changed", "gamemode-changed"),
            Map.entry("commands.difficulty.success", "difficulty"),
            // Objetos
            Map.entry("commands.give.success.single", "give"),
            Map.entry("commands.clear.success.single", "clear"),
            Map.entry("commands.enchant.success.single", "enchant"),
            // Mundo
            Map.entry("commands.time.set", "time-set"),
            Map.entry("commands.weather.set.clear", "weather-clear"),
            Map.entry("commands.weather.set.rain", "weather-rain"),
            Map.entry("commands.weather.set.thunder", "weather-thunder"),
            Map.entry("commands.seed.success", "seed"),
            // Entidades
            Map.entry("commands.kill.success.single", "kill"),
            Map.entry("commands.summon.success", "summon"),
            Map.entry("commands.effect.give.success.single", "effect-give"),
            Map.entry("commands.effect.clear.success.single", "effect-clear"),
            // Operadores
            Map.entry("commands.op.success", "op"),
            Map.entry("commands.deop.success", "deop"));

    private final JavaPlugin plugin;
    private volatile boolean enabled;
    private volatile List<String> ignored = List.of();

    public VanillaMessages(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("Vanilla_Messages.Enabled", true);
        ignored = plugin.getConfig().getStringList("Vanilla_Messages.Ignore");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True si esta clave vanilla tiene reescritura (y el dueño no la excluyo). */
    public boolean handles(String vanillaKey) {
        return enabled && KEYS.containsKey(vanillaKey) && !ignored.contains(vanillaKey);
    }

    /**
     * Version con estilo del mensaje, en el idioma de {@code viewer}, o null para dejar
     * pasar el original (clave desconocida, excluida, o plantilla vacia en el lang — poner
     * "" en el lang es la forma de conservar el mensaje vanilla de una clave concreta).
     */
    public Component rewrite(CommandSender viewer, Component original) {
        if (!enabled || !(original instanceof TranslatableComponent translatable)) return null;
        String langKey = KEYS.get(translatable.key());
        if (langKey == null || ignored.contains(translatable.key())) return null;
        String template = Lang.raw(viewer, "vanilla." + langKey);
        if (template.isEmpty() || template.equals("vanilla." + langKey)) return null;
        return MM.full(applyArguments(template, arguments(translatable)));
    }

    /**
     * Argumentos del componente traducible como texto plano. Un nombre de jugador o un item
     * puede venir a su vez traducible/con formato; se aplana para insertarlo escapado.
     */
    static List<String> arguments(TranslatableComponent translatable) {
        List<String> out = new java.util.ArrayList<>();
        for (var argument : translatable.arguments()) {
            Component value = argument.asComponent();
            out.add(PlainTextComponentSerializer.plainText().serialize(value));
        }
        return out;
    }

    /**
     * Sustituye {0}, {1}, {2}... por los argumentos (escapados: un nombre de item con
     * caracteres de MiniMessage no debe inyectar formato). Los indices que no existan se
     * dejan vacios en vez de mostrar "{2}" crudo.
     */
    static String applyArguments(String template, List<String> arguments) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int from = 0;
        while (true) {
            int open = template.indexOf('{', from);
            if (open < 0) break;
            int close = template.indexOf('}', open + 1);
            if (close < 0) break;
            String token = template.substring(open + 1, close);
            if (isIndex(token)) {
                int index = Integer.parseInt(token);
                out.append(template, from, open);
                if (index < arguments.size()) out.append(MM.escape(arguments.get(index)));
                from = close + 1;
            } else {
                out.append(template, from, open + 1);
                from = open + 1;
            }
        }
        out.append(template, from, template.length());
        return out.toString();
    }

    private static boolean isIndex(String token) {
        if (token.isEmpty() || token.length() > 2) return false;
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) return false;
        }
        return true;
    }

    /** Claves vanilla soportadas (para la documentacion y los tests). */
    public static java.util.Set<String> supportedKeys() {
        return KEYS.keySet();
    }
}
