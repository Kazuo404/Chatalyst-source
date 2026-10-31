package dev.chatalyst.mail;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Online recipient picker for composing mail from the GUI. */
public final class MailRecipientMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREV_SLOT = 45;
    private static final int BACK_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final MailManager mail;
    private final MailComposeManager compose;
    private final int page;
    private final Map<Integer, OfflinePlayer> slots = new HashMap<>();
    /** Quien se pinta como conectado: los conectados que este espectador puede ver. */
    private final java.util.Set<UUID> shownOnline = new java.util.HashSet<>();

    public MailRecipientMenu(JavaPlugin plugin, MailManager mail, MailComposeManager compose, int page) {
        this.plugin = plugin;
        this.mail = mail;
        this.compose = compose;
        this.page = Math.max(0, page);
    }

    @Override
    public net.kyori.adventure.text.Component title() {
        return MM.full(Lang.raw("mail.gui-recipients-title"));
    }

    @Override
    public net.kyori.adventure.text.Component title(Player viewer) {
        return Lang.get(viewer, "mail.gui-recipients-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<OfflinePlayer> players = visiblePlayers(viewer);
        int pages = pages(players.size());
        int safePage = Math.min(page, pages - 1);
        int start = safePage * PAGE_SIZE;
        int shown = Math.max(0, Math.min(players.size() - start, PAGE_SIZE));
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(shown, 0);
        for (int n = 0; n < shown; n++) {
            int slot = layout[n];
            OfflinePlayer target = players.get(start + n);
            boolean anonymous = dev.chatalyst.hook.Placeholders.isAnonymous(target.getUniqueId());
            inv.setItem(slot, playerItem(viewer, target, anonymous));
            // Igual que en amistades: la skin es la cuenta REAL. Con ella, el alias de un
            // anonimo salia en el selector con la cara de quien es de verdad.
            if (!anonymous) {
                dev.chatalyst.gui.PlayerHeads.completeLater(viewer, inv, slot, target.getUniqueId());
            }
            slots.put(slot, target);
        }
        if (players.isEmpty()) {
            inv.setItem(22, MailMenu.basic(Material.BARRIER, Lang.raw(viewer, "mail.gui-no-recipients"),
                    List.of(Lang.raw(viewer, "mail.gui-no-players"))));
        }
        inv.setItem(PREV_SLOT, MenuNavigation.previousOrFirst(viewer, safePage + 1, pages));
        inv.setItem(BACK_SLOT, MenuNavigation.back(viewer));
        inv.setItem(NEXT_SLOT, MenuNavigation.nextOrLast(viewer, safePage + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        List<OfflinePlayer> players = visiblePlayers(player);
        int pages = pages(players.size());
        int safePage = Math.min(page, pages - 1);
        if (slot == PREV_SLOT) {
            Menu.open(player, new MailRecipientMenu(plugin, mail, compose, Math.max(0, safePage - 1)));
            return;
        }
        if (slot == NEXT_SLOT) {
            Menu.open(player, new MailRecipientMenu(plugin, mail, compose, Math.min(pages - 1, safePage + 1)));
            return;
        }
        if (slot == BACK_SLOT) {
            Menu.open(player, new MailMenu(plugin, mail, compose, 0));
            return;
        }
        OfflinePlayer target = slots.get(slot);
        if (target == null || target.getName() == null) {
            return;
        }
        if (mail.isReceivingDisabled(target.getUniqueId())) {
            String shown = MM.strip(dev.chatalyst.nick.NickManager.shownName(
                    target.getUniqueId().toString(), target.getName()));
            player.sendMessage(Lang.get(player, "mail.cannot-send", "player", MM.escape(shown)));
            Menu.open(player, new MailRecipientMenu(plugin, mail, compose, safePage));
            return;
        }
        // Sin amistad forzada: al elegir a alguien se abre el libro para escribir el correo.
        String shown = MM.strip(dev.chatalyst.nick.NickManager.shownName(
                target.getUniqueId().toString(), target.getName()));
        MailBook.beginCompose(player, compose, MailComposeManager.Mode.SEND,
                target.getUniqueId(), shown);
    }

    private List<OfflinePlayer> visiblePlayers(Player viewer) {
        Map<UUID, OfflinePlayer> map = new LinkedHashMap<>();
        shownOnline.clear();
        // Conectados (visibles salvo vanish sin permiso).
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(viewer)) continue;
            if (dev.chatalyst.util.Vanish.isVanished(online)
                    && !dev.chatalyst.staff.StaffAccess.canSeeVanished(viewer)) continue;
            map.put(online.getUniqueId(), online);
            shownOnline.add(online.getUniqueId());
        }
        // Y todos los que ya han jugado aqui, para poder escribirles aunque esten offline.
        // Un vanish oculto entra tambien por aqui, pero como DESCONECTADO: antes su
        // OfflinePlayer decia isOnline() y salia arriba, sin "(offline)", delatandolo.
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || op.getUniqueId().equals(viewer.getUniqueId())) continue;
            map.putIfAbsent(op.getUniqueId(), op);
        }
        List<OfflinePlayer> players = new ArrayList<>(map.values());
        // Conectados primero, luego alfabetico por el nombre que SE VE. Ordenar por el
        // nombre de cuenta colocaba el alias de un anonimo justo donde iria su nombre real.
        Map<UUID, String> shown = new HashMap<>();
        for (OfflinePlayer op : players) {
            shown.put(op.getUniqueId(), MM.strip(dev.chatalyst.nick.NickManager.shownName(
                    op.getUniqueId().toString(), op.getName())));
        }
        players.sort(Comparator.<OfflinePlayer, Boolean>comparing(op -> !shownOnline.contains(op.getUniqueId()))
                .thenComparing(op -> shown.get(op.getUniqueId()), String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    private ItemStack playerItem(Player viewer, OfflinePlayer target, boolean anonymous) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(meta -> {
            boolean online = shownOnline.contains(target.getUniqueId());
            boolean disabled = mail.isReceivingDisabled(target.getUniqueId());
            boolean friend = mail.areFriends(viewer.getUniqueId(), target.getUniqueId(), target.getName());
            // Apodo actual si tiene (con su formato); si no, el nombre en el color de estado.
            String shown = MailManager.shownSender(
                    target.getUniqueId().toString(), target.getName());
            meta.displayName(MM.full((disabled ? "<#FF4D6D>" : friend ? "<#3DFFA8>" : "<#B56CFF>")
                    + shown + (online ? "" : Lang.raw(viewer, "mail.gui-offline"))));
            meta.lore(List.of(
                    MM.full(disabled ? Lang.raw(viewer, "mail.gui-not-receiving")
                            : friend ? Lang.raw(viewer, "mail.gui-write-friend")
                            : Lang.raw(viewer, "mail.gui-send-request"))
            ));
            if (!anonymous && meta instanceof SkullMeta skull) {
                dev.chatalyst.gui.PlayerHeads.apply(skull, target.getUniqueId());
            }
        });
        return item;
    }

    private int pages(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

}
