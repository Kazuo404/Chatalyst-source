package dev.chatalyst.tag;

import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import org.bukkit.Material;

import java.util.Locale;

/** Definicion inmutable y validada de un titulo administrable. */
public record TagDefinition(
        String id,
        String displayName,
        String text,
        String description,
        Material icon,
        TagPosition position,
        int spacing,
        boolean enabled,
        boolean hiddenUntilUnlocked,
        String category,
        TagRarity rarity,
        int sortOrder,
        int points,
        boolean secret,
        TagSeason season,
        TagRequirementGroup requirements,
        TagRewards rewards,
        TagAchievement achievement
) {

    public static final int MAX_DISPLAY_LENGTH = 48;
    public static final int MAX_TEXT_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 256;
    public static final int MAX_CATEGORY_LENGTH = 32;
    public static final int MAX_SPACING = 8;
    public static final int MAX_SORT_ORDER = 10_000;
    public static final int MAX_POINTS = 1_000_000;

    public TagDefinition {
        id = normalizeId(id);
        if (id.isEmpty()) throw new IllegalArgumentException("A title id cannot be empty");
        displayName = plain(displayName, MAX_DISPLAY_LENGTH, id);
        text = styled(text);
        description = plain(description, MAX_DESCRIPTION_LENGTH, "");
        icon = TagAchievement.validIcon(icon) ? icon : Material.NAME_TAG;
        position = position == null ? TagPosition.LEFT : position;
        spacing = Math.max(0, Math.min(MAX_SPACING, spacing));
        category = plain(category, MAX_CATEGORY_LENGTH, "General");
        rarity = rarity == null ? TagRarity.COMMON : rarity;
        sortOrder = Math.max(-MAX_SORT_ORDER, Math.min(MAX_SORT_ORDER, sortOrder));
        points = Math.max(0, Math.min(MAX_POINTS, points));
        season = season == null ? TagSeason.none() : season;
        requirements = requirements == null ? TagRequirementGroup.free() : requirements;
        rewards = rewards == null ? TagRewards.none() : rewards;
        achievement = achievement == null ? TagAchievement.defaults(icon) : achievement;
    }

    /** Constructor compatible con el esquema anterior a puntos, secretos y temporadas. */
    public TagDefinition(String id, String displayName, String text, String description,
                         Material icon, TagPosition position, int spacing, boolean enabled,
                         boolean hiddenUntilUnlocked, String category, TagRarity rarity,
                         int sortOrder, TagRequirementGroup requirements, TagRewards rewards,
                         TagAchievement achievement) {
        this(id, displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder,
                (rarity == null ? TagRarity.COMMON : rarity).defaultPoints(),
                false, TagSeason.none(), requirements, rewards, achievement);
    }

    /** Constructor compatible con el esquema avanzado anterior a los logros configurables. */
    public TagDefinition(String id, String displayName, String text, String description,
                         Material icon, TagPosition position, int spacing, boolean enabled,
                         boolean hiddenUntilUnlocked, String category, TagRarity rarity,
                         int sortOrder, TagRequirementGroup requirements, TagRewards rewards) {
        this(id, displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, requirements, rewards,
                TagAchievement.defaults(icon));
    }

    /** Constructor compatible con el esquema v1 de una sola regla. */
    public TagDefinition(String id, String displayName, String text, String description,
                         Material icon, TagPosition position, int spacing, boolean enabled,
                         boolean hiddenUntilUnlocked, TagRequirement requirement) {
        this(id, displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, "General", TagRarity.COMMON, 0,
                TagRequirementGroup.single(requirement), TagRewards.none(),
                TagAchievement.defaults(icon));
    }

    /**
     * Nombre del objeto en los menus de titulos: el titulo con SU diseño (colores y degradados,
     * efectos animados ya fijos via MM), no el nombre plano en blanco. Equipado lleva una marca verde.
     */
    public String menuName(boolean equipped) {
        String mark = equipped ? "<#3DFFA8>✔ <reset>" : "";
        return text.isBlank() ? mark + "<#FBF2FF>" + MM.escape(displayName) : mark + text + "<reset>";
    }

    // {tag_prefix}/{tag_suffix} acaban en el chat, el TAB y PlaceholderAPI, que no conocen
    // <flujo>/<brillo>/<glitch>: sin pasarlos a degradado fijo el titulo salia sin colores
    // (KZ 2026-10-17, «Leyenda del Tablero»). Solo el titulo flotante los anima.
    public String prefix() {
        return position == TagPosition.LEFT && !text.isEmpty()
                ? dev.chatalyst.util.TitleEffects.toStatic(text) + "<reset>" + " ".repeat(spacing) : "";
    }

    public String suffix() {
        return position == TagPosition.RIGHT && !text.isEmpty()
                ? " ".repeat(spacing) + dev.chatalyst.util.TitleEffects.toStatic(text) + "<reset>" : "";
    }

    public TagDefinition withDisplayName(String value) {
        return copy(value, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withText(String value) {
        return copy(displayName, value, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withDescription(String value) {
        return copy(displayName, text, value, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withIcon(Material value) {
        return copy(displayName, text, description, value, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withPosition(TagPosition value) {
        return copy(displayName, text, description, icon, value, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withSpacing(int value) {
        return copy(displayName, text, description, icon, position, value, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withEnabled(boolean value) {
        return copy(displayName, text, description, icon, position, spacing, value,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withHiddenUntilUnlocked(boolean value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                value, category, rarity, sortOrder, points, secret, season, requirements,
                rewards, achievement);
    }

    /** Compatibilidad para integraciones v1: reemplaza el grupo por una sola regla. */
    public TagDefinition withRequirement(TagRequirement value) {
        return withRequirements(TagRequirementGroup.single(value));
    }

    /** Compatibilidad de lectura para codigo v1. */
    public TagRequirement requirement() {
        return requirements.rules().isEmpty()
                ? TagRequirement.free() : requirements.rules().getFirst();
    }

    public TagDefinition withCategory(String value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, value, rarity, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withRarity(TagRarity value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, value, sortOrder, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withSortOrder(int value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, value, points, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withPoints(int value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, value, secret, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withSecret(boolean value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, value, season,
                requirements, rewards, achievement);
    }

    public TagDefinition withSeason(TagSeason value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, value,
                requirements, rewards, achievement);
    }

    public TagDefinition withRequirements(TagRequirementGroup value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                value, rewards, achievement);
    }

    public TagDefinition withRewards(TagRewards value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, value, achievement);
    }

    public TagDefinition withAchievement(TagAchievement value) {
        return copy(displayName, text, description, icon, position, spacing, enabled,
                hiddenUntilUnlocked, category, rarity, sortOrder, points, secret, season,
                requirements, rewards, value);
    }

    private TagDefinition copy(String nextDisplay, String nextText, String nextDescription,
                               Material nextIcon, TagPosition nextPosition, int nextSpacing,
                               boolean nextEnabled, boolean nextHidden,
                               String nextCategory, TagRarity nextRarity, int nextSortOrder,
                               int nextPoints, boolean nextSecret, TagSeason nextSeason,
                               TagRequirementGroup nextRequirements, TagRewards nextRewards,
                               TagAchievement nextAchievement) {
        return new TagDefinition(id, nextDisplay, nextText, nextDescription, nextIcon,
                nextPosition, nextSpacing, nextEnabled, nextHidden, nextCategory, nextRarity,
                nextSortOrder, nextPoints, nextSecret, nextSeason, nextRequirements,
                nextRewards, nextAchievement);
    }

    public boolean isAvailable(long nowMillis) {
        return season.active(nowMillis);
    }

    public String unlockToken() {
        return season.unlockToken(id);
    }

    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String normalized = Sanitize.stripInvisible(raw).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.length() <= 48) return normalized;
        // Cortar a 48 puede dejar un guion final que una segunda normalizacion quitaria: el
        // id dejaria de ser un punto fijo y byId() no encontraria su propio titulo.
        return normalized.substring(0, 48).replaceAll("-+$", "");
    }

    private static String plain(String raw, int max, String fallback) {
        String stripped = MM.strip(Sanitize.stripInvisible(raw == null ? "" : raw)).trim();
        if (stripped.isEmpty()) stripped = fallback;
        return Sanitize.limit(stripped, max);
    }

    private static String styled(String raw) {
        String safe = Sanitize.stripInvisible(raw == null ? "" : raw).trim();
        safe = dev.chatalyst.util.TitleEffects.sanitizeKeeping(Legacy.toMini(safe), MM::sanitizeInteraction);
        String plain = MM.strip(safe);
        if (plain.length() <= MAX_TEXT_LENGTH) return safe;
        // No cortamos una etiqueta MiniMessage a la mitad: una entrada demasiado larga se
        // reduce a texto plano seguro, que sigue siendo editable desde la GUI.
        return MM.escape(Sanitize.limit(plain, MAX_TEXT_LENGTH));
    }

    static boolean isAir(Material material) {
        return material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }
}
