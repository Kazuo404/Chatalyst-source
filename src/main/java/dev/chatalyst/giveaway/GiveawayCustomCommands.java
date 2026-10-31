package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Subcomandos de {@code /sorteo} para los premios hechos en el juego (solo admins):
 *
 * <pre>
 *   crear &lt;id&gt; [nombre]          crea el premio y abre el cofre para meter objetos
 *   editar &lt;id&gt;                   vuelve a abrir su cofre
 *   nombre &lt;id&gt; &lt;texto&gt;           nombre visible (MiniMessage)
 *   descripcion &lt;id&gt; &lt;texto|limpiar&gt;  anade una linea o las borra
 *   comando &lt;id&gt; agregar|quitar|lista [comando|n]   comandos por consola ({player}, {uuid})
 *   icono &lt;id&gt;                    el objeto de la mano pasa a ser su icono
 *   ganadores &lt;id&gt; &lt;n&gt;            ganadores por defecto (1-10)
 *   ver &lt;id&gt;  |  personalizados  |  borrar &lt;id&gt; confirmar  |  historial [n]
 * </pre>
 */
final class GiveawayCustomCommands {

    static final List<String> SUBCOMMANDS = List.of("crear", "editar", "nombre", "descripcion",
            "comando", "icono", "ganadores", "ver", "personalizados", "borrar", "historial");

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    private final GiveawayCustomPrizes prizes;

    GiveawayCustomCommands(ChatalystPlugin plugin, GiveawayManager giveaways,
                           GiveawayCustomPrizes prizes) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.prizes = prizes;
    }

    /** @return false si la accion no es de premios personalizados */
    boolean handle(CommandSender sender, String label, String action, String[] args) {
        switch (action) {
            case "crear", "create", "nuevo" -> create(sender, label, args);
            case "editar", "edit" -> edit(sender, label, args);
            case "nombre", "name", "rename" -> name(sender, label, args);
            case "descripcion", "descripción", "description", "desc" -> description(sender, label, args);
            case "comando", "comandos", "command", "commands" -> commands(sender, label, args);
            case "icono", "icon" -> icon(sender, label, args);
            case "ganadores", "winners" -> winners(sender, label, args);
            case "ver", "info", "view" -> view(sender, label, args);
            case "personalizados", "custom", "customs" -> listCustom(sender);
            case "borrar", "delete", "eliminar" -> delete(sender, label, args);
            case "historial", "history", "ganadoresrecientes" -> history(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    List<String> complete(String[] args) {
        if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))
                && !args[0].equalsIgnoreCase("personalizados") && !args[0].equalsIgnoreCase("historial")
                && !args[0].equalsIgnoreCase("crear")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return prizes.all().stream().map(c -> c.id).filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("comando")) {
            return List.of("agregar", "quitar", "lista").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("borrar")) return List.of("confirmar");
        return List.of();
    }

    // ---- subcomandos ---------------------------------------------------------------------

    private void create(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label);
            return;
        }
        String id = GiveawayCustomPrizes.normalize(args[1]);
        if (!GiveawayCustomPrizes.validId(id)) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.invalid-id"));
            return;
        }
        if (giveaways.prize(id).isPresent()) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.exists", "id", id));
            return;
        }
        String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : id;
        async(sender, () -> {
            GiveawayCustomPrizes.Custom custom = prizes.create(id, sender.getName());
            custom.name = name;
            if (!prizes.save(custom)) {
                reply(sender, "giveaway-custom.save-failed");
                return;
            }
            reply(sender, "giveaway-custom.created", "id", id, "command", label);
            if (sender instanceof Player player) {
                FoliaTasks.entity(plugin, player, target -> GiveawayCustomEditor.open(target, custom));
            }
        });
    }

    private void edit(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return;
        }
        withCustom(sender, label, args, custom ->
                FoliaTasks.entity(plugin, player, target -> GiveawayCustomEditor.open(target, custom)));
    }

    private void name(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label);
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        modify(sender, label, args, custom -> custom.name = text, "giveaway-custom.name-set");
    }

    private void description(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label);
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        boolean clear = text.equalsIgnoreCase("limpiar") || text.equalsIgnoreCase("clear");
        modifyChecked(sender, label, args, custom -> {
            if (clear) {
                custom.description.clear();
                return null;
            }
            if (custom.description.size() >= GiveawayCustomPrizes.MAX_DESCRIPTION) {
                return new Refusal("giveaway-custom.limit-reached",
                        GiveawayCustomPrizes.MAX_DESCRIPTION);
            }
            custom.description.add(text);
            return null;
        }, clear ? "giveaway-custom.description-cleared" : "giveaway-custom.description-added");
    }

    private void commands(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label);
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "lista", "list" -> withCustom(sender, label, args, custom -> {
                reply(sender, "giveaway-custom.commands-header", "id", custom.id,
                        "count", custom.commands.size());
                for (int i = 0; i < custom.commands.size(); i++) {
                    reply(sender, "giveaway-custom.commands-entry", "n", i + 1,
                            "command", dev.chatalyst.util.MM.escape(custom.commands.get(i)));
                }
            });
            case "agregar", "add", "anadir", "añadir" -> {
                if (args.length < 4) {
                    usage(sender, label);
                    return;
                }
                String command = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                if (command.startsWith("/")) command = command.substring(1);
                String finalCommand = command;
                modifyChecked(sender, label, args, custom -> {
                    if (custom.commands.size() >= GiveawayCustomPrizes.MAX_COMMANDS) {
                        return new Refusal("giveaway-custom.limit-reached",
                                GiveawayCustomPrizes.MAX_COMMANDS);
                    }
                    custom.commands.add(finalCommand);
                    return null;
                }, "giveaway-custom.command-added");
            }
            case "quitar", "remove", "borrar" -> {
                int index;
                try {
                    index = Integer.parseInt(args.length > 3 ? args[3] : "") - 1;
                } catch (NumberFormatException notANumber) {
                    usage(sender, label);
                    return;
                }
                modifyChecked(sender, label, args, custom -> {
                    if (index < 0 || index >= custom.commands.size()) {
                        return new Refusal("giveaway-custom.command-bad-index",
                                custom.commands.size());
                    }
                    custom.commands.remove(index);
                    return null;
                }, "giveaway-custom.command-removed");
            }
            default -> usage(sender, label);
        }
    }

    private void icon(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.icon-empty-hand"));
            return;
        }
        ItemStack one = hand.clone();
        one.setAmount(1);
        String encoded = GiveawayCustomPrizes.encode(one);
        modify(sender, label, args, custom -> custom.icon = encoded, "giveaway-custom.icon-set");
    }

    private void winners(CommandSender sender, String label, String[] args) {
        int count;
        try {
            count = Integer.parseInt(args.length > 2 ? args[2] : "");
        } catch (NumberFormatException notANumber) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.winners-invalid",
                    "max", GiveawayCustomPrizes.MAX_WINNERS));
            return;
        }
        if (count < 1 || count > GiveawayCustomPrizes.MAX_WINNERS) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.winners-invalid",
                    "max", GiveawayCustomPrizes.MAX_WINNERS));
            return;
        }
        modify(sender, label, args, custom -> custom.winners = count, "giveaway-custom.winners-set");
    }

    private void view(CommandSender sender, String label, String[] args) {
        withCustom(sender, label, args, custom -> {
            reply(sender, "giveaway-custom.view-header", "id", custom.id, "name", custom.name);
            GiveawayPrize prize = GiveawayPrize.custom(custom);
            for (String line : prize.description(plugin.getConfig(), sender)) {
                sender.sendMessage(dev.chatalyst.util.MM.full("<#7B68A0>  " + line));
            }
            reply(sender, "giveaway-custom.view-footer", "id", custom.id, "command", label,
                    "by", custom.createdBy == null ? "?" : custom.createdBy);
        });
    }

    private void listCustom(CommandSender sender) {
        async(sender, () -> {
            List<GiveawayCustomPrizes.Custom> all = prizes.all();
            if (all.isEmpty()) {
                reply(sender, "giveaway-custom.list-empty");
                return;
            }
            reply(sender, "giveaway-custom.list-header", "count", all.size());
            for (GiveawayCustomPrizes.Custom custom : all) {
                reply(sender, "giveaway-custom.list-entry", "id", custom.id, "name", custom.name,
                        "items", custom.items.size(), "commands", custom.commands.size(),
                        "winners", custom.winners);
            }
        });
    }

    private void delete(CommandSender sender, String label, String[] args) {
        if (args.length < 3 || !(args[2].equalsIgnoreCase("confirmar")
                || args[2].equalsIgnoreCase("confirm"))) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.delete-confirm",
                    "id", args.length > 1 ? dev.chatalyst.util.MM.escape(args[1]) : "<id>",
                    "command", label));
            return;
        }
        GiveawayPrize running = giveaways.runningPrize();
        if (running != null && running.id().equals(GiveawayCustomPrizes.normalize(args[1]))) {
            sender.sendMessage(Lang.get(sender, "giveaway-custom.delete-running"));
            return;
        }
        withCustom(sender, label, args, custom -> {
            if (prizes.delete(custom.id)) {
                reply(sender, "giveaway-custom.deleted", "id", custom.id);
            } else {
                reply(sender, "giveaway-custom.save-failed");
            }
        });
    }

    private void history(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // se queda en 10
            }
        }
        int finalLimit = limit;
        async(sender, () -> {
            List<GiveawayManager.Win> wins = giveaways.recentWins(finalLimit);
            if (wins.isEmpty()) {
                reply(sender, "giveaway-custom.history-empty");
                return;
            }
            SimpleDateFormat date = new SimpleDateFormat("dd/MM HH:mm");
            reply(sender, "giveaway-custom.history-header", "count", wins.size());
            // El catalogo se monta UNA vez: pedir el premio de cada victoria lo rehacia entero
            // (config, grupos de LuckPerms, premios personalizados) hasta 50 veces seguidas.
            List<GiveawayPrize> catalogue = giveaways.catalogue();
            for (GiveawayManager.Win win : wins) {
                String prizeName = GiveawayPrize.resolveIn(catalogue, win.prizeId())
                        .map(p -> p.displayName(plugin.getConfig(), sender))
                        .orElse(dev.chatalyst.util.MM.escape(win.prizeId()));
                reply(sender, "giveaway-custom.history-entry", "date", date.format(new Date(win.time())),
                        "player", dev.chatalyst.util.MM.escape(win.name()), "prize", prizeName);
            }
        });
    }

    // ---- ayuda -----------------------------------------------------------------------------

    /** Busca el premio (fuera del hilo principal) y ejecuta la accion; avisa si no existe. */
    private void withCustom(CommandSender sender, String label, String[] args,
                            Consumer<GiveawayCustomPrizes.Custom> action) {
        if (args.length < 2) {
            usage(sender, label);
            return;
        }
        String id = args[1];
        async(sender, () -> {
            GiveawayCustomPrizes.Custom custom = prizes.find(id).orElse(null);
            if (custom == null) {
                unknown(sender, label, id);
                return;
            }
            action.accept(custom);
        });
    }

    /** El id lo escribio quien ejecuta el comando: se escapa antes de ir a un mensaje. */
    private void unknown(CommandSender sender, String label, String id) {
        reply(sender, "giveaway-custom.unknown",
                "id", dev.chatalyst.util.MM.escape(String.valueOf(GiveawayCustomPrizes.normalize(id))),
                "command", label);
    }

    /** Por que no se aplico un cambio: la clave del aviso y el numero que lo explica. */
    private record Refusal(String key, int max) {
    }

    /** Cambia algo del premio, lo guarda y confirma con la clave dada. */
    private void modify(CommandSender sender, String label, String[] args,
                        Consumer<GiveawayCustomPrizes.Custom> change, String doneKey) {
        modifyChecked(sender, label, args, custom -> {
            change.accept(custom);
            return null;
        }, doneKey);
    }

    /**
     * Igual, pero el cambio puede negarse (lista llena, numero que no existe) y entonces se
     * dice POR QUE en vez de confirmar algo que no paso. Leer, cambiar y guardar va en un solo
     * paso de {@link GiveawayCustomPrizes#update}: dos comandos seguidos ya no se pisan.
     */
    private void modifyChecked(CommandSender sender, String label, String[] args,
                               java.util.function.Function<GiveawayCustomPrizes.Custom, Refusal> change,
                               String doneKey) {
        if (args.length < 2) {
            usage(sender, label);
            return;
        }
        String id = args[1];
        async(sender, () -> {
            Refusal[] refusal = new Refusal[1];
            GiveawayCustomPrizes.Update result = prizes.update(id, custom -> {
                refusal[0] = change.apply(custom);
                return refusal[0] == null;
            });
            String shown = String.valueOf(GiveawayCustomPrizes.normalize(id));
            switch (result) {
                case MISSING -> unknown(sender, label, id);
                case SKIPPED -> reply(sender, refusal[0].key(), "id", shown,
                        "max", refusal[0].max());
                case SAVED -> reply(sender, doneKey, "id", shown);
                case FAILED -> reply(sender, "giveaway-custom.save-failed", "id", shown);
            }
        });
    }

    private void async(CommandSender sender, Runnable action) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                action.run();
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Giveaway custom command failed: " + failure);
                reply(sender, "giveaway-custom.save-failed");
            }
        });
    }

    private void reply(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(Lang.get(sender, key, replacements));
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Lang.get(sender, "giveaway-custom.usage", "command", label));
    }

    /** Para la lista de ayuda: los ids que ya existen. */
    List<String> ids() {
        List<String> out = new ArrayList<>();
        for (GiveawayCustomPrizes.Custom c : prizes.all()) out.add(c.id);
        return out;
    }
}
