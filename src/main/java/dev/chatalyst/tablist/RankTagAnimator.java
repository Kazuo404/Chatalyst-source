package dev.chatalyst.tablist;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imagen (o texto) del rango ANIMADA: {@code {rank_tag}} (2026-10-03, pedido de KZ).
 *
 * <p>Antes la etiqueta flotante usaba {@code %tab_tagprefix%} para ver animada la placa del rango, porque
 * {@code {player_prefix}} es el prefijo fijo de LuckPerms (un solo fotograma). Ahora Chatalyst lo hace solo.
 *
 * <p>El fotograma sale de la HORA: {@code (ahora / intervalo) % fotogramas}. No hay reloj ni tareas: cualquier
 * sitio que resuelva placeholders (etiqueta flotante, tab, chat, hovers) ve el fotograma del momento, y todos los
 * jugadores del mismo rango van sincronizados. Quien refresca es quien ya refrescaba (la etiqueta cada 2 ticks).
 *
 * <p>Fotogramas por grupo de LuckPerms en {@code nametag.yml -> Nametag.Rank_Animations}:
 * <pre>
 * Nametag:
 *   Rank_Animations:
 *   Enabled: true
 *   Import_From_TAB: true      # lee TAB/groups.yml (tagprefix "%animation:X%") y TAB/animations.yml
 *   Groups:                    # propios; mandan sobre los importados
 *     nova:
 *       Interval_Ms: 100
 *       Frames: ["...", "..."]
 * </pre>
 * Grupo sin animacion: {@code {rank_tag}} = el prefijo fijo de siempre.
 */
public final class RankTagAnimator {

    /** Una animacion: fotogramas y milisegundos por fotograma. */
    public record Animacion(List<String> fotogramas, long intervaloMs) {

        public Animacion {
            fotogramas = List.copyOf(fotogramas);
            intervaloMs = Math.max(50, intervaloMs);
        }

        public String en(long ahoraMs) {
            if (fotogramas.isEmpty()) return "";
            int i = (int) Math.floorMod(ahoraMs / intervaloMs, (long) fotogramas.size());
            return fotogramas.get(i);
        }
    }

    private static final Pattern ANIMACION_TAB = Pattern.compile("%animation:([A-Za-z0-9_\\-]+)%");

    private static volatile boolean enabled = false;
    private static volatile Map<String, Animacion> porGrupo = Map.of();

    private RankTagAnimator() {
    }

    /** Carga la seccion de nametag.yml; {@code carpetaTab} es plugins/TAB (o null si no hay TAB). */
    public static void load(ConfigurationSection cfg, File carpetaTab) {
        Map<String, Animacion> mapa = new HashMap<>();
        boolean on = cfg != null && cfg.getBoolean("Enabled", false);
        if (on) {
            if (cfg.getBoolean("Import_From_TAB", true) && carpetaTab != null) {
                mapa.putAll(importarDeTab(new File(carpetaTab, "groups.yml"), new File(carpetaTab, "animations.yml")));
            }
            ConfigurationSection g = cfg.getConfigurationSection("Groups");
            if (g != null) {
                for (String grupo : g.getKeys(false)) {
                    List<String> frames = g.getStringList(grupo + ".Frames");
                    if (!frames.isEmpty()) {
                        mapa.put(grupo.toLowerCase(Locale.ROOT), new Animacion(frames, g.getLong(grupo + ".Interval_Ms", 100)));
                    }
                }
            }
        }
        porGrupo = Map.copyOf(mapa);
        enabled = on && !mapa.isEmpty();
        RESUELTOS.clear();
    }

    /**
     * Grupos de TAB cuyo tagprefix (o tabprefix) es una animacion, con sus fotogramas y change-interval.
     * Ficheros que faltan o no se leen: mapa vacio.
     */
    public static Map<String, Animacion> importarDeTab(File grupos, File animaciones) {
        Map<String, Animacion> out = new HashMap<>();
        if (!grupos.isFile() || !animaciones.isFile()) return out;
        YamlConfiguration g = YamlConfiguration.loadConfiguration(grupos);
        YamlConfiguration a = YamlConfiguration.loadConfiguration(animaciones);
        for (String grupo : g.getKeys(false)) {
            String prefijo = g.getString(grupo + ".tagprefix", g.getString(grupo + ".tabprefix", ""));
            Matcher m = ANIMACION_TAB.matcher(prefijo == null ? "" : prefijo);
            if (!m.find()) continue;
            String anim = m.group(1);
            List<String> textos = new ArrayList<>(a.getStringList(anim + ".texts"));
            if (textos.isEmpty()) continue;
            out.put(grupo.toLowerCase(Locale.ROOT), new Animacion(textos, a.getLong(anim + ".change-interval", 100)));
        }
        return out;
    }

    /** Fotograma actual del grupo, o {@code fijo} (el prefijo de siempre) si no anima. */
    public static String frame(String grupo, String fijo) {
        return frameAt(grupo, fijo, System.currentTimeMillis());
    }

    static String frameAt(String grupo, String fijo, long ahoraMs) {
        if (!enabled || grupo == null || grupo.isEmpty()) return fijo == null ? "" : fijo;
        Animacion a = porGrupo.get(grupo.toLowerCase(Locale.ROOT));
        return a == null ? (fijo == null ? "" : fijo) : resolver(a.en(ahoraMs));
    }

    /** Resuelve los :atajos: de ItemsAdder de un fotograma propio; los de TAB ya son los caracteres. */
    private static volatile java.util.function.UnaryOperator<String> resolvedor = s -> s;
    private static final Map<String, String> RESUELTOS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Lo instala quien tiene ItemsAdder a mano (tests: identidad). */
    public static void setResolvedor(java.util.function.UnaryOperator<String> r) {
        resolvedor = r == null ? s -> s : r;
        RESUELTOS.clear();
    }

    private static String resolver(String fotograma) {
        if (fotograma.indexOf(':') < 0) return fotograma;
        String hecho = RESUELTOS.get(fotograma);
        if (hecho != null) return hecho;
        String r = resolvedor.apply(fotograma);
        // Solo se guarda si ItemsAdder ya lo resolvio: al arrancar puede no tener aun sus imagenes y devolverlo igual.
        if (r != null && !r.equals(fotograma)) RESUELTOS.put(fotograma, r);
        return r == null ? fotograma : r;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int grupos() {
        return porGrupo.size();
    }
}
