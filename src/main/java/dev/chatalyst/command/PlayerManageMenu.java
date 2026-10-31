package dev.chatalyst.command;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.Mute;
import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.TargetInventoryMenu;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Menu de manejo de un jugador para staff, abierto con click derecho desde /players.
 * Reune las acciones de moderacion mas comunes (mute con presets, unmute, warnings,
 * chatlog y mensaje) sin escribir comandos. Cada boton respeta el permiso del comando
 * equivalente, asi que un helper sin /mute simplemente no ve esa fila.
 */
public final class PlayerManageMenu implements Menu {

    // Layout de 6 filas, espejado alrededor de la columna central (4). Cada boton va en una
    // columna PAR (0,2,4,6,8) con un hueco vacio entre medio: nada queda pegado y cada casilla
    // es facil de tocar en Bedrock/Geyser. Cinco botones por fila, agrupados por tema:
    //   fila 0: cabeza del objetivo (4)
    //   fila 1  Presencia:   ir(9)  traer(11)  mensaje(13)  freeze(15)  kick(17)
    //   fila 2  Sanciones:   warn(18) mute(20) unmute(22, si muteado) ban(24) warnings(26)
    //   fila 3  Registros:   chatlog(27) historial(29) notas(31) alts(33) spy(35)
    //   fila 4  Inspeccion e identidad: inventario(36) ender(38) apodo(40) color-apodo(42) color-chat(44)
    //   fila 5: volver, centrado (49)
    // Los huecos quedan vacios a proposito (mejor lectura y menos toques erroneos en Bedrock).
    private static final int HEAD = 4;
    // fila 1 - Presencia
    private static final int TELEPORT = 9;
    private static final int TPHERE = 11;
    private static final int MESSAGE = 13;
    private static final int FREEZE = 15;
    private static final int KICK = 17;
    // fila 2 - Sanciones
    private static final int WARN = 18;
    private static final int MUTE = 20;
    private static final int UNMUTE = 22;
    private static final int BAN = 24;
    private static final int WARNINGS = 26;
    // fila 3 - Registros / inteligencia
    private static final int CHATLOG = 27;
    private static final int HISTORY = 29;
    private static final int NOTES = 31;
    private static final int ALTS = 33;
    private static final int SPY = 35;
    // fila 4 - Inspeccion e identidad (gestionadas por staff sin suplantar)
    private static final int INVENTORY = 36;
    private static final int ENDER_CHEST = 38;
    private static final int NICK = 40;
    private static final int NICK_COLOR = 42;
    private static final int CHAT_COLOR = 44;
    private static final int HOME = 45;   // salto directo al panel de staff (/staff)
    // Fila de la cabeza, en pareja espejo: identidad y multicuentas (2) | cabeza (4) | mochilas (6).
    // Las mochilas de PlayerVaultsX viven en disco, asi que no exigen que el objetivo siga
    // conectado. Si alguno no esta disponible sale un hueco gris: el menu nunca se tuerce.
    private static final int IDENTITY = 2;
    private static final int VAULTS = 6;
    private static final int BACK = 49;
    private static final int CLOSE = 53;  // cierra el menu; fila inferior simetrica 45/49/53

    private static final String PERM_MUTE = "chatalyst.command.mute";
    private static final String PERM_WARNINGS = "chatalyst.command.warnings";
    private static final String PERM_LOG = "chatalyst.command.log";

    private final ChatalystPlugin plugin;
    private final PlayerUiSnapshot target;
    private final int listPage; // pagina de /players a la que vuelve el boton Back

    private PlayerManageMenu(ChatalystPlugin plugin, PlayerUiSnapshot target, int listPage) {
        this.plugin = plugin;
        this.target = target;
        this.listPage = listPage;
    }

    /** Captures the target only from its owning Folia region, then opens on the staff region. */
    public static void open(ChatalystPlugin plugin, Player staff, UUID targetId, int listPage) {
        Player online = Bukkit.getPlayer(targetId);
        Runnable missing = () -> FoliaTasks.entity(plugin, staff, current -> {
            current.sendMessage(Lang.get(current, "common.player-offline"));
            PlayerListMenu.openStaff(plugin, current, listPage);
        });
        if (online == null) {
            missing.run();
            return;
        }
        FoliaTasks.entity(plugin, online, selected -> {
            PlayerUiSnapshot snapshot = PlayerUiSnapshot.capture(selected);
            FoliaTasks.entity(plugin, staff, current -> {
                if (snapshot.vanished() && !StaffAccess.canSeeVanished(current)) {
                    current.sendMessage(Lang.get(current, "common.player-offline"));
                    PlayerListMenu.openStaff(plugin, current, listPage);
                    return;
                }
                Menu.open(current, new PlayerManageMenu(plugin, snapshot, listPage));
            });
        }, missing);
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("players.manage.title", "player", target.shownName()));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "players.manage.title", "player", nameFor(viewer));
    }

    /**
     * El nombre que este staff puede ver: el de la cuenta solo con el permiso de /realname.
     * Entrar a Moderar pide permisos de sancion, no ese; y el titulo, el lore y los comandos
     * sugeridos le desenmascaraban a un anonimo a cualquier helper con /mute. Las ACCIONES
     * siguen usando la cuenta real por dentro; esto es solo lo que se pinta o se sugiere.
     */
    private String nameFor(org.bukkit.command.CommandSender viewer) {
        return StaffAccess.has(viewer, "chatalyst.command.realname")
                ? target.name() : target.shownName();
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        inv.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
        // Home directo al panel de staff (mucha lata cerrar y reabrir para saltar de un jugador
        // a otra herramienta): este menu es de staff, asi que siempre esta disponible.
        inv.setItem(HOME, dev.chatalyst.gui.MenuNavigation.home(viewer));
        inv.setItem(CLOSE, dev.chatalyst.gui.MenuNavigation.close(viewer));

        inv.setItem(HEAD, head(target, viewer));

        // --- Identidad: apodo, color de apodo y color de chat ---
        if (viewer.hasPermission("chatalyst.nick.others")) {
            inv.setItem(NICK, icon(Material.NAME_TAG,
                    Lang.get(viewer, "players.manage.nick"),
                    List.of(Lang.get(viewer, "players.manage.nick-lore",
                            "nick", target.shownName()))));
        }
        if (viewer.hasPermission("chatalyst.nick.color.others")) {
            inv.setItem(NICK_COLOR, icon(Material.GLOW_INK_SAC,
                    Lang.get(viewer, "players.manage.nick-color"),
                    List.of(Lang.get(viewer, "players.manage.nick-color-lore"))));
        }
        if (viewer.hasPermission("chatalyst.chat.color.others")) {
            inv.setItem(CHAT_COLOR, icon(Material.LEATHER_CHESTPLATE,
                    Lang.get(viewer, "players.manage.chat-color"),
                    List.of(Lang.get(viewer, "players.manage.chat-color-lore"))));
        }

        if (StaffAccess.has(viewer, "chatalyst.staff.teleport")) {
            inv.setItem(TELEPORT, icon(Material.ENDER_PEARL,
                    Lang.get(viewer, "players.manage.teleport"),
                    List.of(Lang.get(viewer, "players.manage.teleport-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.tp")) {
            inv.setItem(TPHERE, icon(Material.ENDER_EYE,
                    Lang.get(viewer, "players.manage.tphere"),
                    List.of(Lang.get(viewer, "players.manage.tphere-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.kick")) {
            inv.setItem(KICK, icon(Material.YELLOW_CONCRETE,
                    Lang.get(viewer, "players.manage.kick"),
                    List.of(Lang.get(viewer, "players.manage.kick-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.freeze")) {
            boolean frozen = plugin.getStaffModeration().freezes().isFrozen(target.id());
            inv.setItem(FREEZE, icon(frozen ? Material.BLUE_ICE : Material.PACKED_ICE,
                    Lang.get(viewer, frozen ? "players.manage.unfreeze" : "players.manage.freeze"),
                    List.of(Lang.get(viewer, frozen ? "players.manage.unfreeze-lore"
                            : "players.manage.freeze-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.ban")) {
            inv.setItem(BAN, icon(Material.RED_CONCRETE,
                    Lang.get(viewer, "players.manage.ban"),
                    List.of(Lang.get(viewer, "players.manage.ban-lore"))));
        }
        // El mismo permiso que pide la accion (inspectInventory): con command.staff se veia un
        // boton que no hacia nada, y quien tenia invsee sin command.staff no lo veia.
        if (StaffAccess.has(viewer, "chatalyst.command.invsee")) {
            inv.setItem(INVENTORY, icon(Material.CHEST,
                    Lang.get(viewer, "players.manage.inventory"),
                    List.of(Lang.get(viewer, "players.manage.inventory-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.echest")) {
            inv.setItem(ENDER_CHEST, icon(Material.ENDER_CHEST,
                    Lang.get(viewer, "players.manage.echest"),
                    List.of(Lang.get(viewer, "players.manage.echest-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.vaultsee")
                && dev.chatalyst.hook.PlayerVaultsHook.isAvailable()) {
            inv.setItem(VAULTS, icon(Material.BARREL,
                    Lang.get(viewer, "players.manage.vaults"),
                    List.of(Lang.get(viewer, "players.manage.vaults-lore"))));
        } else {
            inv.setItem(VAULTS, dev.chatalyst.gui.MenuNavigation.unavailable(viewer));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.dupeip")) {
            inv.setItem(IDENTITY, dev.chatalyst.gui.MenuItems.item(Material.SPYGLASS,
                    Lang.raw(viewer, "staff-identity.open-button"),
                    List.of(Lang.raw(viewer, "staff-identity.open-lore"))));
        } else {
            inv.setItem(IDENTITY, dev.chatalyst.gui.MenuNavigation.unavailable(viewer));
        }

        if (StaffAccess.has(viewer, PERM_MUTE)) {
            Mute mute = plugin.getMuteManager().getMute(target.id());
            List<Component> muteLore = new ArrayList<>();
            if (mute != null) {
                muteLore.add(Lang.get(viewer, "players.manage.info-muted",
                        "duration", mute.isPermanent() ? "∞" : mute.remaining()));
                if (!mute.reason.isEmpty()) {
                    muteLore.add(Lang.get(viewer, "players.manage.info-mute-reason", "reason",
                            MM.escape(plugin.getStaffModeration().formatter()
                                    .plainReason(viewer, mute.reason))));
                }
            }
            muteLore.add(Lang.get(viewer, "players.manage.mute-lore",
                    "player", target.shownName()));
            inv.setItem(MUTE, icon(Material.ORANGE_DYE,
                    Lang.get(viewer, "players.manage.mute"), muteLore));
            if (mute != null) {
                inv.setItem(UNMUTE, icon(Material.MILK_BUCKET,
                        Lang.get(viewer, "players.manage.unmute"),
                        List.of(Lang.get(viewer, "players.manage.unmute-lore"),
                                Lang.get(viewer, "players.manage.click-apply"))));
            }
        }

        if (StaffAccess.has(viewer, PERM_WARNINGS)) {
            int points = plugin.getWarningPoints().get(target.id());
            inv.setItem(WARNINGS, icon(Material.PAPER,
                    Lang.get(viewer, "players.manage.warnings-title"),
                    List.of(Lang.get(viewer, "players.manage.warnings-lore", "points", points),
                            Lang.get(viewer, "players.manage.warnings-clear"))));
        }

        if (StaffAccess.has(viewer, PERM_LOG)) {
            inv.setItem(CHATLOG, icon(Material.WRITABLE_BOOK,
                    Lang.get(viewer, "players.manage.chatlog"),
                    List.of(Lang.get(viewer, "players.manage.chatlog-lore"),
                            Lang.get(viewer, "players.manage.chatlog-click"))));
        }

        if (PrivateMessages.systemEnabled(plugin)) {
            inv.setItem(MESSAGE, icon(Material.OAK_SIGN,
                    Lang.get(viewer, "players.manage.message"),
                    List.of(Lang.get(viewer, "players.manage.message-lore"))));
        }

        if (StaffAccess.has(viewer, "chatalyst.command.warn")) {
            inv.setItem(WARN, icon(Material.YELLOW_DYE,
                    Lang.get(viewer, "players.manage.warn"),
                    List.of(Lang.get(viewer, "players.manage.warn-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.staff.notes")) {
            inv.setItem(NOTES, icon(Material.MAP,
                    Lang.get(viewer, "players.manage.notes"),
                    List.of(Lang.get(viewer, "players.manage.notes-lore", "count",
                            plugin.getStaffModeration().notes().list(target.id()).size()))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.check")) {
            var module = plugin.getStaffModeration();
            inv.setItem(HISTORY, icon(Material.BOOK,
                    Lang.get(viewer, "staff-moderation.history-button"),
                    List.of(Lang.get(viewer, "staff-moderation.history-button-lore",
                            "count", module.history().list(target.id()).size()))));
        }
        if (StaffAccess.has(viewer, "chatalyst.staff.alts")) {
            // Siempre visible (con 0 incluidos) para que la fila no pierda la simetria.
            int altCount = plugin.getStaffModeration().alts()
                    .altsOf(target.id(), target.name()).size();
            inv.setItem(ALTS, icon(altCount > 0 ? Material.SPIDER_EYE : Material.GRAY_DYE,
                    Lang.get(viewer, "staff-moderation.alts-button"),
                    List.of(Lang.get(viewer, "staff-moderation.alts-button-lore",
                            "count", altCount))));
        }

        // Vigilar a ESTE jugador con el spy (equivale a /spy <jugador>).
        if (PrivateMessages.canSpyAny(viewer)) {
            boolean watching = plugin.getPrivateMessages().spyTargets(viewer)
                    .contains(target.name().toLowerCase(java.util.Locale.ROOT));
            inv.setItem(SPY, icon(watching ? Material.TARGET : Material.SPYGLASS,
                    Lang.get(viewer, watching ? "players.manage.spy-watching"
                            : "players.manage.spy"),
                    List.of(Lang.get(viewer, watching ? "players.manage.spy-watching-lore"
                                    : "players.manage.spy-lore",
                            "player", target.shownName()))));
        }
    }

    /** Sugiere el comando de apodo ya escrito: el nombre lo teclea el staff en el chat. */
    private void suggestNick(Player staff) {
        staff.closeInventory();
        staff.sendMessage(Lang.get(staff, "players.manage.nick-suggest",
                "player", nameFor(staff)));
    }

    /** Abre la paleta apuntando a este jugador (apodo o chat). */
    private void openPalette(Player staff, boolean nick) {
        String permiso = nick ? "chatalyst.nick.color.others" : "chatalyst.chat.color.others";
        if (!staff.hasPermission(permiso)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        Menu.open(staff, new TargetPaletteMenu(plugin, target.id(), nameFor(staff),
                nick, listPage));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            PlayerListMenu.openStaff(plugin, player, listPage);
            return;
        }
        if (slot == HOME) {
            dev.chatalyst.staff.StaffDashboardMenu.open(plugin.getStaffModeration(), player);
            return;
        }
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == VAULTS) {
            if (StaffAccess.has(player, "chatalyst.command.vaultsee")
                    && dev.chatalyst.hook.PlayerVaultsHook.isAvailable()) openVaults(player);
            return;
        }
        if (slot == IDENTITY) {
            if (StaffAccess.has(player, "chatalyst.command.dupeip")) {
                dev.chatalyst.staff.lookup.IdentityMenu.open(plugin.getStaffModeration(), player,
                        target.id(), target.name(), false, p -> Menu.open(p, this));
            }
            return;
        }

        if (Bukkit.getPlayer(target.id()) == null) {
            player.sendMessage(Lang.get(player, "common.player-offline"));
            PlayerListMenu.openStaff(plugin, player, listPage);
            return;
        }

        switch (slot) {
            case TELEPORT -> teleport(player);
            case TPHERE -> bringHere(player);
            case KICK -> openReasonMenu(player, dev.chatalyst.staff.ModerationReasonMenu.Action.KICK);
            case FREEZE -> toggleFreeze(player);
            case BAN -> openBanComposer(player);
            case INVENTORY -> inspectInventory(player);
            case ENDER_CHEST -> inspectEnderChest(player);
            case MUTE -> openMuteComposer(player);
            case UNMUTE -> unmute(player);
            case WARNINGS -> clearWarnings(player, click);
            case CHATLOG -> suggestChatlog(player);
            case MESSAGE -> suggestMessage(player);
            case HISTORY -> openHistory(player);
            case NICK -> suggestNick(player);
            case NICK_COLOR -> openPalette(player, true);
            case CHAT_COLOR -> openPalette(player, false);
            case ALTS -> openAlts(player);
            case SPY -> toggleSpyTarget(player);
            case WARN -> openReasonMenu(player,
                    dev.chatalyst.staff.ModerationReasonMenu.Action.WARN);
            case NOTES -> openNotes(player);
            default -> { }
        }
    }

    private void openNotes(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.staff.notes")) return;
        UUID targetId = target.id();
        int page = listPage;
        Menu.open(staff, new dev.chatalyst.staff.StaffNotesMenu(
                plugin.getStaffModeration(), targetId, target.name(),
                current -> open(plugin, current, targetId, page)));
    }

    private void openHistory(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.check")) return;
        UUID targetId = target.id();
        int page = listPage;
        Menu.open(staff, new dev.chatalyst.staff.PunishmentHistoryMenu(
                plugin.getStaffModeration(), targetId, target.name(),
                current -> open(plugin, current, targetId, page)));
    }

    private void openAlts(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.staff.alts")) return;
        UUID targetId = target.id();
        int page = listPage;
        List<String> alts = plugin.getStaffModeration().alts().altsOf(targetId, target.name());
        Menu.open(staff, new dev.chatalyst.staff.AltsListMenu(
                plugin.getStaffModeration(), target.name(), alts,
                current -> open(plugin, current, targetId, page)));
    }

    // ---- acciones -------------------------------------------------------------------

    /** El mute se arma por piezas (duracion, motivo, estilo, shadow) con vista previa. */
    private void openMuteComposer(Player staff) {
        if (!StaffAccess.has(staff, PERM_MUTE)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        UUID targetId = target.id();
        int page = listPage;
        Menu.open(staff, new dev.chatalyst.staff.MuteComposerMenu(
                plugin.getStaffModeration(), target.name(),
                current -> open(plugin, current, targetId, page)));
    }

    private void unmute(Player staff) {
        if (!StaffAccess.has(staff, PERM_MUTE)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        Runnable remove = () -> {
            dev.chatalyst.moderation.MuteManager.RemovalResult result =
                    plugin.getMuteManager().unmuteResult(target.id());
            // Igual que /unmute: quitar un mute desde el menu tambien queda en el historial.
            if (result == dev.chatalyst.moderation.MuteManager.RemovalResult.REMOVED
                    && plugin.getStaffModeration() != null) {
                plugin.getStaffModeration().history().record(target.id(), "unmute",
                        staff.getName(), "");
            }
            FoliaTasks.entity(plugin, staff, current -> {
                current.sendMessage(result
                        == dev.chatalyst.moderation.MuteManager.RemovalResult.REMOVED
                        ? Lang.get(current, "mute.unmute-done", "player", nameFor(current))
                        : result == dev.chatalyst.moderation.MuteManager.RemovalResult.NOT_FOUND
                        ? Lang.get(current, "mute.unmute-none")
                        : Lang.get(current, "plugin.storage-unavailable"));
                if (result == dev.chatalyst.moderation.MuteManager.RemovalResult.REMOVED) {
                    audit(current, "unmute", target.name());
                }
                open(plugin, current, target.id(), listPage);
            });
        };
        Player online = Bukkit.getPlayer(target.id());
        if (online != null) {
            FoliaTasks.entity(plugin, online, ignored -> remove.run(),
                    () -> FoliaTasks.global(plugin, remove));
        } else {
            FoliaTasks.global(plugin, remove);
        }
    }

    private void clearWarnings(Player staff, ClickType click) {
        if (!StaffAccess.has(staff, PERM_WARNINGS)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        // Solo shift+click borra, para que un click de lectura no destruya los puntos.
        if (!click.isShiftClick()) return;
        if (!plugin.getWarningPoints().clear(target.id())) {
            staff.sendMessage(Lang.get(staff, "plugin.storage-unavailable"));
            return;
        }
        audit(staff, "warnings clear", target.name());
        staff.sendMessage(Lang.get(staff, "warnings.cleared", "player", nameFor(staff)));
        open(plugin, staff, target.id(), listPage);
    }

    /** Vigila o deja de vigilar a este jugador; el menu se refresca con el estado nuevo. */
    private void toggleSpyTarget(Player staff) {
        if (!PrivateMessages.canSpyAny(staff)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        PrivateMessages.TargetChange change =
                plugin.getPrivateMessages().toggleSpyTarget(staff, target.name());
        String key = switch (change) {
            case ADDED -> "pm.spy-target-added";
            case REMOVED -> "pm.spy-target-removed";
            case REMOVED_LAST_OFF -> "pm.spy-target-removed-off";
            case REMOVED_LAST_GLOBAL -> "pm.spy-target-removed-global";
        };
        staff.sendMessage(Lang.get(staff, key, "player", target.shownName(),
                "count", plugin.getPrivateMessages().spyTargets(staff).size()));
        Menu.open(staff, this);
    }

    private void suggestChatlog(Player staff) {
        if (!StaffAccess.has(staff, PERM_LOG)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        staff.closeInventory();
        staff.sendMessage(Lang.get(staff, "players.manage.chatlog-suggest",
                        "player", target.shownName())
                .clickEvent(ClickEvent.suggestCommand("/chatlog " + nameFor(staff) + " "))
                .hoverEvent(HoverEvent.showText(Lang.get(staff, "players.manage.chatlog-click"))));
    }

    private void suggestMessage(Player staff) {
        if (!PrivateMessages.systemEnabled(plugin)) return;
        if (target.vanished() && !staff.isOp()
                && !staff.hasPermission(PrivateMessages.REACH_PERMISSION)) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        staff.closeInventory();
        staff.sendMessage(Lang.get(staff, "players.message-suggest",
                        "player", target.shownName())
                .clickEvent(ClickEvent.suggestCommand("/msg " + nameFor(staff) + " "))
                .hoverEvent(HoverEvent.showText(Lang.get(staff, "players.message-suggest-hover"))));
    }

    private void teleport(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.staff.teleport")) return;
        staff.closeInventory();
        Player online = Bukkit.getPlayer(target.id());
        if (online == null) {
            staff.sendMessage(Lang.get(staff, "players.manage.offline"));
            return;
        }
        FoliaTasks.entity(plugin, online, selected -> {
            org.bukkit.Location destination = selected.getLocation().clone();
            String targetName = selected.getName();
            FoliaTasks.entity(plugin, staff, player ->
                    player.teleportAsync(destination).thenAccept(success -> {
                        if (success) FoliaTasks.entity(plugin, player,
                                current -> current.sendMessage(Lang.get(current,
                                        "players.manage.teleported", "player", targetName)));
                    }));
        }, () -> FoliaTasks.entity(plugin, staff,
                player -> player.sendMessage(Lang.get(player, "players.manage.offline"))));
    }

    /** Kick y ban eligen su motivo en un menu de presets (o escrito por chat), sin comandos. */
    private void openReasonMenu(Player staff, dev.chatalyst.staff.ModerationReasonMenu.Action action) {
        String permission = "chatalyst.command."
                + action.name().toLowerCase(java.util.Locale.ROOT);
        if (!StaffAccess.has(staff, permission)) return;
        UUID targetId = target.id();
        int page = listPage;
        Menu.open(staff, new dev.chatalyst.staff.ModerationReasonMenu(
                plugin.getStaffModeration(), action, target.name(),
                current -> open(plugin, current, targetId, page)));
    }

    private void bringHere(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.tp")) return;
        staff.closeInventory();
        staff.performCommand("chatalyst:tphere " + target.name());
    }

    /** El ban se arma por piezas (duracion, motivo, estilo) con vista previa. */
    private void openBanComposer(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.ban")) return;
        UUID targetId = target.id();
        int page = listPage;
        Menu.open(staff, new dev.chatalyst.staff.BanComposerMenu(
                plugin.getStaffModeration(), target.name(),
                current -> open(plugin, current, targetId, page)));
    }

    private void toggleFreeze(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.freeze")) return;
        if (plugin.getStaffModeration().freezes().isFrozen(target.id())) {
            staff.closeInventory();
            staff.performCommand("chatalyst:unfreeze " + target.name());
            return;
        }
        openReasonMenu(staff, dev.chatalyst.staff.ModerationReasonMenu.Action.FREEZE);
    }

    private void inspectInventory(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.invsee")) {
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        if (StaffAccess.has(staff, "chatalyst.command.invsee.edit")) {
            plugin.getStaffModeration().inventoryEditor().open(staff, target.id(),
                    dev.chatalyst.staff.InventoryEditSession.Kind.INVENTORY);
            return;
        }
        audit(staff, "invsee", target.name());
        TargetInventoryMenu.open(plugin.getStaffModeration(), staff, target.id(), listPage);
    }

    private void inspectEnderChest(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.echest")) return;
        if (StaffAccess.has(staff, "chatalyst.command.echest.edit")) {
            plugin.getStaffModeration().inventoryEditor().open(staff, target.id(),
                    dev.chatalyst.staff.InventoryEditSession.Kind.ENDER_CHEST);
            return;
        }
        audit(staff, "echest", target.name());
        dev.chatalyst.staff.TargetEnderChestMenu.open(
                plugin.getStaffModeration(), staff, target.id(), listPage);
    }

    private void openVaults(Player staff) {
        if (!StaffAccess.has(staff, "chatalyst.command.vaultsee")) return;
        dev.chatalyst.staff.PlayerVaultsMenu.open(plugin.getStaffModeration(), staff,
                target.id(), target.name(),
                () -> PlayerManageMenu.open(plugin, staff, target.id(), listPage));
    }

    private void audit(Player staff, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(staff, action, detail);
        }
    }

    // ---- items ----------------------------------------------------------------------

    private ItemStack head(PlayerUiSnapshot target, Player viewer) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, target.id());
            meta.displayName(dev.chatalyst.gui.MenuItems.noItalic(
                    MM.full("<reset>" + target.rankNick())));

            List<Component> lore = new ArrayList<>();
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
            if (StaffAccess.has(viewer, PERM_WARNINGS)) {
                lore.add(Lang.get(viewer, "players.manage.info-warnings",
                        "points", plugin.getWarningPoints().get(target.id())));
            }
            Mute mute = plugin.getMuteManager().getMute(target.id());
            if (mute != null) {
                lore.add(Lang.get(viewer, "players.manage.info-muted",
                        "duration", mute.isPermanent() ? "∞" : mute.remaining()));
                if (!mute.reason.isEmpty()) {
                    lore.add(Lang.get(viewer, "players.manage.info-mute-reason", "reason",
                            MM.escape(plugin.getStaffModeration().formatter()
                                    .plainReason(viewer, mute.reason))));
                }
            } else {
                lore.add(Lang.get(viewer, "players.manage.info-not-muted"));
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
