package dev.chatalyst.store;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.InventoryUtil;
import dev.chatalyst.util.MM;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Delivery of one-shot goods bought in the store.
 *
 * <p>The guarantee is <b>at most once</b>: the claim is written before anything is given and
 * consumed the moment it is handed over. Nothing is ever dropped on the floor to force a
 * delivery through — if there is no room the claim simply stays pending, so goods cannot be
 * lost to a full inventory, a lava pit, or someone standing next to the buyer.
 */
public final class StoreService implements Listener {

    private final ChatalystPlugin plugin;
    private final StoreClaims claims;
    /** Tebex rank receipts, read only for the purchase history (TebexRankService writes them). */
    private final dev.chatalyst.db.Store rankReceipts;

    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask retry;

    public StoreService(ChatalystPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.claims = new StoreClaims(plugin, storage);
        this.rankReceipts = storage.store("tebex_rank_receipts");
    }

    // ---- lifecycle ----------------------------------------------------------

    public synchronized void start() {
        if (retry != null) return;
        long interval = Math.max(15L, Math.min(3_600L,
                plugin.getConfig().getLong("Store.Retry_Interval_Seconds", 60L)));
        try {
            retry = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                    ignored -> retryPending(), interval, interval, TimeUnit.SECONDS);
        } catch (RuntimeException rejected) {
            retry = null;
        }
    }

    public synchronized void stop() {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask current = retry;
        retry = null;
        if (current != null) current.cancel();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("Store.Enabled", false);
    }

    public int pendingCount() {
        return claims.pendingCount();
    }

    public List<String> configuredProductIds() {
        return StoreProduct.configuredIds(plugin.getConfig());
    }

    // ---- the Tebex endpoint --------------------------------------------------

    /** Handles the arguments after {@code /chatalyst store}. */
    public void handle(CommandSender sender, String[] args) {
        // Quién puede hablar se decide ANTES que nada: a partir de aquí un rechazo grita en el
        // log y avisa al staff, y eso no puede provocarlo cualquiera escribiendo el comando.
        if (plugin.getConfig().getBoolean("Store.Console_Only", true)
                && !(sender instanceof ConsoleCommandSender)) {
            send(sender, "<#FF4D6D>Por seguridad, este comando solamente puede ejecutarlo "
                    + "la consola/Tebex.");
            return;
        }
        if (!(sender instanceof ConsoleCommandSender)
                && !sender.hasPermission("chatalyst.admin.store")) {
            send(sender, "<#FF4D6D>No tienes permiso.");
            return;
        }

        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (!enabled()) {
            String disabled = "<#FF9F1C>La tienda de objetos está desactivada "
                    + "(Store.Enabled: false).";
            // Una entrega con la tienda apagada es una compra cobrada que nadie va a recibir.
            if (action.equals("give") || action.equals("dar")) {
                reject(sender, disabled, describe(args) + ": Store.Enabled es false");
            } else {
                send(sender, disabled);
            }
            return;
        }
        if (action.equals("status")) {
            List<String> products = configuredProductIds();
            send(sender, "<#3DFFA8>Tienda de objetos activa. <#C0B3D9>Productos: <#FBF2FF>"
                    + (products.isEmpty() ? "ninguno" : String.join(", ", products))
                    + "<#C0B3D9>, entregas pendientes: <#FBF2FF>" + claims.pendingCount());
            return;
        }
        if (!action.equals("give") && !action.equals("dar")) {
            send(sender, "<#C0B3D9>Entrega: <#FBF2FF>/chatalyst store give "
                    + "{id} <product_id> {transaction}");
            return;
        }
        if (args.length < 4) {
            send(sender, "<#C0B3D9>Entrega: <#FBF2FF>/chatalyst store give "
                    + "{id} <product_id> {transaction}");
            return;
        }

        UUID target = dev.chatalyst.util.BuyerId.resolve(plugin.getServer(), args[1]);
        if (target == null) {
            reject(sender, "<#FF4D6D>No se pudo identificar al jugador <#FBF2FF>"
                            + MM.escape(args[1]) + "<#FF4D6D>. En Tebex usa la variable "
                            + "<#FBF2FF>{id}<#FF4D6D>.",
                    "\"" + args[1] + "\" no es un UUID ni una cuenta que este servidor conozca");
            return;
        }
        Optional<StoreProduct> resolved =
                StoreProduct.resolve(plugin.getConfig(), args[2]);
        if (resolved.isEmpty()) {
            reject(sender, "<#FF4D6D>Producto desconocido o vacío: <#FBF2FF>"
                            + MM.escape(args[2]) + "<#FF4D6D>. Decláralo en Store_Catalog.",
                    "el producto \"" + args[2] + "\" no existe en Store_Catalog");
            return;
        }
        StoreProduct product = resolved.get();
        String transaction = args[3];

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            StoreClaims.Reservation reservation =
                    claims.reserve(target, product.productId(), transaction,
                            product.snapshot());
            switch (reservation) {
                case ALREADY_DELIVERED -> send(sender,
                        "<#3DFFA8>Entrega ya realizada; no se duplicó " + product.productId() + ".");
                case ALREADY_PENDING -> send(sender,
                        "<#FF9F1C>Esa compra ya está registrada y pendiente de entrega.");
                case STORAGE_FAILED -> reject(sender, "<#FF4D6D>No se pudo registrar la compra; "
                                + "no se entregó nada. Revisa el almacenamiento y reintenta.",
                        product.productId() + " para " + target + " (" + transaction
                                + "): el almacenamiento no aceptó la reserva");
                case INVALID -> reject(sender,
                        "<#FF4D6D>Transacción inválida. En Tebex usa <#FBF2FF>{transaction}.",
                        product.productId() + " para " + target + ": el identificador de pago \""
                                + transaction + "\" no tiene una forma válida");
                case CREATED -> {
                    send(sender, "<#3DFFA8>Compra registrada: <#FBF2FF>" + product.productId()
                            + "<#3DFFA8> para " + target + ".");
                    if (plugin.getConfig().getBoolean("Store.Notify_Discord", true)
                            && plugin.getStaffNotifier() != null) {
                        plugin.getStaffNotifier().alert("Store: " + product.productId()
                                + " purchased by " + target + " (" + transaction + ")");
                    }
                    deliverPending(target);
                }
            }
        });
    }

    // ---- delivery ------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) return;
        UUID owner = event.getPlayer().getUniqueId();
        long delay = Math.max(1L, Math.min(1_200L,
                plugin.getConfig().getLong("Store.Join_Delay_Ticks", 40L)));
        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                ignored -> deliverPending(owner), delay * 50L, TimeUnit.MILLISECONDS);
    }

    /**
     * Owes {@code productId} to {@code owner} under {@code transaction}, exactly like a
     * purchase but started from inside the plugin (a reward, a gift). The same
     * at-most-once ledger applies: the same transaction can never be owed twice, and the
     * goods wait for the player if they are offline or their inventory is full.
     *
     * <p>Blocks on storage: call it off the server thread.
     *
     * @return how the reservation went, or {@code INVALID} when the store is disabled or the
     *         product is not in {@code Store_Catalog}
     */
    public StoreClaims.Reservation grant(UUID owner, String productId, String transaction) {
        if (!enabled() || owner == null) return StoreClaims.Reservation.INVALID;
        Optional<StoreProduct> resolved = StoreProduct.resolve(plugin.getConfig(), productId);
        if (resolved.isEmpty()) return StoreClaims.Reservation.INVALID;
        StoreProduct product = resolved.get();
        StoreClaims.Reservation reservation =
                claims.reserve(owner, product.productId(), transaction, product.snapshot());
        if (reservation == StoreClaims.Reservation.CREATED) deliverPending(owner);
        return reservation;
    }

    /** Retries everyone still owed something: a full inventory, an offline buyer. */
    private void retryPending() {
        if (!enabled()) return;
        for (UUID owner : claims.ownersWithPending()) deliverPending(owner);
    }

    /** Hands over everything owed to this player that can be handed over right now. */
    public void deliverPending(UUID owner) {
        if (!enabled()) return;
        boolean manual = "MANUAL".equalsIgnoreCase(
                plugin.getConfig().getString("Store.Delivery", "AUTO"));
        if (manual) {
            notifyWaiting(owner);
            return;
        }
        claim(owner, false);
    }

    /** {@code /store claim}: the player asking for it always overrides MANUAL mode. */
    public void claimNow(Player player) {
        claim(player.getUniqueId(), true);
    }

    /**
     * Hands over one specific purchase, for the per-card click in the store menu.
     *
     * <p>A cart is one payment with several products, so the transaction alone does not name
     * a card: matching on it handed over the first product of the cart whichever was clicked.
     */
    public void claimOne(Player player, String transaction, String productId) {
        UUID owner = player.getUniqueId();
        plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
            Optional<StoreClaims.Claim> picked =
                    pick(claims.pendingFor(owner), transaction, productId);
            if (picked.isEmpty()) {
                tell(owner, "store.nothing-pending");
                return;
            }
            Optional<StoreProduct> resolved = contentsOf(picked.get());
            if (resolved.isEmpty()) {
                tell(owner, "store.product-gone");
                return;
            }
            deliverOne(owner, picked.get(), resolved.get(), true);
        });
    }

    /** The pending claim behind one card: same payment AND same product. */
    static Optional<StoreClaims.Claim> pick(List<StoreClaims.Claim> pending, String transaction,
                                            String productId) {
        for (StoreClaims.Claim claim : pending) {
            if (claim.transaction().equals(transaction) && claim.productId().equals(productId)) {
                return Optional.of(claim);
            }
        }
        return Optional.empty();
    }

    /**
     * Hands over ONE purchase, then comes back for the next.
     *
     * <p>Delivering the whole queue in a loop meant every claim measured the same free slots
     * before any of them had actually landed, so three one-item purchases into two free slots
     * all passed the check, all were consumed, and the last ended up on the floor. Serialising
     * them makes each check see the inventory the previous delivery left behind.
     */
    private void claim(UUID owner, boolean requested) {
        List<StoreClaims.Claim> pending = claims.pendingFor(owner);
        if (pending.isEmpty()) {
            if (requested) tell(owner, "store.nothing-pending");
            return;
        }
        for (StoreClaims.Claim claim : pending) {
            Optional<StoreProduct> resolved = contentsOf(claim);
            if (resolved.isEmpty()) {
                // The product was removed or renamed after the sale and the claim predates
                // snapshots. Keep it: an owner restoring the entry must not refund by hand.
                plugin.getLogger().warning("Store claim " + claim.transaction()
                        + " references unknown product '" + claim.productId()
                        + "'; it stays pending until the catalog defines it again.");
                continue;
            }
            deliverOne(owner, claim, resolved.get(), requested);
            return; // the next one goes out after this delivery has really happened
        }
    }

    /**
     * What this purchase actually contains: the snapshot taken when it was bought, falling
     * back to the catalog only for claims written before snapshots existed.
     */
    Optional<StoreProduct> contentsOf(StoreClaims.Claim claim) {
        Optional<StoreProduct> frozen =
                StoreProduct.fromSnapshot(claim.productId(), claim.snapshot());
        return frozen.isPresent() ? frozen
                : StoreProduct.resolve(plugin.getConfig(), claim.productId());
    }

    private void deliverOne(UUID owner, StoreClaims.Claim claim, StoreProduct product,
                            boolean requested) {
        Player player = plugin.getServer().getPlayer(owner);
        if (product.requiresOnlinePlayer() && (player == null || !player.isOnline())) {
            return; // waits for them to join; the claim is already safe on disk
        }
        if (player == null) {
            // Money only, buyer offline: nothing needs their thread or their inventory.
            if (!claims.consume(claim)) return;
            payAndRun(owner, product, claim, null);
            return;
        }

        // The room check runs on the PLAYER'S thread, reading the inventory as it is at the
        // moment of handing over — not as it looked when an async pass decided to try.
        FoliaTasks.entity(plugin, player, target -> {
            if (!product.items().isEmpty() && !hasRoom(target, product)) {
                if (requested) target.sendMessage(Lang.get(target, "store.inventory-full"));
                return; // claim untouched: still owed, retried later
            }
            // Consumed BEFORE handing over: if the server dies in between it is better to owe
            // a manual redelivery (logged loudly) than to hand the same purchase out twice.
            // The durable write goes off this thread, then the goods land back on it.
            plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
                if (!claims.consume(claim)) return;
                boolean handed = FoliaTasks.entity(plugin, target,
                        online -> handOver(owner, claim, product, online),
                        () -> reopen(claim, product, owner,
                                "the buyer left before it could be handed over"));
                if (!handed) return; // entity() already invoked the retired callback
            });
        }, () -> {
            // Left before the check even ran; nothing was consumed.
        });
    }

    /** Runs on the buyer's own thread with the claim already consumed. */
    private void handOver(UUID owner, StoreClaims.Claim claim, StoreProduct product,
                          Player target) {
        // El hueco se comprobo ANTES del salto async que consume la reclamacion, y en ese
        // intervalo el jugador pudo llenar el inventario recogiendo cosas. Se vuelve a mirar
        // aqui, en su hilo y con NADA entregado todavia: asi devolver la compra es exacto, no
        // duplica nada, y una compra pagada deja de poder evaporarse con una linea de log.
        if (!product.items().isEmpty() && !hasRoom(target, product)) {
            reopen(claim, product, owner,
                    "their inventory filled up between the check and the hand-over");
            target.sendMessage(Lang.get(target, "store.inventory-full"));
            return;
        }
        try {
            for (StoreProduct.ItemSpec spec : product.items()) {
                // Deliberately NOT giveOrDrop: the room check above guarantees a free slot
                // per stack, and dropping goods on the ground is how a purchase gets burned
                // in lava or picked up by whoever was standing there.
                var leftover = target.getInventory().addItem(spec.build());
                if (!leftover.isEmpty()) {
                    plugin.getLogger().severe("Store delivery of " + product.productId()
                            + " to " + owner + " could not fit " + leftover.size()
                            + " stack(s) despite the room check. Transaction "
                            + claim.transaction() + " needs manual follow-up.");
                }
            }
            target.sendMessage(Lang.get(target, "store.delivered",
                    "product", MM.sanitizeInteraction(product.displayName())));
        } catch (RuntimeException failure) {
            reopen(claim, product, owner, "an error occurred: " + failure);
            return;
        }
        payAndRun(owner, product, claim, target.getName());
        // Anything still owed goes out now that this one has really landed.
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                ignored -> claim(owner, false));
    }

    /**
     * The part that does not depend on the player's thread. A failure here is NOT rolled back
     * on purpose: the items are already in their inventory, and reopening the claim would hand
     * them a second copy. Duplicating goods is worse for a server than one loud log line.
     */
    private void payAndRun(UUID owner, StoreProduct product, StoreClaims.Claim claim,
                           String playerName) {
        FoliaTasks.global(plugin, () -> {
            try {
                if (product.money() > 0d && !giveMoney(owner, product.money())) {
                    // Vault reports refusal by returning false, not by throwing; ignoring it
                    // meant a purchase could be marked delivered with no money moved.
                    incomplete(owner, product, claim, "the economy refused the deposit of "
                            + product.money());
                }
                for (String command : product.commands()) {
                    String resolved = command
                            .replace("{player}", playerName == null
                                    ? owner.toString() : playerName)
                            .replace("{uuid}", owner.toString());
                    if (!plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), resolved)) {
                        incomplete(owner, product, claim,
                                "the console command '" + resolved + "' was refused");
                    }
                }
            } catch (RuntimeException failure) {
                incomplete(owner, product, claim, "an error occurred: " + failure);
            }
        });
    }

    /**
     * A part of an already-handed-over purchase did not go through. The claim stays consumed
     * on purpose: the items are in the buyer's inventory, and reopening it would give them a
     * second copy. Duplicating goods is worse for a server than a loud line staff can act on.
     */
    private void incomplete(UUID owner, StoreProduct product, StoreClaims.Claim claim,
                            String detail) {
        plugin.getLogger().severe("Store delivery of " + product.productId() + " to " + owner
                + " was only partly completed: " + detail + ". Transaction "
                + claim.transaction() + " needs manual follow-up; the claim stays consumed so"
                + " nothing is handed out twice.");
        if (plugin.getConfig().getBoolean("Store.Notify_Discord", true)
                && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Store: INCOMPLETE delivery of "
                    + product.productId() + " to " + owner + " (" + detail + ")");
        }
    }

    /** Puts an unstarted delivery back so the retry pass picks it up again. */
    private void reopen(StoreClaims.Claim claim, StoreProduct product, UUID owner,
                        String reason) {
        plugin.getLogger().warning("Store delivery of " + product.productId() + " to " + owner
                + " did not happen (" + reason + "). Reopening the claim so it is retried.");
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin,
                    ignored -> claims.restore(claim));
        } catch (RuntimeException rejected) {
            // Shutting down: the claim is still marked delivered on disk, so say so loudly
            // rather than let a paid purchase disappear without a trace.
            plugin.getLogger().severe("Could not reopen store claim " + claim.transaction()
                    + " during shutdown. Deliver " + product.productId() + " to " + owner
                    + " manually.");
        }
    }

    private boolean giveMoney(UUID owner, double amount) {
        if (!dev.chatalyst.hook.VaultHook.hasEconomy()) {
            plugin.getLogger().warning("Store product paid " + amount
                    + " but no Vault economy is available for " + owner + ".");
            return false;
        }
        return dev.chatalyst.hook.VaultHook.deposit(
                plugin.getServer().getOfflinePlayer(owner), amount);
    }

    /** Enough empty slots that a delivery never has to spill onto the ground. */
    private boolean hasRoom(Player player, StoreProduct product) {
        int required = Math.max(product.items().size(), Math.max(0, Math.min(36,
                plugin.getConfig().getInt("Store.Required_Free_Slots", 2))));
        int free = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) free++;
        }
        return free >= required;
    }

    private void notifyWaiting(UUID owner) {
        if (claims.pendingFor(owner).isEmpty()) return;
        tell(owner, "store.pending-notice");
    }

    private void tell(UUID owner, String key) {
        Player player = plugin.getServer().getPlayer(owner);
        if (player == null) return;
        FoliaTasks.entity(plugin, player, online -> online.sendMessage(Lang.get(online, key)));
    }

    public List<StoreClaims.Claim> pendingFor(UUID owner) {
        return claims.pendingFor(owner);
    }

    /** Everything this player got through the store and the rank shop, newest first. */
    public List<PurchaseHistory.Entry> historyFor(UUID owner) {
        return PurchaseHistory.of(owner, claims.allFor(owner),
                PurchaseHistory.receipts(rankReceipts));
    }

    /** The web store address for the menu button, or "" when none is configured. */
    public String webUrl() {
        String url = plugin.getConfig().getString("Store.Web_Url", "");
        return url == null || !url.startsWith("https://") ? "" : url.trim();
    }

    public Optional<StoreProduct> product(String productId) {
        return StoreProduct.resolve(plugin.getConfig(), productId);
    }

    private void send(CommandSender sender, String miniMessage) {
        FoliaTasks.global(plugin, () -> sender.sendMessage(MM.full(miniMessage)));
    }

    /** El intento tal y como llegó, en una línea que se pueda leer en un log. */
    private static String describe(String[] args) {
        StringBuilder out = new StringBuilder("/cht store");
        for (String arg : args) {
            out.append(' ').append(arg == null ? "" : arg.replaceAll("[\\r\\n]", " "));
        }
        return out.length() > 200 ? out.substring(0, 200) + "..." : out.toString();
    }

    /**
     * Rechaza una entrega dejando rastro donde alguien lo vea.
     *
     * <p>Contestarle a la consola no basta: quien ejecuta esto es Tebex, nadie esta mirando, y
     * el comando no se reenvia. Un rechazo silencioso es una compra cobrada que el jugador no
     * recibe y de la que no queda constancia hasta que se queja.
     */
    private void reject(CommandSender sender, String message, String detail) {
        send(sender, message);
        String line = oneLine(detail);
        plugin.getLogger().severe("ENTREGA DE TIENDA RECHAZADA -- " + line
                + ". Tebex no reenvía el comando: revisa el paquete y entrégalo a mano con"
                + " /cht store give <uuid|jugador> <product_id> <id-del-pago>.");
        if (plugin.getConfig().getBoolean("Store.Notify_Discord", true)
                && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Store: DELIVERY REJECTED -- " + line);
        }
    }

    /**
     * El detalle aplanado, aquí y no en cada sitio que lo construye.
     *
     * <p>Casi todos los detalles llevan dentro lo que llegó en el comando — un nombre de
     * jugador, un id de producto — y eso acaba en dos sitios donde una linea de mas es una
     * linea falsa: el log de la consola, que es el rastro de por que no se entrego una compra
     * cobrada, y un aviso al staff en Discord, que ademas interpreta el markdown. Sanearlo en
     * el unico punto por el que pasan todos es lo que evita que el proximo motivo de rechazo
     * nazca sin la defensa puesta.
     */
    private static String oneLine(String detail) {
        if (detail == null) return "";
        String flat = detail.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
