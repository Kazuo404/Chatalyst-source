package dev.chatalyst.log;

/**
 * Piezas de texto comunes a lo que los jugadores escriben FUERA del chat (carteles, libros,
 * yunque), compartidas por el log, el spy, las reglas y el mute. Sin Bukkit: se testean solas.
 */
public final class ContentText {

    private ContentText() {
    }

    /**
     * "@ mundo x,y,z (detras)": donde esta un cartel. Sin el sitio el registro dice QUE se
     * escribio pero no DONDE, y un cartel ofensivo hay que encontrarlo para quitarlo. La cara
     * importa porque cada una se edita por separado.
     */
    public static String where(String world, int x, int y, int z, boolean back) {
        return "@ " + world + " " + x + "," + y + "," + z + (back ? " (detras)" : "");
    }

    /**
     * Si sacar el resultado del yunque PONE un nombre nuevo. Reparar o combinar un item que ya
     * se llamaba asi no es escribir nada: el campo de nombre trae el nombre que ya tenia.
     *
     * @param renameText  lo que hay escrito en el campo del yunque (null o vacio = sin nombre)
     * @param currentName el nombre que ya tenia el item de la izquierda (null = sin nombre)
     */
    public static boolean isRename(String renameText, String currentName) {
        if (renameText == null || renameText.isBlank()) return false;
        return currentName == null || !renameText.trim().equals(currentName.trim());
    }
}
