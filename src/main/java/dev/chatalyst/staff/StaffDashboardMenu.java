package dev.chatalyst.staff;

import dev.chatalyst.command.PlayerListMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.pm.SpyMenu;
import dev.chatalyst.report.ReportsMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/** Symmetric 5-row operations dashboard reached by the standalone {@code /staff}. */
public final class StaffDashboardMenu implements Menu {

    // Rejilla LIMPIA: sin relleno de cristal (huecos vacios, mejor en Bedrock) y sin cabeceras
    // de categoria. Las herramientas van en columnas PARES para quedar alineadas entre filas.
    //   fila 0: modo staff, centrado (4)
    //   fila 1: jugadores(11) reportes(13) directorio(15)
    //   fila 2: vanish(20)    spy(22)      chatlock(24)
    //   fila 3: chatlog(28)   cofres(30)   combate(32)  congelados(34)
    //   fila 4: clearchat(40, centrado)    salir(44, esquina; solo con el modo activo)
    private static final int MODE = 4;
    private static final int PLAYERS = 11;
    private static final int REPORTS = 13;
    private static final int DIRECTORY = 15;
    private static final int VANISH = 20;
    private static final int SPY = 22;
    private static final int CHAT_LOCK = 24;
    // La fila de registros paso de 3 a 4 huecos al entrar el de combate. Columnas 1-3-5-7
    // en vez de 2-4-6: sigue siendo simetrica (1 con 7, 3 con 5) y los tres registros
    // quedan juntos, que es como se consultan.
    private static final int CHAT_LOG = 28;
    private static final int CONTAINER_LOG = 30;
    private static final int COMBAT_LOG = 32;
    private static final int FREEZE_HELP = 34;
    // Fila de abajo simetrica: limpiar chat | salir del modo staff o cerrar (centro) | lo que has aplicado.
    private static final int CLEAR_CHAT = 38;
    private static final int EXIT = 40;
    private static final int MY_HISTORY = 42;

    private final StaffModerationModule module;

    public StaffDashboardMenu(StaffModerationModule module) {
        this.module = module;
    }

    /**
     * La unica forma de abrir el panel, y por tanto el sitio donde se comprueba que la suite
     * de staff este encendida.
     *
     * <p>El {@code /staff} ya lo comprobaba, pero el panel no se abre solo desde ahi: llegan a
     * el el menu de admin de {@code /cht}, la lista de jugadores, la ficha de un jugador, la
     * lista de congelados y la estrella del kit. Con {@code Staff_Moderation.Enabled} apagado
     * todas esas puertas seguian abiertas, y desde el panel se entra al MODO staff — que
     * cambia el inventario real del jugador por el kit. Un modulo apagado no puede tocar el
     * inventario de nadie, asi que la comprobacion vive aqui y no en cada llamador.
     */
    public static void open(StaffModerationModule module, Player player) {
        if (module == null) return;
        if (!module.enabled()) {
            player.sendMessage(Lang.get(player, "staff-moderation.disabled"));
            return;
        }
        if (!StaffAccess.has(player, "chatalyst.command.staff")) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        // El unico `new` del panel en todo el plugin: los demas sitios llaman a esta puerta.
        Menu.open(player, new StaffDashboardMenu(module));
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.menu-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.menu-title");
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        // Huecos VACIOS a proposito (sin relleno de cristal): mas limpio y sin ruido tocable en
        // Bedrock, igual que el resto de GUIs del plugin. MenuListener cancela todo click.
        boolean active = module.staffMode().isActive(viewer);
        inventory.setItem(MODE, MenuItems.item(active ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                Lang.raw(viewer, active ? "staff-moderation.menu-mode-on"
                        : "staff-moderation.menu-mode-off"),
                List.of(Lang.raw(viewer, active ? "staff-moderation.menu-mode-on-lore"
                        : "staff-moderation.menu-mode-off-lore"))));

        inventory.setItem(PLAYERS, MenuItems.item(Material.PLAYER_HEAD,
                Lang.raw(viewer, "staff-moderation.menu-players"),
                List.of(Lang.raw(viewer, "staff-moderation.menu-players-lore"))));
        if (module.plugin().getReportActions() != null
                && StaffAccess.has(viewer, "chatalyst.report.manage")) {
            // Con el numero de abiertos, como el boton de congelados: verlo sin entrar.
            int open = module.plugin().getReportActions().reports().listOpen().size();
            inventory.setItem(REPORTS, MenuItems.item(Material.PAPER,
                    Lang.raw(viewer, "staff-moderation.menu-reports", "count", open),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-reports-lore"))));
        }
        if (module.plugin().getStaffDirectory() != null
                && StaffAccess.has(viewer, "chatalyst.admin.staff.directory")) {
            inventory.setItem(DIRECTORY, MenuItems.item(Material.RECOVERY_COMPASS,
                    Lang.raw(viewer, "staff-moderation.menu-directory"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-directory-lore"))));
        }

        if (StaffAccess.has(viewer, "chatalyst.command.vanish")) {
            inventory.setItem(VANISH, MenuItems.item(module.vanish().isOwned(viewer)
                            ? Material.LIME_DYE : Material.GRAY_DYE,
                    Lang.raw(viewer, "staff-moderation.menu-vanish"),
                    List.of(Lang.raw(viewer, module.vanish().isOwned(viewer)
                            ? "staff-moderation.menu-vanish-on"
                            : "staff-moderation.menu-vanish-off"))));
        }
        if (PrivateMessages.canSpyAny(viewer)) {
            // Estado a la vista: encendido/apagado y si esta acotado a jugadores.
            PrivateMessages pm = module.plugin().getPrivateMessages();
            boolean on = pm.isSpy(viewer);
            int watched = pm.spyTargets(viewer).size();
            List<String> lore = new java.util.ArrayList<>();
            lore.add(Lang.raw(viewer, on ? "staff-moderation.menu-spy-on"
                    : "staff-moderation.menu-spy-off"));
            lore.add(watched == 0 ? Lang.raw(viewer, "staff-moderation.menu-spy-global")
                    : Lang.raw(viewer, "staff-moderation.menu-spy-targeted",
                            "count", watched));
            lore.add(Lang.raw(viewer, "staff-moderation.menu-spy-lore"));
            lore.add(Lang.raw(viewer, "staff-moderation.menu-spy-click"));
            inventory.setItem(SPY, MenuItems.item(
                    on ? Material.SPYGLASS : Material.GRAY_DYE,
                    Lang.raw(viewer, "staff-moderation.menu-spy"), lore));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.chatlock")) {
            boolean locked = module.plugin().getChatModeration().isLocked();
            inventory.setItem(CHAT_LOCK, MenuItems.item(locked ? Material.REDSTONE_BLOCK : Material.IRON_BARS,
                    Lang.raw(viewer, locked ? "staff-moderation.menu-chat-locked"
                            : "staff-moderation.menu-chat-open"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-chat-lock-lore"))));
        }

        if (StaffAccess.has(viewer, "chatalyst.command.log")) {
            inventory.setItem(CHAT_LOG, MenuItems.item(Material.WRITABLE_BOOK,
                    Lang.raw(viewer, "staff-moderation.menu-chatlog"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-chatlog-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.containerlog")) {
            inventory.setItem(CONTAINER_LOG, MenuItems.item(Material.CHEST,
                    Lang.raw(viewer, "staff-moderation.menu-containerlog"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-containerlog-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.combatlog")) {
            inventory.setItem(COMBAT_LOG, MenuItems.item(Material.IRON_SWORD,
                    Lang.raw(viewer, "staff-moderation.menu-combatlog"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-combatlog-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.freeze")) {
            inventory.setItem(FREEZE_HELP, MenuItems.item(Material.PACKED_ICE,
                    // El contador va en el TITULO, que es quien lleva {count}.
                    Lang.raw(viewer, "staff-moderation.menu-freeze",
                            "count", module.freezes().snapshot().size()),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-freeze-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.clearchat")) {
            inventory.setItem(CLEAR_CHAT, MenuItems.item(Material.SPONGE,
                    Lang.raw(viewer, "staff-moderation.menu-clearchat"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-clearchat-lore"))));
        } else {
            inventory.setItem(CLEAR_CHAT, dev.chatalyst.gui.MenuNavigation.unavailable(viewer));
        }
        inventory.setItem(EXIT, active ? MenuItems.item(Material.RED_DYE,
                Lang.raw(viewer, "staff-moderation.menu-exit"),
                List.of(Lang.raw(viewer, "staff-moderation.menu-exit-lore")))
                : dev.chatalyst.gui.MenuNavigation.close(viewer));
        if (StaffAccess.has(viewer, "chatalyst.command.staffhistory")) {
            inventory.setItem(MY_HISTORY, MenuItems.item(Material.KNOWLEDGE_BOOK,
                    Lang.raw(viewer, "staff-moderation.menu-myhistory"),
                    List.of(Lang.raw(viewer, "staff-moderation.menu-myhistory-lore"))));
        } else {
            inventory.setItem(MY_HISTORY, dev.chatalyst.gui.MenuNavigation.unavailable(viewer));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        // Un reload puede apagar la suite con el panel ya abierto en pantalla. El boton que
        // mas importa (modo staff) toca el inventario real, asi que se revalida en el click.
        if (!module.enabled()) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "staff-moderation.disabled"));
            return;
        }
        switch (slot) {
            case MODE -> toggleMode(player);
            case PLAYERS -> PlayerListMenu.openStaff(module.plugin(), player, 0);
            case REPORTS -> {
                if (module.plugin().getReportActions() != null
                        && StaffAccess.has(player, "chatalyst.report.manage")) {
                    Menu.open(player, new ReportsMenu(module.plugin().getReportActions()));
                }
            }
            case DIRECTORY -> {
                if (module.plugin().getStaffDirectory() != null
                        && StaffAccess.has(player, "chatalyst.admin.staff.directory")) {
                    Menu.open(player, new StaffDirectoryMenu(module.plugin().getStaffDirectory(), 0));
                }
            }
            case VANISH -> {
                if (!StaffAccess.has(player, "chatalyst.command.vanish")) return;
                boolean on = module.vanish().toggle(player);
                if (module.staffMode().isActive(player)) module.staffMode().giveKit(player);
                module.audit(player, on ? "vanish on" : "vanish off", player.getName());
                player.sendMessage(Lang.get(player, on ? "staff-moderation.vanish-on"
                        : "staff-moderation.vanish-off"));
                StaffDashboardMenu.open(module, player);
            }
            case SPY -> {
                if (!PrivateMessages.canSpyAny(player)) return;
                if (click.isRightClick()) {
                    // Click derecho: enciende/apaga el spy sin salir del panel.
                    boolean on = module.plugin().getPrivateMessages().toggleSpy(player);
                    player.sendMessage(Lang.get(player, on ? "pm.spy-on" : "pm.spy-off"));
                    StaffDashboardMenu.open(module, player);
                } else {
                    Menu.open(player, new SpyMenu(module.plugin().getPrivateMessages(),
                            staff -> StaffDashboardMenu.open(module, staff)));
                }
            }
            case CHAT_LOCK -> toggleChatLock(player);
            case CHAT_LOG -> {
                if (!StaffAccess.has(player, "chatalyst.command.log")) return;
                player.closeInventory();
                player.performCommand("chatalyst:chatlog");
            }
            case CONTAINER_LOG -> {
                if (!StaffAccess.has(player, "chatalyst.command.containerlog")) return;
                player.closeInventory();
                player.performCommand("chatalyst:containerlog");
            }
            case COMBAT_LOG -> {
                if (!StaffAccess.has(player, "chatalyst.command.combatlog")) return;
                player.closeInventory();
                player.performCommand("chatalyst:combatlog");
            }
            case CLEAR_CHAT -> {
                if (!StaffAccess.has(player, "chatalyst.command.clearchat")) return;
                // Reutiliza el comando: broadcast localizado y bypass de staff ya resueltos.
                player.closeInventory();
                player.performCommand("chatalyst:clearchat");
            }
            case FREEZE_HELP -> {
                if (!StaffAccess.has(player, "chatalyst.command.freeze")) return;
                Menu.open(player, new FrozenListMenu(module));
            }
            case MY_HISTORY -> {
                if (!StaffAccess.has(player, "chatalyst.command.staffhistory")) return;
                player.closeInventory();
                player.performCommand("chatalyst:staffhistory " + player.getName());
            }
            case EXIT -> {
                player.closeInventory();
                if (module.staffMode().exit(player)) {
                    player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-off"));
                }
            }
            default -> { }
        }
    }

    private void toggleMode(Player player) {
        if (!StaffAccess.has(player, "chatalyst.command.staff")) return;
        // Cerrar SIEMPRE el menu antes de tocar el inventario. Al entrar, giveKit escribe el
        // inventario inferior mientras este menu (inventario superior) sigue abierto, y el
        // cliente no siempre renderiza esos cambios hasta cerrar: el kit se veia incompleto.
        player.closeInventory();
        if (module.staffMode().isActive(player)) {
            if (module.staffMode().exit(player)) {
                player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-off"));
            }
        } else if (module.staffMode().enter(player)) {
            player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-on"));
        }
    }

    private void toggleChatLock(Player player) {
        if (!StaffAccess.has(player, "chatalyst.command.chatlock")) return;
        boolean next = !module.plugin().getChatModeration().isLocked();
        module.plugin().getChatModeration().setLocked(next);
        module.audit(player, next ? "chatlock" : "chatunlock", "staff menu");
        player.sendMessage(Lang.get(player, next ? "chatmod.locked" : "chatmod.unlocked"));
        StaffDashboardMenu.open(module, player);
    }
}
