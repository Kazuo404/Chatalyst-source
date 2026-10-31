package dev.chatalyst.hologram;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Pinta un trozo de mural en su mapa. Ignora por completo los datos de mapa del mundo: el
 * contenido sale de la imagen ya convertida (cacheada), asi que no se pierde, no se mezcla con
 * mapas de jugadores y no depende de lo que haya en {@code data/map_N.dat}.
 *
 * <p>No contextual: el lienzo es uno para todos y solo se reescribe cuando cambia el fotograma
 * (o la imagen). Bukkit llama a esto cada vez que va a mandar el mapa a alguien; las demas
 * veces es una comparacion de dos enteros.
 */
final class MuralRenderer extends MapRenderer {

    private final MuralService.Live live;
    private final int tile;
    private ImageLibrary.MuralArt drawnArt;
    private int drawnFrame = -1;

    MuralRenderer(MuralService.Live live, int tile) {
        super(false);
        this.live = live;
        this.tile = tile;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void render(MapView map, MapCanvas canvas, Player player) {
        ImageLibrary.MuralArt art = live.art;
        if (art == null || tile >= art.frames().getFirst().size()) return;
        int frame = Math.min(live.frame, art.frames().size() - 1);
        if (art == drawnArt && frame == drawnFrame) return;
        byte[] pixels = art.frames().get(frame).get(tile);
        for (int y = 0; y < MuralLayout.TILE; y++) {
            int row = y * MuralLayout.TILE;
            for (int x = 0; x < MuralLayout.TILE; x++) {
                canvas.setPixel(x, y, pixels[row + x]);
            }
        }
        drawnArt = art;
        drawnFrame = frame;
    }
}
