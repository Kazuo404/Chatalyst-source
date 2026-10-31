package dev.chatalyst.gift;

import dev.chatalyst.lang.LocalizedConfig;
import dev.chatalyst.store.StoreProduct;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Uno de los premios que puede salir de una caja.
 *
 * <p>El {@code Weight} es un peso, no un porcentaje: se reparte contra la suma de los pesos de
 * su caja. Asi anadir un premio nuevo no obliga a recalcular los demas para que sumen cien —
 * el error mas facil de cometer y el mas dificil de ver, porque una tabla que suma 97 no
 * falla, solo miente.
 *
 * <p>El objeto se construye con {@link StoreProduct.ItemSpec}, el mismo constructor que usa la
 * tienda. Un premio y un producto son la misma cosa vista desde dos sitios, y tener dos
 * lectores de {@code Material}/{@code Enchantments} era garantizar que uno acabase soportando
 * un campo que el otro no.
 *
 * @param weight peso relativo dentro de su caja; 0 o menos = nunca sale.
 * @param min    cantidad minima entregada (ya recortada al stack maximo del material).
 * @param max    cantidad maxima; igual a {@code min} cuando se escribio una cifra sola.
 */
public record GiftReward(int weight, int min, int max, StoreProduct.ItemSpec spec,
                         String label, String labelEs) {

    /** Un premio sin nada que entregar no es un premio: se descarta al leer. */
    static List<GiftReward> all(ConfigurationSection tier) {
        List<?> rows = tier == null ? List.of() : tier.getList("Rewards", List.of());
        List<GiftReward> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) of(map).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    private static java.util.Optional<GiftReward> of(Map<?, ?> row) {
        Map<String, Object> raw = new LinkedHashMap<>();
        row.forEach((key, value) -> raw.put(String.valueOf(key), value));

        Material material = Material.matchMaterial(
                String.valueOf(raw.getOrDefault("Material", "")).toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem() || material.isAir()) {
            return java.util.Optional.empty();
        }

        int[] amount = amount(raw.get("Amount"), material.getMaxStackSize());
        int weight = (int) Math.max(0L, Math.min(1_000_000L, longOf(raw.get("Weight"), 1L)));

        String name = raw.get("Name") == null ? "" : String.valueOf(raw.get("Name"));
        List<String> lore = new ArrayList<>();
        if (raw.get("Lore") instanceof List<?> configured) {
            for (Object line : configured) lore.add(String.valueOf(line));
        }
        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (raw.get("Enchantments") instanceof Map<?, ?> configured) {
            configured.forEach((key, level) -> {
                String id = String.valueOf(key).toLowerCase(Locale.ROOT).trim();
                if (id.matches("[a-z0-9_:./-]{1,64}")) {
                    enchants.put(id, (int) Math.max(1L, Math.min(255L, longOf(level, 1L))));
                }
            });
        }
        boolean glow = Boolean.parseBoolean(String.valueOf(raw.get("Glow")));

        // El spec se construye con cantidad 1: la de verdad se tira al abrir, y guardarla
        // aqui haria creer que la caja da siempre lo mismo.
        StoreProduct.ItemSpec spec = new StoreProduct.ItemSpec(material, 1, name,
                List.copyOf(lore), Map.copyOf(enchants), 0, glow);

        String label = raw.get("Label") == null ? "" : String.valueOf(raw.get("Label"));
        String labelEs = raw.get("Label_es") == null ? "" : String.valueOf(raw.get("Label_es"));
        return java.util.Optional.of(new GiftReward(weight, amount[0], amount[1], spec,
                label, labelEs));
    }

    /**
     * Lee {@code 3} o {@code 2-6}. Un rango al reves se endereza en vez de rechazarse: quien
     * escribio {@code 6-2} queria entre dos y seis, no un error de arranque.
     */
    private static int[] amount(Object raw, int stackSize) {
        long low = 1L;
        long high = 1L;
        String text = raw == null ? "1" : String.valueOf(raw).trim();
        int dash = text.indexOf('-', 1);
        try {
            if (dash > 0) {
                low = Long.parseLong(text.substring(0, dash).trim());
                high = Long.parseLong(text.substring(dash + 1).trim());
            } else {
                low = high = Long.parseLong(text);
            }
        } catch (NumberFormatException ignored) {
            // Cantidad ilegible: una unidad. Es lo que menos se parece a un premio roto.
        }
        if (low > high) {
            long swap = low;
            low = high;
            high = swap;
        }
        int max = Math.max(1, stackSize);
        return new int[]{(int) Math.max(1L, Math.min(max, low)),
                (int) Math.max(1L, Math.min(max, high))};
    }

    private static long longOf(Object raw, long fallback) {
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** El objeto entregado, con la cantidad ya tirada. */
    public ItemStack roll(Random random) {
        ItemStack stack = spec.build();
        stack.setAmount(min >= max ? min : min + random.nextInt(max - min + 1));
        return stack;
    }

    /**
     * Como se llama este premio en el lore de la caja.
     *
     * <p>Orden: {@code Label} escrito a mano, si no el {@code Name} del objeto, si no el
     * material en limpio. Lo ultimo es un respaldo feo a proposito — se lee "Golden Apple" en
     * vez de "GOLDEN_APPLE", pero no se traduce, y eso empuja a escribir {@code Label_es}.
     */
    public String label(CommandSender viewer) {
        boolean spanish = LocalizedConfig.suffix(viewer).equals("_es");
        String chosen = spanish && !labelEs.isBlank() ? labelEs : label;
        if (chosen.isBlank()) chosen = label;
        if (chosen.isBlank() && !spec.name().isBlank()) chosen = spec.name();
        if (chosen.isBlank()) chosen = prettify(spec.material());
        return chosen;
    }

    private static String prettify(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** "x3" o "x2-6", vacio cuando es una sola unidad y decirlo sobraria. */
    public String amountText() {
        if (min == max) return min == 1 ? "" : "x" + min;
        return "x" + min + "-" + max;
    }
}
