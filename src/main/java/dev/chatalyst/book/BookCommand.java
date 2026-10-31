package dev.chatalyst.book;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opens player books and provides a permission-gated in-game authoring workflow. */
public final class BookCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "chatalyst.book.admin";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    /** Lo que cabe en una pagina de un libro que aun se puede editar. */
    static final int EDITABLE_PAGE_LENGTH = 1024;

    private final ChatalystPlugin plugin;
    private final BookManager books;

    public BookCommand(ChatalystPlugin plugin, BookManager books) {
        this.plugin = plugin;
        this.books = books;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) return usage(sender);
        String operation = args[0].toLowerCase(Locale.ROOT);
        if (operation.equals("list")) return list(sender);
        if (operation.equals("new")) return create(sender);
        if (operation.equals("edit")) return edit(sender, args);
        if (operation.equals("save")) return save(sender, args);
        if (operation.equals("delete")) return delete(sender, args);
        if (operation.equals("open")) return open(sender, args);
        return openForSelf(sender, args[0]);
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "book.usage", "books",
                String.join(", ", visibleNames(sender))));
        return true;
    }

    private boolean list(CommandSender sender) {
        sender.sendMessage(Lang.get(sender, "book.list", "books",
                String.join(", ", visibleNames(sender))));
        return true;
    }

    private boolean create(CommandSender sender) {
        Player player = adminPlayer(sender);
        if (player == null) return true;
        giveDraft(player, new ItemStack(Material.WRITABLE_BOOK));
        player.sendMessage(Lang.get(player, "book.draft-created"));
        return true;
    }

    private boolean edit(CommandSender sender, String[] args) {
        Player player = adminPlayer(sender);
        if (player == null) return true;
        if (args.length < 2) return usage(player);
        BookManager.BookDef definition = books.get(args[1]);
        if (definition == null) {
            player.sendMessage(Lang.get(player, "book.unknown", "book", args[1]));
            return true;
        }
        // El borrador lleva el TEXTO FUENTE de cada pagina, etiquetas incluidas. Antes se
        // metia la pagina ya pintada, y un libro editable solo guarda texto con los 16
        // colores clasicos: los degradados se aplastaban, los enlaces y hovers desaparecian,
        // y al guardar el libro del servidor quedaba peor de lo que estaba sin que nadie
        // hubiera cambiado una letra.
        if (definition.pages().stream().anyMatch(p -> p.length() > EDITABLE_PAGE_LENGTH)) {
            // Una pagina que no cabe se recortaria al entregar el libro y se guardaria
            // recortada. Mejor no dar el borrador que perder el final sin avisar.
            player.sendMessage(Lang.get(player, "book.edit-too-long", "book", args[1]));
            return true;
        }
        ItemStack draft = new ItemStack(Material.WRITABLE_BOOK);
        draft.editMeta(BookMeta.class, meta -> {
            meta.displayName(Lang.get(player, "book.edit-name", "book", args[1]));
            meta.pages(definition.pages().stream()
                    .<net.kyori.adventure.text.Component>map(
                            net.kyori.adventure.text.Component::text).toList());
        });
        giveDraft(player, draft);
        player.sendMessage(Lang.get(player, "book.edit-created", "book", args[1]));
        return true;
    }

    private static void giveDraft(Player player, ItemStack draft) {
        player.getInventory().addItem(draft).values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private boolean save(CommandSender sender, String[] args) {
        Player player = adminPlayer(sender);
        if (player == null) return true;
        if (args.length < 2 || !BookManager.validName(args[1])) {
            player.sendMessage(Lang.get(player, "book.invalid-name"));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!(held.getItemMeta() instanceof BookMeta meta) || meta.pages().isEmpty()) {
            player.sendMessage(Lang.get(player, "book.hold-book"));
            return true;
        }
        BookManager.BookDef existing = books.get(args[1]);
        String permission = args.length >= 3
                ? (args[2].equals("-") ? "" : args[2])
                : (existing != null ? existing.permission() : "");
        String title = meta.title() != null ? PLAIN.serialize(meta.title())
                : (existing != null ? existing.title() : args[1]);
        String author = meta.author() != null ? PLAIN.serialize(meta.author())
                : (existing != null ? existing.author() : player.getName());
        List<String> pages = meta.pages().stream().map(BookCommand::source).toList();
        books.save(args[1], new BookManager.BookDef(title, author, permission, pages))
                .whenComplete((saved, failure) -> FoliaTasks.entity(plugin, player,
                        target -> target.sendMessage(Lang.get(target,
                                Boolean.TRUE.equals(saved) && failure == null
                                        ? "book.saved" : "book.save-error", "book", args[1]))));
        return true;
    }

    /**
     * El texto fuente de una pagina tal como se va a guardar.
     *
     * <p>Lo que se escribe a mano en un libro es texto sin formato, y ese texto ES la fuente:
     * quien teclea {@code <red>Hola} quiere una pagina roja, no las etiquetas a la vista (que
     * es lo que salia al escaparlas, y dejaba el editor del juego sin forma de poner un solo
     * color). Una pagina que ya trae formato de verdad — un libro firmado por otro plugin —
     * se serializa para conservarlo.
     */
    static String source(net.kyori.adventure.text.Component page) {
        String typed = PLAIN.serialize(page);
        String serialized = MM.serialize(page);
        return serialized.equals(MM.escape(typed)) ? typed : serialized;
    }

    private boolean delete(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return true;
        if (args.length < 2) return usage(sender);
        books.delete(args[1]).whenComplete((deleted, failure) -> sendResult(sender,
                Boolean.TRUE.equals(deleted) && failure == null ? "book.deleted" : "book.unknown",
                args[1]));
        return true;
    }

    private boolean open(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return true;
        if (args.length < 2) return usage(sender);
        if (args.length < 3) return openForSelf(sender, args[1]);
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", args[2]));
            return true;
        }
        if (!books.open(target, args[1], false)) {
            sender.sendMessage(Lang.get(sender, "book.unknown", "book", args[1]));
            return true;
        }
        sender.sendMessage(Lang.get(sender, "book.opened", "book", args[1],
                "player", target.getName()));
        return true;
    }

    private boolean openForSelf(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!books.open(player, name, !player.hasPermission(ADMIN_PERMISSION))) {
            player.sendMessage(Lang.get(player, books.get(name) == null
                    ? "book.unknown" : "common.no-permission", "book", name));
        }
        return true;
    }

    private Player adminPlayer(CommandSender sender) {
        if (!requireAdmin(sender)) return null;
        if (sender instanceof Player player) return player;
        sender.sendMessage(Lang.get(sender, "common.players-only"));
        return null;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) return true;
        sender.sendMessage(Lang.get(sender, "common.no-permission"));
        return false;
    }

    private void sendResult(CommandSender sender, String key, String book) {
        if (sender instanceof Player player) {
            FoliaTasks.entity(plugin, player,
                    target -> target.sendMessage(Lang.get(target, key, "book", book)));
        } else {
            FoliaTasks.global(plugin,
                    () -> sender.sendMessage(Lang.get(sender, key, "book", book)));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission(ADMIN_PERMISSION)) {
            List<String> options = new ArrayList<>(List.of(
                    "list", "new", "edit", "save", "delete", "open"));
            options.addAll(books.names());
            return complete(options, args[0]);
        }
        if (args.length == 1) return complete(visibleNames(sender), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("open")
                || args[0].equalsIgnoreCase("edit")
                || args[0].equalsIgnoreCase("delete"))) {
            if (!args[0].equalsIgnoreCase("open") && !sender.hasPermission(ADMIN_PERMISSION)) {
                return List.of();
            }
            return complete(sender.hasPermission(ADMIN_PERMISSION)
                    ? books.names() : visibleNames(sender), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("open")
                && sender.hasPermission(ADMIN_PERMISSION)) {
            return complete(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        return List.of();
    }

    private List<String> visibleNames(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) return books.names();
        return books.names().stream().filter(name -> {
            BookManager.BookDef definition = books.get(name);
            return definition != null && (definition.permission().isEmpty()
                    || sender.hasPermission(definition.permission()));
        }).toList();
    }

    private static List<String> complete(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> output = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) output.add(value);
        return output;
    }
}
