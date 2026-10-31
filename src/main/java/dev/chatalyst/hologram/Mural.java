package dev.chatalyst.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Un mural de mapas: una imagen troceada en marcos sobre una pared, un suelo o un techo.
 * Inmutable; se guarda en {@code data/murals.yml} (por servidor, como los carteles).
 *
 * @param x      bloque del marco de abajo a la izquierda (el ancla)
 * @param face   hacia donde miran los marcos (la cara del bloque en que se apoyan)
 * @param up     en suelo y techo, hacia donde queda la parte de arriba de la imagen
 * @param mapIds ids de mapa de cada trozo (por filas); se reutilizan entre reinicios para no
 *               crear mapas nuevos cada vez (vacio hasta que se crean)
 */
public record Mural(String id, String world, int x, int y, int z, MuralLayout.Facing face,
                    MuralLayout.Facing up, int width, int height, ImageSource source,
                    ImageResize.Fit fit, boolean dither, boolean glow, List<Integer> mapIds) {

    public Mural {
        id = id == null ? "" : id.toLowerCase(Locale.ROOT);
        world = world == null ? "world" : world;
        face = face == null ? MuralLayout.Facing.NORTH : face;
        up = up == null || !up.horizontal() ? MuralLayout.Facing.NORTH : up;
        width = Math.max(1, width);
        height = Math.max(1, height);
        fit = fit == null ? ImageResize.Fit.CONTAIN : fit;
        mapIds = mapIds == null ? List.of() : List.copyOf(mapIds);
    }

    public MuralLayout.Size size() {
        return new MuralLayout.Size(width, height);
    }

    public Mural withMapIds(List<Integer> ids) {
        return new Mural(id, world, x, y, z, face, up, width, height, source, fit, dither, glow, ids);
    }

    public Mural withId(String next) {
        return new Mural(next, world, x, y, z, face, up, width, height, source, fit, dither, glow,
                mapIds);
    }

    /** Otro sitio y orientacion; los mapas se conservan (la imagen es la misma). */
    public Mural movedTo(String nextWorld, int nx, int ny, int nz, MuralLayout.Facing nextFace,
                         MuralLayout.Facing nextUp) {
        return new Mural(id, nextWorld, nx, ny, nz, nextFace, nextUp, width, height, source, fit,
                dither, glow, mapIds);
    }

    /** Bloque de cada marco, en el orden de los trozos de la imagen. */
    public List<int[]> blocks() {
        List<int[]> out = new ArrayList<>(width * height);
        for (MuralLayout.Placement p : MuralLayout.place(size(), face, up)) {
            out.add(new int[]{x + p.dx(), y + p.dy(), z + p.dz(), p.quarterTurns()});
        }
        return out;
    }

    // ---- Guardado -----------------------------------------------------------------------------

    public Map<String, Object> encode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("world", world);
        out.put("x", x);
        out.put("y", y);
        out.put("z", z);
        out.put("face", face.name().toLowerCase(Locale.ROOT));
        out.put("up", up.name().toLowerCase(Locale.ROOT));
        out.put("width", width);
        out.put("height", height);
        out.put("image", source.value());
        out.put("fit", fit.name().toLowerCase(Locale.ROOT));
        out.put("dither", dither);
        out.put("glow", glow);
        out.put("maps", new ArrayList<>(mapIds));
        return out;
    }

    /** Null si falta lo imprescindible (id, imagen); lo demas cae a su valor por defecto. */
    public static Mural decode(String id, Map<?, ?> data, int maxSide) {
        if (!Hologram.validId(id) || data == null) return null;
        ImageSource source = ImageSource.parse(String.valueOf(data.get("image")));
        if (source == null) return null;
        List<Integer> maps = new ArrayList<>();
        if (data.get("maps") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Number number) maps.add(number.intValue());
            }
        }
        ImageResize.Fit fit = ImageResize.Fit.parse(String.valueOf(data.get("fit")));
        MuralLayout.Facing face = MuralLayout.Facing.parse(String.valueOf(data.get("face")));
        return new Mural(id, String.valueOf(data.get("world") == null ? "world" : data.get("world")),
                integer(data.get("x")), integer(data.get("y")), integer(data.get("z")),
                face, MuralLayout.Facing.parse(String.valueOf(data.get("up"))),
                Math.min(maxSide, integer(data.get("width"))),
                Math.min(maxSide, integer(data.get("height"))), source,
                fit, Boolean.TRUE.equals(data.get("dither")), Boolean.TRUE.equals(data.get("glow")),
                maps);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
