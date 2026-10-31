package dev.chatalyst.hologram;

import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * PNG, JPG y GIF (tambien animado) a {@link DecodedImage}, con la libreria estandar de Java
 * (sin dependencias). Sin Bukkit; se llama siempre fuera del hilo del servidor.
 *
 * <p>Protecciones: el tamaño se mira ANTES de descodificar (un PNG de 30.000 x 30.000 son
 * 3,6 GB en memoria aunque el archivo ocupe 100 KB) y los fotogramas de un GIF se cortan en
 * {@code maxFrames}.
 *
 * <p>Un GIF no guarda fotogramas completos: cada uno es un parche sobre el anterior, con su
 * posicion y su forma de "deshacerse" (dejarlo, borrar su rectangulo o volver a como estaba).
 * Aqui se componen sobre un lienzo para que cada fotograma salga entero.
 */
public final class ImageDecoder {

    private static final String GIF_IMAGE = "javax_imageio_gif_image_1.0";
    private static final String GIF_STREAM = "javax_imageio_gif_stream_1.0";

    private ImageDecoder() {
    }

    public static DecodedImage decode(byte[] data, int maxFrames, int maxDimension)
            throws IOException {
        if (data == null || data.length == 0) throw new IOException("empty file");
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) throw new IOException("unreadable image");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("not a PNG, JPG or GIF image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                checkSize(width, height, maxDimension);
                boolean gif = reader.getFormatName().toLowerCase(Locale.ROOT).contains("gif");
                if (!gif) {
                    BufferedImage image = reader.read(0);
                    return new DecodedImage(List.of(toFrame(image, 0)));
                }
                return decodeGif(reader, Math.max(1, maxFrames), maxDimension);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void checkSize(int width, int height, int maxDimension) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("empty image");
        if (width > maxDimension || height > maxDimension) {
            throw new IOException("image too large: " + width + "x" + height
                    + " (max " + maxDimension + ")");
        }
    }

    private static DecodedImage decodeGif(ImageReader reader, int maxFrames, int maxDimension)
            throws IOException {
        int[] screen = logicalScreen(reader.getStreamMetadata());
        int canvasW = screen[0] > 0 ? screen[0] : reader.getWidth(0);
        int canvasH = screen[1] > 0 ? screen[1] : reader.getHeight(0);
        checkSize(canvasW, canvasH, maxDimension);
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < maxFrames; index++) {
            BufferedImage patch;
            IIOMetadata metadata;
            try {
                patch = reader.read(index);
                metadata = reader.getImageMetadata(index);
            } catch (IndexOutOfBoundsException end) {
                break;
            }
            GifFrameInfo info = frameInfo(metadata);
            int[] previous = info.disposal().equals("restoreToPrevious")
                    ? pixels(canvas) : null;
            Graphics2D g = canvas.createGraphics();
            try {
                g.drawImage(patch, info.left(), info.top(), null);
            } finally {
                g.dispose();
            }
            frames.add(new ImageFrame(canvasW, canvasH, pixels(canvas), info.delayMs()));
            switch (info.disposal()) {
                case "restoreToBackgroundColor" -> clear(canvas, info.left(), info.top(),
                        patch.getWidth(), patch.getHeight());
                case "restoreToPrevious" -> canvas.setRGB(0, 0, canvasW, canvasH, previous, 0,
                        canvasW);
                default -> {
                    // "none" / "doNotDispose": el siguiente se pinta encima de este.
                }
            }
        }
        if (frames.isEmpty()) throw new IOException("GIF without frames");
        return new DecodedImage(frames);
    }

    private record GifFrameInfo(int left, int top, int delayMs, String disposal) {
    }

    private static GifFrameInfo frameInfo(IIOMetadata metadata) {
        int left = 0;
        int top = 0;
        int delay = 0;
        String disposal = "none";
        if (metadata != null) {
            Node root = metadata.getAsTree(GIF_IMAGE);
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                switch (child.getNodeName()) {
                    case "ImageDescriptor" -> {
                        left = attribute(child, "imageLeftPosition", 0);
                        top = attribute(child, "imageTopPosition", 0);
                    }
                    case "GraphicControlExtension" -> {
                        delay = attribute(child, "delayTime", 0) * 10;
                        Node node = child.getAttributes().getNamedItem("disposalMethod");
                        if (node != null) disposal = node.getNodeValue();
                    }
                    default -> {
                    }
                }
            }
        }
        return new GifFrameInfo(left, top, delay, disposal);
    }

    private static int[] logicalScreen(IIOMetadata metadata) {
        if (metadata == null) return new int[]{0, 0};
        try {
            Node root = metadata.getAsTree(GIF_STREAM);
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeName().equals("LogicalScreenDescriptor")) {
                    return new int[]{attribute(child, "logicalScreenWidth", 0),
                            attribute(child, "logicalScreenHeight", 0)};
                }
            }
        } catch (IllegalArgumentException unsupported) {
            // Formato de metadatos distinto: se usa el tamaño del primer fotograma.
        }
        return new int[]{0, 0};
    }

    private static int attribute(Node node, String name, int fallback) {
        Node value = node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.getNodeValue());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static void clear(BufferedImage canvas, int x, int y, int w, int h) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(canvas.getWidth(), x + w);
        int y1 = Math.min(canvas.getHeight(), y + h);
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) canvas.setRGB(xx, yy, 0);
        }
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    static ImageFrame toFrame(BufferedImage image, int delayMs) {
        return new ImageFrame(image.getWidth(), image.getHeight(), pixels(image), delayMs);
    }
}
