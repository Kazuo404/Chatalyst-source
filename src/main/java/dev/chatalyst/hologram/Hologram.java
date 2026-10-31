package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Definicion inmutable de un cartel flotante: lo que se guarda en disco y lo que el runtime
 * convierte en entidades. Cada cambio produce una copia; el runtime reconstruye el cartel.
 */
public record Hologram(String id, Position position, List<HoloLine> lines, HoloLook look,
                       HoloMotion motion, HoloAction action, BedrockMode bedrock) {

    /** Mundo y punto de anclaje (base del cartel: el texto crece hacia arriba desde aqui). */
    public record Position(String world, double x, double y, double z, float yaw) {

        public Position {
            world = world == null ? "world" : world;
            yaw = normalizeYaw(yaw);
        }

        public Position withYaw(float next) {
            return new Position(world, x, y, z, next);
        }

        public int chunkX() {
            return (int) Math.floor(x) >> 4;
        }

        public int chunkZ() {
            return (int) Math.floor(z) >> 4;
        }

        public double distanceSquared(Position other) {
            if (other == null || !world.equals(other.world)) return Double.MAX_VALUE;
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final int MAX_LINES = 32;

    public Hologram {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
        lines = lines == null ? List.of() : List.copyOf(
                lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines);
        look = look == null ? HoloLook.DEFAULT : look;
        motion = motion == null ? HoloMotion.DEFAULT : motion;
        action = action == null ? HoloAction.NONE : action;
        bedrock = bedrock == null ? BedrockMode.GEYSER : bedrock;
    }

    public static Hologram create(String id, Position position, String firstLine) {
        List<HoloLine> lines = firstLine == null || firstLine.isBlank()
                ? List.of(HoloLine.text(id)) : List.of(HoloLine.parse(firstLine));
        return new Hologram(id, position, lines, HoloLook.DEFAULT, HoloMotion.DEFAULT,
                HoloAction.NONE, BedrockMode.GEYSER);
    }

    public static boolean validId(String raw) {
        return raw != null && ID.matcher(raw.toLowerCase(Locale.ROOT)).matches();
    }

    /** Grados a [-180, 180), como los guarda Minecraft. */
    public static float normalizeYaw(float yaw) {
        if (!Float.isFinite(yaw)) return 0f;
        float value = yaw % 360f;
        if (value >= 180f) value -= 360f;
        if (value < -180f) value += 360f;
        return value;
    }

    public Hologram withId(String next) {
        return new Hologram(next, position, lines, look, motion, action, bedrock);
    }

    public Hologram withPosition(Position next) {
        return new Hologram(id, next, lines, look, motion, action, bedrock);
    }

    public Hologram withLines(List<HoloLine> next) {
        return new Hologram(id, position, next, look, motion, action, bedrock);
    }

    public Hologram withLook(HoloLook next) {
        return new Hologram(id, position, lines, next, motion, action, bedrock);
    }

    public Hologram withMotion(HoloMotion next) {
        return new Hologram(id, position, lines, look, next, action, bedrock);
    }

    public Hologram withAction(HoloAction next) {
        return new Hologram(id, position, lines, look, motion, next, bedrock);
    }

    public Hologram withBedrock(BedrockMode next) {
        return new Hologram(id, position, lines, look, motion, action, next);
    }

    // ---- Edicion de lineas (indices base 1, como los escribe el staff) -----------------

    /** Sustituye la linea n; null si no existe. */
    public Hologram setLine(int n, HoloLine line) {
        if (n < 1 || n > lines.size()) return null;
        List<HoloLine> next = new ArrayList<>(lines);
        // La animacion propia de la linea sobrevive a cambiarle el texto.
        next.set(n - 1, line.animation() == null
                ? line.withAnimation(lines.get(n - 1).animation()) : line);
        return withLines(next);
    }

    /** Añade al final; null si ya esta lleno. */
    public Hologram addLine(HoloLine line) {
        if (lines.size() >= MAX_LINES) return null;
        List<HoloLine> next = new ArrayList<>(lines);
        next.add(line);
        return withLines(next);
    }

    /** Inserta para que la nueva quede en la posicion n (1..size+1); null si no cabe. */
    public Hologram insertLine(int n, HoloLine line) {
        if (n < 1 || n > lines.size() + 1 || lines.size() >= MAX_LINES) return null;
        List<HoloLine> next = new ArrayList<>(lines);
        next.add(n - 1, line);
        return withLines(next);
    }

    /** Quita la linea n; null si no existe o era la ultima que quedaba. */
    public Hologram removeLine(int n) {
        if (n < 1 || n > lines.size() || lines.size() == 1) return null;
        List<HoloLine> next = new ArrayList<>(lines);
        next.remove(n - 1);
        return withLines(next);
    }

    /** Animacion efectiva de la linea: la suya o la del cartel. */
    public String animationOf(HoloLine line) {
        return line.animation() != null ? line.animation() : motion.animation();
    }

    /** Si algo cambia con el tiempo: hay que tickearlo mientras haya alguien cerca. */
    public boolean animated() {
        if (motion.moves()) return true;
        for (HoloLine line : lines) {
            if (line.isText() && !"none".equals(animationOf(line))) return true;
        }
        return false;
    }
}
