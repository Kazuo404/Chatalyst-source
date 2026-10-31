package dev.chatalyst.giveaway;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a spin: when each step of the reel happens, and at what pitch it ticks.
 *
 * <p>A wheel that moves at a constant speed and then simply stops does not read as a wheel —
 * it reads as a slideshow that ended. What sells it is deceleration, so the schedule is a
 * cubic ease-out: the gap between steps grows from {@link #MIN_DELAY_TICKS} to {@link
 * #MAX_DELAY_TICKS} along the spin, which puts most of the movement at the start and most of
 * the suspense at the end, with the last few slots crawling past the window one at a time.
 *
 * <p>The tick sound rides the same curve. Its pitch climbs as the wheel loses speed, so the
 * ticks both space out AND rise — the two cues the ear already associates with something
 * spinning down. That is why this is computed here rather than inside the ticker: it is the
 * one piece of the animation worth locking in a test, and the sound must not drift from the
 * movement it is supposed to be describing.
 *
 * <p>Pure and side-effect free by design.
 */
public final class GiveawaySpin {

    /** Fastest the reel ever moves: two ticks, a tenth of a second. */
    static final int MIN_DELAY_TICKS = 2;
    /** Slowest: the final slots take almost a second each. */
    static final int MAX_DELAY_TICKS = 16;
    /** Below this many steps a spin is not a spin; a very short one is padded out. */
    static final int MIN_STEPS = 6;

    private GiveawaySpin() {
    }

    /** One movement of the reel: how long to wait for it, and how far in it lands. */
    public record Step(int delayTicks, double progress) {
    }

    /**
     * @param totalTicks how long the whole spin should last
     * @return the steps in order; their delays sum to at least {@code totalTicks}
     */
    public static List<Step> schedule(int totalTicks) {
        int total = Math.max(MIN_DELAY_TICKS * MIN_STEPS, totalTicks);
        List<Integer> delays = new ArrayList<>();
        int elapsed = 0;
        while (elapsed < total) {
            int delay = delayTicks((double) elapsed / total);
            delays.add(delay);
            elapsed += delay;
        }
        // Progress is measured against what the schedule ACTUALLY spans, not the requested
        // total: the last delay usually overshoots it, and dividing by the request would hand
        // the final step a progress above 1 — an out-of-range pitch on the most audible tick.
        List<Step> steps = new ArrayList<>(delays.size());
        int span = elapsed;
        int at = 0;
        for (int delay : delays) {
            at += delay;
            steps.add(new Step(delay, span == 0 ? 1d : (double) at / span));
        }
        return List.copyOf(steps);
    }

    /** Ticks to wait before the step that lands at {@code progress} of the way through. */
    static int delayTicks(double progress) {
        double p = clamp01(progress);
        return (int) Math.round(MIN_DELAY_TICKS + (MAX_DELAY_TICKS - MIN_DELAY_TICKS) * p * p * p);
    }

    /** Pitch for a tick at {@code progress}, inside whatever range the owner configured. */
    static float pitch(double progress, float from, float to) {
        float low = clampPitch(from);
        float high = clampPitch(to);
        return clampPitch((float) (low + (high - low) * clamp01(progress)));
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0d;
        return Math.max(0d, Math.min(1d, value));
    }

    private static float clampPitch(float value) {
        if (!Float.isFinite(value)) return 1.0f;
        return Math.max(0.5f, Math.min(2.0f, value));
    }
}
