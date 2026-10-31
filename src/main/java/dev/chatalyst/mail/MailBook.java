package dev.chatalyst.mail;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Correo como LIBRO de Minecraft: se lee abriendo un libro escrito (openBook) y se escribe con
 * un "libro y pluma" (el jugador redacta y al cerrarlo se envia, lo maneja {@link MailBookListener}).
 * Mucho mas agradable que leer/escribir por el chat.
 */
public final class MailBook {

    // Marcador legacy usado por libros entregados antes de migrar el token al PDC.
    static final String COMPOSE_TAG = "chatalyst-mail-compose";
    private static final NamespacedKey COMPOSE_KEY = new NamespacedKey("chatalyst", "mail_compose");

    private MailBook() {
    }

    /** Abre el correo como un libro escrito, con botones para responder y borrar. */
    public static void read(Player viewer, MailManager.MailEntry entry, int index, MailFolder folder) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        // Apodo actual del remitente si tiene (el "From" del libro sigue al /nick).
        String shown = MailManager.shownFrom(entry);
        book.editMeta(BookMeta.class, meta -> {
            meta.author(Component.text(MM.strip(shown)));
            meta.title(Component.text(Lang.raw(viewer, "mail.book-title")));

            Component page = Component.empty()
                    .append(MM.full(Lang.raw(viewer, "mail.book-header") + "\n"))
                    .append(MM.full(Lang.raw(viewer, "mail.book-from", "from", shown) + "\n"))
                    .append(MM.full("<#7B68A0>" + MailMenu.DATE.format(Instant.ofEpochMilli(entry.time())) + "\n"))
                    .append(MM.full("<#7B68A0>───────────\n\n"))
                    .append(MM.full("<black>" + MM.escape(entry.message())))
                    .append(MM.full("\n\n<#7B68A0>───────────\n"))
                    .append(MM.full(Lang.raw(viewer, "mail.book-reply"))
                            .hoverEvent(HoverEvent.showText(MM.full(
                                    Lang.raw(viewer, "mail.book-reply-hover", "from", shown))))
                            .clickEvent(ClickEvent.runCommand("/chatalyst:inbox replyid "
                                    + (folder == MailFolder.SPAM ? "spam " : "") + entry.id())))
                    // Apilados verticalmente con una linea en blanco entre ellos, no lado a lado.
                    .append(MM.full("\n\n"))
                    .append(MM.full(Lang.raw(viewer, "mail.book-delete"))
                            .hoverEvent(HoverEvent.showText(MM.full(Lang.raw(viewer, "mail.book-delete-hover"))))
                            .clickEvent(ClickEvent.runCommand("/chatalyst:inbox deleteid "
                                    + (folder == MailFolder.SPAM ? "spam " : "") + entry.id())));
            meta.addPages(page);
        });
        viewer.openBook(book);
    }

    /** Empieza a redactar un correo: abre sesion, cierra la GUI y entrega el libro-y-pluma. */
    public static void beginCompose(Player player, MailComposeManager compose,
                                    MailComposeManager.Mode mode, UUID targetId, String targetName) {
        removeStaleComposeBooks(player);
        String token = UUID.randomUUID().toString();
        if (!giveComposeBook(player, targetName, token)) {
            player.sendMessage(Lang.get(player, "mail.compose-no-space"));
            return;
        }
        compose.start(player.getUniqueId(), mode, targetId, targetName, token);
        player.closeInventory();
        player.sendMessage(Lang.get(player, "mail.compose-prompt",
                "player", MM.escape(targetName)));
    }

    /** Entrega un "libro y pluma" para redactar el correo; false si no cabe. */
    static boolean giveComposeBook(Player player, String targetName, String token) {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        book.editMeta(BookMeta.class, meta -> {
            meta.displayName(MM.full(Lang.raw(player, "mail.book-compose-title", "player", MM.escape(targetName)))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    MM.full(Lang.raw(player, "mail.book-compose-lore1"))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                    MM.full(Lang.raw(player, "mail.book-compose-lore2"))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(COMPOSE_KEY, PersistentDataType.STRING, token);
        });
        // A la mano si esta libre (asi solo hay que hacer click derecho); si no, al inventario.
        if (player.getInventory().getItemInMainHand().getType().isAir()) {
            player.getInventory().setItemInMainHand(book);
            return true;
        } else {
            int slot = player.getInventory().firstEmpty();
            if (slot < 0) return false;
            player.getInventory().setItem(slot, book);
            return true;
        }
    }

    /** True si el item es un libro-y-pluma de redaccion de correo (por el marcador del lore). */
    static boolean isComposeBook(ItemStack item) {
        if (item == null || (item.getType() != Material.WRITABLE_BOOK
                && item.getType() != Material.WRITTEN_BOOK) || !item.hasItemMeta()) {
            return false;
        }
        if (item.getItemMeta() instanceof BookMeta meta && isComposeMeta(meta)) return true;
        List<Component> lore = item.getItemMeta().lore();
        if (lore == null) return false;
        for (Component line : lore) {
            if (PlainTextComponentSerializer.plainText().serialize(line).contains(COMPOSE_TAG)) return true;
        }
        return false;
    }

    static boolean isComposeMeta(BookMeta meta) {
        return meta.getPersistentDataContainer().has(COMPOSE_KEY, PersistentDataType.STRING);
    }

    static boolean isComposeMeta(BookMeta meta, String token) {
        String stored = meta.getPersistentDataContainer().get(COMPOSE_KEY, PersistentDataType.STRING);
        return token != null && token.equals(stored);
    }

    private static void removeStaleComposeBooks(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isComposeBook(contents[i])) player.getInventory().setItem(i, null);
        }
    }

    /** Junta las paginas escritas en un solo texto plano (separadas por saltos de linea). */
    static String extractText(BookMeta meta) {
        StringBuilder sb = new StringBuilder();
        for (Component page : meta.pages()) {
            String text = PlainTextComponentSerializer.plainText().serialize(page).trim();
            if (text.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.toString().trim();
    }
}
