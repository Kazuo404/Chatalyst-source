package dev.chatalyst.profile;

import dev.chatalyst.gui.MenuLayout;

/**
 * Posiciones de los menus de /perfil (logica pura, sin Bukkit). Todo queda simetrico, como
 * exige {@code MenuSymmetry}: cabecera en el centro, interruptores y accesos centrados en su
 * fila y la barra de abajo en espejo.
 */
public final class ProfileLayout {

    /** Menu principal: 6 filas. */
    public static final int PROFILE_SIZE = 54;
    public static final int HEAD = 4;
    public static final int SETTINGS_ROW = 2;
    public static final int SHORTCUTS_ROW = 4;
    public static final int CLOSE = 49;

    /** Lista de bloqueados: 4 filas de cabezas y la barra de navegacion. */
    public static final int BLOCKED_SIZE = 54;
    public static final int BLOCKED_FIRST_ROW = 1;
    public static final int BLOCKED_PER_PAGE = 36;
    public static final int BLOCKED_EMPTY = 22;
    public static final int BLOCKED_BACK = 45;
    public static final int BLOCKED_PREVIOUS = 48;
    public static final int BLOCKED_PAGE = 49;
    public static final int BLOCKED_NEXT = 50;
    public static final int BLOCKED_CLOSE = 53;

    /** Confirmacion de dejar de ignorar: 3 filas. */
    public static final int CONFIRM_SIZE = 27;
    public static final int CONFIRM_YES = 11;
    public static final int CONFIRM_HEAD = 13;
    public static final int CONFIRM_NO = 15;

    private ProfileLayout() {
    }

    /** Slots de {@code count} interruptores, centrados en la fila de ajustes. */
    public static int[] settingSlots(int count) {
        return MenuLayout.row(count, SETTINGS_ROW);
    }

    /** Slots de {@code count} accesos directos, centrados en su fila. */
    public static int[] shortcutSlots(int count) {
        return MenuLayout.row(count, SHORTCUTS_ROW);
    }

    /** Paginas de una lista de {@code total} elementos (al menos 1, aunque este vacia). */
    public static int pages(int total) {
        return Math.max(1, (Math.max(0, total) + BLOCKED_PER_PAGE - 1) / BLOCKED_PER_PAGE);
    }

    /** Pagina (empezando en 0) recortada al rango valido. */
    public static int clampPage(int page, int total) {
        return Math.max(0, Math.min(page, pages(total) - 1));
    }

    /** Elementos que caben en la pagina {@code page} (empezando en 0). */
    public static int countOnPage(int page, int total) {
        int start = clampPage(page, total) * BLOCKED_PER_PAGE;
        return Math.max(0, Math.min(BLOCKED_PER_PAGE, total - start));
    }

    /** Slots de las cabezas de la pagina {@code page}. */
    public static int[] blockedSlots(int page, int total) {
        return MenuLayout.slots(countOnPage(page, total), BLOCKED_FIRST_ROW);
    }

    /** Indice en la lista completa del bloqueado que ocupa {@code slot}, o -1. */
    public static int blockedIndexAt(int slot, int page, int total) {
        int local = MenuLayout.indexAt(slot, countOnPage(page, total), BLOCKED_FIRST_ROW);
        return local < 0 ? -1 : clampPage(page, total) * BLOCKED_PER_PAGE + local;
    }
}
