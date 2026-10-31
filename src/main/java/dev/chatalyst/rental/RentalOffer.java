package dev.chatalyst.rental;

import dev.chatalyst.lang.LocalizedConfig;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Una ventaja que se alquila con dinero DEL JUEGO: tanto tiempo, por tanto precio.
 *
 * <p>Existe para que la tienda de dinero real no sea el unico camino a nada. Un rango o un
 * {@code /fly} que solo se compran pagando convierten el server en pay-to-win; los mismos
 * alquilables con lo que se gana jugando le dan al que juega una via propia, y al que paga le
 * dejan la comodidad, no el poder.
 *
 * <p>Se concede exactamente igual que un premio de sorteo: por la API de LuckPerms y con
 * caducidad. Un alquiler que no caduca no es un alquiler, y escribir la caducidad a mano en un
 * comando de otro plugin no permite ni saber si funciono ni devolver el dinero si no.
 */
public record RentalOffer(String id, Material icon, boolean glow, List<String> permissions,
                          String rank, long durationMillis, double price) {

    private static final String SECTION = "Rental_Offers";

    /** Todo lo alquilable, en el orden del fichero. */
    public static List<RentalOffer> all(FileConfiguration config) {
        ConfigurationSection catalog = config.getConfigurationSection(SECTION);
        if (catalog == null) return List.of();
        List<RentalOffer> out = new ArrayList<>();
        for (String key : catalog.getKeys(false)) {
            ConfigurationSection entry = catalog.getConfigurationSection(key);
            if (entry != null) of(key, entry).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    public static Optional<RentalOffer> resolve(FileConfiguration config, String rawId) {
        String id = normalize(rawId);
        return id == null ? Optional.empty()
                : all(config).stream().filter(offer -> offer.id().equals(id)).findFirst();
    }

    static Optional<RentalOffer> of(String rawId, ConfigurationSection entry) {
        String id = normalize(rawId);
        if (id == null) return Optional.empty();

        List<String> permissions = new ArrayList<>();
        for (String raw : entry.getStringList("Permissions")) {
            String node = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            // La misma regla que el sorteo: un permiso es un nodo, no una frase. Dos reglas
            // distintas para lo mismo acaban aceptando aqui lo que alla se descarta.
            if (dev.chatalyst.giveaway.GiveawayPrize.validPermission(node)) permissions.add(node);
        }
        String rank = normalize(entry.getString("Rank"));

        long duration = TimeUtil.parseDuration(entry.getString("Duration", ""));
        // Un alquiler PERMANENTE no es un alquiler: es una venta, y una venta por dinero del
        // juego de algo que no caduca desequilibra el server sin que nadie lo revise.
        if (duration <= 0L) return Optional.empty();

        double price = entry.getDouble("Price", -1d);
        if (!Double.isFinite(price) || price < 0d) return Optional.empty();

        if (permissions.isEmpty() && rank == null) return Optional.empty();

        Material icon = Material.matchMaterial(
                String.valueOf(entry.getString("Icon", "")).toUpperCase(Locale.ROOT));
        if (icon == null || !icon.isItem() || icon.isAir()) icon = Material.CLOCK;

        return Optional.of(new RentalOffer(id, icon, entry.getBoolean("Glow", false),
                List.copyOf(permissions), rank, duration, price));
    }

    /** En que categoria se busca. Sin escribir nada cae en la de descarte, no desaparece. */
    public String category(FileConfiguration config) {
        ConfigurationSection entry = entry(config, id);
        String written = entry == null ? null : entry.getString("Category");
        String normalized = dev.chatalyst.gui.Catalog.normalize(written);
        return normalized == null ? dev.chatalyst.gui.Catalog.FALLBACK : normalized;
    }

    public String displayName(FileConfiguration config, CommandSender viewer) {
        ConfigurationSection entry = entry(config, id);
        return LocalizedConfig.string(entry, "Name", viewer, id);
    }

    public List<String> description(FileConfiguration config, CommandSender viewer) {
        return LocalizedConfig.list(entry(config, id), "Description", viewer);
    }

    /** Cuando caduca lo concedido si se compra AHORA. */
    public long expiryMillis() {
        long deadline = TimeUtil.deadline(System.currentTimeMillis(), durationMillis);
        return deadline == TimeUtil.PERMANENT ? 0L : deadline;
    }

    private static ConfigurationSection entry(FileConfiguration config, String id) {
        ConfigurationSection catalog = config.getConfigurationSection(SECTION);
        if (catalog == null) return null;
        for (String key : catalog.getKeys(false)) {
            if (key.equalsIgnoreCase(id)) return catalog.getConfigurationSection(key);
        }
        return null;
    }

    static String normalize(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.matches("[a-z0-9_+-]{1,64}") ? id : null;
    }
}
