package dev.chatalyst.staff.lookup;

/**
 * Paginacion de las listas en texto (/banlist, /mutelist, /warnlist): pagina pedida en base 1,
 * acotada a las que existen. Sin Bukkit para poder fijarla con tests.
 *
 * @param page  pagina mostrada, base 1
 * @param pages total de paginas (al menos 1, aunque la lista este vacia)
 * @param from  primer indice incluido
 * @param to    ultimo indice excluido
 */
public record Paging(int page, int pages, int from, int to) {

    public static final int PER_PAGE = 10;

    public static Paging of(int total, int perPage, int requestedPage) {
        int size = Math.max(1, perPage);
        int count = Math.max(0, total);
        int pages = Math.max(1, (count + size - 1) / size);
        int page = Math.min(Math.max(1, requestedPage), pages);
        int from = (page - 1) * size;
        return new Paging(page, pages, from, Math.min(count, from + size));
    }

    /** La pagina escrita por el staff, o 1 si no es un numero. */
    public static int parsePage(String raw) {
        if (raw == null) return 1;
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < pages;
    }
}
