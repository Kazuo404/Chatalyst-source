package dev.chatalyst.gui;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.lang.LocalizedConfig;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Como el DUEÑO agrupa un catalogo, que no es como el plugin lo entrega.
 *
 * <p>Nacio en el sorteo y sirve igual para el alquiler, asi que vive aqui y no en uno de los
 * dos. Dos modelos de categoria acaban divergiendo — uno gana un orden configurable, el otro
 * un icono por defecto — y entonces las dos pantallas del mismo plugin se comportan distinto
 * sin que nadie lo haya decidido.
 *
 * @param section el nombre de la seccion de config que declara estas categorias, para que cada
 *                catalogo tenga las suyas sin compartirlas con el otro.
 */
public record Catalog(String id, Material icon, int order, String section) {

    /** Donde va lo que nadie clasifico. */
    public static final String FALLBACK = "otros";

    /** Las categorias declaradas, en el orden en que se enseñan. */
    public static List<Catalog> all(FileConfiguration config, String section) {
        ConfigurationSection root = config.getConfigurationSection(section);
        if (root == null) return List.of();
        List<Catalog> out = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            String id = normalize(key);
            ConfigurationSection entry = root.getConfigurationSection(key);
            if (id == null || entry == null) continue;
            Material icon = Material.matchMaterial(
                    String.valueOf(entry.getString("Icon", "")).toUpperCase(Locale.ROOT));
            if (icon == null || !icon.isItem() || icon.isAir()) icon = Material.CHEST;
            out.add(new Catalog(id, icon, entry.getInt("Order", 100), section));
        }
        out.sort(Comparator.comparingInt(Catalog::order).thenComparing(Catalog::id));
        return List.copyOf(out);
    }

    /**
     * Las categorias que de verdad tienen algo que enseñar, con sus cosas ya repartidas.
     *
     * <p>Una categoria vacia no se enseña: un icono que abre una pantalla en blanco parece un
     * fallo del plugin. Y lo que apunte a una categoria que no existe cae en la de descarte en
     * vez de perderse, que es el unico final del que nadie se entera.
     */
    public static <T> Map<Catalog, List<T>> group(FileConfiguration config, String section,
                                                  List<T> items, Function<T, String> categoryOf) {
        Map<String, List<T>> byId = new LinkedHashMap<>();
        for (T item : items) {
            byId.computeIfAbsent(categoryOf.apply(item), key -> new ArrayList<>()).add(item);
        }
        Map<Catalog, List<T>> out = new LinkedHashMap<>();
        for (Catalog category : all(config, section)) {
            List<T> mine = byId.remove(category.id());
            if (mine != null && !mine.isEmpty()) out.put(category, List.copyOf(mine));
        }
        List<T> orphans = new ArrayList<>();
        byId.values().forEach(orphans::addAll);
        if (!orphans.isEmpty()) {
            out.put(new Catalog(FALLBACK, Material.CHEST_MINECART, Integer.MAX_VALUE, section),
                    List.copyOf(orphans));
        }
        return out;
    }

    /** Nombre escrito por el dueño, leido por espectador; si no lo puso, "sin categoria". */
    public String displayName(FileConfiguration config, CommandSender viewer) {
        ConfigurationSection root = config.getConfigurationSection(section);
        ConfigurationSection entry = root == null ? null : root.getConfigurationSection(id);
        if (entry == null) return Lang.raw(viewer, "menu.category-other");
        return LocalizedConfig.string(entry, "Name", viewer, id);
    }

    /** Mismo tamiz que un id: se teclea y se guarda, asi que no admite cualquier cosa. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.matches("[a-z0-9_+-]{1,64}") ? id : null;
    }
}
