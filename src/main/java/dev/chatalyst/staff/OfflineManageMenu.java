package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Moderacion de un jugador DESCONECTADO, pensada para el flujo "me reportan y el objetivo
 * se salio antes de poder congelarlo": freeze y ban funcionan en ausencia (el freeze es
 * durable, al volver a entrar el jugador queda estatico con su aviso y salirse activa el
 * ban del FreezeGuard). Si el objetivo se reconecta, cada accion sigue funcionando: los
 * comandos resuelven online/offline por si mismos.
 */
public final class OfflineManageMenu implements Menu {

    // Fila central espejada alrededor del ban de conexion (13): chatlog(10) mute(11)
    // freeze(12) banip(13) ban(14) historial(15) notas(16); cabeza (4) y volver (22) centrados.
    private static final int HEAD = 4;
    private static final int CHATLOG = 10;
    private static final int MUTE = 11;
    private static final int FREEZE = 12;
    private static final int IP_BAN = 13;
    private static final int BAN = 14;
    private static final int HISTORY = 15;
    private static final int NOTES = 16;
    private static final int BACK = 22;
    private static final int IDENTITY = 20;
    private static final int SUMMARY = 24;   // espejo de IDENTITY: resumen /check en el chat

    private final StaffModerationModule module;
    private final UUID targetId;
    private final String targetName;
    private final Consumer<Player> back;

    private OfflineManageMenu(StaffModerationModule module, UUID targetId, String targetName,
                              Consumer<Player> back) {
        this.module = module;
        this.targetId = targetId;
        this.targetName = targetName;
        this.back = back;
    }

    /** {@code back} vuelve al menu de origen (p. ej. el detalle del reporte); null cierra. */
    public static void open(StaffModerationModule module, Player viewer, OfflinePlayer target,
                            String shownName, Consumer<Player> back) {
        String name = target.getName() == null ? shownName : target.getName();
        Menu.open(viewer, new OfflineManageMenu(module, target.getUniqueId(), name, back));
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.offline-title", "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.offline-title",
                "player", MM.escape(targetName));
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        inventory.setItem(HEAD, head(viewer));

        if (StaffAccess.has(viewer, "chatalyst.command.freeze")) {
            boolean frozen = module.freezes().isFrozen(targetId);
            inventory.setItem(FREEZE, MenuItems.item(
                    frozen ? Material.BLUE_ICE : Material.PACKED_ICE,
                    Lang.raw(viewer, frozen ? "players.manage.unfreeze"
                            : "players.manage.freeze"),
                    List.of(Lang.raw(viewer, frozen
                            ? "players.manage.unfreeze-lore"
                            : "staff-moderation.offline-freeze-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.ban")) {
            boolean banned = module.bans().get(targetId) != null;
            inventory.setItem(BAN, MenuItems.item(
                    banned ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                    Lang.raw(viewer, banned ? "staff-moderation.offline-unban"
                            : "players.manage.ban"),
                    List.of(Lang.raw(viewer, banned
                            ? "staff-moderation.offline-unban-lore"
                            : "staff-moderation.offline-ban-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.banip")) {
            boolean ipBanned = module.ipBans().has(module.alts().lastHash(targetId));
            inventory.setItem(IP_BAN, MenuItems.item(
                    ipBanned ? Material.LIME_DYE : Material.CHAIN,
                    Lang.raw(viewer, ipBanned ? "staff-moderation.offline-unbanip"
                            : "staff-moderation.offline-banip"),
                    List.of(Lang.raw(viewer, ipBanned
                            ? "staff-moderation.offline-unbanip-lore"
                            : "staff-moderation.offline-banip-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.mute")) {
            var mutes = module.plugin().getMuteManager();
            boolean muted = mutes != null && mutes.isMuted(targetId);
            inventory.setItem(MUTE, MenuItems.item(
                    muted ? Material.MILK_BUCKET : Material.ORANGE_DYE,
                    Lang.raw(viewer, muted ? "players.manage.unmute" : "players.manage.mute"),
                    List.of(Lang.raw(viewer, muted ? "players.manage.unmute-lore"
                            : "staff-moderation.offline-mute-lore"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.check")) {
            inventory.setItem(HISTORY, MenuItems.item(Material.BOOK,
                    Lang.raw(viewer, "staff-moderation.history-button"),
                    List.of(Lang.raw(viewer, "staff-moderation.history-button-lore",
                            "count", module.history().list(targetId).size()))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.log")) {
            inventory.setItem(CHATLOG, MenuItems.item(Material.WRITABLE_BOOK,
                    Lang.raw(viewer, "players.manage.chatlog"),
                    List.of(Lang.raw(viewer, "players.manage.chatlog-lore"),
                            Lang.raw(viewer, "players.manage.chatlog-click"))));
        }
        if (StaffAccess.has(viewer, "chatalyst.staff.notes")) {
            inventory.setItem(NOTES, MenuItems.item(Material.MAP,
                    Lang.raw(viewer, "players.manage.notes"),
                    List.of(Lang.raw(viewer, "players.manage.notes-lore",
                            "count", module.notes().list(targetId).size()))));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.dupeip")) {
            inventory.setItem(IDENTITY, MenuItems.item(Material.SPYGLASS,
                    Lang.raw(viewer, "staff-identity.open-button"),
                    List.of(Lang.raw(viewer, "staff-identity.open-lore"))));
        } else {
            inventory.setItem(IDENTITY, dev.chatalyst.gui.MenuNavigation.unavailable(viewer));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.check")) {
            inventory.setItem(SUMMARY, MenuItems.item(Material.OAK_SIGN,
                    Lang.raw(viewer, "staff-identity.summary-button"),
                    List.of(Lang.raw(viewer, "staff-identity.summary-lore"))));
        } else {
            inventory.setItem(SUMMARY, dev.chatalyst.gui.MenuNavigation.unavailable(viewer));
        }
        inventory.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case BACK -> {
                if (back != null) back.accept(player);
                else player.closeInventory();
            }
            case FREEZE -> {
                if (!StaffAccess.has(player, "chatalyst.command.freeze")) return;
                if (module.freezes().isFrozen(targetId)) {
                    player.closeInventory();
                    player.performCommand("chatalyst:unfreeze " + targetName);
                } else {
                    Menu.open(player, new ModerationReasonMenu(module,
                            ModerationReasonMenu.Action.FREEZE, targetName, this::reopen));
                }
            }
            case BAN -> {
                if (!StaffAccess.has(player, "chatalyst.command.ban")) return;
                if (module.bans().get(targetId) != null) {
                    // Desbanear es lo unico de esta ficha que no se deshace con otro clic:
                    // pide Shift, igual que borrar los avisos.
                    if (!click.isShiftClick()) return;
                    player.closeInventory();
                    player.performCommand("chatalyst:unban " + targetName);
                } else {
                    Menu.open(player, new BanComposerMenu(module, targetName, this::reopen));
                }
            }
            case IP_BAN -> {
                if (!StaffAccess.has(player, "chatalyst.command.banip")) return;
                if (module.ipBans().has(module.alts().lastHash(targetId))) {
                    if (!click.isShiftClick()) return;
                    player.closeInventory();
                    player.performCommand("chatalyst:unbanip " + targetName);
                } else {
                    // Compositor con el alcance "Cuenta + conexion" ya pre-marcado.
                    Menu.open(player, new BanComposerMenu(module, targetName, true,
                            this::reopen));
                }
            }
            case MUTE -> {
                if (!StaffAccess.has(player, "chatalyst.command.mute")) return;
                var mutes = module.plugin().getMuteManager();
                if (mutes != null && mutes.isMuted(targetId)) {
                    player.closeInventory();
                    player.performCommand("chatalyst:unmute " + targetName);
                } else {
                    // /mute resuelve objetivos offline por si mismo (resolveOffline).
                    Menu.open(player, new MuteComposerMenu(module, targetName, this::reopen));
                }
            }
            case HISTORY -> {
                if (!StaffAccess.has(player, "chatalyst.command.check")) return;
                Menu.open(player, new PunishmentHistoryMenu(module, targetId, targetName,
                        this::reopen));
            }
            case CHATLOG -> {
                if (!StaffAccess.has(player, "chatalyst.command.log")) return;
                player.closeInventory();
                player.sendMessage(Lang.get(player, "players.manage.chatlog-suggest",
                                "player", MM.escape(targetName))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent
                                .suggestCommand("/chatlog " + targetName + " "))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent
                                .showText(Lang.get(player, "players.manage.chatlog-click"))));
            }
            case NOTES -> {
                if (!StaffAccess.has(player, "chatalyst.staff.notes")) return;
                Menu.open(player, new StaffNotesMenu(module, targetId, targetName,
                        this::reopen));
            }
            case SUMMARY -> {
                if (!StaffAccess.has(player, "chatalyst.command.check")) return;
                player.closeInventory();
                player.performCommand("chatalyst:check " + targetName);
            }
            case IDENTITY -> {
                if (!StaffAccess.has(player, "chatalyst.command.dupeip")) return;
                dev.chatalyst.staff.lookup.IdentityMenu.open(module, player, targetId, targetName,
                        false, this::reopen);
            }
            default -> { }
        }
    }

    private void reopen(Player player) {
        Menu.open(player, new OfflineManageMenu(module, targetId, targetName, back));
    }

    private ItemStack head(Player viewer) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, targetId);
            meta.displayName(MenuItems.noItalic(MM.full("<#FBF2FF>" + MM.escape(targetName))));

            List<Component> lore = new ArrayList<>();
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            Player online = target.getPlayer();
            boolean visible = online != null && (!dev.chatalyst.util.Vanish.isVanished(online)
                    || StaffAccess.canSeeVanished(viewer));
            if (visible) {
                lore.add(Lang.get(viewer, "staff-moderation.check-online"));
            } else {
                long last = online != null ? target.getLastLogin() : target.getLastSeen();
                lore.add(Lang.get(viewer, "staff-moderation.check-offline", "duration",
                        TimeUtil.formatElapsed(System.currentTimeMillis() - last)));
            }
            BanRecord ban = module.bans().get(targetId);
            if (ban != null) {
                lore.add(Lang.get(viewer, "staff-moderation.check-banned",
                        "staff", MM.escape(ban.staff()),
                        "duration", StaffCommandSupport.remaining(viewer, ban.until()),
                        "reason", MM.escape(module.formatter().plainReason(viewer, ban.reason()))));
            }
            FreezeRecord freeze = module.freezes().get(targetId);
            if (freeze != null) {
                lore.add(Lang.get(viewer, "staff-moderation.check-frozen",
                        "staff", MM.escape(freeze.staff()),
                        "reason", MM.escape(module.formatter().plainReason(viewer, freeze.reason()))));
            }
            IpBanManager.IpBan ipBan = module.ipBans().get(module.alts().lastHash(targetId));
            if (ipBan != null) {
                lore.add(Lang.get(viewer, "staff-moderation.check-ipbanned",
                        "staff", MM.escape(ipBan.staff()),
                        "duration", StaffCommandSupport.remaining(viewer, ipBan.until())));
            }
            var mutes = module.plugin().getMuteManager();
            if (mutes != null && mutes.isMuted(targetId)) {
                lore.add(Lang.get(viewer, "staff-moderation.check-muted"));
            }
            var warnings = module.plugin().getWarningPoints();
            int points = warnings == null ? 0 : warnings.get(targetId);
            if (points > 0) {
                lore.add(Lang.get(viewer, "staff-moderation.check-warnings", "points", points));
            }
            // El ban de conexion tambien es un castigo activo: sin el, alguien baneado solo
            // por IP salia "sin castigos" justo encima de la linea que lo dice.
            if (ban == null && freeze == null && ipBan == null && points == 0
                    && (mutes == null || !mutes.isMuted(targetId))) {
                lore.add(Lang.get(viewer, "staff-moderation.check-clean"));
            }
            int pastPunishments = module.history().list(targetId).size();
            if (pastPunishments > 0) {
                lore.add(Lang.get(viewer, "staff-moderation.check-history",
                        "count", pastPunishments));
            }
            if (StaffAccess.has(viewer, "chatalyst.staff.alts")) {
                List<String> alts = module.alts().altsOf(targetId, targetName);
                if (!alts.isEmpty()) {
                    lore.add(Lang.get(viewer, "staff-moderation.check-alts",
                            "players", MM.escape(String.join(", ", alts))));
                }
            }
            meta.lore(lore.stream().map(MenuItems::noItalic).toList());
        });
        return item;
    }
}
