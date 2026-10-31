package dev.chatalyst.market;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.channel.ChannelHistory;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.chat.ChatRenderer;
import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.hook.TradePluginsHook;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mercado NATIVO de Chatalyst: ventas y subastas de items con deposito (escrow), negociacion
 * de doble aceptacion (dinero y/o trueque) y anuncios publicos en los canales trade/auction.
 *
 * <p>Reglas de oro del deposito: el item sale de la mano del vendedor AL PUBLICAR y solo
 * puede acabar en el comprador o de vuelta en el vendedor; lo que no cabe en un inventario
 * (o pertenece a alguien desconectado) espera en el alijo de reclamos ({@code /market claim}).
 *
 * <p>Compatibilidad: es un sistema propio que se apaga con {@code Market.Enabled: false} o
 * automaticamente si hay otro plugin de mercado instalado ({@code Market.Defer_To_Plugins}),
 * en cuyo caso Chatalyst se limita a anunciar los listados ajenos via {@link TradePluginsHook}.
 *
 * <p>Los anuncios llegan a cada espectador en su idioma, muestran el item con su tooltip
 * (mismo componente que el token {@code [item]} del chat) y llevan botones clicables de
 * comprar/pujar; ademas quedan en el historial del canal y en el historial personal de
 * transacciones de cada parte.
 */
public final class MarketManager {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String ITEM_TOKEN = "{item}";
    private static final int HISTORY_CAP = 20;
    private static final long SWEEP_PERIOD_TICKS = 20L * 30;
    /** Tiempo del tick que puede ocupar el barrido; lo que falte sigue en el siguiente. */
    private static final long SWEEP_BUDGET_NANOS = 5_000_000L;
    static final int MAX_DESCRIPTION_LENGTH = 512;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long MAX_CONFIG_DAYS = 3_650L;
    private static final long MAX_CONFIG_MINUTES = MAX_CONFIG_DAYS * 24L * 60L;

    private final ChatalystPlugin plugin;
    private final ChannelManager channels;
    private final ChannelHistory channelHistory;
    private final StorageManager storage;
    private final Store listingsStore;
    private final Store claimsStore;
    private final Store historyStore;
    // Reintegros que Vault rechazo tras un retiro. Son por servidor, igual que el mercado:
    // se reintentan aunque la feature se desactive para que nunca queden olvidados.
    private final Store creditsStore;
    private final EscrowItems escrowItems;
    private final DurableEscrow negotiationEscrow;
    private final SettlementLedger settlements;
    private final MarketDiscordFeed discordFeed;

    private final Map<Integer, MarketListing> listings = new ConcurrentHashMap<>();
    private final Map<Integer, MarketListing> pendingListings = new ConcurrentHashMap<>();
    private final Map<Integer, Negotiation> negotiations = new ConcurrentHashMap<>();
    private final AtomicInteger localIds = new AtomicInteger();
    private final Object claimsLock = new Object();
    private final Object creditsLock = new Object();
    private final java.util.Set<String> ambiguousCredits = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> ambiguousSettlements = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile String deferredTo = "";
    private volatile String saleChannel = "trade";
    private volatile String auctionChannel = "auction";
    private volatile int maxListings;
    private volatile int maxStash;
    private volatile long saleTtlMs;
    private volatile long auctionDefaultMs;
    private volatile long auctionMaxMs;
    private volatile double minIncrementPercent;
    private volatile boolean allowItemOffers;
    // Highlight por rango: permiso -> tag de color/estilo del nombre del producto. Orden del
    // YAML preservado (gana el primero que el vendedor tenga). Vacio = sin resaltado.
    private volatile Map<String, String> highlightRanks = Map.of();
    // Sonido distintivo del mercado: SOLO lo oye quien recibe el anuncio (los del canal
    // trade/auction), asi identifica la actividad sin molestar a nadie mas.
    private volatile boolean soundEnabled;
    private volatile String soundKey = "minecraft:block.amethyst_block.chime";
    private volatile float soundVolume = 0.8f;
    private volatile float soundPitch = 1.1f;
    private MarketPublishManager publish; // GUI de publicacion (inyectado tras construir)
    private ScheduledTask sweepTask;

    public MarketManager(ChatalystPlugin plugin, StorageManager storage, ChannelManager channels,
                         ChannelHistory channelHistory) {
        this.plugin = plugin;
        this.storage = storage;
        this.channels = channels;
        this.channelHistory = channelHistory;
        // Per-servidor SIEMPRE: la compra es local, asi que dos servers no deben compartir
        // anuncios (con DB network-shared, cargar el mismo anuncio en dos servers permitiria
        // vender el mismo item dos veces). El alijo tambien es local a donde cayeron los items.
        this.listingsStore = storage.perServerStore("market");
        this.claimsStore = storage.perServerStore("market_claims");
        this.historyStore = storage.perServerStore("market_history");
        this.creditsStore = storage.perServerStore("market_credits");
        this.escrowItems = new EscrowItems(plugin);
        this.negotiationEscrow = new DurableEscrow(plugin,
                storage.perServerStore("market_negotiation_escrow"), this);
        this.settlements = new SettlementLedger(
                storage.perServerStore("market_settlements"));
        this.discordFeed = new MarketDiscordFeed(plugin);
    }

    public void load() {
        var cfg = plugin.getConfig();
        // Desactivado de fabrica: feature integrada que el dueño enciende cuando quiere
        // (no todos los servidores son survival). Ellos lo activan como novedad.
        enabled = cfg.getBoolean("Market.Enabled", false);
        saleChannel = cfg.getString("Market.Sale_Channel", "trade").toLowerCase(java.util.Locale.ROOT);
        auctionChannel = cfg.getString("Market.Auction_Channel", "auction").toLowerCase(java.util.Locale.ROOT);
        maxListings = boundedCount(cfg.getInt("Market.Max_Listings", 10));
        maxStash = boundedCount(cfg.getInt("Market.Max_Stash", 54));
        saleTtlMs = boundedDuration(cfg.getLong("Market.Listing_Days", 7), 1,
                MAX_CONFIG_DAYS, DAY_MILLIS);
        auctionMaxMs = boundedDuration(cfg.getLong("Market.Auction.Max_Minutes", 1440), 1,
                MAX_CONFIG_MINUTES, 60_000L);
        auctionDefaultMs = Math.min(auctionMaxMs, boundedDuration(
                cfg.getLong("Market.Auction.Default_Minutes", 60), 1,
                MAX_CONFIG_MINUTES, 60_000L));
        double configuredIncrement = cfg.getDouble("Market.Auction.Min_Increment_Percent", 5);
        minIncrementPercent = Double.isFinite(configuredIncrement)
                ? Math.max(0, configuredIncrement) : 5;
        allowItemOffers = cfg.getBoolean("Market.Allow_Item_Offers", true);

        // Highlight del nombre del producto por rango del vendedor (automatico, sin /chatcolor).
        Map<String, String> ranks = new LinkedHashMap<>();
        if (cfg.getBoolean("Market.Highlight.Enabled", false)) {
            List<Map<?, ?>> list = cfg.getMapList("Market.Highlight.Ranks");
            for (Map<?, ?> row : list) {
                Object perm = row.get("Permission");
                Object color = row.get("Color");
                if (perm != null && color != null && !perm.toString().isBlank()
                        && !color.toString().isBlank()) {
                    ranks.put(perm.toString(), color.toString());
                }
            }
        }
        // LinkedHashMap directo (no Map.copyOf): hay que preservar el orden del YAML porque
        // gana el primer rango que el vendedor tenga. Se reemplaza entero cada load (volatile).
        highlightRanks = ranks;

        soundEnabled = cfg.getBoolean("Market.Sound.Enabled", true);
        soundKey = cfg.getString("Market.Sound.Key", "minecraft:block.amethyst_block.chime");
        soundVolume = (float) cfg.getDouble("Market.Sound.Volume", 0.8);
        soundPitch = (float) cfg.getDouble("Market.Sound.Pitch", 1.1);

        // Compatibilidad: si el dueño instala un plugin de mercado dedicado, el nativo se
        // aparta solo (los anuncios de ese plugin ya los hace TradePluginsHook).
        deferredTo = "";
        if (enabled && cfg.getBoolean("Market.Defer_To_Plugins", true)) {
            List<String> others = TradePluginsHook.detected();
            if (!others.isEmpty()) {
                deferredTo = String.join(", ", others);
                plugin.getLogger().info("Built-in market disabled: deferring to " + deferredTo
                        + " (set Market.Defer_To_Plugins: false to run both).");
            }
        }
    }

    /** Carga los anuncios persistidos y arranca el barrido de caducidades. */
    public void start() {
        stopSweep();
        listings.clear(); // recarga limpia (el store es la fuente de verdad)
        pendingListings.clear();
        int maxId = 0;
        for (Map.Entry<String, String> e : listingsStore.all().entrySet()) {
            MarketListing l = MarketListing.decode(e.getValue());
            if (l == null) {
                // Ilegible NO es basura: dentro va el objeto de alguien. Antes se borraba la
                // fila y con ella el item, para siempre. Se deja donde esta, sin cargar, y se
                // avisa para recuperarlo a mano. Su id sigue contando para no reutilizarlo.
                plugin.getLogger().severe("Market: listing '" + e.getKey() + "' could not be"
                        + " read and was left untouched in storage (its escrowed item is"
                        + " still inside). Raw value: " + e.getValue());
                try {
                    maxId = Math.max(maxId, Integer.parseInt(e.getKey().trim()));
                } catch (NumberFormatException notAnId) {
                    // clave que no es un id: no compite con los ids nuevos
                }
                continue;
            }
            maxId = Math.max(maxId, l.id);
            if (l.terminalOwner != null) {
                if (stash(l.terminalOwner, List.of(l.item()))) {
                    listingsStore.compareAndRemoveDurably(e.getKey(), e.getValue());
                }
                continue;
            }
            if (l.handoffPending) pendingListings.put(l.id, l);
            else listings.put(l.id, l);
        }
        localIds.set(maxId);
        recoverMarketSettlements();
        negotiationEscrow.recoverAll(settlements.blockedMarkers(
                SettlementLedger.Kind.SALE, SettlementLedger.Kind.AUCTION));
        retryPendingCredits();
        if (!isActive()) {
            // /trade puede generar un reintegro DESPUES del arranque aunque Market este
            // apagado y el ledger estuviera vacio. La tarea debe existir siempre.
            sweepTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    task -> runFinancialMaintenance(), SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
            return;
        }
        sweepTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                t -> sweep(), SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    public void shutdown() {
        stopSweep();
        // Las negociaciones no sobreviven a un reinicio: el trueque en deposito vuelve al
        // alijo del comprador para que no se pierda ni un item. Mismo gate atomico que las
        // demas rutas terminales: si una venta/rechazo en vuelo ya reclamo el deposito,
        // aqui no se devuelve por segunda vez.
        for (Negotiation neg : List.copyOf(negotiations.values())) {
            List<ItemStack> barter = claimBarter(neg);
            if (barter != null) releaseBarter(neg.buyer, barter);
        }
        negotiations.clear();
        creditsStore.flushNow();
    }

    static int boundedCount(int configured) {
        return Math.max(1, Math.min(1_000, configured));
    }

    private void stopSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    // ---- Estado -----------------------------------------------------------------------

    public boolean isActive() {
        return enabled && deferredTo.isEmpty();
    }

    /** Nombre(s) del plugin de mercado al que se cede el sitio, o "" si no aplica. */
    public String deferredTo() {
        return deferredTo;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean allowItemOffers() {
        return allowItemOffers;
    }

    public double minIncrementPercent() {
        return minIncrementPercent;
    }

    /** Todos los anuncios, mas recientes primero. */
    public List<MarketListing> listings() {
        List<MarketListing> out = new ArrayList<>(listings.values());
        out.sort((a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    /**
     * Anuncios visibles para {@code viewer}: los activos + los programados PROPIOS (para que
     * el vendedor vea/cancele los suyos en espera); los programados de otros no se ven.
     */
    public List<MarketListing> visibleFor(UUID viewer) {
        long now = System.currentTimeMillis();
        List<MarketListing> out = new ArrayList<>();
        for (MarketListing l : listings.values()) {
            if (!l.scheduled(now) || l.seller.equals(viewer)) out.add(l);
        }
        out.sort((a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    public MarketListing byId(int id) {
        return listings.get(id);
    }

    public Negotiation negotiationOf(int listingId) {
        return negotiations.get(listingId);
    }

    /** Si esta inactivo, explica el motivo al jugador y devuelve false. */
    public boolean checkActive(Player p) {
        if (!p.hasPermission("chatalyst.command.market")) {
            p.sendMessage(Lang.get(p, "common.no-permission"));
            return false;
        }
        if (isActive()) return true;
        p.sendMessage(deferredTo.isEmpty()
                ? Lang.get(p, "market.disabled")
                : Lang.get(p, "market.deferred", "plugin", deferredTo));
        return false;
    }

    // ---- Publicar ---------------------------------------------------------------------

    /** Conecta la GUI de publicacion (se inyecta tras construir por el orden de arranque). */
    public void setPublishManager(MarketPublishManager publish) {
        this.publish = publish;
    }

    /**
     * Abre la GUI de publicacion (estilo /announce). Mantiene el borrador entre aperturas
     * (asi la vista previa, que cierra el menu, no pierde lo elegido); se limpia al publicar
     * o al desconectarse.
     */
    public void openPublish(Player p) {
        if (!checkActive(p) || publish == null) return;
        Menu.open(p, new MarketPublishMenu(this, publish));
    }

    /** Publica el item EN MANO como venta o subasta. El item pasa al deposito. */
    public void create(Player p, MarketListing.Type type, double price, long auctionMinutes) {
        create(p, type, price, auctionMinutes, -1);
    }

    /**
     * Publica un item como venta o subasta desde un slot del inventario ({@code invSlot} = -1
     * usa la mano). El item pasa al deposito. Version simple (sin descripcion ni programacion).
     */
    public void create(Player p, MarketListing.Type type, double price, long auctionMinutes, int invSlot) {
        if (!checkActive(p)) return;
        ItemStack source = invSlot < 0
                ? p.getInventory().getItemInMainHand()
                : p.getInventory().getItem(invSlot);
        if (source == null || source.getType().isAir()) {
            p.sendMessage(Lang.get(p, "market.hand-empty"));
            return;
        }
        int sourceSlot = invSlot < 0 ? p.getInventory().getHeldItemSlot() : invSlot;
        ItemStack original = source.clone();
        ItemStack escrow = escrowItems.marked(original);
        p.getInventory().setItem(sourceSlot, escrow.clone());
        try {
            p.saveData(); // hace durable el marker que resuelve una caida durante el handoff
        } catch (RuntimeException failure) {
            p.getInventory().setItem(sourceSlot, original);
            p.sendMessage(Lang.get(p, "market.inventory-save-error"));
            return;
        }

        MarketListing listing = createListing(p, escrow, type, price,
                auctionMinutes, 0, "", true);
        if (listing == null) {
            p.getInventory().setItem(sourceSlot, original);
            try {
                p.saveData();
            } catch (RuntimeException failure) {
                // El playerdata anterior conserva la copia marcada. Al entrar, el marker
                // huerfano se limpia sin retirar el item, por lo que no hay perdida.
                plugin.getLogger().severe("Could not save market escrow rollback for "
                        + p.getName() + ": " + failure.getMessage());
            }
            return;
        }

        String marker = escrowItems.marker(escrow);
        ItemStack current = p.getInventory().getItem(sourceSlot);
        if (marker != null && marker.equals(escrowItems.marker(current))) {
            p.getInventory().setItem(sourceSlot, null);
        }
        try {
            p.saveData();
        } catch (RuntimeException failure) {
            // El anuncio queda PENDING e invisible. onPlayerJoin reintenta usando el marker.
            plugin.getLogger().severe("Could not finish market escrow handoff for "
                    + p.getName() + ": " + failure.getMessage());
            return;
        }
        if (activatePendingListing(listing)) notifyListingCreated(p, listing, escrow);
    }

    /**
     * Publica un item que YA esta en deposito (fuera del inventario), con precio, duracion,
     * publicacion programada (delay en minutos, 0 = ya) y descripcion. Lo usa la GUI de cofre,
     * un item por anuncio. Si no se puede publicar (limite, sin economia) DEVUELVE el item al
     * vendedor. Devuelve true si se publico.
     */
    public boolean publishItem(Player p, ItemStack escrow, MarketListing.Type type, double price,
                               long durationMinutes, long publishDelayMinutes, String description) {
        ItemStack marked = escrowItems.marked(escrow);
        MarketListing listing = createListing(p, marked, type, price, durationMinutes,
                publishDelayMinutes, description, false);
        if (listing == null) {
            give(p.getUniqueId(), List.of(marked));
            return false;
        }
        notifyListingCreated(p, listing, marked);
        return true;
    }

    private MarketListing createListing(Player p, ItemStack escrow, MarketListing.Type type,
            double price, long durationMinutes, long publishDelayMinutes, String description,
            boolean handoffPending) {
        if (!Double.isFinite(price) || price < 0) {
            p.sendMessage(Lang.get(p, "market.invalid-price"));
            return null;
        }
        if (!isActive()) {
            return null;
        }
        long own = java.util.stream.Stream.concat(
                        listings.values().stream(), pendingListings.values().stream())
                .filter(l -> l.seller.equals(p.getUniqueId())).count();
        if (own >= maxListings && !p.hasPermission("chatalyst.market.bypasslimit")) {
            p.sendMessage(Lang.get(p, "market.max-listings", "max", maxListings));
            return null;
        }
        boolean needsEconomy = type == MarketListing.Type.AUCTION || price > 0;
        if (needsEconomy && !VaultHook.hasEconomy()) {
            p.sendMessage(Lang.get(p, "market.no-economy"));
            return null;
        }
        long now = System.currentTimeMillis();
        long durationMs = durationMinutes > 0 ? saturatedDuration(durationMinutes, 60_000L)
                : (type == MarketListing.Type.AUCTION ? auctionDefaultMs : saleTtlMs);
        if (type == MarketListing.Type.AUCTION) durationMs = Math.min(durationMs, auctionMaxMs);
        long publishAt = publishDelayMinutes > 0
                ? deadlineOrMax(now, saturatedDuration(publishDelayMinutes, 60_000L)) : 0;
        // La caducidad cuenta desde que se PUBLICA (para los programados, desde su hora).
        long expires = deadlineOrMax(publishAt > 0 ? publishAt : now, durationMs);

        int listingId;
        try {
            listingId = nextId();
        } catch (IllegalStateException exhausted) {
            p.sendMessage(Lang.get(p, "market.id-exhausted"));
            return null;
        }
        // Nombre MOSTRADO del vendedor (apodo o disfraz de /anonymous): sellerName es solo
        // visual (anuncios, GUIs, notificaciones) — la logica resuelve siempre por UUID —
        // y guardar el real desenmascaraba a un anonimo en cada anuncio del mercado.
        MarketListing l = new MarketListing(listingId, type, p.getUniqueId(), shownName(p),
                price, now, expires, MarketListing.itemToB64(escrow));
        l.nameColor = highlightColorFor(p);
        l.description = dev.chatalyst.util.Sanitize.limit(
                description == null ? "" : description, MAX_DESCRIPTION_LENGTH);
        l.publishAt = publishAt;
        l.announced = publishAt <= 0; // inmediato = ya anunciado; programado = pendiente
        l.handoffPending = handoffPending;
        if (!listingsStore.compareAndPutDurably(String.valueOf(l.id), null, l.encode())) {
            p.sendMessage(Lang.get(p, "market.storage-error"));
            return null;
        }
        if (handoffPending) pendingListings.put(l.id, l);
        else listings.put(l.id, l);
        return l;
    }

    private void notifyListingCreated(Player p, MarketListing l, ItemStack escrow) {
        long now = System.currentTimeMillis();
        if (l.scheduled(now)) {
            p.sendMessage(withItem(Lang.get(p, "market.scheduled",
                    "id", l.id, "minutes", Math.max(0, (l.publishAt - now) / 60_000L)),
                    escrow, l.nameColor));
        } else {
            announceListing(l);
            p.sendMessage(withItem(Lang.get(p, l.type == MarketListing.Type.AUCTION
                            ? "market.listed-auction" : "market.listed",
                    "price", money(l.price), "minutes", Math.max(0, (l.expires - now) / 60_000L),
                    "id", l.id), escrow, l.nameColor));
        }
        addHistory(p.getUniqueId(), Lang.raw(p, "market.hist-listed",
                "item", plainItem(escrow), "price", money(l.price)));
    }

    private boolean activatePendingListing(MarketListing listing) {
        synchronized (listing) {
            if (!listing.handoffPending) return true;
            String expected = listing.encode();
            listing.handoffPending = false;
            if (!listingsStore.compareAndPutDurably(
                    String.valueOf(listing.id), expected, listing.encode())) {
                listing.handoffPending = true;
                return false;
            }
            pendingListings.remove(listing.id, listing);
            listings.put(listing.id, listing);
            return true;
        }
    }

    /**
     * Publica un LOTE desde la GUI de cofre: cada item su propio anuncio con su precio, con la
     * misma config (tipo, duracion, programacion, descripcion). Respeta el limite: los que no
     * caben se devuelven al vendedor con un aviso.
     */
    public void publishBatch(Player p, List<MarketPublishManager.PricedItem> items,
                             MarketListing.Type type, long durationMinutes,
                             long publishDelayMinutes) {
        if (!checkActive(p)) {
            for (var it : items) give(p.getUniqueId(), List.of(it.item()));
            return;
        }
        boolean bypass = p.hasPermission("chatalyst.market.bypasslimit");
        long own = listings.values().stream().filter(l -> l.seller.equals(p.getUniqueId())).count();
        int slotsLeft = bypass ? Integer.MAX_VALUE : (int) Math.max(0, maxListings - own);
        int published = 0;
        int skipped = 0;
        for (var it : items) {
            if (published >= slotsLeft) {
                // publishItem() devuelve por si mismo el deposito cuando falla. Solo
                // devolvemos aqui los items que ni siquiera intentamos publicar por limite;
                // hacerlo tambien tras un false duplicaba el stack en el alijo/inventario.
                give(p.getUniqueId(), List.of(it.item()));
                skipped++;
                continue;
            }
            if (publishItem(p, it.item(), type, it.price(), durationMinutes,
                    publishDelayMinutes, it.description())) {
                published++;
            } else {
                // El contrato de publishItem garantiza que el item ya fue devuelto.
                skipped++;
            }
        }
        if (skipped > 0) {
            p.sendMessage(Lang.get(p, "market.batch-limit", "max", maxListings, "skipped", skipped));
        }
        if (published > 0) {
            p.sendMessage(Lang.get(p, "market.batch-published", "count", published));
        }
    }

    /** El anuncio publico de un listing (usado al publicar y al activar un programado). */
    private void announceListing(MarketListing l) {
        ItemStack item = l.item();
        String shown = l.sellerName;
        String desc = l.description.isEmpty() ? ""
                : Lang.raw("market.desc-suffix", "text", dev.chatalyst.util.MM.escape(l.description));
        if (l.isAuction()) {
            long minutes = Math.max(1, (l.expires - System.currentTimeMillis()) / 60_000L);
            announce(auctionChannel, null, item, l, "market.announce-auction", "market.buttons-auction",
                    "player", shown, "price", money(l.price), "minutes", minutes,
                    "id", l.id, "next", plainMoney(l.minBid(minIncrementPercent)), "description", desc);
        } else {
            announce(saleChannel, null, item, l, "market.announce-sale", "market.buttons-sale",
                    "player", shown, "price", money(l.price), "id", l.id, "description", desc);
        }
    }

    // ---- Compra con doble aceptacion ----------------------------------------------------

    /**
     * Un comprador pulsa [Comprar]: si el vendedor esta libre se abre la negociacion; si ya
     * hay OTRA compra en curso sobre este anuncio, el comprador debe esperar a que el
     * vendedor la termine o la rechace (requisito del flujo).
     */
    public void requestBuy(Player buyer, int id) {
        if (!checkActive(buyer)) return;
        MarketListing l = listings.get(id);
        if (l == null || l.scheduled(System.currentTimeMillis())) { // programado = aun no visible
            buyer.sendMessage(Lang.get(buyer, "market.not-found"));
            return;
        }
        if (l.seller.equals(buyer.getUniqueId())) {
            buyer.sendMessage(Lang.get(buyer, "market.buy-own"));
            return;
        }
        if (l.isAuction()) {
            hintBid(buyer, l);
            return;
        }
        Negotiation existing = negotiations.get(id);
        if (existing != null) {
            if (existing.buyer.equals(buyer.getUniqueId())) {
                openNegotiationMenu(buyer, existing);
                return;
            }
            buyer.sendMessage(Lang.get(buyer, "market.seller-busy", "player", l.sellerName));
            return;
        }
        // Alijo lleno: no se ABRE una compra NUEVA que no tendria donde entregarse. Se comprueba
        // aqui (no antes) para que reabrir/cancelar una negociacion propia ya en curso siga
        // funcionando. El alijo es la red de seguridad cuando el inventario esta lleno; si ya
        // esta al tope, se rechaza y se pide vaciarlo con /market claim, para no perder items.
        if (stashFull(buyer.getUniqueId())) {
            buyer.sendMessage(Lang.get(buyer, "market.stash-full", "max", maxStash));
            return;
        }
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller == null) {
            // Vendedor desconectado: la venta directa se cierra al precio publicado. No hay
            // nada que negociar (el vendedor ya consintio ese precio al publicar y su item ya
            // esta en deposito), y exigir que estuviera online dejaba incomprable casi todo
            // el mercado. El cobro al vendedor ausente usa el ledger de credits de siempre.
            instantBuy(buyer, l);
            return;
        }
        double initialMoney = VaultHook.hasEconomy() ? l.price : 0;
        Negotiation neg = new Negotiation(l.id, l.seller, l.sellerName,
                buyer.getUniqueId(), shownName(buyer), initialMoney);
        // Atomico: si dos compradores clican [Comprar] a la vez, solo uno abre negociacion.
        Negotiation raced = negotiations.putIfAbsent(l.id, neg);
        if (raced != null) {
            buyer.sendMessage(Lang.get(buyer, "market.seller-busy", "player", l.sellerName));
            return;
        }
        openNegotiationMenu(buyer, neg);
        FoliaTasks.entity(plugin, seller, s -> s.sendMessage(withItem(
                Lang.get(s, "market.negotiation-started-seller", "player", neg.buyerName, "id", l.id),
                l.item())), () -> {
            if (negotiations.remove(l.id, neg)) {
                notify(neg.buyer, "market.seller-offline", "player", l.sellerName);
            }
        });
    }

    /**
     * Venta directa inmediata al precio publicado, para cuando el vendedor no esta conectado.
     *
     * <p>No duplica la liquidacion: monta la misma {@link Negotiation} que usaria una compra
     * normal (con el precio del anuncio y sin trueque) y la pasa por {@link #completeSale},
     * de modo que hereda TODOS los gates de seguridad — saldo, el gate atomico del deposito
     * del anuncio, el WAL de pago y el historial. Los avisos al vendedor ausente se descartan
     * solos ({@code notify} ignora offline); su dinero y su historial si quedan registrados.
     */
    private void instantBuy(Player buyer, MarketListing l) {
        double money = VaultHook.hasEconomy() ? l.price : 0;
        // Se comprueba antes de reservar la negociacion para no dejar el anuncio bloqueado
        // por un comprador que no puede pagar.
        if (money > 0 && VaultHook.balance(buyer) < money) {
            buyer.sendMessage(Lang.get(buyer, "market.not-enough-money", "amount", money(money)));
            return;
        }
        Negotiation neg = new Negotiation(l.id, l.seller, l.sellerName,
                buyer.getUniqueId(), shownName(buyer), money);
        // Mismo gate que la compra normal: si otro comprador ya tiene abierta una negociacion
        // sobre este anuncio, esta compra no se cuela por delante.
        if (negotiations.putIfAbsent(l.id, neg) != null) {
            buyer.sendMessage(Lang.get(buyer, "market.seller-busy", "player", l.sellerName));
            return;
        }
        completeSale(neg);
        // completeSale consume la negociacion en el camino feliz (claimBarter la saca del
        // mapa). Si abortó — saldo justo perdido en una carrera, fallo del proveedor — hay
        // que soltar el anuncio: sin menus abiertos nadie mas lo cerraria y quedaria
        // bloqueado para siempre.
        negotiations.remove(l.id, neg);
    }

    /** Abre (o reabre) la GUI de una negociacion en la que {@code p} es parte. */
    public void view(Player p, int id) {
        if (!checkActive(p)) return;
        Negotiation neg = negotiations.get(id);
        if (neg == null || !neg.isParty(p.getUniqueId())) {
            p.sendMessage(Lang.get(p, "market.not-in-negotiation"));
            return;
        }
        openNegotiationMenu(p, neg);
    }

    private void openNegotiationMenu(Player p, Negotiation neg) {
        MarketListing l = listings.get(neg.listingId);
        if (l == null) return;
        Menu.open(p, new NegotiationMenu(this, neg, l));
    }

    // Las dos partes de una negociacion viven en hilos de region distintos (Folia): toda
    // mutacion del estado compartido de la oferta se sincroniza sobre la negociacion.

    // La negociacion sigue viva solo mientras este en el mapa: closeMenus() es asincrono,
    // asi que un click en un menu obsoleto puede llegar DESPUES de que la venta/rechazo la
    // cerrara. Sin este check, un item añadido a una negociacion ya drenada no se devuelve
    // nunca (nadie volvera a drenarla).
    private boolean isLive(Negotiation neg) {
        return negotiations.get(neg.listingId) == neg;
    }

    /** El comprador añade al trueque el stack del slot {@code invSlot} de su inventario. */
    public void offerAddItem(Player buyer, Negotiation neg, int invSlot) {
        if (!neg.buyer.equals(buyer.getUniqueId()) || !allowItemOffers) return;
        ItemStack item = buyer.getInventory().getItem(invSlot);
        if (item == null || item.getType().isAir()) return;
        synchronized (neg) {
            if (!isLive(neg)) {
                buyer.sendMessage(Lang.get(buyer, "market.not-in-negotiation"));
                return;
            }
            if (neg.itemCount() >= Negotiation.MAX_OFFER_ITEMS) {
                buyer.sendMessage(Lang.get(buyer, "market.offer-full"));
                return;
            }
        }
        ItemStack deposited = negotiationEscrow.deposit(buyer, invSlot, item);
        if (deposited == null) {
            buyer.sendMessage(Lang.get(buyer, "market.escrow-error"));
            return;
        }
        synchronized (neg) {
            if (!isLive(neg) || !neg.addItem(deposited)) {
                negotiationEscrow.release(deposited, buyer.getUniqueId());
                return;
            }
        }
        refreshMenus(neg);
    }

    /** El comprador retira el stack {@code index} del trueque (vuelve a su inventario). */
    public void offerRemoveItem(Player buyer, Negotiation neg, int index) {
        if (!neg.buyer.equals(buyer.getUniqueId())) return;
        synchronized (neg) {
            if (!isLive(neg)) return;
            ItemStack item = neg.removableItem(index);
            if (item == null) return;
            if (!negotiationEscrow.release(item, buyer.getUniqueId())) {
                buyer.sendMessage(Lang.get(buyer, "market.storage-error"));
                refreshMenus(neg);
                return;
            }
            neg.removeItem(index);
        }
        deliverStashIfAny(buyer.getUniqueId());
        refreshMenus(neg);
    }

    /** El comprador ajusta el dinero ofrecido. */
    public void offerAdjustMoney(Player buyer, Negotiation neg, double delta) {
        if (!neg.buyer.equals(buyer.getUniqueId()) || !VaultHook.hasEconomy()) return;
        synchronized (neg) {
            if (!isLive(neg)) return;
            double balance = VaultHook.balance(buyer);
            double safeBalance = Double.isFinite(balance) ? Math.max(0, balance) : 0;
            double requested = neg.moneyOffer() + delta;
            if (!Double.isFinite(requested)) return;
            neg.setMoney(Math.min(safeBalance, Math.max(0, requested)));
        }
        refreshMenus(neg);
    }

    /** Una de las partes acepta. Cuando aceptan AMBAS, la venta se ejecuta (una sola vez). */
    public void acceptNegotiation(Player p, Negotiation neg) {
        boolean complete;
        synchronized (neg) {
            // Un accept en un menu obsoleto (negociacion ya rechazada/vendida) no debe
            // re-disparar completeSale: delistaria un anuncio que ya no esta en juego.
            if (!isLive(neg)) return;
            if (neg.changedWithin(1_500L)) {
                p.sendMessage(Lang.get(p, "market.offer-just-changed"));
                refreshMenus(neg);
                return;
            }
            if (!neg.accept(p.getUniqueId())) return; // ya habia aceptado o no es parte
            complete = neg.bothAccepted();
        }
        if (complete) {
            completeSale(neg);
        } else {
            UUID other = neg.isSeller(p.getUniqueId()) ? neg.buyer : neg.seller;
            notify(other, "market.accept-waiting", "player", shownName(p));
            refreshMenus(neg);
        }
    }

    /** Cualquiera de las partes rechaza/cancela: el trueque vuelve al comprador. */
    public void rejectNegotiation(Player p, Negotiation neg) {
        if (!neg.isParty(p.getUniqueId())) return;
        List<ItemStack> barter = claimBarter(neg);
        if (barter == null) return; // otra ruta (la venta) ya cerro la negociacion
        releaseBarter(neg.buyer, barter);
        closeMenus(neg);
        boolean bySeller = neg.isSeller(p.getUniqueId());
        notify(neg.buyer, bySeller ? "market.rejected-by-seller" : "market.rejected-by-you",
                "player", neg.sellerName);
        notify(neg.seller, bySeller ? "market.rejected-by-you" : "market.rejected-by-buyer",
                "player", neg.buyerName);
    }

    /**
     * Reclama el trueque de una negociacion: usa {@code negotiations.remove(id, neg)} como
     * gate ATOMICO, asi solo UN camino terminal (venta, rechazo, cancelacion, caducidad,
     * desconexion) obtiene y devuelve/consume el deposito. El resto recibe null y aborta —
     * sin esto, una venta y un rechazo simultaneos duplicarian el trueque.
     */
    private List<ItemStack> claimBarter(Negotiation neg) {
        if (neg == null || !negotiations.remove(neg.listingId, neg)) return null;
        synchronized (neg) {
            return neg.drainItems();
        }
    }

    private void releaseBarter(UUID owner, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (!negotiationEscrow.release(item, owner)) {
                plugin.getLogger().severe("Could not release negotiation escrow item to "
                        + owner);
            }
        }
    }

    /** Ambos aceptaron: verifica el dinero, mueve deposito y pagos, y lo anuncia a todos. */
    private void completeSale(Negotiation neg) {
        MarketListing l = listings.get(neg.listingId);
        if (l == null) { // el anuncio desaparecio (cancelado/expirado) a mitad de negociacion
            List<ItemStack> barter = claimBarter(neg);
            if (barter != null) {
                releaseBarter(neg.buyer, barter);
                closeMenus(neg);
                notify(neg.buyer, "market.not-found");
            }
            return;
        }
        double money = neg.moneyOffer();
        // Saldo comprobado ANTES de reclamar nada: si no llega, la negociacion sigue abierta
        // para que el comprador ajuste la oferta (sin haber tocado item ni dinero).
        if (money > 0 && VaultHook.balance(Bukkit.getOfflinePlayer(neg.buyer)) < money) {
            synchronized (neg) {
                neg.resetAccepts();
            }
            notify(neg.buyer, "market.not-enough-money", "amount", money(money));
            notify(neg.seller, "market.buyer-cannot-pay", "player", neg.buyerName);
            refreshMenus(neg);
            return;
        }
        // Gate del deposito del anuncio: solo un camino (venta/cancelacion/caducidad) lo saca.
        if (!listings.remove(l.id, l)) {
            List<ItemStack> barter = claimBarter(neg);
            if (barter != null) {
                releaseBarter(neg.buyer, barter);
                closeMenus(neg);
                notify(neg.buyer, "market.not-found");
            }
            return;
        }
        // Gate del trueque: si un rechazo simultaneo lo reclamo primero, ya volvio al
        // comprador; entonces devolvemos el item del anuncio al vendedor y no hay venta.
        List<ItemStack> barter = claimBarter(neg);
        if (barter == null) {
            if (markTerminal(l, l.seller, true)) finishTerminalListing(l);
            else listings.putIfAbsent(l.id, l);
            closeMenus(neg);
            return;
        }
        ItemStack sold = l.item();
        // Incluso un trueque sin dinero usa WAL: de otro modo un crash entre entregar el
        // listing y reasignar el escrow devolveria los items al comprador original.
        PaymentOutcome outcome = settleMarketPayment(l, barter, SettlementLedger.Kind.SALE,
                neg.buyer, neg.seller, money);
        closeMenus(neg);
        if (!outcome.paid()) {
            if (outcome.rollbackRequired()) {
                // No se creo WAL, por lo que sabemos que ningun efecto externo comenzo.
                if (listingsStore.get(String.valueOf(l.id)) != null
                        && markTerminal(l, l.seller, true)) finishTerminalListing(l);
                releaseBarter(neg.buyer, barter);
            }
            if (outcome.pending()) {
                notify(neg.buyer, "market.payment-pending");
                notify(neg.seller, "market.payment-pending");
            } else if (outcome.result() == EconomyTransaction.Result.WITHDRAW_FAILED) {
                notify(neg.buyer, "market.not-enough-money", "amount", money(money));
                notify(neg.seller, "market.buyer-cannot-pay", "player", neg.buyerName);
            } else {
                notify(neg.buyer, "market.payment-provider-failed");
                notify(neg.seller, "market.payment-provider-failed");
            }
            return;
        }

        String offerBuyer = offerSummary(null, money, barter.size());
        String offerSeller = offerSummary(null, money, barter.size());
        // El anuncio del canal es un broadcast generico (repl unico); su resumen va en el
        // idioma por defecto (es casi todo numerico). Comprador/vendedor/historial usan el suyo.
        String offer = offerSummary(null, money, barter.size());
        notifyItem(neg.buyer, sold, "market.completed-buyer", "offer", offerBuyer, "player", neg.sellerName);
        notifyItem(neg.seller, sold, "market.completed-seller", "offer", offerSeller, "player", neg.buyerName);
        // La compra queda en el alijo (finishTerminalListing la deposita ahi). Se entrega y
        // avisa al comprador ya conectado (y al vendedor si recibio trueque) para que sus cosas
        // no se queden olvidadas en el alijo sin que nadie lo sepa. El que solo cobro dinero no
        // recibe aviso (claimCount 0). Mismo patron de auto-entrega online que los reintegros.
        deliverStashIfAny(neg.buyer);
        deliverStashIfAny(neg.seller);
        addHistory(neg.buyer, Lang.raw("market.hist-bought",
                "item", plainItem(sold), "offer", offerBuyer, "player", neg.sellerName));
        addHistory(neg.seller, Lang.raw("market.hist-sold",
                "item", plainItem(sold), "offer", offerSeller, "player", neg.buyerName));
        announce(saleChannel, null, sold, l,
                "market.announce-sold", null,
                "seller", neg.sellerName, "buyer", neg.buyerName, "offer", offer);
        // El anuncio del chat se lo lleva el siguiente mensaje; en Discord queda escrito, asi
        // que los nombres salen del UUID: una tarjeta de hace un mes no puede seguir diciendo
        // como se llamaba entonces alguien que ya se cambio el nombre.
        discordFeed.sold(currentName(l.seller, neg.sellerName),
                currentName(neg.buyer, neg.buyerName), plainItem(sold),
                MarketDiscordFeed.paid(money > 0 ? money(money) : "", barter.size()));
    }

    // ---- Subastas -----------------------------------------------------------------------

    /** Puja por una subasta. Valida minimo y saldo; el cobro real es al cerrar la subasta. */
    public void bid(Player p, int id, double amount) {
        if (!checkActive(p)) return;
        if (!Double.isFinite(amount) || amount < 0) {
            p.sendMessage(Lang.get(p, "market.invalid-price"));
            return;
        }
        MarketListing l = listings.get(id);
        long now = System.currentTimeMillis();
        if (l == null || l.expired(now) || l.scheduled(now)) { // caducado o aun programado
            p.sendMessage(Lang.get(p, "market.not-found"));
            return;
        }
        if (!l.isAuction()) {
            p.sendMessage(Lang.get(p, "market.not-auction"));
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            p.sendMessage(Lang.get(p, "market.bid-own"));
            return;
        }
        double min = l.minBid(minIncrementPercent);
        if (amount < min) {
            p.sendMessage(Lang.get(p, "market.bid-too-low", "min", money(min)));
            return;
        }
        if (VaultHook.balance(p) < amount) {
            p.sendMessage(Lang.get(p, "market.not-enough-money", "amount", money(amount)));
            return;
        }
        UUID previous;
        double previousBid;
        String previousName;
        synchronized (l) {
            if (listings.get(id) != l) { // la subasta cerro/se cancelo durante la puja
                p.sendMessage(Lang.get(p, "market.not-found"));
                return;
            }
            if (amount < l.minBid(minIncrementPercent)) { // otra puja gano la carrera
                p.sendMessage(Lang.get(p, "market.bid-too-low",
                        "min", money(l.minBid(minIncrementPercent))));
                return;
            }
            previous = l.topBidder;
            previousBid = l.topBid;
            previousName = l.topBidderName;
            l.topBid = amount;
            l.topBidder = p.getUniqueId();
            l.topBidderName = shownName(p);
            if (!persist(l)) {
                l.topBid = previousBid;
                l.topBidder = previous;
                l.topBidderName = previousName;
                p.sendMessage(Lang.get(p, "market.storage-error"));
                return;
            }
        }
        ItemStack item = l.item();
        p.sendMessage(withItem(Lang.get(p, "market.bid-done", "amount", money(amount)), item));
        if (previous != null && !previous.equals(p.getUniqueId())) {
            notifyItem(previous, item, "market.outbid",
                    "player", shownName(p), "amount", money(amount), "id", l.id);
        }
        announce(auctionChannel, p, item, l, "market.announce-bid", "market.buttons-auction",
                "player", shownName(p), "amount", money(amount),
                "id", l.id, "next", plainMoney(l.minBid(minIncrementPercent)));
    }

    /** Anuncio tal como saldria (para la vista previa de la GUI de publicacion; no publica). */
    public Component previewAnnounce(Player seller, ItemStack item, boolean auction,
                                     double price, long minutes) {
        String color = highlightColorFor(seller);
        if (auction) {
            long mins = minutes > 0 ? minutes : auctionDefaultMs / 60_000L;
            return withItem(Lang.get(seller, "market.announce-auction",
                    "player", shownName(seller), "price", money(price), "minutes", mins), item, color);
        }
        return withItem(Lang.get(seller, "market.announce-sale",
                "player", shownName(seller), "price", money(price)), item, color);
    }

    /** Mensaje con el comando de puja sugerido (click para autocompletarlo en el chat). */
    public void hintBid(Player p, MarketListing l) {
        p.sendMessage(withItem(Lang.get(p, "market.use-bid",
                "id", l.id, "min", plainMoney(l.minBid(minIncrementPercent))), l.item()));
    }

    // ---- Cancelar / caducar ---------------------------------------------------------------

    /** El vendedor (o staff con chatalyst.market.manage) retira un anuncio. */
    public void cancel(Player p, int id) {
        MarketListing l = listings.get(id);
        if (l == null) {
            p.sendMessage(Lang.get(p, "market.not-found"));
            return;
        }
        boolean own = l.seller.equals(p.getUniqueId());
        if (!own && !p.hasPermission("chatalyst.market.manage")) {
            p.sendMessage(Lang.get(p, "market.cancel-not-yours"));
            return;
        }
        // Bajo el lock de la puja: bid() escribe topBidder con synchronized(l) tras verificar
        // que el anuncio sigue en el mapa. Comprobar y retirar bajo el mismo lock cierra la
        // carrera "cancelo justo cuando entra la primera puja" (la puja gana o la cancelacion
        // gana, nunca se cancela una subasta que ya tiene postor).
        synchronized (l) {
            if (l.isAuction() && l.topBidder != null) {
                p.sendMessage(Lang.get(p, "market.cancel-has-bids"));
                return;
            }
            if (!markTerminal(l, l.seller, false)) {
                p.sendMessage(Lang.get(p, "market.not-found"));
                return;
            }
        }
        Negotiation neg = negotiations.get(id);
        List<ItemStack> barter = claimBarter(neg);
        if (barter != null) {
            releaseBarter(neg.buyer, barter);
            closeMenus(neg);
            notify(neg.buyer, "market.negotiation-cancelled", "player", l.sellerName);
        }
        finishTerminalListing(l);
        ItemStack item = l.item();
        p.sendMessage(withItem(Lang.get(p, "market.cancelled"), item));
        if (!own) {
            notifyItem(l.seller, item, "market.cancelled-by-staff", "player", shownName(p));
        }
        addHistory(l.seller, Lang.raw("market.hist-cancelled", "item", plainItem(item)));
        discordFeed.returned(currentName(l.seller, l.sellerName), plainItem(item),
                MarketDiscordFeed.reason(own ? "cancelled" : "cancelled-by-staff"));
    }

    /** Barrido periodico: activa programados a su hora, cierra subastas y devuelve caducados. */
    private void sweep() {
        runFinancialMaintenance();
        if (!sweeping.compareAndSet(false, true)) return;
        continueSweep(new java.util.ArrayDeque<>(listings.values()), System.currentTimeMillis());
    }

    private final java.util.concurrent.atomic.AtomicBoolean sweeping =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Cada anuncio se cierra entero y con sus escrituras en el mismo orden: ese orden es lo que
     * impide perder o duplicar un objeto si el servidor cae a mitad. Agruparlas lo romperia, asi
     * que un lote grande de caducados se reparte entre varios ticks.
     */
    private void continueSweep(java.util.Deque<MarketListing> pending, long now) {
        if (sweepTask == null) {
            sweeping.set(false);
            return;
        }
        boolean done = dev.chatalyst.util.BudgetedWork.run(pending, l -> sweepSafely(l, now),
                SWEEP_BUDGET_NANOS, System::nanoTime);
        if (done) {
            sweeping.set(false);
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                task -> continueSweep(pending, now), 1L);
    }

    private void sweepSafely(MarketListing l, long now) {
        if (listings.get(l.id) != l) return;   // vendido o cancelado desde el tramo anterior
        // Cada anuncio por separado: uno cuyo objeto no se puede leer lanzaba dentro del
        // bucle y cortaba el barrido entero, asi que los que venian detras ni caducaban
        // ni se anunciaban mientras ese siguiera ahi.
        try {
            sweepOne(l, now);
        } catch (RuntimeException failure) {
            if (UNREADABLE_REPORTED.add("listing:" + l.id)) {
                plugin.getLogger().severe("Market listing #" + l.id + " could not be"
                        + " processed and was skipped (it stays stored): " + failure);
            }
        }
    }

    private void sweepOne(MarketListing l, long now) {
        {
            // Publicacion programada: al llegar la hora, se anuncia y pasa a estar activo.
            if (l.scheduled(now)) return; // aun en espera
            if (!l.announced && l.publishAt > 0 && l.publishAt <= now) {
                l.announced = true;
                if (!persist(l)) {
                    l.announced = false;
                    return;
                }
                if (listings.get(l.id) == l) announceListing(l);
                return;
            }
            if (!l.expired(now)) return;
            // Se lee ANTES de retirar nada: si el objeto no deserializa hoy, el anuncio se
            // queda como esta en vez de salir del mapa a medio liquidar.
            l.item();
            if (l.isAuction()) {
                if (!listings.remove(l.id, l)) return; // settlement decide destino tras pago
                settleAuction(l);
            } else {
                if (!markTerminal(l, l.seller, false)) return;
                Negotiation neg = negotiations.get(l.id);
                List<ItemStack> barter = claimBarter(neg);
                if (barter != null) {
                    releaseBarter(neg.buyer, barter);
                    closeMenus(neg);
                    notify(neg.buyer, "market.negotiation-cancelled", "player", l.sellerName);
                }
                finishTerminalListing(l);
                notifyItem(l.seller, l.item(), "market.expired");
                addHistory(l.seller, Lang.raw("market.hist-expired", "item", plainItem(l.item())));
                discordFeed.returned(currentName(l.seller, l.sellerName), plainItem(l.item()),
                        MarketDiscordFeed.reason("expired"));
            }
        }
    }

    /** Cierre de subasta: cobra al mejor postor y entrega; si no puede pagar, devuelve. */
    private void settleAuction(MarketListing l) {
        // El anuncio ya salio del mapa (sweep), pero una puja que ya tenia el lock de l
        // podria estar escribiendo topBid: leemos la puja ganadora bajo el mismo lock.
        UUID winner;
        double winningBid;
        String winnerName;
        synchronized (l) {
            winner = l.topBidder;
            winningBid = l.topBid;
            winnerName = l.topBidderName;
        }
        ItemStack item = l.item();
        if (winner == null) {
            if (!markTerminal(l, l.seller, true)) return;
            finishTerminalListing(l);
            notifyItem(l.seller, item, "market.auction-no-bids");
            discordFeed.returned(currentName(l.seller, l.sellerName), plainItem(item),
                    MarketDiscordFeed.reason("no-bids"));
            addHistory(l.seller, Lang.raw("market.hist-expired", "item", plainItem(item)));
            return;
        }
        PaymentOutcome outcome = settleMarketPayment(l, List.of(),
                SettlementLedger.Kind.AUCTION, winner, l.seller, winningBid);
        if (!outcome.paid()) {
            if (outcome.rollbackRequired()
                    && listingsStore.get(String.valueOf(l.id)) != null
                    && markTerminal(l, l.seller, true)) {
                finishTerminalListing(l);
            }
            if (outcome.pending()) {
                notify(l.seller, "market.payment-pending");
                notify(winner, "market.payment-pending");
            } else if (outcome.result() == EconomyTransaction.Result.WITHDRAW_FAILED) {
                notifyItem(l.seller, item, "market.auction-payment-failed-seller",
                        "player", winnerName);
                notifyItem(winner, item, "market.auction-payment-failed-buyer",
                        "amount", money(winningBid));
                discordFeed.returned(currentName(l.seller, l.sellerName), plainItem(item),
                        MarketDiscordFeed.reason("payment-failed"));
            } else {
                notify(l.seller, "market.payment-provider-failed");
                notify(winner, "market.payment-provider-failed");
            }
            return;
        }
        notifyItem(l.seller, item, "market.auction-ended-seller",
                "player", winnerName, "amount", money(winningBid));
        notifyItem(winner, item, "market.auction-won", "amount", money(winningBid));
        addHistory(l.seller, Lang.raw("market.hist-auction-sold",
                "item", plainItem(item), "amount", money(winningBid), "player", winnerName));
        addHistory(winner, Lang.raw("market.hist-auction-won",
                "item", plainItem(item), "amount", money(winningBid), "player", l.sellerName));
        announce(auctionChannel, null, item, l,
                "market.announce-auction-won", null,
                "player", winnerName, "seller", l.sellerName, "amount", money(winningBid));
        discordFeed.auctionWon(currentName(l.seller, l.sellerName),
                currentName(winner, winnerName), plainItem(item), money(winningBid));
    }

    /** Cancela (devolviendo el trueque) toda negociacion en la que participe el jugador. */
    public void abandonNegotiations(UUID who) {
        for (Negotiation neg : List.copyOf(negotiations.values())) {
            if (!neg.isParty(who)) continue;
            List<ItemStack> barter = claimBarter(neg);
            if (barter == null) continue; // ya cerrada por otra ruta (venta simultanea)
            releaseBarter(neg.buyer, barter);
            closeMenus(neg);
            UUID other = neg.isSeller(who) ? neg.buyer : neg.seller;
            String name = neg.isSeller(who) ? neg.sellerName : neg.buyerName;
            notify(other, "market.negotiation-cancelled", "player", name);
        }
    }

    // ---- Alijo de reclamos ----------------------------------------------------------------

    /** Cuantos stacks esperan a que el jugador los recoja. */
    public int claimCount(UUID who) {
        String raw = claimsStore.get(who.toString());
        if (raw == null || raw.isEmpty()) return 0;
        return raw.split(";").length;
    }

    /** Entrega todo lo que quepa del alijo; lo que no cabe se queda esperando. */
    public void claim(Player p) {
        deliverClaims(p, true);
    }

    /** Repara handoffs interrumpidos y limpia markers huerfanos al entrar el jugador. */
    public void recoverPlayerEscrow(Player player) {
        for (MarketListing listing : List.copyOf(pendingListings.values())) {
            if (!listing.seller.equals(player.getUniqueId())) continue;
            String marker = escrowItems.marker(listing.item());
            boolean removed = escrowItems.removeFromInventory(player, marker);
            if (removed) {
                try {
                    player.saveData();
                } catch (RuntimeException failure) {
                    plugin.getLogger().severe("Could not recover pending listing " + listing.id
                            + " for " + player.getName() + ": " + failure.getMessage());
                    continue;
                }
            }
            if (activatePendingListing(listing)) {
                notifyListingCreated(player, listing, listing.item());
            }
        }
        deliverClaims(player, false);
        escrowItems.clearOrphans(player, activeEscrowMarkers());
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not persist escrow cleanup for "
                    + player.getName() + ": " + failure.getMessage());
        }
    }

    private void deliverClaims(Player p, boolean announce) {
        String key = p.getUniqueId().toString();
        synchronized (claimsLock) {
            String observed = claimsStore.get(key);
            List<ItemStack> pending = decodeStash(observed);
            if (pending.isEmpty()) {
                if (announce) p.sendMessage(Lang.get(p, "market.claim-none"));
                escrowItems.clearOrphans(p, activeEscrowMarkers());
                return;
            }

            // Lo que hoy no se puede leer se vuelve a escribir tal cual: ver unreadableStash.
            List<String> unreadable = unreadableStash(observed);
            List<ItemStack> normalized = new ArrayList<>(pending.size());
            for (ItemStack item : pending) normalized.add(escrowItems.marked(item));
            String normalizedRaw = encodeStash(normalized, unreadable);
            if (!java.util.Objects.equals(observed, normalizedRaw)
                    && !claimsStore.compareAndPutDurably(key, observed, normalizedRaw)) {
                p.sendMessage(Lang.get(p, "market.storage-error"));
                return;
            }

            java.util.Set<String> delivered = new java.util.HashSet<>();
            java.util.Set<String> reservedDrafts = publishDraftMarkers();
            List<ItemStack> left = new ArrayList<>();
            int given = 0;
            int reserved = 0;
            for (ItemStack item : normalized) {
                String marker = escrowItems.marker(item);
                // Un shift-click de la GUI guarda primero en claims y despues consume el
                // borrador. Si ese segundo write falla, entregar ahora permitiria conservar
                // tambien la copia publicable. El marker deja el claim bloqueado hasta que
                // el journal de publicacion desaparezca.
                if (reservedDrafts.contains(marker)) {
                    left.add(item);
                    reserved++;
                    continue;
                }
                if (escrowItems.inventoryContains(p, marker)) {
                    delivered.add(marker); // reintento tras saveData previo
                    given++;
                    continue;
                }
                int empty = p.getInventory().firstEmpty();
                if (empty < 0) {
                    left.add(item);
                    continue;
                }
                // Slot vacio: nunca hay entrega parcial ni split ambiguo de un stack.
                p.getInventory().setItem(empty, item.clone());
                delivered.add(marker);
                given++;
            }

            if (!delivered.isEmpty()) {
                try {
                    p.saveData();
                } catch (RuntimeException saveFailure) {
                    plugin.getLogger().severe("Could not save claimed items for " + p.getName()
                            + ": " + saveFailure.getMessage());
                    return; // el Store sigue intacto; los markers hacen seguro el reintento
                }
            }

            boolean consumed = left.isEmpty() && unreadable.isEmpty()
                    ? claimsStore.compareAndRemoveDurably(key, normalizedRaw)
                    : claimsStore.compareAndPutDurably(key, normalizedRaw,
                            encodeStash(left, unreadable));
            if (!consumed) {
                p.sendMessage(Lang.get(p, "market.storage-error"));
                return;
            }

            escrowItems.clearMarkers(p, delivered);
            if (!delivered.isEmpty()) {
                try {
                    p.saveData();
                } catch (RuntimeException cleanupFailure) {
                    plugin.getLogger().warning("Could not remove escrow markers for "
                            + p.getName() + ": " + cleanupFailure.getMessage());
                }
            }
            int inventoryBlocked = left.size() - reserved;
            if (announce && inventoryBlocked > 0) {
                p.sendMessage(Lang.get(p, "market.claim-full", "count", inventoryBlocked));
            }
            if (announce && reserved > 0) {
                p.sendMessage(Lang.get(p, "market.claim-draft-pending", "count", reserved));
            }
            if (announce && given > 0) {
                p.sendMessage(Lang.get(p, "market.claim-done", "count", given));
            }
        }
    }

    /**
     * Entrega y AVISA el alijo a un jugador ya conectado que tenga algo pendiente, tras cerrar
     * un trato (compra/trueque). Si el inventario esta lleno, deliverClaims avisa "claim-full"
     * con la pista de /market claim; si no hay nada pendiente no molesta. Package-private:
     * lo reutiliza TradeManager al cerrar un intercambio directo (mismo alijo compartido).
     */
    void deliverStashIfAny(UUID who) {
        Player online = Bukkit.getPlayer(who);
        if (online != null && claimCount(who) > 0) {
            FoliaTasks.entity(plugin, online, player -> deliverClaims(player, true));
        }
    }

    /** El alijo del jugador esta al tope (Market.Max_Stash). Lo comparte el trade directo. */
    boolean stashFull(UUID who) {
        return claimCount(who) >= maxStash;
    }

    int maxStash() {
        return maxStash;
    }

    /** Entrega items a un jugador este online o no (online: inventario; offline: alijo). */
    private boolean give(UUID who, List<ItemStack> items) {
        if (items.isEmpty()) return true;
        if (!stash(who, items)) {
            plugin.getLogger().severe("Could not durably queue " + items.size()
                    + " item(s) for " + who + ".");
            return false;
        }
        Player online = Bukkit.getPlayer(who);
        if (online != null) {
            FoliaTasks.entity(plugin, online, player -> deliverClaims(player, false));
        }
        return true;
    }

    /**
     * Mete items al alijo de este jugador (para el trade directo: si una parte se desconecta
     * con items en deposito, no se pierden — los recupera con {@code /market claim}, que
     * funciona aunque el mercado este apagado).
     */
    public boolean stashFor(UUID who, List<ItemStack> items) {
        return stash(who, items);
    }

    ItemStack markEscrowItem(ItemStack item) {
        return escrowItems.marked(item);
    }

    String escrowMarker(ItemStack item) {
        return escrowItems.marker(item);
    }

    public boolean hasListingMarker(ItemStack item) {
        String marker = escrowItems.marker(item);
        if (marker == null) return false;
        return java.util.stream.Stream.concat(
                        listings.values().stream(), pendingListings.values().stream())
                .map(MarketListing::item)
                .anyMatch(listed -> marker.equals(escrowItems.marker(listed)));
    }

    /** Transferencia economica usada tambien por /trade; nunca autoriza items si falla. */
    EconomyTransaction.Result transferMoney(UUID from, UUID to, double amount) {
        return EconomyTransaction.transfer(from, to, amount, new EconomyTransaction.Gateway() {
            @Override
            public boolean withdraw(UUID player, double value) {
                return VaultHook.withdraw(Bukkit.getOfflinePlayer(player), value);
            }

            @Override
            public boolean deposit(UUID player, double value) {
                return VaultHook.deposit(Bukkit.getOfflinePlayer(player), value);
            }
        }, this::queueCredit);
    }

    SettlementLedger settlementLedger() {
        return settlements;
    }

    private void runFinancialMaintenance() {
        retryPendingCredits();
        recoverMarketSettlements();
    }

    private synchronized void recoverMarketSettlements() {
        for (SettlementLedger.Settlement original : settlements.all(
                SettlementLedger.Kind.SALE, SettlementLedger.Kind.AUCTION)) {
            SettlementLedger.Settlement settlement = original;
            if (settlement.status() == SettlementLedger.Status.PREPARED) {
                settlement = settlements.transition(settlement, SettlementLedger.Status.UNPAID);
                if (settlement == null) continue;
            }
            if (settlement.status() == SettlementLedger.Status.PROCESSING) {
                // La fila del listing puede haberse cargado antes de ver el WAL. Retirarla
                // del mapa evita una segunda venta mientras el operador revisa Vault.
                listings.remove(settlement.listingId());
                if (ambiguousSettlements.add(settlement.id())) {
                    plugin.getLogger().severe("Vault settlement " + settlement.id() + " ("
                            + settlement.kind() + ", " + settlement.amount() + ") was interrupted"
                            + " during payment. Items remain locked; use /market settlements"
                            + " paid|unpaid <id> after checking the economy ledger.");
                }
                continue;
            }
            if (finalizeMarketSettlement(settlement)) ambiguousSettlements.remove(settlement.id());
        }
    }

    private boolean finalizeMarketSettlement(SettlementLedger.Settlement settlement) {
        boolean paid = settlement.status() == SettlementLedger.Status.PAID;
        if (!paid && settlement.status() != SettlementLedger.Status.UNPAID) return false;
        for (SettlementLedger.Transfer transfer : settlement.transfers()) {
            UUID owner = paid ? transfer.paidOwner() : transfer.originalOwner();
            if (transfer.source() == SettlementLedger.Source.LISTING) {
                String raw = listingsStore.get(String.valueOf(settlement.listingId()));
                if (raw == null) continue; // ya alcanzo el claim ledger en un intento previo
                MarketListing listing = listings.get(settlement.listingId());
                if (listing == null) listing = MarketListing.decode(raw);
                if (listing == null || (!owner.equals(listing.terminalOwner)
                        && !markTerminal(listing, owner, true))) return false;
                if (!finishTerminalListing(listing)) return false;
            } else if (!negotiationEscrow.release(transfer.marker(), owner)) {
                return false;
            }
        }
        return settlements.remove(settlement);
    }

    private synchronized PaymentOutcome settleMarketPayment(MarketListing listing, List<ItemStack> barter,
            SettlementLedger.Kind kind, UUID payer, UUID payee, double amount) {
        List<SettlementLedger.Transfer> transfers = new ArrayList<>();
        transfers.add(SettlementLedger.Transfer.of(SettlementLedger.Source.LISTING,
                listing.seller, payer, escrowItems.marker(listing.item())));
        for (ItemStack item : barter) {
            transfers.add(SettlementLedger.Transfer.of(SettlementLedger.Source.ESCROW,
                    payer, payee, escrowItems.marker(item)));
        }
        SettlementLedger.Settlement settlement = settlements.create(kind, payer, payee,
                amount, listing.id, transfers);
        if (settlement == null) return new PaymentOutcome(null, false, false, true);

        // Sin Vault no existe ventana ambigua: PREPARED -> PAID compromete el intercambio
        // y el finalizador idempotente puede retomarlo tras cualquier crash.
        if (amount == 0) {
            SettlementLedger.Settlement paid = settlements.transition(
                    settlement, SettlementLedger.Status.PAID);
            if (paid == null) return resolvePreVaultFailure(settlement);
            boolean finalized = finalizeMarketSettlement(paid);
            return new PaymentOutcome(EconomyTransaction.Result.SUCCESS, finalized,
                    !finalized, false);
        }
        SettlementLedger.Settlement processing = settlements.transition(
                settlement, SettlementLedger.Status.PROCESSING);
        if (processing == null) return resolvePreVaultFailure(settlement);

        EconomyTransaction.Result result = transferMoney(payer, payee, amount);
        SettlementLedger.Status resolved = result == EconomyTransaction.Result.SUCCESS
                ? SettlementLedger.Status.PAID : SettlementLedger.Status.UNPAID;
        SettlementLedger.Settlement completed = settlements.transition(processing, resolved);
        if (completed == null) {
            ambiguousSettlements.add(processing.id());
            return new PaymentOutcome(result, false, true, false);
        }
        boolean finalized = finalizeMarketSettlement(completed);
        return new PaymentOutcome(result, finalized, !finalized, false);
    }

    private PaymentOutcome resolvePreVaultFailure(SettlementLedger.Settlement settlement) {
        SettlementLedger.Settlement current = settlements.get(settlement.id());
        if (current != null && current.status() == SettlementLedger.Status.PREPARED) {
            SettlementLedger.Settlement unpaid = settlements.transition(
                    current, SettlementLedger.Status.UNPAID);
            if (unpaid != null) {
                boolean finalized = finalizeMarketSettlement(unpaid);
                return new PaymentOutcome(null, false, !finalized, false);
            }
        }
        // El WAL existe pero no pudo resolverse: sus markers deben permanecer bloqueados.
        return new PaymentOutcome(null, false, true, false);
    }

    private record PaymentOutcome(EconomyTransaction.Result result, boolean finalized,
                                  boolean pending, boolean rollbackRequired) {
        boolean paid() {
            return result == EconomyTransaction.Result.SUCCESS && finalized;
        }
    }

    public List<SettlementView> pendingSettlements() {
        return settlements.all(SettlementLedger.Kind.SALE, SettlementLedger.Kind.AUCTION)
                .stream().map(s -> new SettlementView(s.id(), s.kind().name(), s.status().name(),
                        s.payer(), s.payee(), s.amount(), s.created())).toList();
    }

    public synchronized CreditReconcileResult reconcileSettlement(String id, boolean confirmedPaid) {
        SettlementLedger.Settlement settlement = settlements.get(id);
        if (settlement == null || settlement.kind() == SettlementLedger.Kind.TRADE) {
            return CreditReconcileResult.NOT_FOUND;
        }
        if (settlement.status() == SettlementLedger.Status.PROCESSING) {
            settlement = settlements.transition(settlement, confirmedPaid
                    ? SettlementLedger.Status.PAID : SettlementLedger.Status.UNPAID);
            if (settlement == null) return CreditReconcileResult.STORAGE_FAILURE;
        } else if ((settlement.status() == SettlementLedger.Status.PAID) != confirmedPaid
                && settlement.status() != SettlementLedger.Status.PREPARED) {
            return CreditReconcileResult.INVALID;
        } else if (settlement.status() == SettlementLedger.Status.PREPARED) {
            if (confirmedPaid) return CreditReconcileResult.INVALID;
            settlement = settlements.transition(settlement, SettlementLedger.Status.UNPAID);
            if (settlement == null) return CreditReconcileResult.STORAGE_FAILURE;
        }
        if (!finalizeMarketSettlement(settlement)) return CreditReconcileResult.STORAGE_FAILURE;
        ambiguousSettlements.remove(id);
        return confirmedPaid ? CreditReconcileResult.CLEARED : CreditReconcileResult.QUEUED;
    }

    public record SettlementView(String id, String kind, String status, UUID payer, UUID payee,
                                 double amount, long created) {
    }

    private boolean queueCredit(UUID who, double amount) {
        if (who == null || !Double.isFinite(amount) || amount <= 0) return false;
        String id = UUID.randomUUID().toString();
        String pending = encodeCredit(new PendingCredit(who, amount, CreditStatus.PENDING,
                "", 0L));
        boolean persisted;
        synchronized (creditsLock) {
            persisted = creditsStore.compareAndPutDurably(id, null, pending);
        }
        if (persisted) {
            plugin.getLogger().severe("Vault rejected a market refund for " + who + " (" + amount
                    + "); it was durably queued as credit " + id + ".");
        } else {
            plugin.getLogger().severe("CRITICAL: Vault rejected a market refund for " + who
                    + " (" + amount + ") and market_credits could not persist it.");
        }
        return persisted;
    }

    private void retryPendingCredits() {
        if (!VaultHook.hasEconomy() || creditsStore.all().isEmpty()) return;
        synchronized (creditsLock) {
            for (Map.Entry<String, String> entry : creditsStore.all().entrySet()) {
                PendingCredit credit = decodeCredit(entry.getKey(), entry.getValue());
                if (credit == null) {
                    // Asiento ilegible: representa un reintegro adeudado a alguien. Se retira
                    // para no reprocesarlo, pero SIEMPRE con rastro en el log: es la unica
                    // pista que quedara para reconciliarlo a mano contra el ledger de Vault.
                    plugin.getLogger().severe("Discarding a corrupt market credit entry (id "
                            + entry.getKey() + "). Raw value: " + entry.getValue());
                    creditsStore.compareAndRemoveDurably(entry.getKey(), entry.getValue());
                    continue;
                }
                if (credit.status() == CreditStatus.PROCESSING) {
                    if (ambiguousCredits.add(entry.getKey())) {
                        plugin.getLogger().severe("Vault credit " + entry.getKey() + " for "
                                + credit.player() + " (" + credit.amount() + ") was interrupted"
                                + " while processing. It will NOT be auto-paid again; reconcile"
                                + " it manually to avoid a duplicate payment.");
                    }
                    continue;
                }
                PendingCredit claimed = new PendingCredit(credit.player(), credit.amount(),
                        CreditStatus.PROCESSING, UUID.randomUUID().toString(),
                        System.currentTimeMillis());
                String claimedRaw = encodeCredit(claimed);
                if (!creditsStore.compareAndPutDurably(
                        entry.getKey(), entry.getValue(), claimedRaw)) {
                    continue;
                }
                if (VaultHook.deposit(Bukkit.getOfflinePlayer(credit.player()), credit.amount())) {
                    if (creditsStore.compareAndRemoveDurably(entry.getKey(), claimedRaw)) {
                        plugin.getLogger().info("Applied queued Vault refund for " + credit.player()
                                + " (" + credit.amount() + ").");
                    } else {
                        ambiguousCredits.add(entry.getKey());
                        plugin.getLogger().severe("Vault credit " + entry.getKey()
                                + " was paid but its processing marker could not be removed;"
                                + " automatic retry is blocked to prevent double payment.");
                    }
                } else {
                    String pendingRaw = encodeCredit(new PendingCredit(credit.player(),
                            credit.amount(), CreditStatus.PENDING, "", 0L));
                    creditsStore.compareAndPutDurably(entry.getKey(), claimedRaw, pendingRaw);
                }
            }
        }
    }

    /** Snapshot seguro para que un operador pueda reconciliar reintegros ambiguos de Vault. */
    public List<CreditView> pendingCredits() {
        List<CreditView> out = new ArrayList<>();
        synchronized (creditsLock) {
            for (Map.Entry<String, String> entry : creditsStore.all().entrySet()) {
                PendingCredit credit = decodeCredit(entry.getKey(), entry.getValue());
                if (credit == null) {
                    out.add(new CreditView(entry.getKey(), null, 0, "INVALID", 0));
                } else {
                    out.add(new CreditView(entry.getKey(), credit.player(), credit.amount(),
                            credit.status().name(), credit.claimedAt()));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(CreditView::id));
        return List.copyOf(out);
    }

    /**
     * Resuelve el unico punto que Vault no puede hacer exactamente-once: un deposito que pudo
     * completarse justo antes de una caida. {@code confirmedPaid=true} consume el asiento porque
     * el operador comprobo el saldo; false confirma que NO se pago y lo devuelve a la cola.
     */
    public CreditReconcileResult reconcileCredit(String id, boolean confirmedPaid) {
        if (id == null || id.isBlank()) return CreditReconcileResult.NOT_FOUND;
        synchronized (creditsLock) {
            String raw = creditsStore.get(id);
            if (raw == null) return CreditReconcileResult.NOT_FOUND;
            if (confirmedPaid) {
                if (!creditsStore.compareAndRemoveDurably(id, raw)) {
                    return CreditReconcileResult.STORAGE_FAILURE;
                }
                ambiguousCredits.remove(id);
                return CreditReconcileResult.CLEARED;
            }
            PendingCredit credit = decodeCredit(id, raw);
            if (credit == null) return CreditReconcileResult.INVALID;
            if (credit.status() == CreditStatus.PENDING) {
                ambiguousCredits.remove(id);
                return CreditReconcileResult.QUEUED;
            }
            String pending = encodeCredit(new PendingCredit(credit.player(), credit.amount(),
                    CreditStatus.PENDING, "", 0L));
            if (!creditsStore.compareAndPutDurably(id, raw, pending)) {
                return CreditReconcileResult.STORAGE_FAILURE;
            }
            ambiguousCredits.remove(id);
            return CreditReconcileResult.QUEUED;
        }
    }

    public record CreditView(String id, UUID player, double amount, String status,
                             long claimedAt) {
    }

    public enum CreditReconcileResult {
        QUEUED, CLEARED, NOT_FOUND, INVALID, STORAGE_FAILURE
    }

    private enum CreditStatus { PENDING, PROCESSING }

    private record PendingCredit(UUID player, double amount, CreditStatus status,
                                 String claimId, long claimedAt) {
    }

    private static String encodeCredit(PendingCredit credit) {
        return "1|" + (credit.status() == CreditStatus.PENDING ? "P" : "X") + "|"
                + credit.player() + "|" + credit.amount() + "|" + credit.claimId() + "|"
                + credit.claimedAt();
    }

    private static PendingCredit decodeCredit(String key, String raw) {
        if (raw == null) return null;
        try {
            if (!raw.startsWith("1|")) {
                double legacyAmount = Double.parseDouble(raw);
                if (!Double.isFinite(legacyAmount) || legacyAmount <= 0) return null;
                return new PendingCredit(UUID.fromString(key), legacyAmount,
                        CreditStatus.PENDING, "", 0L);
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 6) return null;
            double amount = Double.parseDouble(parts[3]);
            if (!Double.isFinite(amount) || amount <= 0) return null;
            CreditStatus status = switch (parts[1]) {
                case "P" -> CreditStatus.PENDING;
                case "X" -> CreditStatus.PROCESSING;
                default -> null;
            };
            if (status == null) return null;
            return new PendingCredit(UUID.fromString(parts[2]), amount, status, parts[4],
                    Long.parseLong(parts[5]));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private boolean stash(UUID who, List<ItemStack> items) {
        if (items.isEmpty()) return true;
        List<ItemStack> incoming = new ArrayList<>(items.size());
        for (ItemStack item : items) incoming.add(escrowItems.marked(item));
        synchronized (claimsLock) {
            return dev.chatalyst.db.StoreCas.updateDurably(
                    claimsStore, who.toString(), raw -> {
                List<ItemStack> all = decodeStash(raw);
                java.util.Set<String> known = new java.util.HashSet<>();
                List<ItemStack> normalized = new ArrayList<>(all.size() + incoming.size());
                for (ItemStack item : all) {
                    ItemStack marked = escrowItems.marked(item);
                    String marker = escrowItems.marker(marked);
                    if (known.add(marker)) normalized.add(marked);
                }
                for (ItemStack item : incoming) {
                    if (known.add(escrowItems.marker(item))) normalized.add(item.clone());
                }
                return encodeStash(normalized, unreadableStash(raw));
            });
        }
    }

    private static String encodeStash(List<ItemStack> items, List<String> unreadable) {
        List<String> encoded = new ArrayList<>(items.size() + unreadable.size());
        for (ItemStack item : items) encoded.add(MarketListing.itemToB64(item));
        encoded.addAll(unreadable);
        return String.join(";", encoded);
    }

    /** Stacks ilegibles ya avisados en consola, para no repetir el aviso en cada lectura. */
    private static final java.util.Set<String> UNREADABLE_REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Los stacks del alijo que HOY no se pueden leer, tal como estan guardados.
     *
     * <p>Que un item no deserialice no significa que este roto: puede llevar un encantamiento
     * o un atributo de un plugin o datapack que aun no cargo, o que se quito un rato. Antes se
     * saltaban al leer y el alijo se reescribia sin ellos, asi que un fallo temporal se
     * convertia en un objeto perdido para siempre y sin una linea en el log. Ahora se
     * conservan en bruto y vuelven a estar disponibles en cuanto se puedan leer.
     */
    private static List<String> unreadableStash(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String b64 : raw.split(";")) {
            if (b64.isEmpty()) continue;
            try {
                ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
            } catch (Exception unreadable) {
                out.add(b64);
                if (UNREADABLE_REPORTED.add(b64)) {
                    java.util.logging.Logger.getLogger("Chatalyst").severe(
                            "Market stash: an item could not be read and is being KEPT as"
                            + " stored (not delivered, not deleted). It becomes claimable"
                            + " again once the server can read it: " + unreadable);
                }
            }
        }
        return out;
    }

    private java.util.Set<String> activeEscrowMarkers() {
        java.util.Set<String> active = new java.util.HashSet<>();
        for (String raw : claimsStore.all().values()) {
            for (ItemStack item : decodeStash(raw)) {
                String marker = escrowItems.marker(item);
                if (marker != null) active.add(marker);
            }
        }
        for (MarketListing listing : listings.values()) {
            String marker = escrowItems.marker(listing.item());
            if (marker != null) active.add(marker);
        }
        for (MarketListing listing : pendingListings.values()) {
            String marker = escrowItems.marker(listing.item());
            if (marker != null) active.add(marker);
        }
        active.addAll(publishDraftMarkers());
        return active;
    }

    private java.util.Set<String> publishDraftMarkers() {
        if (publish == null) return java.util.Set.of();
        java.util.Set<String> markers = new java.util.HashSet<>();
        for (ItemStack item : publish.activeEscrowItems()) {
            String marker = escrowItems.marker(item);
            if (marker != null) markers.add(marker);
        }
        return markers;
    }

    private static List<ItemStack> decodeStash(String raw) {
        List<ItemStack> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String b64 : raw.split(";")) {
            try {
                out.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)));
            } catch (Exception ignore) {
                // Ilegible hoy: no se entrega, pero tampoco se pierde (unreadableStash lo
                // vuelve a escribir tal cual en cada reescritura del alijo).
            }
        }
        return out;
    }

    // ---- Historial personal de transacciones ------------------------------------------------

    private void addHistory(UUID who, String plainLine) {
        String key = who.toString();
        // \n y \t son los separadores del propio historial: un nombre de item con esos
        // caracteres partiria las lineas, asi que se aplanan a espacios antes de guardar.
        String safe = plainLine.replaceAll("[\\r\\n\\t]", " ");
        String added = System.currentTimeMillis() + "\t" + safe;
        boolean persisted = dev.chatalyst.db.StoreCas.updateDurably(historyStore, key, raw -> {
            List<String> lines = new ArrayList<>();
            lines.add(added);
            if (raw != null && !raw.isEmpty()) {
                for (String old : raw.split("\n")) {
                    if (lines.size() >= HISTORY_CAP) break;
                    lines.add(old);
                }
            }
            return String.join("\n", lines);
        });
        if (!persisted) {
            plugin.getLogger().warning("Could not durably append market history for " + who);
        }
    }

    /** Muestra al jugador sus ultimas transacciones (compras, ventas, subastas, retiros). */
    public void sendHistory(Player p) {
        String raw = historyStore.get(p.getUniqueId().toString());
        if (raw == null || raw.isEmpty()) {
            p.sendMessage(Lang.get(p, "market.history-empty"));
            return;
        }
        String[] lines = raw.split("\n");
        p.sendMessage(Lang.get(p, "market.history-header", "count", lines.length));
        for (String line : lines) {
            int tab = line.indexOf('\t');
            long time = 0;
            String text = line;
            if (tab > 0) {
                try {
                    time = Long.parseLong(line.substring(0, tab));
                } catch (NumberFormatException ignore) {
                    // linea vieja sin marca de tiempo: se muestra igual
                }
                text = line.substring(tab + 1);
            }
            p.sendMessage(Lang.get(p, "market.history-entry",
                    "time", ChannelHistory.ago(time), "text", dev.chatalyst.util.MM.escape(text)));
        }
    }

    // ---- Anuncios publicos --------------------------------------------------------------

    /**
     * Anuncia al canal configurado: cada espectador en SU idioma, con el item embebido
     * (tooltip completo) y botones clicables. SOLO local (nunca cruza el proxy): la compra
     * es de este servidor, asi que anunciar en otros solo tentaria a comprar algo que no
     * esta aqui. El mercado es por-servidor a proposito.
     */
    private void announce(String channelName, Player sender, ItemStack item, MarketListing l,
                          String mainKey, String buttonsKey, Object... repl) {
        Channel target = channels.isEnabled() ? channels.byName(channelName) : null;
        var sound = soundEnabled
                ? dev.chatalyst.util.SoundUtil.sound(soundKey, "minecraft:block.amethyst_block.chime",
                        soundVolume, soundPitch)
                : null;
        // announce() se alcanza desde el barrido async (publicacion programada, cierre de
        // subasta): iterar y tocar entidades aqui no es Folia-safe. El mensaje y el sonido
        // de cada espectador se despachan a su entity scheduler, como el resto de la clase.
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                viewer -> target == null
                        || channels.canSee(viewer, l.seller, target),
                viewer -> {
                    Component msg = withItem(Lang.get(viewer, mainKey, repl), item, l.nameColor);
                    if (buttonsKey != null) {
                        msg = msg.append(Component.space()).append(Lang.get(viewer, buttonsKey, repl));
                    }
                    viewer.sendMessage(msg);
                    // El sonido va SOLO a quien recibe el anuncio (esta en el canal).
                    if (sound != null) dev.chatalyst.util.SoundUtil.play(plugin, viewer, sound);
                });
        Component console = withItem(Lang.get(mainKey, repl), item, l.nameColor);
        plugin.getServer().getConsoleSender().sendMessage(console);
        channelHistory.record(channelName, UUID.randomUUID().toString(), l.seller, l.sellerName,
                PLAIN.serialize(console));
    }

    // ---- GUI helpers ----------------------------------------------------------------------

    /** Reconstruye la GUI de negociacion para ambas partes si la tienen abierta. */
    void refreshMenus(Negotiation neg) {
        for (UUID who : List.of(neg.buyer, neg.seller)) {
            Player p = Bukkit.getPlayer(who);
            if (p == null) continue;
            FoliaTasks.entity(plugin, p, viewer -> {
                var top = viewer.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof MenuHolder holder
                        && holder.getMenu() instanceof NegotiationMenu nm
                        && nm.negotiation() == neg) {
                    top.clear();
                    nm.build(top, viewer);
                }
            });
        }
    }

    /** Cierra la GUI de negociacion a ambas partes (venta hecha, rechazo o cancelacion). */
    private void closeMenus(Negotiation neg) {
        for (UUID who : List.of(neg.buyer, neg.seller)) {
            Player p = Bukkit.getPlayer(who);
            if (p == null) continue;
            FoliaTasks.entity(plugin, p, viewer -> {
                var top = viewer.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof MenuHolder holder
                        && holder.getMenu() instanceof NegotiationMenu nm
                        && nm.negotiation() == neg) {
                    viewer.closeInventory();
                }
            });
        }
    }

    // ---- Utilidades -------------------------------------------------------------------------

    private int nextId() {
        // listingsStore usa perServerStore(): en modo DB compartida su namespace real es
        // <serverTag>_market. El contador replica ese alcance para que cada backend mantenga
        // la misma secuencia local que sus propias filas.
        int fromDb = storage.nextId(storage.serverTag() + "_market", localIds.get());
        if (fromDb > 0) {
            localIds.set(Math.max(localIds.get(), fromDb));
            return fromDb;
        }
        return localIds.updateAndGet(current -> {
            if (current == Integer.MAX_VALUE) {
                throw new IllegalStateException("market id sequence exhausted");
            }
            return current + 1;
        });
    }

    /**
     * Convierte una fila activa en un recibo terminal durable. El item sigue viviendo en la
     * propia fila hasta que tambien alcance el alijo; start() completa el mismo protocolo.
     */
    private boolean markTerminal(MarketListing listing, UUID owner, boolean alreadyRemoved) {
        synchronized (listing) {
            if (!alreadyRemoved && listings.get(listing.id) != listing) return false;
            String expected = listingsStore.get(String.valueOf(listing.id));
            if (expected == null) return false;
            UUID previous = listing.terminalOwner;
            listing.terminalOwner = owner;
            if (!listingsStore.compareAndPutDurably(
                    String.valueOf(listing.id), expected, listing.encode())) {
                listing.terminalOwner = previous;
                return false;
            }
            listings.remove(listing.id, listing);
            return true;
        }
    }

    private boolean finishTerminalListing(MarketListing listing) {
        UUID owner = listing.terminalOwner;
        if (owner == null || !stash(owner, List.of(listing.item()))) return false;
        return listingsStore.compareAndRemoveDurably(
                String.valueOf(listing.id), listing.encode());
    }

    private boolean persist(MarketListing l) {
        // computeIfPresent serializa por clave con listings.remove(id, l) de las rutas de
        // retirada (venta/cancelacion/caducidad, que borran mapa y luego store): persistir
        // un anuncio ya retirado re-crearia su fila y lo resucitaria en el proximo arranque
        // con un item que ya fue entregado o devuelto (duplicacion).
        java.util.concurrent.atomic.AtomicBoolean persisted = new java.util.concurrent.atomic.AtomicBoolean();
        listings.computeIfPresent(l.id, (id, current) -> {
            if (current == l) persisted.set(
                    listingsStore.putDurably(String.valueOf(l.id), l.encode()));
            return current;
        });
        return persisted.get();
    }

    /** Sustituye el marcador {item} por la etiqueta clicable del item (hover = tooltip). */
    private static Component withItem(Component msg, ItemStack item) {
        return msg.replaceText(cfg -> cfg.matchLiteral(ITEM_TOKEN)
                .replacement(ChatRenderer.itemTag(item)));
    }

    /** Variante que tiñe el tag del item con el color de highlight del anuncio (por rango). */
    private static Component withItem(Component msg, ItemStack item, String colorTag) {
        TextColor color = parseColor(colorTag);
        Component tag = color != null ? ChatRenderer.itemTag(item, color) : ChatRenderer.itemTag(item);
        return msg.replaceText(cfg -> cfg.matchLiteral(ITEM_TOKEN).replacement(tag));
    }

    /**
     * Color de highlight del vendedor: el primer rango de {@code Market.Highlight.Ranks} cuyo
     * permiso tenga, o "" si ninguno. Se evalua al publicar (el vendedor esta online) y se
     * guarda en el anuncio, asi se ve igual aunque luego se desconecte.
     */
    public String highlightColorFor(Player seller) {
        for (Map.Entry<String, String> e : highlightRanks.entrySet()) {
            if (seller.hasPermission(e.getKey())) return e.getValue();
        }
        return "";
    }

    /**
     * Tag MiniMessage de color -&gt; {@link TextColor}. Acepta nombre ({@code <#FFB627>}), hex
     * ({@code <#FFD700>}) y gradiente (toma el primer stop, porque el nombre del item suele
     * ser un translatable que no admite gradiente por caracter). "" o invalido -&gt; null.
     */
    static TextColor parseColor(String tag) {
        if (tag == null || tag.isBlank()) return null;
        String t = tag.replace("<", "").replace(">", "").trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("gradient:")) {
            String rest = t.substring("gradient:".length());
            int colon = rest.indexOf(':');
            t = colon > 0 ? rest.substring(0, colon) : rest;
        }
        if (t.startsWith("#")) return TextColor.fromHexString(t);
        return NamedTextColor.NAMES.value(t);
    }

    private void notify(UUID who, String key, Object... repl) {
        Player p = Bukkit.getPlayer(who);
        if (p == null) return;
        FoliaTasks.entity(plugin, p, v -> v.sendMessage(Lang.get(v, key, repl)));
    }

    private void notifyItem(UUID who, ItemStack item, String key, Object... repl) {
        Player p = Bukkit.getPlayer(who);
        if (p == null) return;
        FoliaTasks.entity(plugin, p, v -> v.sendMessage(withItem(Lang.get(v, key, repl), item)));
    }

    /** Resumen "dinero + N objetos" de una oferta, en el idioma del jugador que lo vera. */
    private static String offerSummary(org.bukkit.command.CommandSender viewer, double money,
            int itemCount) {
        if (money > 0 && itemCount > 0) {
            return Lang.raw(viewer, "market.offer-both", "amount", money(money), "count", itemCount);
        }
        if (itemCount > 0) {
            return Lang.raw(viewer, "market.offer-items", "count", itemCount);
        }
        if (money > 0) {
            return money(money);
        }
        return Lang.raw(viewer, "market.offer-nothing");
    }

    static String money(double amount) {
        return VaultHook.format(amount);
    }

    /** Cantidad "cruda" para meter DENTRO de un comando sugerido (sin simbolos del formato). */
    private static String plainMoney(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    /**
     * Como se llama hoy quien publicó esto.
     *
     * <p>El anuncio guarda el nombre del vendedor tal y como era al publicar, que es lo
     * correcto para el chat del momento. Una tarjeta de Discord, en cambio, se queda escrita:
     * enseñar ahí una foto de un nombre viejo convierte a un jugador que se cambió el nombre
     * en un desconocido.
     */
    private String currentName(UUID id, String stored) {
        // El nombre de HOY, pero el MOSTRADO: apodo o disfraz de /anonymous por delante del
        // nombre de la cuenta. Antes iba el de la cuenta a secas, y la tarjeta de Discord
        // decia quien era el vendedor que en el juego aparecia con su alias.
        String account = dev.chatalyst.util.PlayerNames.current(plugin.getServer(), id, stored);
        if (id == null) return dev.chatalyst.util.MM.strip(account);
        return dev.chatalyst.util.MM.strip(
                dev.chatalyst.nick.NickManager.shownName(id.toString(), account));
    }

    /** Nombre plano del item para historiales y resúmenes (custom name o material). */
    static String plainItem(ItemStack item) {
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? PLAIN.serialize(item.getItemMeta().displayName())
                : prettyMaterial(item.getType().name());
        return item.getAmount() > 1 ? name + " x" + item.getAmount() : name;
    }

    private static String prettyMaterial(String material) {
        String[] words = material.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String shownName(Player p) {
        return dev.chatalyst.nick.NickManager.safeName(p);
    }

    static long saturatedDuration(long amount, long unitMillis) {
        if (amount <= 0 || unitMillis <= 0) return 0L;
        try {
            return Math.multiplyExact(amount, unitMillis);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static long deadlineOrMax(long now, long duration) {
        long deadline = dev.chatalyst.util.TimeUtil.deadline(now, duration);
        return deadline == dev.chatalyst.util.TimeUtil.PERMANENT ? Long.MAX_VALUE : deadline;
    }

    private static long boundedDuration(long configured, long minimum, long maximum,
                                        long unitMillis) {
        return saturatedDuration(Math.max(minimum, Math.min(maximum, configured)), unitMillis);
    }
}
