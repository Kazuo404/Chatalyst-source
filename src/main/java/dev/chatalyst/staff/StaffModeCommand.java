package dev.chatalyst.staff;

import dev.chatalyst.command.PlayerListMenu;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** /staff entry, dashboard and safe inventory-mode lifecycle. */
final class StaffModeCommand implements CommandExecutor, TabCompleter {

    private final StaffModerationModule module;

    StaffModeCommand(StaffModerationModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!StaffCommandSupport.available(module, sender)
                || !StaffCommandSupport.require(sender, "chatalyst.command.staff")) return true;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "off", "salir" -> disable(player);
            case "on", "entrar" -> enable(player);
            case "players", "jugadores" -> PlayerListMenu.openStaff(module.plugin(), player, 0);
            // /staff (sin args) SIEMPRE abre el dashboard clickeable. Antes, la primera vez
            // entraba en modo staff (cambiaba el inventario por el kit) sin abrir GUI, lo que
            // confundia: "escribo /staff y no llego al dashboard". Entrar/salir del modo se
            // hace ahora desde el boton de modo del dashboard, o con /staff on|off.
            case "menu", "" -> StaffDashboardMenu.open(module, player);
            default -> player.sendMessage(Lang.get(player, "staff-moderation.staff-usage"));
        }
        return true;
    }

    private void enable(Player player) {
        // "Ya estas en modo staff" solo cuando es verdad. Si enter() falla por el
        // almacenamiento, ya avisa el mismo de ello, y añadir "ya estas dentro" encima
        // mandaba al staff a buscar un modo staff que no estaba activo.
        if (module.staffMode().isActive(player)) {
            player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-already"));
            return;
        }
        if (module.staffMode().enter(player)) {
            player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-on"));
        }
    }

    private void disable(Player player) {
        if (module.staffMode().exit(player)) {
            player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-off"));
        } else {
            player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-not-active"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1
                ? StaffCommandSupport.prefix(args[0], List.of("menu", "on", "off", "players"))
                : List.of();
    }
}
