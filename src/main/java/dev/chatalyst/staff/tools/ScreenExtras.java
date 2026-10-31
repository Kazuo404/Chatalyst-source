package dev.chatalyst.staff.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Donde meter las lineas nuevas de una pantalla de castigo (numero, fecha, apelacion).
 *
 * <p>Las pantallas de ban y kick salen de moderation.yml, y los servidores que ya existen
 * tienen ahi SU plantilla, sin {@code {id}} ni {@code {appeal}}. Para que el numero del
 * castigo — lo que el jugador tiene que dar al apelar — aparezca sin obligar a nadie a
 * reescribir su config, se añade una linea por cada dato que la plantilla no coloque ya. Si
 * el dueño pone {@code {id}} en su plantilla, manda el: aqui no se duplica.
 *
 * <p>Se insertan ANTES del ultimo separador ("━━━"), que es como cierran todas las pantallas
 * del plugin; sin separador, al final. Sin Bukkit, para poder testearlo.
 */
public final class ScreenExtras {

    private ScreenExtras() {
    }

    /** Si alguna linea de la plantilla ya coloca {@code placeholder} (p. ej. "{id}"). */
    public static boolean placed(List<String> template, String placeholder) {
        for (String line : template) {
            if (line != null && line.contains(placeholder)) return true;
        }
        return false;
    }

    /** La plantilla con {@code extras} insertadas antes del separador final. */
    public static List<String> insert(List<String> template, List<String> extras) {
        List<String> out = new ArrayList<>(template);
        if (extras.isEmpty()) return out;
        int at = out.size();
        if (!out.isEmpty() && isSeparator(out.get(out.size() - 1))) at = out.size() - 1;
        List<String> block = new ArrayList<>();
        // Un hueco en blanco separa el bloque nuevo de lo anterior, salvo que ya lo haya.
        if (at > 0 && !out.get(at - 1).isBlank()) block.add("");
        block.addAll(extras);
        out.addAll(at, block);
        return out;
    }

    /** Una linea hecha solo de trazos (con o sin color delante): "<#5A4775>━━━━". */
    static boolean isSeparator(String line) {
        if (line == null) return false;
        String plain = line.replaceAll("<[^>]*>", "").trim();
        return plain.length() >= 3 && plain.chars().allMatch(c -> c == '━' || c == '─'
                || c == '-' || c == '=' || c == '▬');
    }
}
