package dev.chatalyst.command;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Lista paginada de jugadores conectados, filtrada por vanish para quien mira. */
public final class PlayerListMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    // Centro de la barra: volver al panel de staff (moderacion) o cerrar (lista social).
    private static final int HOME = 49;
    private static final int NEXT = 53;

    private final ChatalystPlugin plugin;
    private final List<PlayerUiSnapshot> snapshots;
    private final List<PlayerUiSnapshot> shown = new ArrayList<>();
    private int[] shownSlots = new int[0];
    private final boolean moderation;
    private int page;
    private boolean hasPrevious;
    private boolean hasNext;

    private PlayerListMenu(ChatalystPlugin plugin, int page,
                           List<PlayerUiSnapshot> snapshots, boolean moderation) {
        this.plugin = plugin;
        this.page = Math.max(0, page);
        this.snapshots = List.copyOf(snapshots);
        this.moderation = moderation;
    }

    public static void open(ChatalystPlugin plugin, Player viewer) {
        open(plugin, viewer, 0);
    }

    public static void open(ChatalystPlugin plugin, Player viewer, int page) {
        open(plugin, viewer, page, false);
    }

    /** Lista del dashboard de staff: cualquier clic sobre una cabeza abre Moderar directo. */
    public static void openStaff(ChatalystPlugin plugin, Player viewer, int page) {
        open(plugin, viewer, page, true);
    }

    /** Captures every target on its own region before opening the menu for the viewer. */
    public static void open(ChatalystPlugin plugin, Player viewer, int page,
                            boolean moderation) {
        FoliaTasks.global(plugin, () -> {
            List<Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
            if (online.isEmpty()) {
                finishOpen(plugin, viewer, page, List.of(), moderation);
                return;
            }
            List<PlayerUiSnapshot> captured = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger remaining = new AtomicInteger(online.size());
            Runnable completeOne = () -> {
                if (remaining.decrementAndGet() != 0) return;
                List<PlayerUiSnapshot> frozen;
                synchronized (captured) {
                    frozen = List.copyOf(captured);
                }
                finishOpen(plugin, viewer, page, frozen, moderation);
            };
            for (Player target : online) {
                FoliaTasks.entity(plugin, target, current -> {
                    captured.add(PlayerUiSnapshot.capture(current));
                    completeOne.run();
                }, completeOne);
            }
        });
    }

    private static void finishOpen(ChatalystPlugin plugin, Player viewer, int page,
                                   List<PlayerUiSnapshot> snapshots, boolean moderation) {
        FoliaTasks.entity(plugin, viewer, current ->
                Menu.open(current, new PlayerListMenu(plugin, page, snapshots, moderation)));
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("players.menu.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "players.menu.title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        shown.clear();
        shownSlots = new int[0];

        List<PlayerUiSnapshot> visible = visiblePlayers(viewer);
        if (visible.isEmpty()) {
            hasPrevious = false;
            hasNext = false;
            // PAPER y no cristal: icono neutro que Bedrock (Geyser) muestra bien.
            inv.setItem(22, icon(Material.PAPER,
                    Lang.get(viewer, "players.menu.none"), List.of()));
            // Aun con la lista vacia hay que poder volver al panel de staff.
            inv.setItem(HOME, moderation ? MenuNavigation.home(viewer) : MenuNavigation.close(viewer));
            return;
        }

        int maxPage = (visible.size() - 1) / PAGE_SIZE;
        page = Math.min(page, maxPage);
        hasPrevious = page > 0;
        hasNext = page < maxPage;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, visible.size());
        shownSlots = dev.chatalyst.gui.MenuLayout.slots(end - start, 0);
        for (int i = start; i < end; i++) {
            PlayerUiSnapshot target = visible.get(i);
            shown.add(target);
            inv.setItem(shownSlots[i - start], head(target, viewer));
        }

        int pages = maxPage + 1;
        // Barra simetrica: flecha - centro - flecha. Desde el panel de staff (/staff) el centro
        // vuelve a el; en la lista social (/players) no hay panel padre y el centro cierra.
        inv.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inv.setItem(HOME, moderation ? MenuNavigation.home(viewer) : MenuNavigation.close(viewer));
        inv.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == PREVIOUS && hasPrevious) {
            open(plugin, player, page - 1, moderation);
            return;
        }
        if (slot == NEXT && hasNext) {
            open(plugin, player, page + 1, moderation);
            return;
        }
        if (slot == HOME) {
            if (moderation) dev.chatalyst.staff.StaffDashboardMenu.open(plugin.getStaffModeration(), player);
            else player.closeInventory();
            return;
        }
        int local = -1;
        for (int i = 0; i < shownSlots.length; i++) if (shownSlots[i] == slot) local = i;
        if (local < 0 || local >= shown.size()) return;

        PlayerUiSnapshot selected = shown.get(local);
        if (plugin.getServer().getPlayer(selected.id()) == null) {
            player.sendMessage(Lang.get(player, "common.player-offline"));
            open(plugin, player, page, moderation);
            return;
        }
        // Lista del dashboard: aqui SOLO se modera, cualquier clic abre el menu de manejo.
        if (moderation && canManage(player)) {
            PlayerManageMenu.open(plugin, player, selected.id(), page);
            return;
        }
        // Click derecho de staff: atajo directo al menu de manejo (mute, warnings, chatlog...).
        if (click.isRightClick() && canManage(player)) {
            PlayerManageMenu.open(plugin, player, selected.id(), page);
            return;
        }
        // Cualquier otro click: menu de interaccion jugador-a-jugador (mensaje, ignorar, correo,
        // trade) — y para staff, tambien un boton de moderacion dentro.
        PlayerInteractMenu.open(plugin, player, selected.id(), page);
    }

    /** Puede abrir el menu de manejo: cualquier permiso de moderacion aplicable. */
    static boolean canManage(Player viewer) {
        return StaffAccess.has(viewer, "chatalyst.command.staff")
                || StaffAccess.has(viewer, "chatalyst.command.kick")
                || StaffAccess.has(viewer, "chatalyst.command.ban")
                || StaffAccess.has(viewer, "chatalyst.command.freeze")
                || StaffAccess.has(viewer, "chatalyst.command.mute")
                || StaffAccess.has(viewer, "chatalyst.command.warnings")
                || StaffAccess.has(viewer, "chatalyst.command.log");
    }

    private List<PlayerUiSnapshot> visiblePlayers(Player viewer) {
        List<PlayerUiSnapshot> visible = new ArrayList<>();
        for (PlayerUiSnapshot online : snapshots) {
            if (online.vanished() && !StaffAccess.canSeeVanished(viewer)) continue;
            visible.add(online);
        }
        visible.sort(Comparator.comparing(PlayerUiSnapshot::name,
                String.CASE_INSENSITIVE_ORDER));
        return visible;
    }

    private ItemStack head(PlayerUiSnapshot target, Player viewer) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, target.id());
            // Titulo con el apodo (la identidad visible); el nombre real va al lore para staff.
            meta.displayName(dev.chatalyst.gui.MenuItems.noItalic(
                    MM.full("<reset>" + target.rankNick())));

            List<Component> lore = new ArrayList<>();
            // El nombre real detras de un apodo es informacion de STAFF (mismo permiso que
            // /realname): mostrarlo a todos rompia los apodos y desenmascaraba /anonymous.
            if (!target.shownName().equals(target.name())
                    && StaffAccess.has(viewer, "chatalyst.command.realname")) {
                lore.add(Lang.get(viewer, "players.menu.realname", "player", target.name()));
            }
            lore.add(Lang.get(viewer, "players.menu.rank",
                    "rank", target.prefix()));
            lore.add(Lang.get(viewer, "players.menu.world",
                    "world", target.world()));
            lore.add(Lang.get(viewer, "players.menu.ping",
                    "ping", target.ping()));
            if (plugin.getAfkManager() != null && plugin.getAfkManager().isAfk(target.id())) {
                lore.add(Lang.get(viewer, "players.menu.afk"));
            }
            lore.add(Component.empty());
            if (moderation) {
                // Lista del dashboard: solo moderacion, sin ruido social.
                lore.add(Lang.get(viewer, "players.menu.click-moderate"));
            } else {
                if (PrivateMessages.systemEnabled(plugin)) {
                    boolean reachable = viewer.getUniqueId().equals(target.id()) || viewer.isOp()
                            || !target.vanished()
                            || viewer.hasPermission(PrivateMessages.REACH_PERMISSION);
                    lore.add(Lang.get(viewer, reachable
                            ? "players.menu.click-message"
                            : "players.menu.no-reach"));
                }
                if (canManage(viewer)) {
                    lore.add(Lang.get(viewer, "players.menu.click-manage"));
                }
            }
            meta.lore(lore.stream().map(dev.chatalyst.gui.MenuItems::noItalic).toList());
        });
        return item;
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(dev.chatalyst.gui.MenuItems.noItalic(name));
            meta.lore(lore.stream().map(dev.chatalyst.gui.MenuItems::noItalic).toList());
        });
        return item;
    }
}
