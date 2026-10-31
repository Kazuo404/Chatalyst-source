package dev.chatalyst.monetisation;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.discord.DiscordBot;
import dev.chatalyst.discord.DiscordLinkGate;
import dev.chatalyst.discord.DiscordLinkManager;
import dev.chatalyst.discord.DiscordRankSync;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.module.Module;
import dev.chatalyst.store.StoreService;
import dev.chatalyst.tebex.ShopSignListener;
import dev.chatalyst.tebex.TebexEntitlements;
import dev.chatalyst.tebex.TebexPresentationPrompt;
import dev.chatalyst.tebex.TebexPresentationService;
import dev.chatalyst.tebex.TebexRankService;
import dev.chatalyst.tebex.TebexReconciler;
import dev.chatalyst.tebex.TebexShopService;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Todo lo que un jugador PAGA: identidad de Discord, derechos de Tebex, la tienda de rangos
 * y la entrega de objetos de un solo uso. Van juntos porque comparten una regla: la compra
 * es la fuente de verdad, y el grupo de LuckPerms y el rol de Discord se DERIVAN de ella.
 *
 * <p>Depende de storage (los derechos se persisten) y del bot de Discord, que sigue siendo
 * del plugin: el bot tambien atiende chat y reportes, que no son monetizacion.
 */
public final class MonetisationModule implements Module {

    private final ChatalystPlugin plugin;
    private final StorageManager storage;
    private final DiscordBot bot;

    private DiscordLinkManager links;
    private DiscordRankSync rankSync;
    private DiscordLinkGate linkGate;
    private TebexPresentationService presentations;
    private TebexPresentationPrompt presentationPrompt;
    private TebexEntitlements entitlements;
    private TebexShopService shop;
    private RuntimeCommandRegistrar shopCommands;
    private TebexReconciler reconciler;
    private TebexRankService ranks;
    private StoreService store;
    private boolean stopped;

    public MonetisationModule(ChatalystPlugin plugin, StorageManager storage, DiscordBot bot) {
        this.plugin = plugin;
        this.storage = storage;
        this.bot = bot;
    }

    @Override
    public String name() {
        return "monetisation";
    }

    @Override
    public void enable() {
        // Account links, automatic ranks and Tebex receipts share the normal Chatalyst
        // storage backend. Discord is only a presentation/sync layer: if its bot is disabled,
        // LuckPerms and Tebex delivery continue operating without it.
        links = new DiscordLinkManager(plugin, storage);
        rankSync = new DiscordRankSync(plugin, links);
        rankSync.bindBot(bot);
        bot.bindAccounts(links, rankSync);
        rankSync.start();
        // "This account is linked" as a role. Independent of Rank_Sync: a server can want its
        // members marked without syncing a single rank.
        dev.chatalyst.discord.DiscordLinkedRole linkedRole =
                new dev.chatalyst.discord.DiscordLinkedRole(plugin, bot, links);
        linkedRole.start();
        listen(linkedRole);
        // One-time reward for linking (Discord.Linking.Reward), delivered through the store
        // ledger so it is given exactly once and waits for an offline player.
        new dev.chatalyst.discord.DiscordLinkReward(plugin, storage, links).start();
        // Mandatory linking. Fail-open by design: it enforces nothing unless the bot is
        // genuinely connected to its guild, so a Discord outage never blocks the server.
        linkGate = new DiscordLinkGate(plugin, links);
        listen(linkGate);
        linkGate.start();

        presentations = new TebexPresentationService(plugin, storage);
        presentationPrompt = new TebexPresentationPrompt(plugin, presentations);
        listen(presentations);
        listen(presentationPrompt);
        // Purchases are stored as entitlements — what each player OWNS — and the LuckPerms
        // group and Discord role are derived from them by the reconciler. Any drift (a failed
        // delivery, a manual edit, a Discord role sync, an expiry coming due) is repaired
        // instead of being lost.
        entitlements = new TebexEntitlements(plugin, storage);
        // Discord may manage its ranks, but it can never revoke one that was paid for.
        rankSync.protectPaidGroups(minecraftId -> TebexEntitlements.desiredGroups(
                entitlements.of(minecraftId), System.currentTimeMillis()).keySet());
        // The storefront reads the same ledger, so a card can say "you already own this"
        // instead of selling someone what they are already paying for.
        shop = new TebexShopService(plugin, entitlements);
        shop.loadAcronyms();
        // Top-level /ranks, registered at runtime so a disabled store reserves nothing and the
        // owner can rename it away from a label their shop plugin already owns.
        shopCommands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Tebex.Shop.Command", "Tebex.Shop.Command_Aliases",
                        "ranks", List.of("rangos"),
                        "Rank storefront", "chatalyst.command.ranks",
                        () -> shop != null && shop.enabled()),
                (sender, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Lang.get(sender, "common.players-only"));
                        return true;
                    }
                    shop.open(player);
                    return true;
                });
        listen(shopCommands);
        shopCommands.apply();
        // A physical entrance at spawn: a sign is seen, not memorised.
        listen(new ShopSignListener(plugin, shop));

        reconciler = new TebexReconciler(plugin, entitlements, rankSync);
        listen(reconciler);
        reconciler.start();
        ranks = new TebexRankService(plugin, storage, rankSync, presentations,
                entitlements, reconciler);
        // One-shot goods (items, commands, money). Deliberately NOT reconciled: an item is an
        // event handed over at most once, not a state to repair, or every sweep would hand
        // out another copy.
        store = new StoreService(plugin, storage);
        listen(store);
        store.start();
        // A buyer who links Discord later gets the role they already paid for right then,
        // instead of waiting for the next sweep.
        links.observe((minecraftId, discordId) -> {
            if (discordId != null) reconciler.reconcileSoon(minecraftId);
        });
    }

    /**
     * Para los sondeos y callbacks propios, en el mismo orden en que lo hacia el monolito.
     *
     * <p>Idempotente A PROPOSITO: el plugin lo llama TEMPRANO en su apagado — estos servicios
     * hablan con Discord, LuckPerms y la red, y deben callarse antes de que empiecen a
     * apagarse los managers que sus callbacks pueden alcanzar — mientras que el registro de
     * modulos apaga en orden inverso al de encendido, o sea TARDE (storage debe cerrarse el
     * ultimo). Las dos llamadas son correctas; la segunda no debe repetir el trabajo.
     */
    @Override
    public void disable() {
        if (stopped) return;
        stopped = true;
        stopSafely("Discord rank sync", rankSync == null ? null : rankSync::stop);
        stopSafely("Discord link gate", linkGate == null ? null : linkGate::stop);
        stopSafely("Tebex rank reconciler", reconciler == null ? null : reconciler::stop);
        stopSafely("store delivery", store == null ? null : store::stop);
    }

    // reload() se deja en el no-op heredado: /chatalyst reload llama reloadAll() y DESPUES
    // reloadDiscordBot(), que ya recarga este bloque intercalado con el bot (rankSync, bot,
    // linkGate, en ese orden). Implementarlo aqui lo recargaria dos veces por comando.

    private void listen(org.bukkit.event.Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void stopSafely(String component, Runnable action) {
        if (action == null) return;
        try {
            action.run();
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to shut down " + component + ": " + t.getMessage());
        }
    }

    public TebexReconciler reconciler() {
        return reconciler;
    }

    public DiscordLinkManager links() {
        return links;
    }

    public DiscordRankSync rankSync() {
        return rankSync;
    }

    public DiscordLinkGate linkGate() {
        return linkGate;
    }

    public TebexPresentationService presentations() {
        return presentations;
    }

    public TebexPresentationPrompt presentationPrompt() {
        return presentationPrompt;
    }

    public TebexShopService shop() {
        return shop;
    }

    /** El registrar de /ranks, para reaplicar la etiqueta al recargar. */
    public RuntimeCommandRegistrar shopCommands() {
        return shopCommands;
    }

    public TebexRankService ranks() {
        return ranks;
    }

    public StoreService store() {
        return store;
    }
}
