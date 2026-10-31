package dev.chatalyst.hologram;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * {@code /cartel opcion <id> <opcion> <valor>}: cada opcion con sus nombres en español e
 * ingles, su validacion y su efecto sobre la definicion. Sin Bukkit, para fijarlo con tests.
 *
 * <p>Un error se devuelve como clave de idioma ({@code holograms.option-...}) con el valor
 * que lo provoco; el comando solo lo traduce.
 */
public final class HoloOptions {

    /** Resultado: el cartel cambiado y como se enseña el valor, o la clave del error. */
    public record Result(Hologram hologram, String shown, String error) {

        static Result ok(Hologram hologram, String shown) {
            return new Result(hologram, shown, null);
        }

        static Result fail(String error) {
            return new Result(null, null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Una opcion: su nombre canonico (el que sale en el menu y en la ayuda) y como se aplica. */
    public record Option(String name, List<String> aliases,
                         BiFunction<Hologram, String, Result> apply) {
    }

    private static final Map<String, Double> SIZE_WORDS = Map.of(
            "mini", 0.5, "pequeño", 0.6, "pequeno", 0.6, "small", 0.6,
            "normal", 1.0, "grande", 2.0, "big", 2.0,
            "enorme", 4.0, "huge", 4.0, "gigante", 8.0);

    public static final List<Option> OPTIONS = List.of(
            new Option("escala", List.of("scale", "tamaño", "tamano", "size"), HoloOptions::scale),
            new Option("fondo", List.of("background", "bg"), HoloOptions::background),
            new Option("sombra", List.of("shadow"), (h, v) -> bool(v, h,
                    b -> h.withLook(h.look().withShadow(b)))),
            new Option("opacidad", List.of("opacity", "alpha"), HoloOptions::opacity),
            new Option("brillo", List.of("brightness", "luz", "light"), HoloOptions::brightness),
            new Option("alineacion", List.of("alineación", "align", "alignment"),
                    HoloOptions::alignment),
            new Option("ancho", List.of("width", "linewidth"), HoloOptions::width),
            new Option("orientacion", List.of("orientación", "billboard", "mirar"),
                    HoloOptions::billboard),
            new Option("doblecara", List.of("doublesided", "dobleface", "doscaras", "twosided"),
                    (h, v) -> bool(v, h, b -> h.withLook(h.look().withDoubleSided(b)))),
            new Option("separacion", List.of("separación", "gap", "sidegap"), HoloOptions::gap),
            new Option("distancia", List.of("range", "viewrange", "alcance"), HoloOptions::range),
            new Option("atraves", List.of("através", "seethrough", "xray"),
                    (h, v) -> bool(v, h, b -> h.withLook(h.look().withSeeThrough(b)))),
            new Option("objeto", List.of("itemscale", "icono", "item"), HoloOptions::itemScale),
            new Option("animacion", List.of("animación", "animation", "anim"),
                    HoloOptions::animation),
            new Option("velocidad", List.of("speed"), HoloOptions::speed),
            new Option("rotacion", List.of("rotación", "spin", "girando"), HoloOptions::spin),
            new Option("flotar", List.of("bob", "vaiven", "vaivén", "float"), HoloOptions::bob),
            new Option("refresco", List.of("refresh", "actualizar"), HoloOptions::refresh),
            new Option("bedrock", List.of("geyser"), HoloOptions::bedrock));

    private HoloOptions() {
    }

    /** La opcion por cualquiera de sus nombres, o null. */
    public static Option find(String raw) {
        if (raw == null) return null;
        String key = raw.toLowerCase(Locale.ROOT).strip();
        for (Option option : OPTIONS) {
            if (option.name().equals(key) || option.aliases().contains(key)) return option;
        }
        return null;
    }

    public static Result apply(Hologram hologram, String option, String value) {
        Option found = find(option);
        if (found == null) return Result.fail("holograms.option-unknown");
        if (value == null || value.isBlank()) return Result.fail("holograms.option-missing");
        return found.apply().apply(hologram, value.strip());
    }

    // ---- Cada opcion ---------------------------------------------------------------------

    private static Result scale(Hologram h, String v) {
        Double word = SIZE_WORDS.get(v.toLowerCase(Locale.ROOT));
        Double number = word != null ? word : number(v);
        if (number == null || number < HoloLook.MIN_SCALE || number > HoloLook.MAX_SCALE) {
            return Result.fail("holograms.option-scale");
        }
        return Result.ok(h.withLook(h.look().withScale(number)), format(number));
    }

    private static Result background(Hologram h, String v) {
        String value = switch (v.toLowerCase(Locale.ROOT)) {
            case "none", "transparente", "transparent", "ninguno", "no", "off" -> "none";
            case "default", "normal", "gris", "gray", "grey", "si", "sí", "on" -> "default";
            default -> v.startsWith("#") ? v.toUpperCase(Locale.ROOT) : null;
        };
        if (value == null || (value.startsWith("#") && HoloLook.backgroundArgb(value) == null)) {
            return Result.fail("holograms.option-background");
        }
        return Result.ok(h.withLook(h.look().withBackground(value)), value);
    }

    private static Result opacity(Hologram h, String v) {
        Double number = number(v.endsWith("%") ? v.substring(0, v.length() - 1) : v);
        if (number == null || number < 0 || number > 100) {
            return Result.fail("holograms.option-percent");
        }
        int percent = (int) Math.round(number);
        return Result.ok(h.withLook(h.look().withOpacity(percent)), percent + "%");
    }

    private static Result brightness(Hologram h, String v) {
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("auto") || lower.equals("normal") || lower.equals("off")) {
            return Result.ok(h.withLook(h.look().withBrightness(-1)), "auto");
        }
        Double number = number(v);
        if (number == null || number < 0 || number > 15) {
            return Result.fail("holograms.option-brightness");
        }
        int level = (int) Math.round(number);
        return Result.ok(h.withLook(h.look().withBrightness(level)), String.valueOf(level));
    }

    private static Result alignment(Hologram h, String v) {
        HoloLook.Alignment value = switch (v.toLowerCase(Locale.ROOT)) {
            case "centro", "center", "centrado" -> HoloLook.Alignment.CENTER;
            case "izquierda", "left" -> HoloLook.Alignment.LEFT;
            case "derecha", "right" -> HoloLook.Alignment.RIGHT;
            default -> null;
        };
        if (value == null) return Result.fail("holograms.option-alignment");
        return Result.ok(h.withLook(h.look().withAlignment(value)),
                value.name().toLowerCase(Locale.ROOT));
    }

    private static Result width(Hologram h, String v) {
        Double number = number(v);
        if (number == null || number < 20 || number > 2000) {
            return Result.fail("holograms.option-width");
        }
        return Result.ok(h.withLook(h.look().withLineWidth(number.intValue())),
                String.valueOf(number.intValue()));
    }

    private static Result billboard(Hologram h, String v) {
        HoloLook.Billboard value = parseBillboard(v);
        if (value == null) return Result.fail("holograms.option-billboard");
        return Result.ok(h.withLook(h.look().withBillboard(value)),
                value.name().toLowerCase(Locale.ROOT));
    }

    public static HoloLook.Billboard parseBillboard(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "centro", "center", "jugador", "player", "siempre" -> HoloLook.Billboard.CENTER;
            case "fijo", "fixed", "plano", "cartel" -> HoloLook.Billboard.FIXED;
            case "vertical" -> HoloLook.Billboard.VERTICAL;
            case "horizontal" -> HoloLook.Billboard.HORIZONTAL;
            default -> null;
        };
    }

    private static Result gap(Hologram h, String v) {
        String value = v.toLowerCase(Locale.ROOT);
        double factor = 1;
        if (value.endsWith("cm")) {
            value = value.substring(0, value.length() - 2);
            factor = 0.01;
        }
        Double number = number(value);
        if (number == null || number * factor < 0 || number * factor > 0.5) {
            return Result.fail("holograms.option-gap");
        }
        double blocks = number * factor;
        return Result.ok(h.withLook(h.look().withSideGap(blocks)), format(blocks));
    }

    private static Result range(Hologram h, String v) {
        Double number = number(v);
        if (number == null || number < 4 || number > 512) {
            return Result.fail("holograms.option-range");
        }
        return Result.ok(h.withLook(h.look().withViewRange(number.intValue())),
                String.valueOf(number.intValue()));
    }

    private static Result itemScale(Hologram h, String v) {
        Double number = number(v);
        if (number == null || number < 0.1 || number > 10) {
            return Result.fail("holograms.option-itemscale");
        }
        return Result.ok(h.withLook(h.look().withItemScale(number)), format(number));
    }

    private static Result animation(Hologram h, String v) {
        String name = HoloAnimation.normalize(v);
        if (name == null) return Result.fail("holograms.option-animation");
        return Result.ok(h.withMotion(h.motion().withAnimation(name)), name);
    }

    private static Result speed(Hologram h, String v) {
        Double number = number(v);
        if (number == null || number < 1 || number > 40) {
            return Result.fail("holograms.option-speed");
        }
        return Result.ok(h.withMotion(h.motion().withSpeed(number.intValue())),
                String.valueOf(number.intValue()));
    }

    private static Result spin(Hologram h, String v) {
        String lower = v.toLowerCase(Locale.ROOT);
        Double number = switch (lower) {
            case "off", "no", "quieto" -> 0.0;
            case "on", "si", "sí", "lento", "slow" -> 30.0;
            case "rapido", "rápido", "fast" -> 90.0;
            default -> number(v);
        };
        if (number == null || number < -360 || number > 360) {
            return Result.fail("holograms.option-spin");
        }
        return Result.ok(h.withMotion(h.motion().withSpin(number)), format(number));
    }

    private static Result bob(Hologram h, String v) {
        String lower = v.toLowerCase(Locale.ROOT);
        Double number = switch (lower) {
            case "off", "no" -> 0.0;
            case "on", "si", "sí", "suave" -> 0.08;
            default -> number(v);
        };
        if (number == null || number < 0 || number > 2) {
            return Result.fail("holograms.option-bob");
        }
        return Result.ok(h.withMotion(h.motion().withBob(number)), format(number));
    }

    private static Result refresh(Hologram h, String v) {
        String value = v.toLowerCase(Locale.ROOT);
        if (value.endsWith("s")) value = value.substring(0, value.length() - 1);
        Double number = number(value);
        if (number == null || number < 1 || number > 600) {
            return Result.fail("holograms.option-refresh");
        }
        return Result.ok(h.withMotion(h.motion().withRefresh(number.intValue())),
                number.intValue() + "s");
    }

    private static Result bedrock(Hologram h, String v) {
        BedrockMode mode = BedrockMode.parse(v);
        if (mode == null) return Result.fail("holograms.option-bedrock");
        return Result.ok(h.withBedrock(mode), mode.name().toLowerCase(Locale.ROOT));
    }

    // ---- Comunes -------------------------------------------------------------------------

    private static Result bool(String v, Hologram h,
                               java.util.function.Function<Boolean, Hologram> apply) {
        Boolean value = parseBoolean(v);
        if (value == null) return Result.fail("holograms.option-boolean");
        return Result.ok(apply.apply(value), value ? "on" : "off");
    }

    public static Boolean parseBoolean(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "si", "sí", "true", "yes", "activar", "1" -> true;
            case "off", "no", "false", "desactivar", "0" -> false;
            default -> null;
        };
    }

    /** Numero con punto o coma decimal; null si no lo es o no es finito. */
    static Double number(String raw) {
        if (raw == null) return null;
        try {
            double value = Double.parseDouble(raw.strip().replace(',', '.'));
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** "2", "0.5", "1.25": sin ceros de sobra. */
    static String format(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
