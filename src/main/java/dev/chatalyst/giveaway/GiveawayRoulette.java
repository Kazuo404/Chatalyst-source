package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The wheel itself: a reel of heads scrolling through a window, the prize above it and one
 * status line below.
 *
 * <p>Deliberately sparse. Chatalyst's menus ship without filler panes because a Bedrock client
 * renders an inventory as a touch grid where every decorative pane is one more tappable thing
 * (see {@link Menu}); a roulette needs a frame around its window to read as a wheel at all, so
 * it gets exactly that — two markers — and nothing else.
 *
 * <p>Every viewer holds their OWN inventory rather than sharing one. A shared inventory would
 * be simpler on Paper, but under Folia an inventory belongs to the region of whoever has it
 * open, and two players in different regions may not touch the same one. Per-viewer copies also
 * make the title and the status line arrive in each player's own language.
 */
public final class GiveawayRoulette implements Menu {

    /** What the wheel is doing right now. The status slot is the only thing that says so. */
    public enum Phase { READY, SPINNING, WINNER }

    static final int PRIZE_SLOT = 4;
    static final int MARKER_TOP = 13;
    static final int REEL_START = 18;
    static final int REEL_SIZE = 9;
    /** The window: the head sitting here when the wheel stops is the winner. */
    static final int REEL_CENTER = REEL_START + REEL_SIZE / 2;
    static final int MARKER_BOTTOM = 31;
    static final int STATUS_SLOT = 49;
    private static final int SIZE = 54;

    private final ChatalystPlugin plugin;
    private final GiveawayPrize prize;

    // Written by the spin ticker (one thread) and read by the per-viewer repaint tasks.
    private volatile List<UUID> reel = List.of();
    private volatile Phase phase = Phase.READY;
    private volatile int countdown;
    private volatile UUID winner;
    private volatile int entrants;
    // Una cabeza por jugador, construida UNA vez y reusada en todos los fotogramas.
    //
    // Antes se reconstruian las nueve en cada fotograma: unas 300 por sorteo y por jugador
    // del carrete. Cada construccion pedia la skin, y para quien no estuviera cacheado eso
    // era una consulta a Mojang — cientos en nueve segundos, hasta que Mojang devolvia
    // HTTP 429 y dejaba limitado al servidor entero. Con los jugadores de Bedrock era peor:
    // su UUID de Floodgate no existe en Mojang, asi que fallaba SIEMPRE y se reintentaba
    // en cada fotograma. El carrete vive lo que vive el sorteo, y estos mapas con el.
    private final java.util.Map<UUID, ItemStack> plainHeads =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, ItemStack> centreHeads =
            new java.util.concurrent.ConcurrentHashMap<>();

    GiveawayRoulette(ChatalystPlugin plugin, GiveawayPrize prize) {
        this.plugin = plugin;
        this.prize = prize;
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "giveaway-gui.title");
    }

    @Override
    public Component title() {
        return Lang.get("giveaway-gui.title");
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.setItem(PRIZE_SLOT, prizeItem(viewer));
        inv.setItem(MARKER_TOP, marker(viewer, "giveaway-gui.marker-down"));
        inv.setItem(MARKER_BOTTOM, marker(viewer, "giveaway-gui.marker-up"));
        List<ItemStack> heads = renderHeads();
        for (int i = 0; i < REEL_SIZE; i++) {
            inv.setItem(REEL_START + i, heads.get(i));
        }
        inv.setItem(STATUS_SLOT, statusItem(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        // Nothing is clickable: a draw is watched, not played. MenuListener already cancels
        // the click, so this only has to stay empty.
    }

    // ---- state, written by the manager -----------------------------------------------

    void reel(List<UUID> heads) {
        this.reel = List.copyOf(heads);
    }

    void phase(Phase next) {
        this.phase = next;
    }

    void countdown(int seconds) {
        this.countdown = seconds;
    }

    void entrants(int count) {
        this.entrants = count;
    }

    void winner(UUID id) {
        this.winner = id;
    }

    Phase phase() {
        return phase;
    }

    UUID winner() {
        return winner;
    }

    GiveawayPrize prize() {
        return prize;
    }

    // ---- painting ---------------------------------------------------------------------

    /**
     * Repaints the moving parts for everyone currently watching THIS wheel. Viewers who closed
     * the menu simply stop matching and drop out, so no separate list of watchers can go stale.
     */
    void repaintAll() {
        // The nine heads are viewer-independent, so they are rendered ONCE per frame and the
        // same instances go into every inventory (Bukkit copies a stack on the way in). Only
        // the status line is rebuilt per viewer, because it is the one part that has to arrive
        // in that player's own language.
        List<ItemStack> heads = renderHeads();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            FoliaTasks.entity(plugin, player, viewer -> {
                Inventory open = viewer.getOpenInventory().getTopInventory();
                if (!(open.getHolder() instanceof MenuHolder holder)
                        || holder.getMenu() != this) {
                    return;
                }
                for (int i = 0; i < REEL_SIZE; i++) {
                    open.setItem(REEL_START + i, heads.get(i));
                }
                open.setItem(STATUS_SLOT, statusItem(viewer));
            });
        }
    }

    /** Opens (or reopens) the wheel for one player, off whatever thread the caller is on. */
    void openFor(Player player) {
        FoliaTasks.entity(plugin, player, viewer -> Menu.open(viewer, this));
    }

    private List<ItemStack> renderHeads() {
        List<UUID> current = reel;
        List<ItemStack> heads = new ArrayList<>(REEL_SIZE);
        for (int i = 0; i < REEL_SIZE; i++) {
            UUID id = current.isEmpty() ? null : current.get(i % current.size());
            heads.add(head(id, i == REEL_SIZE / 2));
        }
        return heads;
    }

    private ItemStack head(UUID id, boolean centre) {
        if (id == null) return new ItemStack(Material.AIR);
        return (centre ? centreHeads : plainHeads)
                .computeIfAbsent(id, key -> buildHead(key, centre));
    }

    private ItemStack buildHead(UUID id, boolean centre) {
        // The name on a head is the one the server SHOWS: a player in a nick, or hiding behind
        // an anonymous alias, must not have their real name printed nine slots wide. One path
        // for online and offline alike, so an entrant who disconnects mid-spin keeps their
        // name on the reel instead of turning into a question mark.
        String shown = GiveawayNames.shown(plugin.getServer(), id);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        // applyCached, no apply: por este camino una skin que no tengamos ya cacheada NO
        // se sale a buscar a la red. Ver PlayerHeads.applyCached.
        item.editMeta(SkullMeta.class, meta -> PlayerHeads.applyCached(meta, id));
        ItemStack named = MenuItems.item(item,
                (centre ? "<gradient:#FFD166:#FF9F1C><bold>" : "<#E0AAFF>") + shown,
                List.of());
        if (centre) named.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return named;
    }

    private ItemStack prizeItem(Player viewer) {
        ItemStack item = new ItemStack(prize.icon());
        if (prize.glow()) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        List<String> lore = new ArrayList<>(prize.description(plugin.getConfig(), viewer));
        lore.add("");
        lore.add(Lang.raw(viewer, "giveaway-gui.prize-kind",
                "kind", Lang.raw(viewer, "giveaway.kind." + prize.kind().key())));
        return MenuItems.item(item,
                prize.displayName(plugin.getConfig(), viewer), List.copyOf(lore));
    }

    private ItemStack marker(Player viewer, String key) {
        return MenuItems.item(Material.ARROW, Lang.raw(viewer, key), List.of());
    }

    private ItemStack statusItem(Player viewer) {
        UUID won = winner;
        String name = won == null ? "" : GiveawayNames.shown(plugin.getServer(), won);
        String title = switch (phase) {
            case READY -> Lang.raw(viewer, "giveaway-gui.status-ready",
                    "seconds", String.valueOf(Math.max(0, countdown)));
            case SPINNING -> Lang.raw(viewer, "giveaway-gui.status-spinning");
            case WINNER -> Lang.raw(viewer, "giveaway-gui.status-winner", "player", name);
        };
        return MenuItems.item(phase == Phase.WINNER ? Material.NETHER_STAR : Material.CLOCK,
                title, List.of(Lang.raw(viewer, "giveaway-gui.status-entrants",
                        "count", String.valueOf(entrants))));
    }
}
