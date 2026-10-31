package dev.chatalyst.hologram;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /cartel importar}: convertir TextDisplay hechos a mano con {@code /summon} en carteles
 * gestionados. Sin Bukkit: el comando lee las entidades a {@link Found} y aqui se decide que
 * va con que y como queda.
 *
 * <p>Un cartel a mano "de doble cara" son dos entidades: la cara y una copia girada 180 grados
 * unos centimetros detras. Se juntan cuando tienen el mismo texto, miran en sentidos opuestos
 * (180 ± 10 grados) y estan a menos de un bloque, y pasan a ser UN cartel de doble cara.
 */
public final class DisplayImport {

    /** Tolerancia en grados para considerar dos caras opuestas. */
    static final double OPPOSITE_TOLERANCE = 10;
    /** Distancia maxima entre las dos caras de un mismo cartel. */
    static final double PAIR_DISTANCE = 1.0;

    /**
     * Un TextDisplay del mundo tal como lo ve el importador.
     *
     * @param mini       texto ya convertido a MiniMessage
     * @param plain      texto visible (para emparejar y para el id)
     * @param background ARGB del fondo, o null si usa el gris por defecto del juego
     * @param brightness luz fija 0-15, o -1 si no tiene
     * @param opacity    byte de opacidad de Minecraft (-1 = opaco)
     * @param viewRange  multiplicador de Minecraft (1 = 64 bloques)
     */
    public record Found(UUID uuid, String world, double x, double y, double z, float yaw,
                        String mini, String plain, Set<String> tags, double scale,
                        HoloLook.Billboard billboard, Integer background, boolean shadow,
                        int brightness, HoloLook.Alignment alignment, int lineWidth,
                        byte opacity, float viewRange, boolean seeThrough) {

        public Found {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }

        double distance(Found other) {
            if (!world.equals(other.world)) return Double.MAX_VALUE;
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** Etiquetas tipo "cartel_casino_detras": la copia de atras, no la cara. */
        boolean looksLikeBack() {
            for (String tag : tags) {
                String lower = tag.toLowerCase(Locale.ROOT);
                if (lower.contains("detras") || lower.contains("detrás") || lower.contains("back")
                        || lower.contains("behind") || lower.contains("rear")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Un cartel a crear: su cara y, si la hay, la copia de atras. */
    public record Group(Found front, Found back) {

        public boolean doubleSided() {
            return back != null;
        }

        public List<UUID> entities() {
            return back == null ? List.of(front.uuid()) : List.of(front.uuid(), back.uuid());
        }
    }

    /** Lo que se va a hacer con un grupo: el id elegido y el cartel resultante. */
    public record Planned(Group group, Hologram hologram) {
    }

    private DisplayImport() {
    }

    /**
     * Desplazamiento de la transformacion de un Display (ejes de la entidad, girados con su
     * yaw) a ejes del mundo: {x, z}. Con yaw 0 la entidad mira a +Z, como en Minecraft.
     */
    public static double[] localToWorld(double localX, double localZ, float yaw) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{localX * cos - localZ * sin, localX * sin + localZ * cos};
    }

    /** Diferencia angular minima entre dos giros, en [0, 180]. */
    static double angleBetween(float a, float b) {
        double diff = Math.abs(Hologram.normalizeYaw(a) - Hologram.normalizeYaw(b)) % 360.0;
        return diff > 180 ? 360 - diff : diff;
    }

    /** Si el texto cabe entero en una linea de cartel (si no, importarlo lo cortaria). */
    public static boolean fits(Found found) {
        return found.mini() != null && found.mini().length() <= HoloLine.MAX_LENGTH;
    }

    static boolean pairs(Found a, Found b) {
        return a.plain().equals(b.plain())
                && Math.abs(angleBetween(a.yaw(), b.yaw()) - 180) <= OPPOSITE_TOLERANCE
                && a.distance(b) < PAIR_DISTANCE;
    }

    /**
     * Agrupa: cada entidad con su pareja mas cercana si la tiene. El resultado sale en orden
     * estable (por mundo y posicion) para que simular e importar den lo mismo.
     */
    public static List<Group> group(List<Found> found) {
        List<Found> remaining = new ArrayList<>(found);
        remaining.sort(Comparator.comparing(Found::world).thenComparingDouble(Found::x)
                .thenComparingDouble(Found::y).thenComparingDouble(Found::z)
                .thenComparing(f -> f.uuid().toString()));
        List<Group> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Found first = remaining.removeFirst();
            Found best = null;
            for (Found other : remaining) {
                if (pairs(first, other) && (best == null
                        || first.distance(other) < first.distance(best))) {
                    best = other;
                }
            }
            if (best == null) {
                groups.add(new Group(first, null));
                continue;
            }
            remaining.remove(best);
            // La cara es la que no dice "detras"; si ninguna lo dice, la primera.
            boolean firstIsBack = first.looksLikeBack() && !best.looksLikeBack();
            groups.add(firstIsBack ? new Group(best, first) : new Group(first, best));
        }
        return groups;
    }

    /** Planifica todos los grupos con ids que no chocan entre si ni con {@code taken}. */
    public static List<Planned> plan(List<Group> groups, Set<String> taken) {
        Set<String> used = new java.util.HashSet<>(taken);
        List<Planned> out = new ArrayList<>();
        for (Group group : groups) {
            String id = idFor(group.front().plain(), used);
            used.add(id);
            out.add(new Planned(group, toHologram(group, id)));
        }
        return out;
    }

    /** El cartel gestionado equivalente al grupo. */
    public static Hologram toHologram(Group group, String id) {
        Found front = group.front();
        double x = front.x();
        double y = front.y();
        double z = front.z();
        double gap = HoloLook.DEFAULT.sideGap();
        if (group.back() != null) {
            // El cartel nuevo pone cada cara a media separacion del centro: el centro es el
            // punto medio de las dos entidades originales.
            Found back = group.back();
            x = (x + back.x()) / 2;
            y = (y + back.y()) / 2;
            z = (z + back.z()) / 2;
            gap = Math.max(0.005, front.distance(back));
        }
        HoloLook look = new HoloLook(front.scale(), background(front.background()),
                front.shadow(), opacityPercent(front.opacity()), front.brightness(),
                front.alignment(), front.lineWidth(), front.billboard(), group.doubleSided(),
                gap, (int) Math.round(front.viewRange() * 64), front.seeThrough(),
                HoloLook.DEFAULT.itemScale());
        Hologram.Position position = new Hologram.Position(front.world(), x, y, z, front.yaw());
        return new Hologram(id, position, List.of(HoloLine.text(front.mini())), look,
                HoloMotion.DEFAULT, HoloAction.NONE, BedrockMode.GEYSER);
    }

    /** Fondo de Minecraft a la forma de HoloLook: default, none (alfa 0) o #AARRGGBB. */
    static String background(Integer argb) {
        if (argb == null) return "default";
        if ((argb >>> 24) == 0) return "none";
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    /** Byte de opacidad de Minecraft a porcentaje: -1 (255) es opaco. */
    static int opacityPercent(byte opacity) {
        int value = opacity & 0xFF;
        // 0-3 los dibuja el cliente como opacos (desbordamiento conocido del renderer).
        if (value <= 3) return 100;
        return (int) Math.round(value * 100 / 255.0);
    }

    /**
     * Id a partir del texto: "CASINO" → casino, "¡Pesca!" → pesca, "Zona de TPs" → zona_de_tps.
     * Si ya esta cogido, casino_2, casino_3...
     */
    public static String idFor(String plain, Set<String> taken) {
        String base = slug(plain);
        if (!taken.contains(base)) return base;
        for (int n = 2; ; n++) {
            String suffix = "_" + n;
            String candidate = (base.length() + suffix.length() > 32
                    ? base.substring(0, 32 - suffix.length()) : base) + suffix;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    static String slug(String plain) {
        String text = plain == null ? "" : Normalizer.normalize(plain, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.length() > 32) slug = slug.substring(0, 32).replaceAll("_+$", "");
        return slug.isEmpty() ? "cartel" : slug;
    }
}
