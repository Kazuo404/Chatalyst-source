package dev.chatalyst.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Comprueba que un menu sea simetrico (logica pura): en cada fila, las columnas ocupadas tienen
 * que ser espejo respecto a la columna central. Lo usan los tests y {@link Menu#open}, que avisa
 * en el log la primera vez que se abre un menu que no lo es.
 */
public final class MenuSymmetry {

    private MenuSymmetry() {
    }

    /** Filas (0..) que no son simetricas; vacia si el menu lo es entero. */
    public static List<Integer> asymmetricRows(boolean[] occupied) {
        List<Integer> out = new ArrayList<>();
        int rows = occupied.length / 9;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 4; col++) {
                if (occupied[row * 9 + col] != occupied[row * 9 + 8 - col]) {
                    out.add(row);
                    break;
                }
            }
        }
        return out;
    }
}
