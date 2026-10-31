package dev.chatalyst.gift;

import dev.chatalyst.lang.LocalizedConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Un tipo de caja: como se ve, y que puede haber dentro.
 *
 * <p>La caja NO guarda su premio. Se tira al abrirla, no al fabricarla, y a proposito: asi
 * cambiar la tabla de {@code gifts.yml} cambia tambien lo que dan las cajas que ya estan en
 * los cofres de los jugadores. Al reves, cada retoque de la tabla dejaria detras una capa de
 * cajas viejas que reparten lo de antes, y nadie podria decir que da una caja mirandola.
 *
 * @param texture perfil de la cabeza, en base64. Se admite tambien el hash pelado de
 *                textures.minecraft.net, que es lo que uno copia de una web de cabezas.
 */
public record GiftTier(String id, String texture, boolean glow, int order, String color,
                       List<GiftReward> rewards) {

    private static final String SECTION = "Gift_Tiers";

    /** Las cajas escritas, ordenadas por {@code Order} y luego por id. */
    public static List<GiftTier> all(FileConfiguration config) {
        ConfigurationSection catalogue = config.getConfigurationSection(SECTION);
        if (catalogue == null) return List.of();
        List<GiftTier> out = new ArrayList<>();
        for (String key : catalogue.getKeys(false)) {
            ConfigurationSection entry = catalogue.getConfigurationSection(key);
            if (entry != null) of(key, entry).ifPresent(out::add);
        }
        out.sort((a, b) -> a.order() != b.order()
                ? Integer.compare(a.order(), b.order()) : a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    public static Optional<GiftTier> resolve(FileConfiguration config, String rawId) {
        String id = normalize(rawId);
        return id == null ? Optional.empty()
                : all(config).stream().filter(tier -> tier.id().equals(id)).findFirst();
    }

    static Optional<GiftTier> of(String rawId, ConfigurationSection entry) {
        String id = normalize(rawId);
        if (id == null) return Optional.empty();
        String texture = texture(entry.getString("Texture", ""));
        String color = entry.getString("Color", "#FFD166");
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) color = "#FFD166";
        return Optional.of(new GiftTier(id, texture, entry.getBoolean("Glow", true),
                entry.getInt("Order", 0), color, GiftReward.all(entry)));
    }

    /**
     * Acepta el base64 entero o el hash suelto.
     *
     * <p>Lo que se copia de una web de cabezas es una cosa o la otra segun el boton que
     * pulses, y las dos parecen "la textura". Distinguirlas por el prefijo del base64 sale mas
     * barato que explicar en el fichero cual de las dos queriamos.
     */
    static String texture(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("eyJ")) return value;
        if (value.matches("[0-9a-fA-F]{40,72}")) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":"
                    + "\"http://textures.minecraft.net/texture/" + value.toLowerCase(Locale.ROOT)
                    + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return "";
    }

    static String normalize(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.matches("[a-z0-9-]{1,48}") ? id : null;
    }

    /** Suma de los pesos. Cero significa una caja sin premios posibles. */
    public int totalWeight() {
        int total = 0;
        for (GiftReward reward : rewards) total += Math.max(0, reward.weight());
        return total;
    }

    public String displayName(FileConfiguration config, CommandSender viewer) {
        ConfigurationSection entry = entry(config);
        return entry == null ? id : LocalizedConfig.string(entry, "Name", viewer, id);
    }

    public List<String> description(FileConfiguration config, CommandSender viewer) {
        ConfigurationSection entry = entry(config);
        return entry == null ? List.of() : LocalizedConfig.list(entry, "Description", viewer);
    }

    /**
     * La seccion de esta caja. El id va normalizado a minusculas pero la clave del YAML no:
     * {@code Oro:} tiene que encontrarse igual, o la caja se queda sin nombre ni descripcion.
     */
    private ConfigurationSection entry(FileConfiguration config) {
        ConfigurationSection catalogue = config.getConfigurationSection(SECTION);
        if (catalogue == null) return null;
        for (String key : catalogue.getKeys(false)) {
            if (id.equals(normalize(key))) return catalogue.getConfigurationSection(key);
        }
        return null;
    }
}
