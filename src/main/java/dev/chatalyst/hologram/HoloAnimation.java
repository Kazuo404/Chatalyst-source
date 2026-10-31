package dev.chatalyst.hologram;

import java.util.List;
import java.util.Locale;

/**
 * Reloj de los carteles: que fotograma toca, cuanto dura un ciclo de cada animacion (para que
 * se repitan en bucle en vez de acabar como un /announce) y el giro y vaiven del cartel.
 * Sin Bukkit: el texto de cada fotograma lo pinta {@code AnnounceAnimator.frame}.
 */
public final class HoloAnimation {

    /** Las de AnnounceAnimator, en el orden en que se ofrecen. */
    public static final List<String> NAMES = List.of("none", "typewriter", "expand", "glitch",
            "marquee", "wave", "frames", "pulse", "flash", "countdown");

    // Constantes de AnnounceAnimator que fijan cuantos fotogramas dura cada efecto.
    private static final int CHARS_PER_FRAME = 2;
    private static final int GLITCH_REVEAL_FRAMES = 50;
    private static final int WAVE_CYCLE = 20;
    private static final int PULSE_CYCLE = 10;
    private static final int FLASH_CYCLE = 6;
    private static final int COUNTDOWN_FRAMES = 30;
    private static final int MARQUEE_GAP = 7;
    /** Fotogramas que el texto ya revelado se queda quieto antes de volver a empezar. */
    static final int HOLD_FRAMES = 30;
    /** Un vaiven completo dura 3 segundos. */
    static final int BOB_PERIOD_TICKS = 60;

    private HoloAnimation() {
    }

    /** Nombre escrito (es o en) a su nombre canonico, o null si no existe. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String name = raw.toLowerCase(Locale.ROOT).strip();
        return switch (name) {
            case "none", "ninguna", "off", "quieta", "estatica", "estática" -> "none";
            case "typewriter", "escribir", "maquina", "máquina" -> "typewriter";
            case "expand", "expandir" -> "expand";
            case "glitch", "descifrar" -> "glitch";
            case "marquee", "marquesina", "desplazar" -> "marquee";
            case "wave", "ola", "onda" -> "wave";
            case "frames", "cuadros" -> "frames";
            case "pulse", "pulso", "latido" -> "pulse";
            case "flash", "destello", "alarma" -> "flash";
            case "countdown", "cuenta", "cuentaatras" -> "countdown";
            default -> null;
        };
    }

    /**
     * Fotogramas de un ciclo completo de la animacion para un texto de {@code plainLength}
     * caracteres, contando la pausa con el texto entero. 1 = no cambia nunca.
     */
    public static int cycleLength(String animation, int plainLength, int frameCount) {
        int length = Math.max(1, plainLength);
        return switch (animation == null ? "none" : animation) {
            case "typewriter" -> ceilDiv(length, CHARS_PER_FRAME) + HOLD_FRAMES;
            case "expand" -> ceilDiv(length, CHARS_PER_FRAME * 2) + HOLD_FRAMES;
            case "glitch" -> GLITCH_REVEAL_FRAMES + HOLD_FRAMES;
            case "marquee" -> length + MARQUEE_GAP;
            case "wave" -> WAVE_CYCLE;
            case "frames" -> Math.max(1, frameCount) * 3;
            case "pulse" -> PULSE_CYCLE;
            case "flash" -> FLASH_CYCLE;
            case "countdown" -> COUNTDOWN_FRAMES + HOLD_FRAMES * 2;
            default -> 1;
        };
    }

    /**
     * Ciclo de una linea ya preparada (estilo y placeholders resueltos): 0 si no esta animada.
     * Se mide sobre lo que la animacion recorta de verdad, que es el mensaje sin las etiquetas
     * de apertura del principio.
     */
    public static int cycleOf(String animation, String prepared) {
        if (animation == null || "none".equals(animation)) return 0;
        HoloText.Split split = HoloText.split(prepared);
        return cycleLength(animation, HoloText.visibleLength(split.message()), 4);
    }

    /** Fotograma de la animacion en el tick {@code tick} del servidor. */
    public static int frameAt(long tick, int speed, int cycle) {
        if (cycle <= 1) return 0;
        long step = Math.max(0, tick) / Math.max(1, speed);
        return (int) (step % cycle);
    }

    /** Angulo del giro continuo, en radianes, en ese tick. */
    public static float spinRadians(long tick, double degreesPerSecond) {
        if (degreesPerSecond == 0) return 0f;
        double degrees = (tick * degreesPerSecond / 20.0) % 360.0;
        return (float) Math.toRadians(degrees);
    }

    /** Desplazamiento vertical del vaiven, en bloques, en ese tick. */
    public static double bobOffset(long tick, double amplitude) {
        if (amplitude <= 0) return 0;
        return amplitude * Math.sin(2 * Math.PI * (tick % BOB_PERIOD_TICKS) / BOB_PERIOD_TICKS);
    }

    /**
     * Desplazamiento de una cara del cartel (en el sistema de la propia entidad) para que no
     * se pisen la de delante y la de atras: media separacion hacia quien la mira, girada con
     * el giro del cartel. Devuelve {x, z}.
     */
    public static double[] faceOffset(double gap, float spinRadians) {
        double half = gap / 2;
        return new double[]{half * Math.sin(spinRadians), half * Math.cos(spinRadians)};
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
