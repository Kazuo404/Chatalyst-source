package dev.chatalyst.hologram;

import java.util.List;

/** Una imagen (o GIF) descodificada: sus fotogramas, todos del mismo tamaño. Sin Bukkit. */
public record DecodedImage(List<ImageFrame> frames) {

    public DecodedImage {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("no frames");
        frames = List.copyOf(frames);
    }

    public int width() {
        return frames.getFirst().width();
    }

    public int height() {
        return frames.getFirst().height();
    }

    public boolean animated() {
        return frames.size() > 1;
    }

    /** Retardos ya normalizados (ver {@link GifTiming#normalize}). */
    public int[] delays() {
        int[] out = new int[frames.size()];
        for (int i = 0; i < out.length; i++) out[i] = frames.get(i).delayMs();
        return GifTiming.normalize(out);
    }
}
