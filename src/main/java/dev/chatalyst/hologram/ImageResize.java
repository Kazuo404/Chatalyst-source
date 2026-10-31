package dev.chatalyst.hologram;

import java.util.Locale;

/**
 * Reescalado de fotogramas, en aritmetica propia (sin AWT): determinista y testeable.
 *
 * <ul>
 *   <li>Al reducir se hace la media de todos los pixeles que caen en cada pixel de destino
 *       (una foto de 1000 px a 32 px no "salta" pixeles y no sale con ruido).</li>
 *   <li>Al ampliar, interpolacion bilineal.</li>
 *   <li>El color se mezcla premultiplicado por el alfa, para que el borde de un logo con
 *       transparencia no salga con un halo oscuro.</li>
 * </ul>
 */
public final class ImageResize {

    /** Como encajar la imagen en el hueco: entera con margen, recortada o deformada. */
    public enum Fit {
        CONTAIN, COVER, STRETCH;

        public static Fit parse(String raw) {
            if (raw == null) return null;
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "ajustar", "contain", "fit", "entera" -> CONTAIN;
                case "recortar", "cover", "crop", "llenar" -> COVER;
                case "estirar", "stretch", "deformar" -> STRETCH;
                default -> null;
            };
        }
    }

    private ImageResize() {
    }

    /** El fotograma en un lienzo de {@code width x height}, encajado segun {@code fit}. */
    public static ImageFrame fit(ImageFrame source, int width, int height, Fit fit) {
        if (fit == Fit.STRETCH) return resize(source, width, height);
        double scaleX = (double) width / source.width();
        double scaleY = (double) height / source.height();
        double scale = fit == Fit.CONTAIN ? Math.min(scaleX, scaleY) : Math.max(scaleX, scaleY);
        int scaledW = Math.max(1, (int) Math.round(source.width() * scale));
        int scaledH = Math.max(1, (int) Math.round(source.height() * scale));
        ImageFrame scaled = resize(source, scaledW, scaledH);
        int[] out = new int[width * height];
        // Centrado: con CONTAIN sobra margen (transparente); con COVER se recortan los bordes.
        int offsetX = (width - scaledW) / 2;
        int offsetY = (height - scaledH) / 2;
        for (int y = 0; y < height; y++) {
            int sy = y - offsetY;
            if (sy < 0 || sy >= scaledH) continue;
            for (int x = 0; x < width; x++) {
                int sx = x - offsetX;
                if (sx < 0 || sx >= scaledW) continue;
                out[y * width + x] = scaled.argb()[sy * scaledW + sx];
            }
        }
        return new ImageFrame(width, height, out, source.delayMs());
    }

    /** Alto proporcional para un ancho dado (al menos 1). */
    public static int proportionalHeight(int sourceW, int sourceH, int width) {
        return Math.max(1, (int) Math.round((double) sourceH * width / sourceW));
    }

    public static ImageFrame resize(ImageFrame source, int width, int height) {
        if (width == source.width() && height == source.height()) return source;
        int[] out = new int[width * height];
        boolean downX = width < source.width();
        boolean downY = height < source.height();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = downX || downY
                        ? areaAverage(source, x, y, width, height)
                        : bilinear(source, x, y, width, height);
            }
        }
        return new ImageFrame(width, height, out, source.delayMs());
    }

    /** Media (premultiplicada) de la caja de origen que cubre el pixel de destino. */
    private static int areaAverage(ImageFrame src, int dx, int dy, int dw, int dh) {
        double x0 = (double) dx * src.width() / dw;
        double x1 = (double) (dx + 1) * src.width() / dw;
        double y0 = (double) dy * src.height() / dh;
        double y1 = (double) (dy + 1) * src.height() / dh;
        int sx0 = (int) Math.floor(x0);
        int sx1 = Math.min(src.width(), (int) Math.ceil(x1));
        int sy0 = (int) Math.floor(y0);
        int sy1 = Math.min(src.height(), (int) Math.ceil(y1));
        double a = 0;
        double r = 0;
        double g = 0;
        double b = 0;
        double weight = 0;
        for (int sy = sy0; sy < sy1; sy++) {
            double wy = Math.min(sy + 1, y1) - Math.max(sy, y0);
            if (wy <= 0) continue;
            for (int sx = sx0; sx < sx1; sx++) {
                double wx = Math.min(sx + 1, x1) - Math.max(sx, x0);
                if (wx <= 0) continue;
                double w = wx * wy;
                int p = src.argb()[sy * src.width() + sx];
                double alpha = (p >>> 24) / 255.0;
                a += alpha * w;
                r += ((p >> 16) & 0xFF) * alpha * w;
                g += ((p >> 8) & 0xFF) * alpha * w;
                b += (p & 0xFF) * alpha * w;
                weight += w;
            }
        }
        return pack(a, r, g, b, weight);
    }

    private static int bilinear(ImageFrame src, int dx, int dy, int dw, int dh) {
        double fx = (dx + 0.5) * src.width() / dw - 0.5;
        double fy = (dy + 0.5) * src.height() / dh - 0.5;
        int x0 = clamp((int) Math.floor(fx), src.width());
        int y0 = clamp((int) Math.floor(fy), src.height());
        int x1 = clamp(x0 + 1, src.width());
        int y1 = clamp(y0 + 1, src.height());
        double tx = Math.max(0, Math.min(1, fx - Math.floor(fx)));
        double ty = Math.max(0, Math.min(1, fy - Math.floor(fy)));
        double a = 0;
        double r = 0;
        double g = 0;
        double b = 0;
        int[][] corners = {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}};
        double[] weights = {(1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty};
        for (int i = 0; i < 4; i++) {
            int p = src.argb()[corners[i][1] * src.width() + corners[i][0]];
            double alpha = (p >>> 24) / 255.0;
            double w = weights[i];
            a += alpha * w;
            r += ((p >> 16) & 0xFF) * alpha * w;
            g += ((p >> 8) & 0xFF) * alpha * w;
            b += (p & 0xFF) * alpha * w;
        }
        return pack(a, r, g, b, 1.0);
    }

    private static int pack(double a, double r, double g, double b, double weight) {
        if (weight <= 0 || a <= 0) return 0;
        double alpha = a / weight;
        int ia = (int) Math.round(alpha * 255);
        int ir = (int) Math.round(r / a);
        int ig = (int) Math.round(g / a);
        int ib = (int) Math.round(b / a);
        return (clamp255(ia) << 24) | (clamp255(ir) << 16) | (clamp255(ig) << 8) | clamp255(ib);
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
