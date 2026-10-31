package dev.chatalyst.tebex;

import dev.chatalyst.lang.Lang;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Immutable, bounded snapshot of the visual shown after a Tebex rank is delivered.
 *
 * <p>A rank can inherit the global presentation or override only selected leaves. Reading a
 * snapshot is cheap and side-effect free, so purchase delivery never retains mutable Bukkit
 * configuration sections across scheduler boundaries.
 *
 * <p>Every text is a {@link Localized} pair rather than a plain string, because a GLOBAL
 * announcement is read by everyone at once and the snapshot is taken before anyone is looked
 * at: carrying only one language here would decide, at resolve time, which half of the server
 * gets to understand it.
 */
public record TebexPresentation(
        boolean enabled,
        Audience audience,
        Preset preset,
        boolean chatEnabled,
        Localized chatMessage,
        boolean toastEnabled,
        Localized toastTitle,
        Localized toastDescription,
        String toastIcon,
        String toastFrame,
        boolean titleEnabled,
        Localized title,
        Localized subtitle,
        int fadeInTicks,
        int stayTicks,
        int fadeOutTicks,
        boolean actionBarEnabled,
        Localized actionBarMessage,
        boolean soundEnabled,
        String soundKey,
        float soundVolume,
        float soundPitch,
        boolean fireworkEnabled,
        boolean inherited) {

    public static final String GLOBAL_PATH = "Tebex.Presentation";
    private static final int MAX_TEXT = 1_024;

    /**
     * Un texto escrito por el dueño del servidor, en los dos idiomas del proyecto.
     *
     * <p>Convención {@code _es} de siempre (ver {@link dev.chatalyst.lang.LocalizedConfig}):
     * una traducción en blanco cuenta como ausente, porque quien dejó {@code Message_es: ""}
     * quiere el texto base, no una línea vacía.
     */
    public record Localized(String base, String spanish) {

        public String forViewer(CommandSender viewer) {
            return pick(viewer != null && Lang.isSpanish(viewer));
        }

        public String pick(boolean spanish) {
            return spanish && this.spanish != null && !this.spanish.isBlank()
                    ? this.spanish : base;
        }
    }

    public enum Audience {
        PURCHASER,
        GLOBAL;

        public Audience next() {
            return this == PURCHASER ? GLOBAL : PURCHASER;
        }

        static Audience parse(String value) {
            if (value == null) return PURCHASER;
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "GLOBAL", "ALL", "TODOS" -> GLOBAL;
                default -> PURCHASER;
            };
        }
    }

    public enum Preset {
        SUBTLE,
        FANCY,
        SILENT,
        CUSTOM;

        public Preset next() {
            return switch (this) {
                case SUBTLE -> FANCY;
                case FANCY -> SILENT;
                case SILENT, CUSTOM -> SUBTLE;
            };
        }

        static Preset parse(String value) {
            if (value == null) return SUBTLE;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CUSTOM;
            }
        }
    }

    public static TebexPresentation resolve(FileConfiguration config, String rankId) {
        ConfigurationSection global = config.getConfigurationSection(GLOBAL_PATH);
        ConfigurationSection rank = rankId == null || rankId.isBlank() ? null
                : config.getConfigurationSection(
                        "Rank_Catalog." + rankId + ".Presentation");
        boolean inherited = rank == null || rank.getBoolean("Use_Global", true);
        ConfigurationSection custom = inherited ? null : rank;
        Layered values = new Layered(custom, global);
        return new TebexPresentation(
                values.bool("Enabled", true),
                Audience.parse(values.string("Audience", "PURCHASER")),
                Preset.parse(values.string("Preset", "SUBTLE")),
                values.bool("Chat.Enabled", true),
                values.localized("Chat.Message",
                        "<#B56CFF>✦ <#FBF2FF>{player} <#C0B3D9>received "
                                + "<#E4A8FF>{rank_id}<#C0B3D9>.",
                        "<#B56CFF>✦ <#FBF2FF>{player} <#C0B3D9>ha recibido "
                                + "<#E4A8FF>{rank_id}<#C0B3D9>."),
                values.bool("Toast.Enabled", false),
                values.localized("Toast.Title",
                        "<gradient:#B56CFF:#E4A8FF>Rank received</gradient>",
                        "<gradient:#B56CFF:#E4A8FF>Rango recibido</gradient>"),
                values.localized("Toast.Description",
                        "<#FBF2FF>{rank_id} <#C0B3D9>is now active",
                        "<#FBF2FF>{rank_id} <#C0B3D9>ya está activo"),
                icon(values.string("Toast.Icon", "EMERALD")),
                frame(values.string("Toast.Frame", "goal")),
                values.bool("Title.Enabled", false),
                values.localized("Title.Title",
                        "<gradient:#B56CFF:#E4A8FF><bold>NEW RANK</bold></gradient>",
                        "<gradient:#B56CFF:#E4A8FF><bold>RANGO NUEVO</bold></gradient>"),
                values.localized("Title.Subtitle",
                        "<#FBF2FF>{player} <#C0B3D9>obtained <#E4A8FF>{rank_id}",
                        "<#FBF2FF>{player} <#C0B3D9>ha obtenido <#E4A8FF>{rank_id}"),
                values.integer("Title.Fade_In_Ticks", 10, 0, 200),
                values.integer("Title.Stay_Ticks", 60, 1, 1_200),
                values.integer("Title.Fade_Out_Ticks", 20, 0, 200),
                values.bool("ActionBar.Enabled", false),
                values.localized("ActionBar.Message",
                        "<#E4A8FF>Thank you! <#FBF2FF>{rank_id} is active.",
                        "<#E4A8FF>¡Gracias! <#FBF2FF>{rank_id} está activo."),
                values.bool("Sound.Enabled", true),
                values.string("Sound.Key", "minecraft:ui.toast.challenge_complete"),
                values.floating("Sound.Volume", 1.0f, 0.0f, 10.0f),
                values.floating("Sound.Pitch", 1.0f, 0.5f, 2.0f),
                values.bool("Firework.Enabled", false),
                inherited);
    }

    public boolean silent() {
        return !enabled || preset == Preset.SILENT
                || !(chatEnabled || toastEnabled || titleEnabled || actionBarEnabled
                || soundEnabled || fireworkEnabled);
    }

    static String icon(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}")
                || normalized.equals("AIR")
                || normalized.equals("CAVE_AIR")
                || normalized.equals("VOID_AIR")) {
            return "EMERALD";
        }
        return normalized;
    }

    public Material toastIconMaterial() {
        Material parsed = Material.matchMaterial(toastIcon);
        return parsed != null && parsed.isItem() && !parsed.isAir()
                ? parsed : Material.EMERALD;
    }

    static String frame(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "task", "challenge" -> raw.trim().toLowerCase(Locale.ROOT);
            default -> "goal";
        };
    }

    private record Layered(ConfigurationSection custom, ConfigurationSection global) {

        private Object value(String path) {
            if (custom != null && custom.contains(path)) return custom.get(path);
            return global == null ? null : global.get(path);
        }

        String string(String path, String fallback) {
            Object value = value(path);
            return value == null ? fallback : String.valueOf(value).trim();
        }

        String text(String path, String fallback) {
            String value = string(path, fallback).replace('\r', ' ').replace('\n', ' ');
            return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
        }

        /**
         * El par completo de un texto — base y {@code _es} — leído de UNA SOLA capa.
         *
         * <p>Si la capa del rango define cualquiera de los dos, el par entero sale de ahí. La
         * alternativa (buscar cada idioma por separado) juntaría el texto propio de un rango
         * con la traducción global de otro texto: dos frases distintas, una por idioma, sin
         * que nadie lo haya escrito así. Quedarse en un solo idioma es menos malo que eso, y
         * es lo que ya hace {@link dev.chatalyst.lang.LocalizedConfig} con las listas.
         */
        Localized localized(String path, String fallback, String spanishFallback) {
            String spanishPath = path + "_es";
            boolean rankOwnsIt = custom != null
                    && (custom.contains(path) || custom.contains(spanishPath));
            ConfigurationSection layer = rankOwnsIt ? custom : global;
            // Quien escribió el texto base y NO su traducción está diciendo, sin decirlo, que
            // ese es el texto que quiere. Servirle el castellano de fábrica al lado dejaría a
            // media sala leyendo una frase que ese dueño nunca escribió; el de fábrica solo
            // vale mientras nadie haya tocado nada.
            boolean ownWording = layer != null && layer.contains(path);
            return new Localized(
                    bounded(layer, path, fallback),
                    bounded(layer, spanishPath, ownWording ? "" : spanishFallback));
        }

        private static String bounded(ConfigurationSection section, String path,
                                      String fallback) {
            Object raw = section == null ? null : section.get(path);
            String value = (raw == null ? fallback : String.valueOf(raw).trim())
                    .replace('\r', ' ').replace('\n', ' ');
            return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
        }

        boolean bool(String path, boolean fallback) {
            Object value = value(path);
            if (value instanceof Boolean bool) return bool;
            if (value instanceof String text) {
                if (text.equalsIgnoreCase("true")) return true;
                if (text.equalsIgnoreCase("false")) return false;
            }
            return fallback;
        }

        int integer(String path, int fallback, int min, int max) {
            Object value = value(path);
            int parsed = value instanceof Number number ? number.intValue() : fallback;
            return Math.max(min, Math.min(max, parsed));
        }

        float floating(String path, float fallback, float min, float max) {
            Object value = value(path);
            float parsed = value instanceof Number number ? number.floatValue() : fallback;
            if (!Float.isFinite(parsed)) parsed = fallback;
            return Math.max(min, Math.min(max, parsed));
        }
    }
}
