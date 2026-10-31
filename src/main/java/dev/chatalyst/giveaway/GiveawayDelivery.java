package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.store.StoreProduct;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.InventoryUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handing the prize over.
 *
 * <p>A winner is drawn from the players who are online and watching, so delivery is normally
 * immediate. It can still miss by a second — a crash, a kick, someone closing the game the
 * instant their head stops in the window — and a prize announced to the whole server that
 * nobody received is worse than one that arrives late. So a missed delivery is held and retried
 * when that player next joins.
 *
 * <p>The hold lives in memory only, and says so out loud: if the server stops before the winner
 * comes back, the console carries the exact command staff can run to hand it over by hand. A
 * durable queue already exists for goods that were PAID for ({@code StoreClaims}); borrowing it
 * for a free prize would mean writing giveaway entries into the purchase ledger, where every
 * other row is a receipt.
 */
public final class GiveawayDelivery implements Listener {

    private final ChatalystPlugin plugin;
    private final GiveawayRanks ranks;
    // Una LISTA por ganador: con un solo premio guardado, ganar dos veces estando fuera hacia
    // que el segundo pisara al primero y este no se entregara nunca.
    //
    // Y se guarda el PREMIO, no su id. La cantidad de un premio de dinero y la duracion escrita
    // al lanzarlo viajan dentro del premio; volviendo a buscarlo por id en el catalogo salia
    // sin ninguna de las dos, y el ganador que se caia en el ultimo segundo recibia al volver
    // "no se pudo entregar" (dinero: cero) o un rango con la duracion global en vez de la suya.
    private final Map<UUID, java.util.List<GiveawayPrize>> pending = new ConcurrentHashMap<>();

    GiveawayDelivery(ChatalystPlugin plugin, GiveawayRanks ranks) {
        this.plugin = plugin;
        this.ranks = ranks;
    }

    /** Delivers now if the winner is still online, and otherwise holds the prize for them. */
    void deliver(UUID winner, GiveawayPrize prize) {
        Player online = plugin.getServer().getPlayer(winner);
        if (online == null) {
            hold(winner, prize);
            return;
        }
        FoliaTasks.entity(plugin, online, player -> handOver(player, prize),
                () -> hold(winner, prize));
    }

    private void hold(UUID winner, GiveawayPrize prize) {
        pending.compute(winner, (id, held) -> {
            java.util.List<GiveawayPrize> next = held == null
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(held);
            next.add(prize);
            return next;
        });
        plugin.getLogger().warning("Giveaway winner " + winner + " went offline before the "
                + "prize '" + prize.id() + "' could be handed over. It will be delivered when "
                + "they rejoin; if the server restarts first, run: /"
                + plugin.getConfig().getString("Giveaway.Command", "sorteo")
                + " give " + winner + " " + prize.id() + amountArgument(prize));
    }

    /**
     * La cantidad de un premio de dinero, como cuarto argumento de {@code /sorteo give}: sin
     * ella el comando que la consola recomienda no podria pagar nada.
     */
    static String amountArgument(GiveawayPrize prize) {
        GiveawayPrize.MoneyBand band = prize.moneyBand();
        if (band == null) return "";
        String low = String.format(Locale.ROOT, "%.0f", band.min());
        return band.isRange()
                ? " " + low + "-" + String.format(Locale.ROOT, "%.0f", band.max()) : " " + low;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        java.util.List<GiveawayPrize> held = pending.remove(event.getPlayer().getUniqueId());
        if (held == null) return;
        Player player = event.getPlayer();
        for (GiveawayPrize prize : held) {
            // Late enough that other join work (kits, resource packs, the store's own
            // deliveries) is not competing for the same inventory slots.
            try {
                // null = el jugador ya se habia ido cuando se pidio: nadie llama al aviso de
                // retirada, asi que el premio se vuelve a guardar aqui.
                if (player.getScheduler().runDelayed(plugin, task -> handOver(player, prize),
                        () -> hold(player.getUniqueId(), prize), 40L) == null) {
                    hold(player.getUniqueId(), prize);
                }
            } catch (RuntimeException rejected) {
                hold(player.getUniqueId(), prize);
            }
        }
    }

    /** Why a prize cannot be handed over, for the admin who is about to start the draw. */
    String problem(GiveawayPrize prize) {
        return switch (prize.kind()) {
            case STORE -> storeProduct(prize) == null
                    ? "Store_Catalog has no product '" + prize.storeProduct() + "'" : null;
            case RANK -> ranks.problem(prize);
            case MONEY -> {
                if (!VaultHook.hasEconomy()) {
                    yield "no Vault economy is installed, so money cannot be paid";
                }
                // Un premio de dinero puede no traer cifra del fichero a proposito: se escribe
                // al lanzarlo. Lo que no puede es EMPEZAR sin ella, o la rueda giraria delante
                // del server entero para pagar cero.
                yield prize.moneyBand() == null
                        ? "no amount has been set for this prize yet" : null;
            }
            case PERMISSION -> dev.chatalyst.hook.LuckPermsHook.isAvailable()
                    ? null : "LuckPerms is not installed, so a permission cannot be granted";
            case ITEMS, COMMANDS -> null;
            case CUSTOM -> prize.customData()
                    .map(c -> c.deliversSomething() ? null
                            : "this custom prize has no items and no commands yet")
                    .orElse("this custom prize no longer exists");
        };
    }

    /**
     * True cuando lanzar esto giraria la rueda para no dar NADA.
     *
     * <p>Distinto de {@link #problem}, que es un aviso a proposito: un dueño a medio configurar
     * prefiere una advertencia a que el sorteo se le niegue. Pero eso vale para un premio que
     * entrega ALGO defectuoso, no para uno que no entrega nada. Un sorteo se apodera de la
     * pantalla de todo el server y nombra a un ganador; hacerlo para no darle nada no es una
     * advertencia que el dueño pueda atender a tiempo, es un ridiculo publico ya consumado.
     *
     * <p>Se limita al dinero porque es el unico donde se puede afirmar con certeza. Un rango o
     * un producto de tienda que hoy fallan pueden seguir entregandose por el ledger o por los
     * Commands extra del propio premio, y adivinarlo seria peor que avisar.
     */
    boolean deliversNothing(GiveawayPrize prize) {
        if (prize.kind() != GiveawayPrize.Kind.MONEY) return false;
        StoreProduct goods = payable(prize);
        if (goods == null || goods.isEmpty()) return true;
        // Dinero sin economia que lo pague: solo se salva si el premio lleva algo mas encima.
        return !VaultHook.hasEconomy()
                && goods.items().isEmpty() && goods.commands().isEmpty();
    }

    // ---- the actual handover ----------------------------------------------------------

    /**
     * Hands the prize over and then says what actually happened.
     *
     * <p>The distinction matters more than it looks. Every step here can come up empty for a
     * reason that is nobody's fault at the wheel — a money prize with no Vault economy behind
     * it, a store id that was renamed, a rank whose group was deleted — and the old version
     * congratulated the winner regardless. The server then watched somebody be announced,
     * thanked, and given nothing, with no trace of it anywhere except a line in the console
     * that no one was reading. So a delivery that handed over nothing tells the winner so, and
     * shouts at the staff instead of at the log.
     */
    private void handOver(Player player, GiveawayPrize prize) {
        boolean handedOver = false;
        java.util.concurrent.CompletableFuture<Boolean> rank =
                java.util.concurrent.CompletableFuture.completedFuture(false);
        switch (prize.kind()) {
            case STORE -> {
                StoreProduct product = storeProduct(prize);
                if (product == null) {
                    plugin.getLogger().warning("Giveaway prize '" + prize.id() + "' points at "
                            + "the store product '" + prize.storeProduct() + "', which is not "
                            + "in Store_Catalog. Nothing was handed to " + player.getName());
                } else {
                    handedOver = give(player, product);
                }
            }
            case RANK -> rank = grantRank(player, prize);
            case CUSTOM -> handedOver = giveCustom(player, prize);
            case ITEMS, COMMANDS, MONEY, PERMISSION -> {
                // handled below: los permisos, como los comandos, valen para TODOS los tipos.
            }
        }
        // Para cualquier tipo, igual que los Commands extra: un kit puede venir con vuelo, y un
        // rango con una ventaja suelta que no merece un grupo propio.
        if (grantPermissions(player, prize)) handedOver = true;
        // Runs for EVERY kind: a prize may carry extra commands on top of what its type does.
        StoreProduct goods = payable(prize);
        if (goods != null && give(player, goods)) handedOver = true;

        // El rango se confirma despues: el aviso espera a saber si quedo registrado de verdad.
        // Antes se daba por entregado en cuanto salia el comando, y un rechazo de Tebex dejaba
        // al ganador felicitado delante de todos y sin nada.
        boolean others = handedOver;
        rank.exceptionally(failure -> {
            plugin.getLogger().warning("Giveaway rank delivery for " + player.getName()
                    + " failed: " + failure);
            return false;
        }).thenAccept(granted -> report(player, prize, others || Boolean.TRUE.equals(granted)));
    }

    /**
     * Premio hecho en el juego: cada objeto tal cual se guardo (lo que no cabe cae a sus pies)
     * y despues sus comandos por consola con {player} y {uuid}.
     */
    private boolean giveCustom(Player player, GiveawayPrize prize) {
        GiveawayCustomPrizes.Custom custom = prize.customData().orElse(null);
        if (custom == null) {
            plugin.getLogger().warning("Giveaway custom prize '" + prize.id() + "' no longer "
                    + "exists. Nothing was handed to " + player.getName());
            return false;
        }
        boolean any = false;
        for (org.bukkit.inventory.ItemStack item : custom.itemStacks()) {
            for (org.bukkit.inventory.ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
            any = true;
        }
        for (String command : custom.commands) {
            String line = command.replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());
            if (line.startsWith("/")) line = line.substring(1);
            final String run = line;
            FoliaTasks.global(plugin, () -> plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(), run));
            any = true;
        }
        return any;
    }

    private void report(Player player, GiveawayPrize prize, boolean handedOver) {
        if (handedOver) {
            player.sendMessage(Lang.get(player, "giveaway.prize-delivered"));
            return;
        }
        player.sendMessage(Lang.get(player, "giveaway.prize-failed"));
        String failure = "Giveaway prize '" + prize.id() + "' handed NOTHING to "
                + player.getName() + " — they were announced as the winner in front of the"
                + " server and paid nothing. Check the prize with /sorteo list.";
        plugin.getLogger().severe(failure);
        if (plugin.getStaffNotifier() != null) plugin.getStaffNotifier().alert(failure);
    }

    /**
     * Lo que se entrega de verdad. Para un premio de dinero con banda, la cifra se tira AQUI
     * — una vez, en el momento de pagar — y no al construir el catalogo: el catalogo se
     * reconstruye en cada apertura de menu, asi que tirar el dado ahi haria que el numero
     * cambiara solo por mirar la lista, y el que se anuncia no seria el que se paga.
     *
     * <p>Los Commands extra del premio se conservan: cambia la cantidad, no el resto.
     */
    /**
     * Concede los permisos del premio, con la duracion del propio premio.
     *
     * <p>Se cuenta como entrega para el aviso de "no se entrego nada": una semana de vuelo es
     * un premio de verdad, y anunciarlo como fallo porque no cayo ningun objeto seria mentir en
     * la direccion contraria.
     *
     * <p>La concesion es asincrona y no se espera. Fallar aqui no puede retrasar el resto de la
     * entrega, pero tampoco puede pasar en silencio: un permiso que no llega es un premio que no
     * llega, asi que se avisa por consola con el nombre de quien se quedo sin el.
     */
    private boolean grantPermissions(Player player, GiveawayPrize prize) {
        java.util.List<String> nodes = prize.permissions(plugin.getConfig());
        if (nodes.isEmpty()) return false;
        if (!dev.chatalyst.hook.LuckPermsHook.isAvailable()) {
            plugin.getLogger().warning("Giveaway prize '" + prize.id() + "' grants "
                    + nodes.size() + " permission(s) but LuckPerms is not installed, so "
                    + player.getName() + " did not get them.");
            return false;
        }
        long expiry = expiryFor(prize);
        java.util.UUID id = player.getUniqueId();
        String name = player.getName();
        for (String node : nodes) {
            dev.chatalyst.hook.LuckPermsHook.grantPermissionAsync(id, node, expiry)
                    .exceptionally(failure -> {
                        plugin.getLogger().warning("Giveaway prize '" + prize.id() + "' could"
                                + " not grant '" + node + "' to " + name + ": " + failure);
                        return false;
                    });
        }
        return true;
    }

    /**
     * Cuando caduca lo que concede este premio: lo que diga el premio, y si no dice nada, el
     * ajuste global de {@code Giveaway.Ranks.Duration}. Un solo sitio para las dos cosas, para
     * que el rango y los permisos del MISMO premio no puedan durar distinto.
     */
    private long expiryFor(GiveawayPrize prize) {
        long own = prize.duration(plugin.getConfig());
        // Sin duracion propia: la del rango puesta en el juego (/sorteo rango), y si no, la global.
        if (own == 0L) return ranks.expiryMillis(prize.rank() == null ? null : ranks.group(prize));
        if (own == dev.chatalyst.util.TimeUtil.PERMANENT) return 0L;
        long deadline = dev.chatalyst.util.TimeUtil.deadline(System.currentTimeMillis(), own);
        return deadline == dev.chatalyst.util.TimeUtil.PERMANENT ? 0L : deadline;
    }

    private StoreProduct payable(GiveawayPrize prize) {
        StoreProduct goods = prize.goods();
        if (prize.kind() != GiveawayPrize.Kind.MONEY) return goods;
        // Se reconstruye SIEMPRE, no solo cuando hay cantidad escrita. Asi un Money o un Amount
        // que alguien dejara en el fichero no paga por la puerta de atras: para un sorteo, lo
        // que se paga es lo que se escribio al lanzarlo, y nada mas.
        double rolled = prize.rollMoney();
        if (goods == null) {
            return rolled <= 0d ? null : new StoreProduct(prize.id(), prize.id(),
                    java.util.List.of(), java.util.List.of(), rolled);
        }
        return new StoreProduct(goods.productId(), goods.displayName(), goods.items(),
                goods.commands(), rolled);
    }

    /** @return true when at least one thing genuinely reached the player */
    private boolean give(Player player, StoreProduct product) {
        boolean anything = false;
        for (StoreProduct.ItemSpec spec : product.items()) {
            InventoryUtil.giveOrDrop(player, spec.build());
            anything = true;
        }
        if (product.money() > 0d) {
            if (!VaultHook.hasEconomy()) {
                plugin.getLogger().warning("Giveaway prize '" + product.productId() + "' pays "
                        + product.money() + " but no Vault economy is installed.");
            } else if (VaultHook.deposit(player, product.money())) {
                anything = true;
            } else {
                // Vault said no: a frozen account, a plugin refusing offline deposits. Silence
                // here is what turns "I won money" into "I won nothing".
                plugin.getLogger().warning("The economy refused a giveaway deposit of "
                        + product.money() + " to " + player.getName() + ".");
            }
        }
        for (String command : product.commands()) {
            dispatch(command
                    .replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString()));
            // A dispatched command is best-effort by nature: whatever it drives is another
            // plugin's business, and the prize did leave here.
            anything = true;
        }
        return anything;
    }

    /**
     * Two doors, and the right one depends on whether this server SELLS the rank.
     *
     * <p>With Tebex on and the rank in the catalogue, the grant goes through the purchase
     * ledger — the same door a paying customer uses — so the group, the Discord role and the
     * expiry are all derived by the reconciler. Skipping it there would not merely be
     * inconsistent: the reconciler repairs any group it has no receipt for, so a hand-written
     * group would be stripped back off the winner within the minute.
     *
     * <p>Without Tebex there is no ledger to write to, which is what a server looks like while
     * it is still being built locally. The group goes straight into LuckPerms, for the duration
     * in {@code Giveaway.Ranks.Duration}.
     */
    private java.util.concurrent.CompletableFuture<Boolean> grantRank(Player player,
                                                                    GiveawayPrize prize) {
        if (ranks.usesTebex(prize)) {
            String transaction = transactionFor(prize.id(), System.currentTimeMillis());
            var tebex = plugin.getTebexRanks();
            if (tebex == null) {
                plugin.getLogger().warning("Giveaway prize '" + prize.id() + "' is a Tebex"
                        + " rank but Tebex delivery is not loaded; " + player.getName()
                        + " was given no rank.");
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            return tebex.grantFor(player.getUniqueId(), prize.rank(), transaction);
        }
        String group = ranks.group(prize);
        if (group == null || group.isBlank()) {
            plugin.getLogger().warning("Giveaway prize '" + prize.id() + "' names no LuckPerms"
                    + " group, so " + player.getName() + " was given no rank.");
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        // La duracion del PREMIO, y solo si no dice nada, la global. El rango y los
        // permisos del mismo premio salen de aqui, asi que no pueden durar distinto.
        long expiry = expiryFor(prize);
        return LuckPermsHook.grantGroupAsync(player.getUniqueId(), group, expiry)
                .thenApply(granted -> {
                    if (Boolean.TRUE.equals(granted)) return true;
                    plugin.getLogger().warning("LuckPerms refused the giveaway rank '" + group
                            + "' for " + player.getName() + " — check the group exists.");
                    return false;
                })
                .exceptionally(failure -> {
                    plugin.getLogger().warning("Could not grant the giveaway rank '" + group
                            + "' to " + player.getName() + ": " + failure.getMessage());
                    return false;
                });
    }

    /**
     * El identificador de pago del premio de rango.
     *
     * <p>El id del premio admite {@code +} y {@code _} ("vip+" existe de verdad), pero
     * {@code /cht tebex grant} solo acepta {@code [A-Za-z0-9._:@-]}: con el id crudo la entrega
     * se rechazaba DESPUES de haber anunciado al ganador. Se traduce aqui a algo que pase
     * siempre, y se recorta para no salirse de los 128 caracteres.
     */
    static String transactionFor(String prizeId, long now) {
        String safe = (prizeId == null ? "" : prizeId)
                .replace("+", "plus")
                .replaceAll("[^A-Za-z0-9.:@-]", ".");
        if (safe.length() > 64) safe = safe.substring(0, 64);
        return "giveaway-" + safe + "-" + Long.toHexString(now);
    }

    private StoreProduct storeProduct(GiveawayPrize prize) {
        return prize.storeProduct() == null ? null
                : StoreProduct.resolve(plugin.getConfig(), prize.storeProduct()).orElse(null);
    }

    private void dispatch(String command) {
        FoliaTasks.global(plugin, () -> {
            try {
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), command);
            } catch (Throwable failure) {
                plugin.getLogger().warning("Giveaway command failed: /" + command
                        + " (" + failure.getMessage() + ")");
            }
        });
    }
}
