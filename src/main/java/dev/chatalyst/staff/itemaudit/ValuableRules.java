package dev.chatalyst.staff.itemaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Que cuenta como "objeto valioso": de esos se avisa SIEMPRE, aunque el resto solo se
 * registre. Un staff que saca 64 piedras para construir no es noticia; uno que saca un
 * spawner, netherite o una espada Filo X si.
 */
final class ValuableRules {

    /**
     * Lo que se sabe de un stack sin tocar Bukkit.
     *
     * @param key           {@code minecraft:diamond_sword}
     * @param enchants      encantamiento -&gt; nivel (incluye los guardados en libros)
     * @param overEnchanted algun nivel supera el maximo vanilla de ese encantamiento
     * @param customName    nombre puesto en un yunque o por comando, "" si no tiene
     * @param contents      claves de lo que lleva dentro (shulker, bundle), un nivel
     */
    record ItemFacts(String key, int amount, Map<String, Integer> enchants,
                     boolean overEnchanted, String customName, List<String> contents) {

        ItemFacts {
            enchants = enchants == null ? Map.of() : Map.copyOf(enchants);
            customName = customName == null ? "" : customName;
            contents = contents == null ? List.of() : List.copyOf(contents);
        }

        static ItemFacts of(String key, int amount) {
            return new ItemFacts(key, amount, Map.of(), false, "", List.of());
        }
    }

    private final List<Pattern> materials;
    private final boolean overEnchanted;
    private final boolean anyEnchanted;
    private final boolean customName;
    private final boolean contents;

    ValuableRules(List<String> materials, boolean overEnchanted, boolean anyEnchanted,
                  boolean customName, boolean contents) {
        List<Pattern> compiled = new ArrayList<>();
        for (String glob : materials) {
            String clean = glob == null ? "" : glob.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) compiled.add(glob(clean));
        }
        this.materials = List.copyOf(compiled);
        this.overEnchanted = overEnchanted;
        this.anyEnchanted = anyEnchanted;
        this.customName = customName;
        this.contents = contents;
    }

    static ValuableRules none() {
        return new ValuableRules(List.of(), false, false, false, false);
    }

    boolean valuable(ItemFacts item) {
        if (item == null) return false;
        if (materialMatches(item.key())) return true;
        if (overEnchanted && item.overEnchanted()) return true;
        if (anyEnchanted && !item.enchants().isEmpty()) return true;
        if (customName && !item.customName().isBlank()) return true;
        // Un shulker lleno de diamantes es lo mismo que los diamantes: no puede ser la
        // forma de sacar 1.728 sin que nadie se entere.
        if (contents) {
            for (String inner : item.contents()) {
                if (materialMatches(inner)) return true;
            }
        }
        return false;
    }

    boolean materialMatches(String key) {
        if (key == null || key.isEmpty()) return false;
        String full = key.toLowerCase(Locale.ROOT);
        String path = full.substring(full.indexOf(':') + 1);
        for (Pattern pattern : materials) {
            if (pattern.matcher(path).matches() || pattern.matcher(full).matches()) return true;
        }
        return false;
    }

    /** "*_ore" -&gt; cualquier mena; el resto de caracteres se toma literal. */
    private static Pattern glob(String glob) {
        String[] parts = glob.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) regex.append(".*");
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.toString());
    }
}
