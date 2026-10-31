package dev.chatalyst.command;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.FoliaTasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/**
 * Menu jugador-a-jugador: abierto al clicar una cabeza en {@link PlayerListMenu}. Reune lo que
 * un jugador puede hacer con otro — mensaje privado, ignorar/dejar de ignorar, correo y trade —
 * y, si quien mira es staff, un boton para abrir el menu de moderacion.
 */
public final class PlayerInteractMenu implements Menu {

    private static final String IGNORE_PERMISSION = "chatalyst.command.ignore";

    private static final int HEAD = 4;
    private static final int MESSAGE = 19, IGNORE = 21, MAIL = 23, TRADE = 25;
    private static final int REPORT = 30, MODERATE = 32;
    private static final int BACK = 40;

    private final ChatalystPlugin plugin;
    private final PlayerUiSnapshot target;
    private final int listPage;

    private PlayerInteractMenu(ChatalystPlugin plugin, PlayerUiSnapshot target, int listPage) {
        this.plugin = plugin;
        this.target = target;
        this.listPage = listPage;
    }

    public static void open(ChatalystPlugin plugin, Player viewer, UUID targetId, int listPage) {
        Player online = Bukkit.getPlayer(targetId);
        Runnable missing = () -> FoliaTasks.entity(plugin, viewer, current -> {
            current.sendMessage(Lang.get(current, "common.player-offline"));
            PlayerListMenu.open(plugin, current, listPage);
        });
        if (online == null) {
            missing.run();
            return;
        }
        FoliaTasks.entity(plugin, online, selected -> {
            PlayerUiSnapshot snapshot = PlayerUiSnapshot.capture(selected);
            FoliaTasks.entity(plugin, viewer, current -> {
                if (snapshot.vanished() && !StaffAccess.canSeeVanished(current)) {
                    current.sendMessage(Lang.get(current, "common.player-offline"));
                    PlayerListMenu.open(plugin, current, listPage);
                    return;
                }
                Menu.open(current, new PlayerInteractMenu(plugin, snapshot, listPage));
            });
        }, missing);
    }

    @Override
    public Component title() {
        return Lang.get("players.interact.title", "player", target.shownName());
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "players.interact.title", "player", target.shownName());
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        inv.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
        inv.setItem(HEAD, head(viewer, target));

        boolean self = viewer.getUniqueId().equals(target.id());
        if (self) {
            return; // sobre uno mismo no hay acciones; solo su ficha y volver
        }

        boolean canReach = self || viewer.isOp() || !target.vanished()
                || viewer.hasPermission(PrivateMessages.REACH_PERMISSION);
        if (PrivateMessages.systemEnabled(plugin) && canReach) {
            inv.setItem(MESSAGE, MenuItems.item(Material.PAPER,
                    Lang.raw(viewer, "players.interact.message"),
                    List.of("<#C0B3D9>" + Lang.raw(viewer, "players.interact.message-lore"))));
        }
        boolean ignoring = plugin.getPrivateMessages() != null
                && plugin.getPrivateMessages().isIgnoring(viewer.getUniqueId(), target.id());
        if (viewer.hasPermission(IGNORE_PERMISSION)) inv.setItem(IGNORE, MenuItems.item(ignoring ? Material.LIME_DYE : Material.GRAY_DYE,
                Lang.raw(viewer, ignoring ? "players.interact.unignore" : "players.interact.ignore"),
                List.of("<#C0B3D9>" + Lang.raw(viewer, ignoring
                        ? "players.interact.unignore-lore" : "players.interact.ignore-lore"))));
        if (plugin.getConfig().getBoolean("Mail.Enabled", true)) {
            inv.setItem(MAIL, MenuItems.item(Material.OAK_SIGN,
                    Lang.raw(viewer, "players.interact.mail"),
                    List.of("<#C0B3D9>" + Lang.raw(viewer, "players.interact.mail-lore"))));
        }
        if (plugin.getConfig().getBoolean("Trade.Enabled", false)) {
            inv.setItem(TRADE, MenuItems.item(Material.EMERALD,
                    Lang.raw(viewer, "players.interact.trade"),
                    List.of("<#C0B3D9>" + Lang.raw(viewer, "players.interact.trade-lore"))));
        }
        if (plugin.getConfig().getBoolean("Reports.Enabled", true)) {
            inv.setItem(REPORT, MenuItems.item(Material.RED_BANNER,
                    Lang.raw(viewer, "players.interact.report"),
                    List.of("<#C0B3D9>" + Lang.raw(viewer, "players.interact.report-lore"))));
        }
        if (PlayerListMenu.canManage(viewer)) {
            inv.setItem(MODERATE, MenuItems.item(Material.SHIELD,
                    Lang.raw(viewer, "players.interact.moderate"),
                    List.of("<#C0B3D9>" + Lang.raw(viewer, "players.interact.moderate-lore"))));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            PlayerListMenu.open(plugin, player, listPage);
            return;
        }
        if (Bukkit.getPlayer(target.id()) == null) {
            player.sendMessage(Lang.get(player, "common.player-offline"));
            return;
        }
        if (player.getUniqueId().equals(target.id())) return; // nada sobre uno mismo
        // Los comandos se rellenan con el nombre MOSTRADO, no el real: este menu lo abre
        // cualquier jugador, y sugerir "/msg <nombre real>" desenmascaraba a quien tiene
        // /nick o /anonymous. Los cuatro comandos resuelven apodos (NickManager.online).
        switch (slot) {
            case MESSAGE -> suggest(player, "players.interact.message-click",
                    "/msg " + target.shownName() + " ");
            case IGNORE -> {
                // El mismo permiso que /ignore: el boton era una puerta trasera para quien lo
                // tenia denegado.
                if (plugin.getPrivateMessages() == null
                        || !player.hasPermission(IGNORE_PERMISSION)) {
                    return;
                }
                boolean nowIgnoring = plugin.getPrivateMessages().toggleIgnore(player, target.id());
                player.sendMessage(Lang.get(player, nowIgnoring
                        ? "players.interact.ignored" : "players.interact.unignored",
                        "player", target.shownName()));
                Menu.open(player, this);
            }
            case MAIL -> {
                if (!plugin.getConfig().getBoolean("Mail.Enabled", true)) return;
                suggest(player, "players.interact.mail-click",
                        "/inbox send " + target.shownName() + " ");
            }
            case TRADE -> {
                if (!plugin.getConfig().getBoolean("Trade.Enabled", false)) return;
                player.closeInventory();
                player.performCommand("trade " + target.shownName());
            }
            case REPORT -> {
                if (!plugin.getConfig().getBoolean("Reports.Enabled", true)) return;
                suggest(player, "players.interact.report-click",
                        "/report " + target.shownName() + " ");
            }
            case MODERATE -> {
                if (PlayerListMenu.canManage(player)) {
                    PlayerManageMenu.open(plugin, player, target.id(), listPage);
                }
            }
            default -> {
            }
        }
    }

    /** Cierra el menu y ofrece un comando clicable que rellena el chat (msg / mail). */
    private void suggest(Player player, String key, String command) {
        player.closeInventory();
        player.sendMessage(Lang.get(player, key, "player", target.shownName())
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Lang.get(player, "players.interact.click-hint"))));
    }

    private ItemStack head(Player viewer, PlayerUiSnapshot target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, target.id());
            meta.displayName(dev.chatalyst.util.MM.full("<reset>" + target.rankNick())
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Lang.get(viewer, "players.menu.rank",
                            "rank", target.prefix())
                    .decoration(TextDecoration.ITALIC, false)));
        });
        return item;
    }
}
