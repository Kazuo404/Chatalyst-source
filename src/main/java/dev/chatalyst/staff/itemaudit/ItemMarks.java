package dev.chatalyst.staff.itemaudit;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La marca de origen que llevan los objetos sacados de la nada: en el PersistentDataContainer
 * del stack, con el espacio de nombres del plugin, viaja con el objeto de mano en mano, a
 * cofres y al suelo, y sobrevive a reinicios.
 *
 * <p>El compromiso con el apilado: dos stacks con marcas distintas NO se apilan. Para no
 * romper el inventario de construccion de nadie, el mismo staff que saca el mismo material
 * dentro de {@code Marking.Reuse_Window_Seconds} recibe EXACTAMENTE la misma marca (mismo id
 * y misma fecha), asi que todo lo que saca en esa ventana se apila entre si. Lo que no se
 * apila es lo marcado con lo no marcado, que es justo la frontera que interesa ver.
 */
final class ItemMarks {

    /** Lo que dice la marca de un stack. */
    record Mark(String id, UUID staff, String staffName, long time) {
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final NamespacedKey idKey;
    private final NamespacedKey staffKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey timeKey;
    /** La del kit del modo staff: esos objetos son herramientas, no botin. */
    private final NamespacedKey kitKey;
    /** staff|material -&gt; la ultima marca entregada, para reusarla dentro de la ventana. */
    private final Map<String, Mark> recent = new ConcurrentHashMap<>();

    ItemMarks(JavaPlugin plugin) {
        this.idKey = new NamespacedKey(plugin, "audit_id");
        this.staffKey = new NamespacedKey(plugin, "audit_staff");
        this.nameKey = new NamespacedKey(plugin, "audit_staff_name");
        this.timeKey = new NamespacedKey(plugin, "audit_time");
        this.kitKey = new NamespacedKey(plugin, "staff_action");
    }

    /** Marca para un objeto nuevo: la de la ventana si sigue viva, o una nueva. */
    Mark markFor(UUID staff, String staffName, String material, long now, long reuseMillis) {
        String key = (staff == null ? staffName : staff.toString()) + "|" + material;
        return recent.compute(key, (ignored, previous) -> previous != null
                && reuseMillis > 0 && now - previous.time() <= reuseMillis
                ? previous : new Mark(AuditIds.next(), staff, staffName, now));
    }

    /** Olvida las ventanas ya cerradas: sin esto el mapa crece con cada material sacado. */
    void expire(long now, long reuseMillis) {
        recent.values().removeIf(mark -> now - mark.time() > reuseMillis);
    }

    void clear() {
        recent.clear();
    }

    /** Pone la marca en el stack (lo modifica). False si el objeto no admite metadatos. */
    boolean apply(ItemStack item, Mark mark) {
        if (item == null || item.getType().isAir()) return false;
        return item.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(idKey, PersistentDataType.STRING, mark.id());
            data.set(staffKey, PersistentDataType.STRING,
                    mark.staff() == null ? "" : mark.staff().toString());
            data.set(nameKey, PersistentDataType.STRING, mark.staffName());
            data.set(timeKey, PersistentDataType.LONG, mark.time());
        });
    }

    Mark read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        String id = data.get(idKey, PersistentDataType.STRING);
        if (id == null) return null;
        String staff = data.get(staffKey, PersistentDataType.STRING);
        UUID staffId = null;
        if (staff != null && !staff.isEmpty()) {
            try {
                staffId = UUID.fromString(staff);
            } catch (IllegalArgumentException corrupt) {
                staffId = null;
            }
        }
        String name = data.get(nameKey, PersistentDataType.STRING);
        Long time = data.get(timeKey, PersistentDataType.LONG);
        return new Mark(id, staffId, name == null ? "?" : name, time == null ? 0L : time);
    }

    boolean isKitItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(kitKey);
    }

    /**
     * Copia sin la marca, para comparar objetos: en creativo el cliente puede mandar la
     * version sin marcar del mismo stack (aun no se habia sincronizado) y eso no puede
     * contar como sacar uno nuevo.
     */
    ItemStack unmarked(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemStack copy = item.clone();
        if (!copy.hasItemMeta()) return copy;
        copy.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.remove(idKey);
            data.remove(staffKey);
            data.remove(nameKey);
            data.remove(timeKey);
        });
        return copy;
    }

    // ---- lo que se sabe de un stack -------------------------------------------------------

    static ValuableRules.ItemFacts facts(ItemStack item) {
        String key = item.getType().getKey().toString();
        if (!item.hasItemMeta()) return ValuableRules.ItemFacts.of(key, item.getAmount());
        ItemMeta meta = item.getItemMeta();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        boolean over = collect(enchants, meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta book) {
            over |= collect(enchants, book.getStoredEnchants());
        }
        String name = meta.hasDisplayName() && meta.displayName() != null
                ? PLAIN.serialize(meta.displayName()) : "";
        List<String> contents = new ArrayList<>();
        if (meta instanceof BlockStateMeta states && states.hasBlockState()
                && states.getBlockState() instanceof Container container) {
            for (ItemStack inner : container.getSnapshotInventory().getContents()) {
                if (inner != null && !inner.getType().isAir()) {
                    contents.add(inner.getType().getKey().toString());
                }
            }
        } else if (meta instanceof BundleMeta bundle) {
            for (ItemStack inner : bundle.getItems()) {
                if (inner != null && !inner.getType().isAir()) {
                    contents.add(inner.getType().getKey().toString());
                }
            }
        }
        return new ValuableRules.ItemFacts(key, item.getAmount(), enchants, over, name, contents);
    }

    /** True si algun nivel pasa del maximo vanilla (Filo X, Proteccion 255...). */
    private static boolean collect(Map<String, Integer> out, Map<Enchantment, Integer> enchants) {
        boolean over = false;
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            out.merge(e.getKey().getKey().toString(), e.getValue(), Math::max);
            over |= e.getValue() > e.getKey().getMaxLevel();
        }
        return over;
    }

    /** "sharpness 10 · "Hoja" · 27 dentro (diamond block)": lo que va al hover y al log. */
    static String detail(ValuableRules.ItemFacts facts) {
        List<String> parts = new ArrayList<>();
        String enchants = AuditFormat.enchantments(facts.enchants());
        if (!enchants.isEmpty()) parts.add(enchants);
        if (!facts.customName().isBlank()) parts.add("\"" + facts.customName() + "\"");
        if (!facts.contents().isEmpty()) {
            Map<String, Integer> inside = new LinkedHashMap<>();
            for (String inner : facts.contents()) inside.merge(inner, 1, Integer::sum);
            List<String> names = inside.keySet().stream().limit(3)
                    .map(AuditFormat::prettyItem).toList();
            parts.add("[" + facts.contents().size() + ": " + String.join(", ", names)
                    + (inside.size() > 3 ? ", …" : "") + "]");
        }
        return String.join(" · ", parts);
    }
}
