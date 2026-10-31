package dev.chatalyst.hook;

import dev.chatalyst.api.TitlesApi;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitlePosition;
import dev.chatalyst.api.title.TitleProfile;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Immutable, player-safe source for the title PlaceholderAPI expansion. */
record TitlePlaceholderValues(
        String id,
        String name,
        String text,
        String animatedText,
        String prefix,
        String suffix,
        String rarity,
        String category,
        String position,
        String season,
        int titlePoints,
        long achievementPoints,
        int earned,
        int total,
        String percent,
        int completedCollections,
        int totalCollections
) {

    /** Placeholders que solo dependen del titulo equipado. */
    private static final java.util.Set<String> TITLE_FIELDS = java.util.Set.of(
            "title_id", "title_name", "title_text", "title_text_anim", "title_prefix",
            "title_suffix", "title_rarity", "title_category", "title_position", "title_season",
            "title_points");

    /** Placeholders que salen del perfil (recorren todo el catalogo y las colecciones). */
    private static final java.util.Set<String> PROFILE_FIELDS = java.util.Set.of(
            "achievement_points", "achievements_earned", "achievements_total",
            "achievements_percent", "collections_completed", "collections_total");

    /** Perfil vacio para cuando solo se pide un campo del titulo y el perfil no se usa. */
    private static TitleProfile emptyProfile(UUID playerId) {
        return new TitleProfile(playerId, "", 0L, 0, 0, 0, 0);
    }

    /**
     * Valor de UN placeholder calculando solo lo que necesita. TAB o un scoreboard piden
     * {@code %chatalyst_title_text%} por jugador cada pocos ticks, y antes cada peticion
     * calculaba tambien el perfil entero (recorrido de todo el catalogo y las colecciones).
     * Devuelve null si el placeholder no es de titulos, igual que {@link #value}.
     */
    static String valueOf(TitlesApi titles, UUID playerId, String parameter) {
        if (TITLE_FIELDS.contains(parameter)) {
            return from(titles.equipped(playerId), emptyProfile(playerId)).value(parameter);
        }
        if (PROFILE_FIELDS.contains(parameter)) {
            return from(Optional.empty(), titles.profile(playerId)).value(parameter);
        }
        return null;
    }

    static TitlePlaceholderValues from(TitlesApi titles, UUID playerId) {
        TitleProfile profile = titles.profile(playerId);
        Optional<TitleDefinition> selected = titles.equipped(playerId);
        return from(selected, profile);
    }

    static TitlePlaceholderValues from(Optional<TitleDefinition> selected,
                                       TitleProfile profile) {
        TitleDefinition title = selected.orElse(null);
        String gap = title == null ? "" : " ".repeat(Math.max(0, title.spacing()));
        // Fuera del titulo flotante nada se anima: <flujo>/<brillo> salen como degradado fijo.
        String raw = title == null ? "" : title.text();
        String still = dev.chatalyst.util.TitleEffects.toStatic(raw);
        String rendered = title == null ? "" : still + "<reset>";
        String prefix = title != null && title.position() == TitlePosition.LEFT
                ? rendered + gap : "";
        String suffix = title != null && title.position() == TitlePosition.RIGHT
                ? gap + rendered : "";
        return new TitlePlaceholderValues(
                title == null ? "" : title.id(),
                title == null ? "" : title.displayName(),
                still, raw,
                prefix, suffix,
                title == null ? "" : title.rarity().name(),
                title == null ? "" : title.category(),
                title == null ? "" : title.position().name(),
                title == null ? "" : title.season().id(),
                title == null ? 0 : title.points(),
                profile.achievementPoints(), profile.earnedTitles(),
                profile.availableTitles(),
                String.format(Locale.ROOT, "%.1f", profile.completionPercent()),
                profile.completedCollections(), profile.availableCollections());
    }

    String value(String parameter) {
        return switch (parameter) {
            case "title_id" -> id;
            case "title_name" -> name;
            case "title_text" -> text;
            // Con <flujo>/<brillo> crudos: SOLO para el titulo flotante de Chatalyst (Default_Format).
            case "title_text_anim" -> animatedText;
            case "title_prefix" -> prefix;
            case "title_suffix" -> suffix;
            case "title_rarity" -> rarity;
            case "title_category" -> category;
            case "title_position" -> position;
            case "title_season" -> season;
            case "title_points" -> String.valueOf(titlePoints);
            case "achievement_points" -> String.valueOf(achievementPoints);
            case "achievements_earned" -> String.valueOf(earned);
            case "achievements_total" -> String.valueOf(total);
            case "achievements_percent" -> percent;
            case "collections_completed" -> String.valueOf(completedCollections);
            case "collections_total" -> String.valueOf(totalCollections);
            default -> null;
        };
    }
}
