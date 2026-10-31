package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One safe visual-text pipeline for kick, ban and mute. Staff may use legacy {@code &4&l},
 * visual MiniMessage tags, gradients/rainbow and the named Fancy Sign styles (for example
 * {@code <galaxy>Hello}). Interaction tags are never accepted in a player-facing reason.
 */
public final class ModerationFormatter {

    public enum Screen { KICK, BAN, MUTE }

    // Motivo default idioma-neutro: se PERSISTE este token y cada espectador lo ve resuelto
    // en su idioma al renderizar (pantalla del castigado, avisos al staff, GUIs y consola).
    private static final Pattern DEFAULT_TOKEN =
            Pattern.compile("\\{default:(ban|kick|mute|freeze|escalation)\\}");
    private static final Map<String, String> DEFAULT_FALLBACK_EN = Map.of(
            "ban", "Banned by the staff team",
            "kick", "Removed by the staff team",
            "mute", "Muted by the staff team",
            "freeze", "Staff review",
            "escalation", "Accumulated punishments: repeated kicks and warnings");
    private static final Map<String, String> DEFAULT_FALLBACK_ES = Map.of(
            "ban", "Baneado por el equipo de staff",
            "kick", "Expulsado por el equipo de staff",
            "mute", "Silenciado por el equipo de staff",
            "freeze", "Revisión del staff",
            "escalation", "Acumulación de castigos: expulsiones y avisos reiterados");

    private static final Pattern LEADING_STYLE = Pattern.compile(
            "^<([a-z][a-z0-9_-]{0,31})>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINI_TAG = Pattern.compile("(?i)</?[a-z#][^<>]*>");
    private static final Pattern BASIC_MINI = Pattern.compile("(?i)</?(?:#[0-9a-f]{6}|"
            + "black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|"
            + "dark_gray|dark_grey|blue|green|aqua|red|light_purple|yellow|white|bold|b|"
            + "italic|em|i|underlined|u|strikethrough|st|obfuscated|obf|reset)>");
    private static final Pattern GRADIENT = Pattern.compile("(?i)</?gradient(?::[^>]*)?>");
    private static final Pattern RAINBOW = Pattern.compile("(?i)</?rainbow(?::[^>]*)?>");
    private static final Pattern RAINBOW_ALIAS = Pattern.compile(
            "(?i)<(/?)(?:raimbow|arcoiris|arcoíris|multicolor|multi-color|multi_color)(?=[:>])");
    private static final Pattern GRADIENT_ALIAS = Pattern.compile(
            "(?i)<(/?)(?:gradiente|degradado)(?=[:>])");

    private final JavaPlugin plugin;
    private volatile Map<String, String> styles = Map.of();

    public ModerationFormatter(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        Map<String, String> loaded = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("Fancy_Signs.Styles");
        if (section != null) {
            for (String raw : section.getKeys(false)) {
                String format = section.getString(raw + ".Format", "");
                String id = raw.toLowerCase(Locale.ROOT);
                if (id.matches("[a-z][a-z0-9_-]{0,31}") && format.contains("{text}")) {
                    loaded.put(id, format);
                }
            }
        }
        styles = Map.copyOf(loaded);
    }

    /** Sanitized, serialized MiniMessage suitable for persistence and later re-rendering. */
    public String reason(CommandSender author, String input) {
        String clean = limit(Sanitize.stripInvisible(input == null ? "" : input));
        Component rendered;
        if (StaffAccess.has(author, "chatalyst.moderation.format")) {
            rendered = renderVisual(clean, styles);
        } else {
            rendered = Component.text(plainFallback(clean));
        }
        return MM.serialize(rendered);
    }

    /**
     * Re-renders a previously sanitized reason (also tolerates old plain stored reasons).
     *
     * <p>Sin {@link #limit}: el motivo ya se recorto al CREARLO, sobre el texto escrito. Lo
     * guardado es MiniMessage serializado, y un degradado o arcoiris lo multiplica por diez;
     * recortarlo otra vez aqui partia una etiqueta por la mitad y el motivo salia roto.
     */
    public Component reasonComponent(String stored) {
        return renderVisual(Sanitize.stripInvisible(stored == null ? "" : stored), styles);
    }

    /** Variante por-espectador: resuelve el token de motivo default en su idioma. */
    public Component reasonComponent(CommandSender viewer, String stored) {
        return reasonComponent(resolveStored(viewer, stored));
    }

    /** Renders trusted visual configuration with the same aliases and named styles. */
    public Component visual(String input) {
        return renderVisual(input == null ? "" : input, styles);
    }

    /** Safe MiniMessage template source; component placeholders may be inserted afterwards. */
    String visualTemplate(String input) {
        return stripUnsupportedTags(Legacy.toMini(expandNamedStyle(
                normalizeAliases(input == null ? "" : input), styles)));
    }

    public String plainReason(String stored) {
        // Sin espectador (historial, auditoria, espejo nativo): el token default se resuelve
        // al idioma base del server, nunca se persiste tal cual en texto plano.
        return MM.strip(MM.serialize(reasonComponent(resolveStored(false, stored))));
    }

    /** Variante por-espectador para lores y /check: token default en su idioma. */
    public String plainReason(CommandSender viewer, String stored) {
        return MM.strip(MM.serialize(reasonComponent(resolveStored(viewer, stored))));
    }

    /** Nombres de los estilos Fancy_Signs disponibles, para ofrecerlos en las GUIs. */
    public List<String> styleNames() {
        return List.copyOf(styles.keySet());
    }

    /** Vista previa exacta de un motivo: el mismo pipeline que aplicaria el castigo real. */
    public Component preview(CommandSender author, String input) {
        return reasonComponent(reason(author, input));
    }

    /** Builds the permanent-ban reason generated after abandoning a freeze review. */
    public String freezeDisconnectReason(boolean spanish, String originalReason) {
        String raw = dev.chatalyst.lang.LocalizedConfig.string(plugin.getConfig(),
                "Staff_Moderation.Freeze.Disconnect_Ban_Reason", spanish,
                "<#FF4D6D><bold>Disconnected during an active staff review</bold>"
                        + " <#7B68A0>· <#C0B3D9>Original reason: <#FBF2FF>{reason}");
        String safeTemplate = visualTemplate(raw)
                .replace("{reason}", "<freeze_original_reason>");
        // El motivo original puede ser un token default: se fija aqui al idioma del baneado,
        // porque esta composicion se persiste en su pantalla de ban.
        String original = resolveStored(spanish, originalReason);
        try {
            return MM.serialize(MM.full(safeTemplate, Placeholder.component(
                    "freeze_original_reason", reasonComponent(original))));
        } catch (RuntimeException ex) {
            return MM.serialize(Component.text(plainFallback(raw) + " · "
                    + plainReason(original)));
        }
    }

    /** Token que un comando persiste cuando el staff no escribió ningún motivo. */
    public String defaultToken(Screen screen) {
        return "{default:" + screen.name().toLowerCase(Locale.ROOT) + "}";
    }

    /** Token del motivo default de /freeze (no es una pantalla, pero se resuelve igual). */
    public static String freezeDefaultToken() {
        return "{default:freeze}";
    }

    /** Token del ban automatico por acumulacion de castigos (Escalation.Default_Reason). */
    public static String escalationToken() {
        return "{default:escalation}";
    }

    /**
     * Si {@code stored} es un token de motivo default, lo devuelve resuelto en el idioma
     * del espectador (config Default_Reason/_es, o el fallback bilingüe de fábrica);
     * cualquier otro motivo pasa intacto.
     */
    public String resolveStored(CommandSender viewer, String stored) {
        return resolveStored(Lang.isSpanish(viewer), stored);
    }

    String resolveStored(boolean spanish, String stored) {
        String action = tokenAction(stored);
        if (action == null) return stored;
        String base = "Staff_Moderation." + Character.toUpperCase(action.charAt(0))
                + action.substring(1) + ".Default_Reason";
        String value = dev.chatalyst.lang.LocalizedConfig.string(
                plugin.getConfig(), base, spanish, "");
        return value.isBlank() ? defaultFallback(action, spanish) : value;
    }

    /** Accion del token default ("ban", "kick", "mute", "freeze"), o null si no es token. */
    static String tokenAction(String stored) {
        if (stored == null) return null;
        Matcher token = DEFAULT_TOKEN.matcher(stored.trim());
        return token.matches() ? token.group(1) : null;
    }

    /** Motivo default de fabrica (coincide con moderation.yml) cuando la config no lo trae. */
    static String defaultFallback(String action, boolean spanish) {
        return (spanish ? DEFAULT_FALLBACK_ES : DEFAULT_FALLBACK_EN).get(action);
    }

    public String defaultReason(Screen screen, CommandSender viewer) {
        return resolveStored(Lang.isSpanish(viewer), defaultToken(screen));
    }

    public Component screen(Player viewer, Screen screen, String playerName, String staff,
                            long until, String storedReason) {
        return screen(viewer, screen, playerName, staff, until, storedReason, 0L, 0L);
    }

    /**
     * La pantalla con el NUMERO del castigo ({@code {id}}), la fecha en que se puso
     * ({@code {date}}) y, en los bans, el enlace de apelacion ({@code {appeal}},
     * Staff_Moderation.Appeal_Link). Si la plantilla de moderation.yml no
     * coloca alguno de esos datos y el dato existe, se añade su linea (ver ScreenExtras): el
     * numero es lo que el jugador tiene que dar al apelar, y no puede depender de que el dueño
     * haya actualizado su config.
     *
     * @param id    numero del castigo, 0 si no tiene
     * @param since cuando se puso, 0 si no se sabe
     */
    public Component screen(Player viewer, Screen screen, String playerName, String staff,
                            long until, String storedReason, long id, long since) {
        Component reason = reasonComponent(viewer, storedReason);
        NamedTextColor color = switch (screen) {
            case KICK -> NamedTextColor.YELLOW;
            case BAN -> NamedTextColor.RED;
            case MUTE -> NamedTextColor.GOLD;
        };
        Component highlightedPlayer = Component.text(playerName, color, TextDecoration.BOLD);
        String duration = until == TimeUtil.PERMANENT
                ? Lang.raw(viewer, "staff-moderation.duration-permanent")
                : TimeUtil.formatRemaining(until);
        String remaining = remainingText(viewer, until);
        String expires = expiresText(viewer, until);

        String idText = dev.chatalyst.staff.tools.PunishmentIds.label(id);
        String dateText = since > 0 ? expiresText(viewer, since) : "";
        String appeal = screen == Screen.BAN ? appealLink(viewer) : "";

        List<String> templates = screenLines(viewer, screen);
        List<String> extras = new ArrayList<>();
        addExtra(extras, templates, viewer, "{date}", dateText, "staff-tools.screen-date");
        addExtra(extras, templates, viewer, "{id}", idText, "staff-tools.screen-id");
        addExtra(extras, templates, viewer, "{appeal}", appeal, "staff-tools.screen-appeal");
        templates = dev.chatalyst.staff.tools.ScreenExtras.insert(templates, extras);

        List<Component> rendered = new ArrayList<>(templates.size());
        for (String raw : templates) {
            String template = Legacy.toMini(expandNamedStyle(raw, styles))
                    .replace("{player}", "<moderated_player>")
                    .replace("{staff}", "<moderating_staff>")
                    .replace("{duration}", "<moderation_duration>")
                    .replace("{remaining}", "<moderation_remaining>")
                    .replace("{expires}", "<moderation_expires>")
                    .replace("{reason}", "<moderation_reason>")
                    .replace("{id}", "<moderation_id>")
                    .replace("{date}", "<moderation_date>")
                    .replace("{appeal}", "<moderation_appeal>");
            try {
                rendered.add(MM.full(template,
                        Placeholder.component("moderated_player", highlightedPlayer),
                        Placeholder.component("moderating_staff", Component.text(staff)),
                        Placeholder.component("moderation_duration", Component.text(duration)),
                        Placeholder.component("moderation_remaining", Component.text(remaining)),
                        Placeholder.component("moderation_expires", Component.text(expires)),
                        Placeholder.component("moderation_reason", reason),
                        Placeholder.component("moderation_id", Component.text(idText)),
                        Placeholder.component("moderation_date", Component.text(dateText)),
                        Placeholder.component("moderation_appeal", Component.text(appeal))));
            } catch (RuntimeException ex) {
                rendered.add(Component.text(plainFallback(raw
                        .replace("{player}", playerName)
                        .replace("{staff}", staff)
                        .replace("{duration}", duration)
                        .replace("{remaining}", remaining)
                        .replace("{expires}", expires)
                        .replace("{reason}", plainReason(storedReason))
                        .replace("{id}", idText)
                        .replace("{date}", dateText)
                        .replace("{appeal}", appeal))));
            }
        }
        return joinLines(rendered);
    }

    /**
     * Linea extra de la pantalla para un dato que la plantilla no coloca. Sin dato, o sin la
     * clave en el idioma todavia (Lang devuelve la propia clave), no se añade nada: mejor una
     * pantalla sin numero que una con "staff-tools.screen-id" escrito.
     */
    private static void addExtra(List<String> extras, List<String> template, Player viewer,
                                 String placeholder, String value, String key) {
        if (value == null || value.isBlank()) return;
        if (dev.chatalyst.staff.tools.ScreenExtras.placed(template, placeholder)) return;
        String line = Lang.raw(viewer, key);
        if (line == null || line.equals(key)) return;
        extras.add(line);
    }

    /**
     * Enlace de apelacion de los bans (Staff_Moderation.Appeal_Link / Appeal_Link_es), como
     * el $appealMessage. Es texto del dueño, pero acaba DENTRO de una plantilla
     * que se parsea: se le quitan las etiquetas para que solo pueda ser texto.
     */
    public String appealLink(CommandSender viewer) {
        String raw = dev.chatalyst.lang.LocalizedConfig.string(plugin.getConfig(),
                "Staff_Moderation.Appeal_Link", viewer, "");
        return raw == null ? "" : plainFallback(raw).trim();
    }

    /**
     * Tiempo restante desglosado y localizado ("1 año 2 meses 5 días 3 horas 10 minutos");
     * cada intento de login lo recalcula, asi el baneado ve su cuenta regresiva real.
     */
    public String remainingText(CommandSender viewer, long until) {
        if (until == TimeUtil.PERMANENT) {
            return Lang.raw(viewer, "staff-moderation.duration-permanent");
        }
        long ms = until - System.currentTimeMillis();
        if (ms <= 0) return Lang.raw(viewer, "staff-moderation.remaining-expired");
        long minutes = ms / 60_000L;
        long years = minutes / (365L * 24 * 60);
        minutes -= years * 365L * 24 * 60;
        long months = minutes / (30L * 24 * 60);
        minutes -= months * 30L * 24 * 60;
        long days = minutes / (24 * 60);
        minutes -= days * 24 * 60;
        long hours = minutes / 60;
        minutes -= hours * 60;

        StringBuilder out = new StringBuilder();
        appendUnit(out, viewer, years, "time-year", "time-years");
        appendUnit(out, viewer, months, "time-month", "time-months");
        appendUnit(out, viewer, days, "time-day", "time-days");
        appendUnit(out, viewer, hours, "time-hour", "time-hours");
        appendUnit(out, viewer, minutes, "time-minute", "time-minutes");
        return out.isEmpty()
                ? Lang.raw(viewer, "staff-moderation.time-under-minute") : out.toString().trim();
    }

    /** Fecha y hora exactas de expiracion en la zona horaria del servidor. */
    public String expiresText(CommandSender viewer, long until) {
        if (until == TimeUtil.PERMANENT) {
            return Lang.raw(viewer, "staff-moderation.expires-never");
        }
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(until));
    }

    private static void appendUnit(StringBuilder out, CommandSender viewer, long value,
                                   String singular, String plural) {
        if (value <= 0) return;
        out.append(value).append(' ')
                .append(Lang.raw(viewer, "staff-moderation." + (value == 1 ? singular : plural)))
                .append(' ');
    }

    /** Inserts a reason as a Component into a translated feedback template. */
    public Component feedback(CommandSender viewer, String key, String storedReason,
                              Object... replacements) {
        String template = Lang.raw(viewer, key, replacements)
                .replace("{reason}", "<moderation_reason>");
        return MM.full(template, Placeholder.component("moderation_reason",
                reasonComponent(viewer, storedReason)));
    }

    private List<String> screenLines(Player viewer, Screen screen) {
        List<String> lines = dev.chatalyst.lang.LocalizedConfig.list(
                plugin.getConfig(), "Staff_Moderation." + title(screen) + ".Screen", viewer);
        if (!lines.isEmpty()) return lines;
        return List.of("<#FF4D6D><bold>CHATALYST</bold>", "", "{player}",
                "<#C0B3D9>{reason}");
    }

    static Component renderVisual(String input, Map<String, String> styles) {
        try {
            return MM.safe(stripUnsupportedTags(Legacy.toMini(
                    expandNamedStyle(normalizeAliases(input), styles))));
        } catch (RuntimeException ex) {
            return Component.text(plainFallback(input));
        }
    }

    private static String expandNamedStyle(String input, Map<String, String> styles) {
        Matcher match = LEADING_STYLE.matcher(input == null ? "" : input);
        if (!match.find()) return input == null ? "" : input;
        String id = match.group(1).toLowerCase(Locale.ROOT);
        String format = styles.get(id);
        if (format == null) return input;
        String text = input.substring(match.end());
        String close = "</" + id + ">";
        if (text.toLowerCase(Locale.ROOT).endsWith(close)) {
            text = text.substring(0, text.length() - close.length());
        }
        return format.replace("{text}", text);
    }

    private static String normalizeAliases(String input) {
        String normalized = RAINBOW_ALIAS.matcher(input).replaceAll("<$1rainbow");
        return GRADIENT_ALIAS.matcher(normalized).replaceAll("<$1gradient");
    }

    private static String stripUnsupportedTags(String input) {
        Matcher matcher = MINI_TAG.matcher(input);
        StringBuilder out = new StringBuilder(input.length());
        while (matcher.find()) {
            String tag = matcher.group();
            boolean supported = BASIC_MINI.matcher(tag).matches()
                    || GRADIENT.matcher(tag).matches() || RAINBOW.matcher(tag).matches();
            matcher.appendReplacement(out, supported ? Matcher.quoteReplacement(tag) : "");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String limit(String input) {
        int max = Math.max(32, plugin.getConfig()
                .getInt("Staff_Moderation.Max_Reason_Length", 256));
        int points = input.codePointCount(0, input.length());
        if (points <= max) return input;
        return input.substring(0, input.offsetByCodePoints(0, max));
    }

    private static String plainFallback(String input) {
        String legacy = Legacy.toMini(input == null ? "" : input);
        try {
            return MM.strip(legacy);
        } catch (RuntimeException ignored) {
            return MINI_TAG.matcher(legacy).replaceAll("");
        }
    }

    private static Component joinLines(List<Component> lines) {
        TextComponent.Builder out = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(Component.newline());
            out.append(lines.get(i));
        }
        return out.build();
    }

    private static String title(Screen screen) {
        String lower = screen.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
