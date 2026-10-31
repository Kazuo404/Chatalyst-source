package dev.chatalyst.tablist;

import dev.chatalyst.util.MM;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Configuracion y logica pura del nametag de VARIAS LINEAS (estilo UnlimitedNameTags): que
 * lineas le tocan a un jugador segun sus permisos, cuales sobreviven al filtro de vacias y con
 * que aspecto se pinta el cartel. Sin servidor: todo lo de entidad vive en
 * {@link FancyNametagManager}.
 */
final class NametagLines {

    private static final int MAX_LINES = 16;
    private static final int MAX_GROUPS = 64;
    private static final int MAX_TEMPLATE_LENGTH = 2048;
    /** Alto de un renglon de TextDisplay a escala 1, en bloques. */
    static final double LINE_HEIGHT = 0.25;

    /**
     * Aspecto del cartel. {@code backgroundArgb} ya trae la opacidad; 0 = sin fondo (texto
     * flotante, el aspecto historico). {@code viewRange} va en multiplos de 64 bloques, como
     * {@link Display#setViewRange}.
     */
    record Look(int backgroundArgb, boolean shadow, boolean seeThrough, float scale,
                Display.Billboard billboard, float viewRange) {

        /** El aspecto de siempre: sin fondo, sin sombra, escala 1, centrado, 64 bloques. */
        static Look classic() {
            return new Look(0, false, false, 1f, Display.Billboard.CENTER, 1f);
        }
    }

    /**
     * Grupo por permiso. {@code lines} vacio = hereda las lineas generales (el grupo solo cambia
     * el aspecto).
     */
    record Group(String id, String permission, List<String> lines, Look look) {
    }

    record Settings(List<String> lines, boolean removeEmpty, List<Group> groups, Look look,
                    byte sneakOpacity) {

        /** Sin Lines ni Groups y con el aspecto historico: identico a antes de existir esto. */
        static Settings classic() {
            return new Settings(List.of(), true, List.of(), Look.classic(), (byte) 70);
        }
    }

    private NametagLines() {
    }

    /** Lee {@code Nametag.Fancy}. Con la seccion ausente devuelve el comportamiento historico. */
    static Settings load(ConfigurationSection fancy) {
        if (fancy == null) return Settings.classic();
        Look look = look(fancy, Look.classic());
        List<Group> groups = new ArrayList<>();
        ConfigurationSection section = fancy.getConfigurationSection("Groups");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection group = section.getConfigurationSection(id);
                if (group == null) continue;
                String permission = group.getString("Permission", "");
                // Sin permiso el grupo encajaria con todos (o con nadie): se ignora.
                if (permission == null || permission.isBlank()) continue;
                groups.add(new Group(id, permission.trim(), lines(group), look(group, look)));
                if (groups.size() == MAX_GROUPS) break;
            }
        }
        return new Settings(lines(fancy), fancy.getBoolean("Remove_Empty_Lines", true),
                List.copyOf(groups), look,
                (byte) clamp(fancy.getInt("Sneak_Opacity", 70), Byte.MIN_VALUE, Byte.MAX_VALUE));
    }

    private static List<String> lines(ConfigurationSection section) {
        List<String> out = new ArrayList<>();
        for (String line : section.getStringList("Lines")) {
            if (line == null || line.length() > MAX_TEMPLATE_LENGTH) continue;
            out.add(line);
            if (out.size() == MAX_LINES) break;
        }
        return List.copyOf(out);
    }

    /** Aspecto de una seccion; lo que no defina lo hereda de {@code base} (grupo -> general). */
    private static Look look(ConfigurationSection section, Look base) {
        int background = base.backgroundArgb();
        if (section.isConfigurationSection("Background")) {
            // El fondo se define entero: el alfa heredado de un fondo apagado seria 0.
            background = !section.getBoolean("Background.Enabled", false) ? 0
                    : argb(section.getString("Background.Color", ""),
                            section.getInt("Background.Opacity", 64));
        }
        double scale = section.getDouble("Scale", base.scale());
        double distance = section.getDouble("View_Distance", base.viewRange() * 64.0);
        return new Look(background,
                section.getBoolean("Shadow", base.shadow()),
                section.getBoolean("See_Through", base.seeThrough()),
                Double.isFinite(scale) ? (float) Math.max(0.1, Math.min(10.0, scale)) : base.scale(),
                billboard(section.getString("Billboard"), base.billboard()),
                Double.isFinite(distance)
                        ? (float) (Math.max(1.0, Math.min(512.0, distance)) / 64.0)
                        : base.viewRange());
    }

    /** "#RRGGBB" + opacidad 0-255 -> ARGB. Un color invalido cae a negro. */
    static int argb(String hex, int opacity) {
        int rgb = 0;
        if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) {
            rgb = Integer.parseInt(hex.substring(1), 16);
        }
        return (clamp(opacity, 0, 255) << 24) | rgb;
    }

    private static Display.Billboard billboard(String name, Display.Billboard fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return Display.Billboard.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /**
     * El grupo del jugador: el PRIMERO de la lista cuyo permiso tenga (como UnlimitedNameTags),
     * o {@code null} si ninguno encaja.
     */
    static Group pick(List<Group> groups, Predicate<String> hasPermission) {
        for (Group group : groups) {
            if (hasPermission.test(group.permission())) return group;
        }
        return null;
    }

    /** Aspecto efectivo: el del grupo si hay, si no el general. */
    static Look lookFor(Settings settings, Group group) {
        return group != null ? group.look() : settings.look();
    }

    /**
     * Plantillas a pintar, de arriba a abajo: las Lines del grupo, si no las generales, y si la
     * lista queda vacia el {@code format} de una sola linea (retrocompatible).
     */
    static List<String> templates(Settings settings, Group group, String format) {
        if (group != null && !group.lines().isEmpty()) return group.lines();
        if (!settings.lines().isEmpty()) return settings.lines();
        return List.of(format);
    }

    /**
     * Inserta el cuadro de la animacion donde va {@code {player_nick}} DENTRO de la linea: el
     * resto (prefijo, iconos, otras lineas) se conserva. En estatico el cuadro es el propio
     * "{player_nick}" y la linea queda intacta.
     */
    static String animate(String line, String nickFrame) {
        return line.replace("{player_nick}", nickFrame);
    }

    /**
     * Lineas YA resueltas que se pintan. Con {@code removeEmpty} se descartan las que no dejan
     * texto visible (p. ej. un %clan% vacio) para que no ocupen un renglon en blanco.
     */
    static List<String> visible(List<String> resolved, boolean removeEmpty) {
        if (!removeEmpty) return resolved;
        List<String> out = new ArrayList<>(resolved.size());
        for (String line : resolved) {
            if (!MM.strip(line).isBlank()) out.add(line);
        }
        return out;
    }

    /**
     * Cuanto hay que subir el Title para que siga ENCIMA del bloque: el texto de un TextDisplay
     * crece hacia arriba desde su origen, un renglon por cada linea extra.
     */
    static double titleLift(int lines, float scale) {
        return Math.max(0, lines - 1) * LINE_HEIGHT * scale;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
