package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cartel a mapa y vuelta, con la forma que se guarda en {@code data/holograms.yml}. Pensado
 * para que el dueño pueda editarlo a mano: las lineas son texto plano salvo que lleven
 * animacion propia, y todo lo que falte o venga mal cae a su valor por defecto en vez de
 * tirar el cartel entero.
 */
public final class HoloCodec {

    private HoloCodec() {
    }

    public static Map<String, Object> encode(Hologram h) {
        Map<String, Object> out = new LinkedHashMap<>();
        Hologram.Position p = h.position();
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("world", p.world());
        location.put("x", round(p.x()));
        location.put("y", round(p.y()));
        location.put("z", round(p.z()));
        location.put("yaw", round(p.yaw()));
        out.put("location", location);

        List<Object> lines = new ArrayList<>();
        for (HoloLine line : h.lines()) {
            if (line.animation() == null) {
                lines.add(line.raw());
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("text", line.raw());
                entry.put("animation", line.animation());
                lines.add(entry);
            }
        }
        out.put("lines", lines);

        HoloLook l = h.look();
        Map<String, Object> look = new LinkedHashMap<>();
        look.put("scale", round(l.scale()));
        look.put("background", l.background());
        look.put("shadow", l.shadow());
        look.put("opacity", l.opacity());
        look.put("brightness", l.brightness());
        look.put("alignment", l.alignment().name().toLowerCase(Locale.ROOT));
        look.put("line-width", l.lineWidth());
        look.put("billboard", l.billboard().name().toLowerCase(Locale.ROOT));
        look.put("double-sided", l.doubleSided());
        look.put("side-gap", round(l.sideGap()));
        look.put("view-range", l.viewRange());
        look.put("see-through", l.seeThrough());
        look.put("item-scale", round(l.itemScale()));
        out.put("look", look);

        HoloMotion m = h.motion();
        Map<String, Object> motion = new LinkedHashMap<>();
        motion.put("animation", m.animation());
        motion.put("speed", m.speed());
        motion.put("spin", round(m.spin()));
        motion.put("bob", round(m.bob()));
        motion.put("refresh", m.refresh());
        out.put("motion", motion);

        HoloAction a = h.action();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", a.type().name().toLowerCase(Locale.ROOT));
        action.put("value", a.value());
        action.put("cooldown", a.cooldown());
        out.put("click", action);

        out.put("bedrock", h.bedrock().name().toLowerCase(Locale.ROOT));
        return out;
    }

    /** Null si el id no es valido; cualquier otro dato roto cae a su valor por defecto. */
    public static Hologram decode(String id, Map<?, ?> data) {
        if (!Hologram.validId(id) || data == null) return null;
        Map<?, ?> location = map(data.get("location"));
        Hologram.Position position = new Hologram.Position(
                string(location.get("world"), "world"),
                dbl(location.get("x"), 0), dbl(location.get("y"), 64), dbl(location.get("z"), 0),
                (float) dbl(location.get("yaw"), 0));

        List<HoloLine> lines = new ArrayList<>();
        if (data.get("lines") instanceof List<?> raw) {
            for (Object entry : raw) {
                if (entry instanceof Map<?, ?> withAnimation) {
                    HoloLine line = HoloLine.parse(string(withAnimation.get("text"), ""));
                    lines.add(line.withAnimation(
                            HoloAnimation.normalize(string(withAnimation.get("animation"), null))));
                } else if (entry != null) {
                    lines.add(HoloLine.parse(string(entry, "")));
                }
            }
        }
        if (lines.isEmpty()) lines.add(HoloLine.text(id));

        Map<?, ?> look = map(data.get("look"));
        HoloLook d = HoloLook.DEFAULT;
        HoloLook.Billboard billboard = HoloOptions.parseBillboard(
                string(look.get("billboard"), "center"));
        HoloLook parsedLook = new HoloLook(
                dbl(look.get("scale"), d.scale()),
                background(string(look.get("background"), d.background())),
                bool(look.get("shadow"), d.shadow()),
                (int) dbl(look.get("opacity"), d.opacity()),
                (int) dbl(look.get("brightness"), d.brightness()),
                alignment(string(look.get("alignment"), "center")),
                (int) dbl(look.get("line-width"), d.lineWidth()),
                billboard == null ? d.billboard() : billboard,
                bool(look.get("double-sided"), d.doubleSided()),
                dbl(look.get("side-gap"), d.sideGap()),
                (int) dbl(look.get("view-range"), d.viewRange()),
                bool(look.get("see-through"), d.seeThrough()),
                dbl(look.get("item-scale"), d.itemScale()));

        Map<?, ?> motion = map(data.get("motion"));
        HoloMotion dm = HoloMotion.DEFAULT;
        String animation = HoloAnimation.normalize(string(motion.get("animation"), "none"));
        HoloMotion parsedMotion = new HoloMotion(animation == null ? "none" : animation,
                (int) dbl(motion.get("speed"), dm.speed()),
                dbl(motion.get("spin"), dm.spin()),
                dbl(motion.get("bob"), dm.bob()),
                (int) dbl(motion.get("refresh"), dm.refresh()));

        Map<?, ?> click = map(data.get("click"));
        HoloAction.Type type = HoloAction.parseType(string(click.get("type"), "none"));
        HoloAction action = new HoloAction(type == null ? HoloAction.Type.NONE : type,
                string(click.get("value"), ""),
                (int) dbl(click.get("cooldown"), HoloAction.NONE.cooldown()));

        BedrockMode bedrock = BedrockMode.parse(string(data.get("bedrock"), "geyser"));
        return new Hologram(id, position, lines, parsedLook, parsedMotion, action,
                bedrock == null ? BedrockMode.GEYSER : bedrock);
    }

    private static String background(String raw) {
        if (raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("default")) {
            return raw.toLowerCase(Locale.ROOT);
        }
        return HoloLook.backgroundArgb(raw) == null ? "default" : raw;
    }

    private static HoloLook.Alignment alignment(String raw) {
        try {
            return HoloLook.Alignment.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return HoloLook.Alignment.CENTER;
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String string(Object value, String fallback) {
        if (value == null) return fallback;
        // SnakeYAML guarda como binario una cadena con caracteres de control y la devuelve
        // como byte[]: sin esto la linea volvia de un reinicio convertida en "[B@1a2b3c".
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static double dbl(Object value, double fallback) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        if (value == null) return fallback;
        Double parsed = HoloOptions.number(String.valueOf(value));
        return parsed == null ? fallback : parsed;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        Boolean parsed = HoloOptions.parseBoolean(String.valueOf(value));
        return parsed == null ? fallback : parsed;
    }

    /** Tres decimales bastan (milimetros) y el archivo queda legible. */
    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
