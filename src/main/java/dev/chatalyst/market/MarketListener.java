package dev.chatalyst.market;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ganchos de sesion del mercado y el trade: al desconectarse una parte, sus negociaciones y
 * trades en curso se cancelan (el deposito vuelve a su dueño; la otra parte se entera); al
 * entrar, si hay items esperando en el alijo, se avisa con el comando de recogida.
 */
public final class MarketListener implements Listener {

    private final JavaPlugin plugin;
    private final MarketManager manager;
    private final TradeManager trades;

    public MarketListener(JavaPlugin plugin, MarketManager manager, TradeManager trades) {
        this.plugin = plugin;
        this.manager = manager;
        this.trades = trades;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.abandonNegotiations(event.getPlayer().getUniqueId());
        trades.abandon(event.getPlayer().getUniqueId());
    }

    /**
     * Cerrar la GUI del trade (Esc, abrir otro menu...) CANCELA el trade y devuelve los
     * depositos. Sin esto los items quedaban retenidos en un trade vivo sin ninguna via de
     * reabrir el menu ni recuperarlos hasta desconectarse. Es seguro frente a carreras: si
     * el cierre viene de una ejecucion o cancelacion ya en curso, tryClose() hace el no-op.
     */
    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof dev.chatalyst.gui.MenuHolder holder)) return;
        DirectTrade trade = tradeShownBy(holder.getMenu());
        if (trade == null) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        // Pasar del contrato a la tesoreria (y volver) tambien CIERRA el inventario, con
        // motivo OPEN_NEW. Cancelar ahi hacia que el boton de dinero anulara SIEMPRE el
        // trade: nadie podia ofrecer dinero. Al cambiar de menu se mira en el tick
        // siguiente que se ha abierto: si sigue siendo este trade, no se cancela nada.
        if (event.getReason() == org.bukkit.event.inventory.InventoryCloseEvent.Reason.OPEN_NEW) {
            p.getScheduler().run(plugin, task -> {
                var top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof dev.chatalyst.gui.MenuHolder next
                        && tradeShownBy(next.getMenu()) == trade) return;
                trades.cancel(p, trade);
            }, null); // retirado = se desconecto: onQuit ya abandona el trade
            return;
        }
        // Esc tambien en la tesoreria: antes dejaba el trade vivo, sin GUI para reabrirlo y
        // con los objetos retenidos en deposito hasta desconectarse.
        trades.cancel(p, trade);
    }

    /** El trade que muestra un menu (el contrato o su tesoreria), o null. */
    static DirectTrade tradeShownBy(dev.chatalyst.gui.Menu menu) {
        if (menu instanceof TradeMenu tradeMenu) return tradeMenu.trade();
        if (menu instanceof TradeMoneyMenu moneyMenu) return moneyMenu.trade();
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        manager.recoverPlayerEscrow(p);
        int pending = manager.claimCount(p.getUniqueId());
        if (pending <= 0) return;
        // Con retardo, para que el aviso no se pierda entre los mensajes de bienvenida.
        FoliaTasks.globalLater(plugin, () -> FoliaTasks.entity(plugin, p,
                v -> v.sendMessage(Lang.get(v, "market.claim-pending", "count", pending))), 60L);
    }
}
