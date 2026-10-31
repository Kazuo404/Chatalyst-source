package dev.chatalyst.economy;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /baltop  Opens the money ranking GUI. */
public final class BalanceTopCommand implements CommandExecutor {

    private final BalanceTop balanceTop;

    public BalanceTopCommand(BalanceTop balanceTop) {
        this.balanceTop = balanceTop;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!balanceTop.isReady()) {
            p.sendMessage(Lang.get(p, "baltop.unavailable"));
            return true;
        }
        Menu.open(p, new BalanceTopMenu(balanceTop));
        // Refresco inmediato bajo demanda: un cambio de saldo reciente se refleja en cuanto
        // termina el recalculo (async), sin esperar los minutos del ciclo periodico. Al
        // completar, se repinta el menu EN SITIO si el jugador sigue con el baltop abierto.
        var plugin = dev.chatalyst.ChatalystPlugin.get();
        balanceTop.requestRefresh(() -> {
            if (plugin == null) return;
            dev.chatalyst.util.FoliaTasks.entity(plugin, p, viewer -> {
                var top = viewer.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof dev.chatalyst.gui.MenuHolder holder
                        && holder.getMenu() instanceof BalanceTopMenu menu) {
                    menu.build(top, viewer);
                }
            });
        });
        return true;
    }
}
