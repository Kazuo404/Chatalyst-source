package dev.chatalyst.gift;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.InventoryUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /caja}: ver que cajas hay, darlas, y sacarlas a un fichero.
 *
 * <p>{@code exportar} existe por un motivo concreto: una caja es una cabeza con perfil y una
 * marca interna, y eso NO se escribe a mano en el config de otro plugin. Que la serialice
 * Bukkit y la escriba aqui convierte "pegar la caja dentro de un kit" en copiar un bloque, en
 * vez de en adivinar como se guarda un perfil de cabeza en YAML.
 */
public final class GiftCommand implements RuntimeCommandRegistrar.Handler {

    private static final String ADMIN = "chatalyst.command.gift.admin";

    private final ChatalystPlugin plugin;
    private final GiftService gifts;

    public GiftCommand(ChatalystPlugin plugin, GiftService gifts) {
        this.plugin = plugin;
        this.gifts = gifts;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "lista" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "dar", "give" -> give(sender, args);
            case "exportar", "export" -> export(sender, args);
            default -> list(sender);
        };
    }

    /** El catalogo, con lo que puede caer de cada caja. */
    private boolean list(CommandSender sender) {
        List<GiftTier> tiers = gifts.catalogue();
        if (tiers.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "gift.none"));
            return true;
        }
        sender.sendMessage(Lang.get(sender, "gift.list-header"));
        for (GiftTier tier : tiers) {
            sender.sendMessage(Lang.get(sender, "gift.list-entry",
                    "id", tier.id(),
                    "box", tier.displayName(plugin.getConfig(), sender),
                    "count", String.valueOf(tier.rewards().size())));
        }
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Lang.get(sender, "gift.usage-give"));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "common.player-offline"));
            return true;
        }
        Optional<GiftTier> tier = gifts.tier(args[2]);
        if (tier.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "gift.unknown"));
            return true;
        }
        int amount = amount(args, 3);
        // Se fabrica en el idioma de QUIEN LA RECIBE, no en el de quien la reparte: el texto
        // viaja dentro del objeto y quien va a leerlo es el otro.
        InventoryUtil.giveOrDrop(target, gifts.create(tier.get(), target, amount));
        sender.sendMessage(Lang.get(sender, "gift.given",
                "amount", String.valueOf(amount),
                "box", tier.get().displayName(plugin.getConfig(), sender),
                "player", target.getName()));
        return true;
    }

    /**
     * Escribe la caja ya serializada por Bukkit, lista para pegar en otro plugin.
     *
     * <p>Se ejecuta desde la consola a proposito: es una tarea de montaje del servidor, no algo
     * que se haga con el objeto en la mano.
     */
    private boolean export(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Lang.get(sender, "gift.usage-export"));
            return true;
        }
        Optional<GiftTier> tier = gifts.tier(args[1]);
        if (tier.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "gift.unknown"));
            return true;
        }
        int amount = amount(args, 2);
        ItemStack item = gifts.create(tier.get(), sender, amount);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        File folder = new File(plugin.getDataFolder(), "exports");
        File file = new File(folder, "gift-" + tier.get().id() + ".yml");
        try {
            Files.createDirectories(folder.toPath());
            Files.writeString(file.toPath(), yaml.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not export gift box: " + failure.getMessage());
            sender.sendMessage(Lang.get(sender, "gift.export-failed"));
            return true;
        }
        sender.sendMessage(Lang.get(sender, "gift.exported", "file", file.getPath()));
        return true;
    }

    private static int amount(String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            return Math.max(1, Math.min(64, Integer.parseInt(args[index].trim())));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("lista", "dar", "exportar")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(option);
            }
            return out;
        }
        if (!sender.hasPermission(ADMIN)) return List.of();
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean giving = sub.equals("dar") || sub.equals("give");
        boolean exporting = sub.equals("exportar") || sub.equals("export");

        if (giving && args.length == 2) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        if ((giving && args.length == 3) || (exporting && args.length == 2)) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (GiftTier tier : gifts.catalogue()) {
                if (tier.id().startsWith(prefix)) out.add(tier.id());
            }
        }
        return out;
    }
}
