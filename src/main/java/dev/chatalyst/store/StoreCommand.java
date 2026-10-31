package dev.chatalyst.store;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * La puerta de entrada del jugador a la tienda: /store abre el escaparate completo — rangos,
 * objetos y las secciones que el dueño haya definido — y desde ahi se recogen las compras.
 *
 * <p>Antes /store abria directamente la bandeja de pendientes, que es lo que un jugador
 * espera menos de un comando llamado "store": la tienda estaba en /ranks y este comando
 * enseñaba una lista vacia el 99% del tiempo. Si el escaparate esta apagado, /store sigue
 * abriendo la bandeja como siempre, para no dejar sin salida a quien solo venda objetos.
 */
public final class StoreCommand implements CommandExecutor, TabCompleter {

    private final StoreService store;
    private final dev.chatalyst.tebex.TebexShopService storefront;

    public StoreCommand(StoreService store, dev.chatalyst.tebex.TebexShopService storefront) {
        this.store = store;
        this.storefront = storefront;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "store.players-only"));
            return true;
        }
        if (!store.enabled()) {
            player.sendMessage(Lang.get(player, "store.disabled"));
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("claim") || sub.equals("reclamar")) {
            store.claimNow(player);
            return true;
        }
        // "list" stays for anyone who prefers the chat output (and for Bedrock quirks).
        if (sub.equals("list") || sub.equals("lista")) {
            list(player);
            return true;
        }
        if (sub.equals("history") || sub.equals("historial")) {
            dev.chatalyst.gui.Menu.open(player, new StoreHistoryMenu(store));
            return true;
        }
        // La bandeja sigue teniendo nombre propio para quien la quiera directa.
        if (sub.equals("pending") || sub.equals("pendientes") || sub.equals("mine")
                || sub.equals("mias")) {
            dev.chatalyst.gui.Menu.open(player, new StoreMenu(store));
            return true;
        }
        // El escaparate exige su propio permiso para interactuar: sin el, llevar alli seria
        // abrirle una tienda que no puede pulsar. Se queda con su bandeja, que si es suya.
        if (storefront != null && storefront.enabled()
                && player.hasPermission("chatalyst.command.ranks")) {
            storefront.open(player);
            return true;
        }
        dev.chatalyst.gui.Menu.open(player, new StoreMenu(store));
        return true;
    }

    private void list(Player player) {
        List<StoreClaims.Claim> pending = store.pendingFor(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendMessage(Lang.get(player, "store.nothing-pending"));
            return;
        }
        player.sendMessage(Lang.get(player, "store.pending-header",
                "count", pending.size()));
        for (StoreClaims.Claim claim : pending) {
            // Lo que se compro, no lo que diga hoy el catalogo.
            String name = store.contentsOf(claim)
                    .map(product -> MM.sanitizeInteraction(product.displayName()))
                    .orElse(MM.escape(claim.productId()));
            player.sendMessage(MM.full(Lang.raw(player, "store.pending-entry",
                    "product", name)));
        }
        player.sendMessage(Lang.get(player, "store.claim-hint"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        boolean spanish = Lang.isSpanish(sender);
        return java.util.stream.Stream.of(
                        spanish ? "reclamar" : "claim", spanish ? "lista" : "list",
                        spanish ? "pendientes" : "pending",
                        spanish ? "historial" : "history")
                .filter(value -> value.startsWith(prefix)).toList();
    }
}
