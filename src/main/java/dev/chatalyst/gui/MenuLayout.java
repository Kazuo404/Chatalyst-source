package dev.chatalyst.gui;

/**
 * Colocacion simetrica de listas en un menu (logica pura, sin Bukkit).
 *
 * <p>Las filas completas se llenan de lado a lado y la ultima se CENTRA: con un numero impar de
 * elementos quedan seguidos en el centro; con uno par se deja libre la columna central (dos a
 * cada lado, tres a cada lado...). Asi cualquier pagina de cualquier lista es simetrica.
 */
public final class MenuLayout {

    private MenuLayout() {
    }

    /** Columnas (0-8) de {@code n} elementos (0-9) centrados en una fila, de izquierda a derecha. */
    public static int[] rowColumns(int n) {
        int count = Math.max(0, Math.min(9, n));
        int[] out = new int[count];
        if (count % 2 == 1) {
            int start = (9 - count) / 2;
            for (int i = 0; i < count; i++) out[i] = start + i;
        } else {
            int half = count / 2;
            for (int i = 0; i < half; i++) {
                out[i] = 4 - half + i;
                out[half + i] = 5 + i;
            }
        }
        return out;
    }

    /**
     * Slots de {@code count} elementos a partir de la fila {@code firstRow}, en filas de 9: las
     * completas llenas y la ultima centrada. {@code count} ya es lo que cabe en la pagina.
     */
    public static int[] slots(int count, int firstRow) {
        int n = Math.max(0, count);
        int[] out = new int[n];
        int full = n / 9;
        int index = 0;
        for (int row = 0; row < full; row++) {
            for (int col = 0; col < 9; col++) out[index++] = (firstRow + row) * 9 + col;
        }
        for (int col : rowColumns(n - full * 9)) out[index++] = (firstRow + full) * 9 + col;
        return out;
    }

    /**
     * Igual que {@link #slots(int, int)} pero con un margen de una columna a cada lado (filas de
     * 7, columnas 1-7): para rejillas enmarcadas. La ultima fila tambien va centrada.
     */
    public static int[] insetSlots(int count, int firstRow) {
        int n = Math.max(0, count);
        int[] out = new int[n];
        int full = n / 7;
        int index = 0;
        for (int row = 0; row < full; row++) {
            for (int col = 1; col <= 7; col++) out[index++] = (firstRow + row) * 9 + col;
        }
        for (int col : rowColumns(n - full * 7)) out[index++] = (firstRow + full) * 9 + col;
        return out;
    }

    /** Posicion en la lista del elemento que ocupa {@code slot}, o -1 si ahi no hay ninguno. */
    public static int indexAt(int slot, int count, int firstRow) {
        int[] slots = slots(count, firstRow);
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }

    /** Lo mismo para una sola fila centrada de {@code count} elementos (0-9). */
    public static int[] row(int count, int row) {
        int[] cols = rowColumns(count);
        int[] out = new int[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = row * 9 + cols[i];
        return out;
    }
}
