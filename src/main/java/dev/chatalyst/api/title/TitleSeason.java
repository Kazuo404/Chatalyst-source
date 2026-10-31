package dev.chatalyst.api.title;

/** Optional season window. Start is inclusive and end is exclusive. */
public record TitleSeason(String id, long startsAtMillis, long endsAtMillis) {

    public TitleSeason {
        id = id == null ? "" : id;
    }

    public static TitleSeason none() {
        return new TitleSeason("", 0L, 0L);
    }

    public boolean enabled() {
        return !id.isBlank();
    }
}
