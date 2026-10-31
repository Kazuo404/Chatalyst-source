package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Base compartida de los compositores de castigo (baneo y mute): la misma experiencia de
 * armar una sanción por piezas — duración, motivo (presets o escrito por chat con estilos)
 * y estilo visual, con vista previa exacta. Aquí vive lo idéntico entre ambos; cada
 * subclase aporta su layout ({@link #build}), su despacho ({@link #onClick}) y el par de
 * datos que difieren: la {@link ModerationFormatter.Screen pantalla} y la clave de config
 * de las duraciones del GUI.
 */
abstract class ComposerMenu implements Menu {

    // Layout común: preview arriba al centro; piezas 10/12/14 (+ una específica en 16);
    // confirmar y volver espejados alrededor del centro inferior (21/23).
    protected static final int PREVIEW = 4;
    protected static final int DURATION = 10;
    protected static final int REASON = 12;
    protected static final int STYLE = 14;
    protected static final int EXTRA = 16; // scope (ban) o shadow (mute)
    protected static final int CONFIRM = 21;
    protected static final int BACK = 23;

    /** Estilos siempre disponibles; los Fancy_Signs.Styles del server se suman detrás. */
    protected static final List<String> BUILTIN_STYLES = List.of(
            "red", "dark_red", "gold", "yellow", "aqua", "light_purple",
            "rainbow", "gradient:#240046:#9D4EDD:#E0AAFF");

    protected final StaffModerationModule module;
    protected final String targetName;
    protected final Consumer<Player> back;

    protected int durationIndex;   // subclase decide el inicial (ban: 0/perm; mute: 1/temp)
    protected int reasonIndex = -1; // -1 = motivo default del server
    protected String customReason;  // escrito por chat; tiene prioridad sobre el índice
    protected int styleIndex;       // 0 = sin estilo extra

    protected ComposerMenu(StaffModerationModule module, String targetName, Consumer<Player> back) {
        this.module = module;
        this.targetName = targetName;
        this.back = back;
    }

    /** Pantalla (BAN/MUTE): decide el motivo default y el token idioma-neutro. */
    protected abstract ModerationFormatter.Screen screen();

    /** Ruta de config de las duraciones del GUI (p. ej. Staff_Moderation.Ban.Gui_Durations). */
    protected abstract String durationsKey();

    /** Duraciones de respaldo cuando la lista del GUI está vacía o inválida. */
    protected abstract List<String> durationFallback();

    @Override
    public int size() {
        return 27;
    }

    // ---- motivo y estilo (compartido) ------------------------------------------------

    protected String currentReason(Player viewer) {
        if (customReason != null) return customReason;
        List<String> presets = presets(viewer);
        if (reasonIndex >= 0 && reasonIndex < presets.size()) return presets.get(reasonIndex);
        return module.formatter().defaultReason(screen(), viewer);
    }

    protected String styledReason(Player viewer) {
        String reason = currentReason(viewer);
        // <= 0 y > size cubren un reload que encogió los estilos sin re-render del menú.
        if (styleIndex <= 0 || styleIndex > styles().size()) return reason;
        return "<" + styles().get(styleIndex - 1) + ">" + reason;
    }

    /**
     * Motivo que se despacha al comando: si es el default sin estilo, va el TOKEN
     * idioma-neutro para que cada espectador lo vea en su idioma; con estilo o texto
     * propio se envía la composición literal del staff.
     */
    protected String commandReason(Player viewer) {
        if (customReason == null && reasonIndex < 0 && styleIndex == 0) {
            return module.formatter().defaultToken(screen());
        }
        return styledReason(viewer);
    }

    protected List<String> styles() {
        List<String> out = new ArrayList<>(BUILTIN_STYLES);
        out.addAll(module.formatter().styleNames());
        return out;
    }

    protected String styleLabel(Player viewer) {
        // Acotado como styledReason: un reload pudo encoger los estilos con el menu abierto.
        List<String> all = styles();
        if (styleIndex <= 0 || styleIndex > all.size()) {
            return Lang.raw(viewer, "staff-moderation.composer-style-default");
        }
        String tag = all.get(styleIndex - 1);
        int colon = tag.indexOf(':');
        String name = colon > 0 ? tag.substring(0, colon) : tag;
        // Los builtin se localizan; los estilos con nombre del server se muestran tal cual.
        return switch (name) {
            case "red", "dark_red", "gold", "yellow", "aqua", "light_purple",
                 "rainbow", "gradient" ->
                    Lang.raw(viewer, "staff-moderation.style-" + name);
            default -> name;
        };
    }

    protected List<String> presets(Player viewer) {
        return dev.chatalyst.lang.LocalizedConfig.list(
                module.plugin().getConfig(), "Staff_Moderation.Reason_Presets", viewer);
    }

    // ---- duración (compartido, parametrizado por durationsKey/durationFallback) --------

    /** Duraciones temporales válidas del GUI (sin permanente; el permanente va aparte). */
    protected List<String> configuredDurations() {
        List<String> valid = new ArrayList<>();
        for (String raw : module.plugin().getConfig().getStringList(durationsKey())) {
            long parsed = TimeUtil.parseDuration(raw);
            if (parsed > 0 && parsed != TimeUtil.PERMANENT) valid.add(raw.trim());
        }
        return valid.isEmpty() ? durationFallback() : valid;
    }

    /** Opciones del selector: permanente primero, luego las temporales configuradas. */
    protected List<Long> durations() {
        List<Long> out = new ArrayList<>();
        out.add(TimeUtil.PERMANENT);
        for (String raw : configuredDurations()) out.add(TimeUtil.parseDuration(raw));
        return out;
    }

    /** Cadena cruda de la duración en {@code index} (0 = "perm"), para pasarla al comando. */
    protected String rawDuration(int index) {
        if (index == 0) return "perm";
        List<String> valid = configuredDurations();
        return valid.get(Math.min(index - 1, valid.size() - 1));
    }

    protected String durationLabel(Player viewer) {
        // Igual que styledReason: un reload puede encoger la lista de duraciones mientras el
        // compositor sigue abierto con un durationIndex viejo; sin acotar, .get(...) reventaria.
        List<Long> all = durations();
        long duration = all.get(Math.max(0, Math.min(durationIndex, all.size() - 1)));
        return duration == TimeUtil.PERMANENT
                ? Lang.raw(viewer, "staff-moderation.duration-permanent")
                : TimeUtil.formatElapsed(duration);
    }
}
