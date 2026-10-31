package dev.chatalyst.staff;

import dev.chatalyst.hook.PlayerVaultsHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import dev.chatalyst.util.Vanish;
import dev.chatalyst.warnings.WarningPoints;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Caja de herramientas de staff: /invsee, /echest, /vaultsee, /seen, /check, /tpto y /tphere. */
final class StaffToolsCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    StaffToolsCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)) return true;
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "invsee" -> inventory(sender, label, args, false);
            case "echest" -> inventory(sender, label, args, true);
            case "vaultsee" -> vaults(sender, label, args);
            case "seen" -> seen(sender, label, args);
            case "check" -> check(sender, label, args);
            case "note" -> note(sender, label, args);
            case "tpto" -> teleport(sender, label, args, false);
            case "tphere" -> teleport(sender, label, args, true);
            case "clearinventory" -> clearInventory(sender, label, args);
            default -> false;
        };
    }

    /**
     * /clearinventory <jugador> [all|ender]: limpia el inventario COMPLETO (almacen,
     * armadura equipada, segunda mano y cursor); con "ender" solo el ender chest y con
     * "all" ambos. Corre en la region del objetivo (Folia) y queda en la auditoria.
     */
    private boolean clearInventory(CommandSender sender, String label, String[] args) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.clearinventory")) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.clearinv-usage",
                    "label", label));
            return true;
        }
        Player target = NickManager.online(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "inv";
        // Un modo desconocido (typo) NO limpia nada: mejor el usage que borrar de mas o
        // de menos por accidente.
        if (!List.of("inv", "all", "todo", "ender", "ec").contains(mode)) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.clearinv-usage",
                    "label", label));
            return true;
        }
        boolean ender = mode.equals("ender") || mode.equals("ec")
                || mode.equals("all") || mode.equals("todo");
        boolean inventory = !mode.equals("ender") && !mode.equals("ec");
        boolean canSeeVanished = StaffAccess.canSeeVanished(sender);
        String staffName = sender.getName();
        // Lo unico de la suite que no se deshace: pasa por la MISMA proteccion que kick/ban/
        // freeze (OP exento, chatalyst.bypass.clearinventory). Sin ella un jr.admin podia vaciar
        // el inventario de un OP o de un rango superior. Vaciar el propio sigue permitido.
        java.util.concurrent.CompletableFuture<Boolean> access = sender.equals(target)
                ? java.util.concurrent.CompletableFuture.completedFuture(true)
                : StaffAccess.canAffectAsync(sender, target, "chatalyst.bypass.clearinventory");
        access.whenComplete((allowed, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(allowed)) {
                feedback(sender, "staff-moderation.protected-target");
                return;
            }
            clearInventoryNow(sender, target, mode, inventory, ender, canSeeVanished, staffName);
        });
        return true;
    }

    private void clearInventoryNow(CommandSender sender, Player target, String mode,
                                   boolean inventory, boolean ender, boolean canSeeVanished,
                                   String staffName) {
        FoliaTasks.entity(module.plugin(), target, current -> {
            if (Vanish.isVanished(current) && !canSeeVanished) {
                feedback(sender, "common.player-offline");
                return;
            }
            String name = current.getName();
            if (inventory) {
                // Explicito slot a slot: el almacen, la armadura PUESTA, la segunda mano y
                // el item agarrado con el cursor. clear() a secas dejaba escapar partes
                // segun la version.
                current.getInventory().setStorageContents(
                        new org.bukkit.inventory.ItemStack[36]);
                current.getInventory().setArmorContents(
                        new org.bukkit.inventory.ItemStack[4]);
                current.getInventory().setItemInOffHand(null);
                current.setItemOnCursor(null);
                // Items olvidados en la rejilla de crafteo 2x2 (caerian al suelo al cerrar).
                if (current.getOpenInventory().getTopInventory().getType()
                        == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                    current.getOpenInventory().getTopInventory().clear();
                }
            }
            if (ender) {
                current.getEnderChest().clear();
            }
            // En modo staff lo que hay puesto es el KIT (el inventario real esta a salvo en el
            // snapshot): vaciarlo dejaba al staff sin herramientas y sin el boton de salir.
            if (inventory && module.staffMode() != null && module.staffMode().isActive(current)) {
                module.staffMode().giveKit(current);
            }
            current.updateInventory();
            current.sendMessage(Lang.get(current, "staff-moderation.clearinv-target",
                    "staff", MM.escape(staffName)));
            module.audit(sender, "clearinventory", name + " (" + mode + ")");
            String doneKey = inventory && ender ? "staff-moderation.clearinv-all-done"
                    : ender ? "staff-moderation.clearinv-ender-done"
                    : "staff-moderation.clearinv-done";
            feedback(sender, doneKey, "player", MM.escape(name));
        }, () -> feedback(sender, "common.player-offline"));
    }

    /** Respuesta segura al emisor: en su region si es jugador, global si es la consola. */
    private void feedback(CommandSender sender, String key, Object... vars) {
        if (sender instanceof Player p) {
            message(p, key, vars);
        } else {
            FoliaTasks.global(module.plugin(),
                    () -> sender.sendMessage(Lang.get(sender, key, vars)));
        }
    }

    private boolean inventory(CommandSender sender, String label, String[] args, boolean ender) {
        String permission = ender ? "chatalyst.command.echest" : "chatalyst.command.invsee";
        if (!StaffCommandSupport.require(sender, permission)) return true;
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, ender
                    ? "staff-moderation.echest-usage" : "staff-moderation.invsee-usage",
                    "label", label));
            return true;
        }
        Player target = NickManager.online(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        // Los menus/sesiones re-verifican visibilidad de vanish y leen el inventario en la
        // region del objetivo; si el viewer no puede verlo responden como si estuviera offline.
        InventoryEditSession.Kind kind = ender
                ? InventoryEditSession.Kind.ENDER_CHEST : InventoryEditSession.Kind.INVENTORY;
        if (StaffAccess.has(viewer, InventoryEditSession.editPermission(kind))) {
            // La sesion comprueba ella misma la proteccion del objetivo (y si esta protegido
            // abre la vista de solo lectura): es la misma puerta para el comando y el menu.
            module.inventoryEditor().open(viewer, target.getUniqueId(), kind);
            return true;
        }
        openReadOnly(viewer, target.getUniqueId(), ender);
        return true;
    }

    /**
     * /vaultsee &lt;jugador&gt; [numero]: las mochilas de PlayerVaultsX de cualquiera, conectado
     * o no (viven en disco, no en el jugador). Sin numero abre la lista; con numero, la copia de
     * solo lectura de esa mochila. Editar la real se hace desde la lista con el permiso .edit,
     * que delega en el propio PlayerVaultsX para que el guardado sea el suyo.
     */
    private boolean vaults(CommandSender sender, String label, String[] args) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.vaultsee")) return true;
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.vaults-usage", "label", label));
            return true;
        }
        if (!PlayerVaultsHook.isAvailable()) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.vaults-no-plugin"));
            return true;
        }
        OfflinePlayer target = resolveKnown(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        String name = target.getName() == null ? args[0] : target.getName();
        if (args.length < 2) {
            PlayerVaultsMenu.open(module, viewer, target.getUniqueId(), name, null);
            return true;
        }
        int number = vaultNumber(args[1]);
        if (number < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.vaults-bad-number",
                    "number", MM.escape(args[1])));
            return true;
        }
        VaultContentsMenu.open(module, viewer, target.getUniqueId(), name, number, null);
        return true;
    }

    /** "3" o "#3" a 3; cualquier otra cosa (o un numero no positivo) a -1. */
    static int vaultNumber(String raw) {
        String digits = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            int n = Integer.parseInt(digits);
            return n > 0 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void openReadOnly(Player viewer, UUID targetId, boolean ender) {
        Player target = org.bukkit.Bukkit.getPlayer(targetId);
        module.audit(viewer, ender ? "echest" : "invsee",
                target != null ? target.getName() : targetId.toString());
        if (ender) {
            TargetEnderChestMenu.open(module, viewer, targetId);
        } else {
            TargetInventoryMenu.open(module, viewer, targetId, 0);
        }
    }

    private boolean seen(CommandSender sender, String label, String[] args) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.seen")) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.seen-usage", "label", label));
            return true;
        }
        OfflinePlayer target = resolveKnown(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        String name = target.getName() == null ? args[0] : target.getName();
        Player online = target.getPlayer();
        if (online != null && (!Vanish.isVanished(online)
                || StaffAccess.canSeeVanished(sender))) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.seen-online",
                    "player", shown(sender, target, name)));
            return true;
        }
        // Un vanished que el viewer no puede ver se reporta con su hora de conexion, no con
        // getLastSeen(): para un jugador conectado eso seria "hace 0s" y delataria el vanish.
        long last = online != null ? target.getLastLogin() : target.getLastSeen();
        sender.sendMessage(Lang.get(sender, "staff-moderation.seen-offline",
                "player", shown(sender, target, name),
                "duration", TimeUtil.formatElapsed(System.currentTimeMillis() - last)));
        return true;
    }

    private boolean check(CommandSender sender, String label, String[] args) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.check")) return true;
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-usage", "label", label));
            return true;
        }
        OfflinePlayer target = resolveKnown(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? args[0] : target.getName();
        sender.sendMessage(Lang.get(sender, "staff-moderation.check-header",
                "player", shown(sender, target, name)));

        Player online = target.getPlayer();
        boolean visible = online != null && (!Vanish.isVanished(online)
                || StaffAccess.canSeeVanished(sender));
        if (visible) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-online"));
            if (Vanish.isVanished(online)) {
                sender.sendMessage(Lang.get(sender, "staff-moderation.check-vanished"));
            }
        } else {
            long last = online != null ? target.getLastLogin() : target.getLastSeen();
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-offline",
                    "duration", TimeUtil.formatElapsed(System.currentTimeMillis() - last)));
        }

        boolean flagged = false;
        BanRecord ban = module.bans().get(id);
        if (ban != null) {
            flagged = true;
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-banned",
                    "staff", MM.escape(ban.staff()),
                    "duration", StaffCommandSupport.remaining(sender, ban.until()),
                    "reason", MM.escape(module.formatter().plainReason(sender, ban.reason()))));
        }
        FreezeRecord freeze = module.freezes().get(id);
        if (freeze != null) {
            flagged = true;
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-frozen",
                    "staff", MM.escape(freeze.staff()),
                    "reason", MM.escape(module.formatter().plainReason(sender, freeze.reason()))));
        }
        IpBanManager.IpBan ipBan = module.ipBans().get(module.alts().lastHash(id));
        if (ipBan != null) {
            flagged = true;
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-ipbanned",
                    "staff", MM.escape(ipBan.staff()),
                    "duration", StaffCommandSupport.remaining(sender, ipBan.until())));
        }
        MuteManager mutes = module.plugin().getMuteManager();
        if (mutes != null && mutes.isMuted(id)) {
            flagged = true;
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-muted"));
        }
        WarningPoints warnings = module.plugin().getWarningPoints();
        int points = warnings == null ? 0 : warnings.get(id);
        if (points > 0) {
            flagged = true;
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-warnings",
                    "points", points));
        }
        if (!flagged) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-clean"));
        }
        List<HistoryLog.Entry> history = module.history().list(id);
        if (!history.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.check-history",
                    "count", history.size()));
            long now = System.currentTimeMillis();
            for (int i = Math.max(0, history.size() - 3); i < history.size(); i++) {
                HistoryLog.Entry entry = history.get(i);
                sender.sendMessage(Lang.get(sender, "staff-moderation.check-history-entry",
                        "type", historyType(sender, entry.type()),
                        "staff", MM.escape(entry.staff()),
                        "duration", TimeUtil.formatElapsed(now - entry.time()),
                        "reason", MM.escape(entry.detail())));
            }
        }
        if (StaffAccess.has(sender, "chatalyst.staff.alts")) {
            List<String> alts = module.alts().altsOf(id, name);
            if (!alts.isEmpty()) {
                sender.sendMessage(Lang.get(sender, "staff-moderation.check-alts",
                        "players", MM.escape(String.join(", ", alts))));
            }
        }
        return true;
    }

    private boolean teleport(CommandSender sender, String label, String[] args, boolean here) {
        if (!StaffCommandSupport.require(sender, "chatalyst.command.tp")) return true;
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get(sender, here
                    ? "staff-moderation.tphere-usage" : "staff-moderation.tpto-usage",
                    "label", label));
            return true;
        }
        Player target = NickManager.online(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        if (target.getUniqueId().equals(viewer.getUniqueId())) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.tp-self"));
            return true;
        }
        boolean canSeeVanished = StaffAccess.canSeeVanished(viewer);
        if (here) {
            bring(viewer, target, canSeeVanished);
        } else {
            goTo(viewer, target, canSeeVanished);
        }
        return true;
    }

    /** /tpto: el destino se lee en la region del objetivo y el viaje parte de la del viewer. */
    private void goTo(Player viewer, Player target, boolean canSeeVanished) {
        FoliaTasks.entity(module.plugin(), target, current -> {
            if (Vanish.isVanished(current) && !canSeeVanished) {
                message(viewer, "common.player-offline");
                return;
            }
            String name = current.getName();
            Location destination = current.getLocation().clone();
            FoliaTasks.entity(module.plugin(), viewer, traveler ->
                    traveler.teleportAsync(destination).thenAccept(success -> {
                        if (success) {
                            module.audit(traveler, "tpto", name);
                            message(viewer, "staff-moderation.tp-done", "player",
                                    MM.escape(name));
                        } else {
                            message(viewer, "staff-moderation.tp-failed");
                        }
                    }));
        }, () -> message(viewer, "common.player-offline"));
    }

    /** /tphere: el destino se lee en la region del viewer y viaja el objetivo. */
    private void bring(Player viewer, Player target, boolean canSeeVanished) {
        FoliaTasks.entity(module.plugin(), viewer, anchor -> {
            String staffName = anchor.getName();
            Location destination = anchor.getLocation().clone();
            FoliaTasks.entity(module.plugin(), target, current -> {
                if (Vanish.isVanished(current) && !canSeeVanished) {
                    message(viewer, "common.player-offline");
                    return;
                }
                String name = current.getName();
                current.teleportAsync(destination).thenAccept(success -> {
                    if (success) {
                        module.audit(viewer, "tphere", name);
                        message(viewer, "staff-moderation.tphere-done", "player",
                                MM.escape(name));
                        FoliaTasks.entity(module.plugin(), current, moved -> moved.sendMessage(
                                Lang.get(moved, "staff-moderation.tphere-target",
                                        "staff", MM.escape(staffName))));
                    } else {
                        message(viewer, "staff-moderation.tp-failed");
                    }
                });
            }, () -> message(viewer, "common.player-offline"));
        });
    }

    private void message(Player player, String key, Object... vars) {
        FoliaTasks.entity(module.plugin(), player,
                current -> current.sendMessage(Lang.get(current, key, vars)));
    }

    /** Nota de staff sobre un jugador (tambien offline): memoria compartida sin castigo. */
    private boolean note(CommandSender sender, String label, String[] args) {
        if (!StaffCommandSupport.require(sender, "chatalyst.staff.notes")) return true;
        if (args.length < 2) {
            sender.sendMessage(Lang.get(sender, "staff-moderation.note-usage", "label", label));
            return true;
        }
        OfflinePlayer target = resolveKnown(args[0]);
        if (target == null) return StaffCommandSupport.unknown(sender, args[0]);
        String text = StaffCommandSupport.join(args, 1);
        if (!module.notes().add(target.getUniqueId(), sender.getName(), text)) {
            sender.sendMessage(Lang.get(sender, "plugin.storage-unavailable"));
            return true;
        }
        // Nombre canonico resuelto (no el input crudo): el input puede ser un nick o
        // diferir en mayusculas, y la auditoria debe apuntar a la cuenta real.
        String shownName = target.getName() == null ? args[0] : target.getName();
        module.audit(sender, "note", shownName + " " + text);
        sender.sendMessage(Lang.get(sender, "staff-moderation.note-added",
                "player", MM.escape(shownName)));
        return true;
    }

    /**
     * El nombre que se le enseña a este staff, ya escapado: el de la cuenta solo con permiso
     * de /realname. "/check Alias" contestaba con la cabecera "Ficha de NombreReal", y bastaba
     * el permiso de /check para desenmascarar a cualquiera en /anonymous.
     */
    private static String shown(CommandSender sender, OfflinePlayer target, String accountName) {
        if (StaffAccess.has(sender, "chatalyst.command.realname")) return MM.escape(accountName);
        return NickManager.safeName(target.getUniqueId().toString(), accountName);
    }

    /** Tipos del historial localizados; un tipo futuro desconocido se muestra tal cual. */
    // Package-private: el menu de historial (PunishmentHistoryMenu) usa la misma traduccion.
    static String historyType(CommandSender viewer, String type) {
        return switch (type) {
            // Los levantamientos tambien: tenian su texto en lang, pero al faltar aqui el
            // historial los enseñaba en crudo ("unban") entre el resto ya traducido.
            case "kick", "ban", "tempban", "banip", "freeze", "freeze-ban",
                 "mute", "softmute", "ipban-alt", "escalation-ban", "warn",
                 "unban", "unmute", "unfreeze" ->
                    Lang.raw(viewer, "staff-moderation.history-type-" + type);
            default -> type;
        };
    }

    /** Solo jugadores que este servidor conoce; evita inventar perfiles offline nuevos. */
    private static OfflinePlayer resolveKnown(String input) {
        OfflinePlayer resolved = NickManager.resolveOffline(input);
        if (resolved == null) return null;
        return resolved.isOnline() || resolved.hasPlayedBefore() ? resolved : null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return StaffCommandSupport.prefix(args[0], StaffCommandSupport.onlineNames(sender));
        }
        if (args.length == 2 && command.getName().equalsIgnoreCase("clearinventory")) {
            return StaffCommandSupport.prefix(args[1], List.of("all", "ender"));
        }
        if (args.length == 2 && command.getName().equalsIgnoreCase("vaultsee")) {
            OfflinePlayer target = resolveKnown(args[0]);
            if (target == null || !PlayerVaultsHook.isAvailable()) return List.of();
            return StaffCommandSupport.prefix(args[1], PlayerVaultsHook
                    .vaultNumbers(target.getUniqueId()).stream().map(String::valueOf).toList());
        }
        return List.of();
    }
}
