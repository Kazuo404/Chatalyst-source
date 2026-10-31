package dev.chatalyst.staff.lookup;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.AltTracker;
import dev.chatalyst.staff.IpBanManager;
import dev.chatalyst.staff.OfflineManageMenu;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.staff.StaffModerationModule;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Ficha de identidad de /dupeip en un menu: la cuenta consultada arriba, sus multicuentas en
 * el centro (coloreadas por estado, clic izquierdo = gestionar y sancionar, clic derecho =
 * su propia ficha) y abajo conexiones, nombres, modo profundo, gestionar y "ver en el chat".
 * Vale igual para una cuenta desconectada: todo sale del registro compartido de la red.
 *
 * <p>Los datos se leen fuera del hilo del servidor ({@link #open}) y el menu solo pinta lo
 * ya leido; lo unico que se decide al pintar es "conectado", que depende de quien mira
 * (vanish, anonimos).
 */
public final class IdentityMenu implements Menu {

    private static final int HEAD = 4;
    private static final int FIRST = 9;
    private static final int PAGE_SIZE = 36;
    private static final int PREVIOUS = 45;
    private static final int CONNECTIONS = 46;
    private static final int NAMES = 47;
    private static final int DEEP = 48;
    private static final int BACK = 49;
    private static final int MANAGE = 50;
    private static final int CHAT = 51;
    private static final int HISTORY = 52;
    private static final int NEXT = 53;
    private static final int DEEP_MAX_ACCOUNTS = 60;

    /** Una conexion de la cuenta, ya resuelta. */
    record Conn(String hash, String ip, long first, long last, int accounts, boolean banned,
                boolean current) {
    }

    /**
     * Lo leido del registro. {@code target} es null en modo conexion (las cuentas de una IP);
     * entonces {@code connectionHash} dice cual.
     */
    record Data(UUID target, String name, long firstSeen, long lastSeen,
                List<AccountView.Facts> accounts, List<Conn> connections,
                List<SeenList.Seen> names, boolean deep, String connectionHash, String connectionIp) {
    }

    private final StaffModerationModule module;
    private final Data data;
    private final Consumer<Player> back;
    private int page;

    private IdentityMenu(StaffModerationModule module, Data data, Consumer<Player> back, int page) {
        this.module = module;
        this.data = data;
        this.back = back;
        this.page = Math.max(0, page);
    }

    // ---- Apertura (lee fuera del hilo y abre en el de quien mira) --------------------------

    /** Ficha de una cuenta. {@code back} vuelve al menu anterior; null cierra. */
    public static void open(StaffModerationModule module, Player viewer, UUID target, String name,
                            boolean deep, Consumer<Player> back) {
        LookupSupport.async(module, () -> {
            Data data = load(module, viewer, target, name, deep);
            LookupSupport.reply(module, viewer, v -> {
                if (v instanceof Player p && p.isOnline()) {
                    Menu.open(p, new IdentityMenu(module, data, back, 0));
                }
            });
        });
    }

    /** Las cuentas que han usado una conexion concreta (desde el submenu de conexiones). */
    static void openConnection(StaffModerationModule module, Player viewer, String hash, String ip,
                               Consumer<Player> back) {
        LookupSupport.async(module, () -> {
            Map<String, IpBanManager.IpBan> cache = new ConcurrentHashMap<>();
            List<AccountView.Facts> facts = new ArrayList<>();
            for (AltTracker.Account account : module.alts().accountsOn(hash)) {
                facts.add(AccountView.gather(module, account, cache));
            }
            Data data = new Data(null, null, 0, 0, facts, List.of(), List.of(), false, hash, ip);
            LookupSupport.reply(module, viewer, v -> {
                if (v instanceof Player p && p.isOnline()) {
                    Menu.open(p, new IdentityMenu(module, data, back, 0));
                }
            });
        });
    }

    static Data load(StaffModerationModule module, Player viewer, UUID target, String name,
                     boolean deep) {
        AltTracker alts = module.alts();
        Map<UUID, AltTracker.Account> found = new LinkedHashMap<>();
        for (AltTracker.Account account : alts.accountsSharingWith(target)) {
            found.putIfAbsent(account.id(), account);
        }
        if (deep) {
            for (UUID first : List.copyOf(found.keySet())) {
                if (first.equals(target)) continue;
                for (AltTracker.Account account : alts.accountsSharingWith(first)) {
                    if (found.size() >= DEEP_MAX_ACCOUNTS) break;
                    found.putIfAbsent(account.id(), account);
                }
            }
        }
        Map<String, IpBanManager.IpBan> cache = new ConcurrentHashMap<>();
        List<AccountView.Facts> facts = new ArrayList<>();
        long firstSeen = 0;
        long lastSeen = 0;
        for (AltTracker.Account account : found.values()) {
            AccountView.Facts f = AccountView.gather(module, account, cache);
            if (account.id().equals(target)) {
                firstSeen = f.firstSeen();
                lastSeen = f.lastSeen();
            }
            facts.add(f);
        }
        boolean showIps = LookupSupport.seesIps(viewer);
        String current = alts.lastHash(target);
        var mutesBans = module.ipBans();
        List<Conn> connections = new ArrayList<>();
        for (var c : alts.connections(target)) {
            connections.add(new Conn(c.key(), showIps ? alts.ipOf(c.key()) : null, c.first(), c.last(),
                    alts.accountsOn(c.key()).size(), mutesBans != null && mutesBans.get(c.key()) != null,
                    c.key().equals(current)));
        }
        return new Data(target, name, firstSeen, lastSeen, facts, connections,
                alts.nameHistory(target), deep, null, null);
    }

    // ---- Menu ------------------------------------------------------------------------------

    @Override
    public Component title() {
        return data.target() == null
                ? Lang.get("staff-identity.title-connection", "id", SharedConnections.shortId(data.connectionHash()))
                : Lang.get("staff-identity.title", "player", MM.escape(data.name()));
    }

    @Override
    public Component title(Player viewer) {
        return data.target() == null
                ? Lang.get(viewer, "staff-identity.title-connection",
                        "id", SharedConnections.shortId(data.connectionHash()))
                : Lang.get(viewer, "staff-identity.title", "player", MM.escape(data.name()));
    }

    @Override
    public int size() {
        return 54;
    }

    /** Las cuentas del centro: sin la consultada (ya esta arriba), en el orden de /dupeip. */
    private List<AccountView.Facts> others(Player viewer) {
        Map<UUID, AccountView.Facts> byId = new HashMap<>();
        List<AccountState.Row> rows = new ArrayList<>();
        for (AccountView.Facts f : data.accounts()) {
            if (f.id().equals(data.target())) continue;
            byId.put(f.id(), f);
            rows.add(new AccountState.Row(f.id(), f.name(), AccountView.state(viewer, f), f.lastSeen()));
        }
        List<AccountView.Facts> out = new ArrayList<>();
        for (AccountState.Row row : AccountState.sorted(rows, data.target())) out.add(byId.get(row.id()));
        return out;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        inventory.setItem(HEAD, data.target() == null ? connectionHeader(viewer) : header(viewer));
        List<AccountView.Facts> others = others(viewer);
        int pages = Math.max(1, (others.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        if (others.isEmpty()) {
            inventory.setItem(22, MenuItems.item(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    Lang.raw(viewer, "staff-identity.none"),
                    List.of(Lang.raw(viewer, "staff-identity.hint"))));
        }
        int start = page * PAGE_SIZE;
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(shownOn(others.size()), 1);
        for (int i = 0; i < layout.length; i++) {
            inventory.setItem(layout[i], accountHead(viewer, others.get(start + i)));
        }
        // Barra simetrica: flechas en los extremos, volver en el centro y tres parejas espejo
        // (conexiones|sanciones, nombres|chat, profundo|gestionar).
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        if (data.target() == null) return;

        List<String> connLore = new ArrayList<>();
        connLore.add(Lang.raw(viewer, "staff-identity.connections-lore"));
        connLore.add(Lang.raw(viewer, "staff-identity.open-click"));
        inventory.setItem(CONNECTIONS, MenuItems.item(Material.FILLED_MAP,
                Lang.raw(viewer, "staff-identity.connections-button", "count", data.connections().size()),
                connLore));
        List<String> nameLore = new ArrayList<>();
        for (SeenList.Seen n : data.names().stream().limit(12).toList()) {
            nameLore.add(Lang.raw(viewer, "staff-identity.names-entry", "name", MM.escape(n.key()),
                    "first", LookupSupport.dateOrUnknown(viewer, n.first()),
                    "last", LookupSupport.dateOrUnknown(viewer, n.last())));
        }
        if (nameLore.isEmpty()) {
            nameLore.add(Lang.raw(viewer, "staff-identity.names-only-current", "name", MM.escape(data.name())));
        }
        inventory.setItem(NAMES, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "staff-identity.names-button", "count", Math.max(1, data.names().size())),
                nameLore));
        inventory.setItem(DEEP, MenuItems.item(data.deep() ? Material.ENDER_EYE : Material.ENDER_PEARL,
                Lang.raw(viewer, data.deep() ? "staff-identity.deep-on" : "staff-identity.deep-off"),
                List.of(Lang.raw(viewer, "staff-identity.deep-lore"),
                        Lang.raw(viewer, "staff-identity.toggle-click"))));
        if (canManage(viewer)) {
            inventory.setItem(MANAGE, MenuItems.item(Material.IRON_SWORD,
                    Lang.raw(viewer, "staff-identity.manage-button"),
                    List.of(Lang.raw(viewer, "staff-identity.manage-lore"))));
        } else {
            inventory.setItem(MANAGE, MenuNavigation.unavailable(viewer));
        }
        if (StaffAccess.has(viewer, "chatalyst.command.check")) {
            inventory.setItem(HISTORY, MenuItems.item(Material.BOOK,
                    Lang.raw(viewer, "staff-identity.history-button",
                            "count", module.history().list(data.target()).size()),
                    List.of(Lang.raw(viewer, "staff-identity.history-lore"),
                            Lang.raw(viewer, "staff-identity.open-click"))));
        } else {
            inventory.setItem(HISTORY, MenuNavigation.unavailable(viewer));
        }
        inventory.setItem(CHAT, MenuItems.item(Material.PAPER,
                Lang.raw(viewer, "staff-identity.chat-button"),
                List.of(Lang.raw(viewer, "staff-identity.chat-lore"))));
    }

    private ItemStack header(Player viewer) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, data.target());
            meta.displayName(MenuItems.noItalic(MM.full("<#FBF2FF><bold>" + MM.escape(data.name()))));
            List<Component> lore = new ArrayList<>();
            AccountView.Facts self = data.accounts().stream()
                    .filter(f -> f.id().equals(data.target())).findFirst().orElse(null);
            if (self != null) {
                lore.addAll(AccountView.lines(module, viewer, self, AccountView.state(viewer, self)));
            }
            lore.add(Lang.get(viewer, "staff-identity.summary",
                    "connections", data.connections().size(),
                    "names", Math.max(1, data.names().size()),
                    "accounts", Math.max(0, data.accounts().size() - 1)));
            lore.add(Lang.get(viewer, "staff-identity.uuid", "uuid", data.target().toString()));
            lore.add(Lang.get(viewer, "staff-identity.copy-uuid"));
            meta.lore(lore.stream().map(MenuItems::noItalic).toList());
        });
        return item;
    }

    private ItemStack connectionHeader(Player viewer) {
        String label = LookupSupport.connection(viewer, data.connectionHash(), data.connectionIp());
        return MenuItems.item(Material.FILLED_MAP,
                Lang.raw(viewer, "staff-identity.conn-name", "id", label),
                List.of(Lang.raw(viewer, "staff-identity.conn-accounts", "count", data.accounts().size()),
                        Lang.raw(viewer, "staff-identity.hint")));
    }

    private ItemStack accountHead(Player viewer, AccountView.Facts facts) {
        AccountState state = AccountView.state(viewer, facts);
        String key = switch (state) {
            case ONLINE -> "staff-lookup.name-online";
            case OFFLINE -> "staff-lookup.name-offline";
            case BANNED -> "staff-lookup.name-banned";
            case IP_BANNED -> "staff-lookup.name-ipbanned";
        };
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            dev.chatalyst.gui.PlayerHeads.apply(meta, facts.id());
            meta.displayName(MenuItems.noItalic(Lang.get(viewer, key, "name", MM.escape(facts.name()))));
            List<Component> lore = new ArrayList<>(AccountView.lines(module, viewer, facts, state));
            lore.add(Component.empty());
            lore.add(Lang.get(viewer, "staff-identity.account-click"));
            lore.add(Lang.get(viewer, "staff-identity.account-right"));
            meta.lore(lore.stream().map(MenuItems::noItalic).toList());
        });
        return item;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.command.dupeip")) {
            player.closeInventory();
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            if (back != null) back.accept(player);
            else player.closeInventory();
            return;
        }
        List<AccountView.Facts> others = others(player);
        int pages = Math.max(1, (others.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new IdentityMenu(module, data, back, page - 1));
            return;
        }
        if (slot == NEXT && page + 1 < pages) {
            Menu.open(player, new IdentityMenu(module, data, back, page + 1));
            return;
        }
        if (slot >= FIRST && slot < FIRST + PAGE_SIZE) {
            int local = dev.chatalyst.gui.MenuLayout.indexAt(slot, shownOn(others.size()), 1);
            if (local < 0) return;
            int index = page * PAGE_SIZE + local;
            AccountView.Facts facts = others.get(index);
            if (click.isRightClick()) {
                open(module, player, facts.id(), facts.name(), false, this::reopen);
            } else {
                OfflineManageMenu.open(module, player, Bukkit.getOfflinePlayer(facts.id()), facts.name(),
                        this::reopen);
            }
            return;
        }
        if (data.target() == null) return;
        switch (slot) {
            case HEAD -> {
                player.closeInventory();
                String uuid = data.target().toString();
                player.sendMessage(Lang.get(player, "staff-identity.copied", "player", MM.escape(data.name()),
                                "uuid", uuid)
                        .clickEvent(ClickEvent.copyToClipboard(uuid))
                        .hoverEvent(HoverEvent.showText(Lang.get(player, "staff-lookup.lastuuid-copy"))));
            }
            case CONNECTIONS -> Menu.open(player, new ConnectionsMenu(module, data, this::reopen, 0));
            case DEEP -> open(module, player, data.target(), data.name(), !data.deep(), back);
            case MANAGE -> {
                if (canManage(player)) OfflineManageMenu.open(module, player,
                        Bukkit.getOfflinePlayer(data.target()), data.name(), this::reopen);
            }
            case HISTORY -> {
                if (StaffAccess.has(player, "chatalyst.command.check")) {
                    Menu.open(player, new dev.chatalyst.staff.PunishmentHistoryMenu(module, data.target(),
                            data.name(), this::reopen));
                }
            }
            case CHAT -> {
                player.closeInventory();
                player.performCommand("chatalyst:dupeip " + data.name() + (data.deep() ? " profundo" : "")
                        + " chat");
            }
            default -> { }
        }
    }

    private void reopen(Player player) {
        Menu.open(player, new IdentityMenu(module, data, back, page));
    }

    /** Cuantas cuentas caben en la pagina actual de las {@code total} del centro. */
    private int shownOn(int total) {
        return Math.max(0, Math.min(total - page * PAGE_SIZE, PAGE_SIZE));
    }

    private static boolean canManage(Player viewer) {
        return StaffAccess.has(viewer, "chatalyst.command.ban") || StaffAccess.has(viewer, "chatalyst.command.mute")
                || StaffAccess.has(viewer, "chatalyst.command.check");
    }

    // ---- Submenu de conexiones -------------------------------------------------------------

    /** Las conexiones de la cuenta; clic = las cuentas que usaron esa conexion. */
    static final class ConnectionsMenu implements Menu {

        private final StaffModerationModule module;
        private final Data data;
        private final Consumer<Player> back;
        private int page;

        ConnectionsMenu(StaffModerationModule module, Data data, Consumer<Player> back, int page) {
            this.module = module;
            this.data = data;
            this.back = back;
            this.page = Math.max(0, page);
        }

        @Override
        public Component title() {
            return Lang.get("staff-identity.title-connections", "player", MM.escape(data.name()));
        }

        @Override
        public Component title(Player viewer) {
            return Lang.get(viewer, "staff-identity.title-connections", "player", MM.escape(data.name()));
        }

        @Override
        public int size() {
            return 54;
        }

        @Override
        public void build(Inventory inventory, Player viewer) {
            inventory.clear();
            List<Conn> list = data.connections();
            int pageSize = 45;
            int pages = Math.max(1, (list.size() + pageSize - 1) / pageSize);
            page = Math.min(page, pages - 1);
            if (list.isEmpty()) {
                inventory.setItem(22, MenuItems.item(Material.BARRIER,
                        Lang.raw(viewer, "staff-identity.no-connections"), List.of()));
            }
            int start = page * pageSize;
            int[] layout = dev.chatalyst.gui.MenuLayout.slots(Math.max(0, Math.min(list.size() - start, pageSize)), 0);
            for (int i = start; i < Math.min(list.size(), start + pageSize); i++) {
                Conn c = list.get(i);
                List<String> lore = new ArrayList<>();
                if (c.current()) lore.add(Lang.raw(viewer, "staff-identity.conn-current"));
                lore.add(Lang.raw(viewer, "staff-identity.conn-dates",
                        "first", LookupSupport.dateOrUnknown(viewer, c.first()),
                        "last", LookupSupport.dateOrUnknown(viewer, c.last())));
                lore.add(Lang.raw(viewer, "staff-identity.conn-accounts", "count", c.accounts()));
                if (c.banned()) lore.add(Lang.raw(viewer, "staff-identity.conn-banned"));
                lore.add(Lang.raw(viewer, "staff-identity.conn-click"));
                Material icon = c.banned() ? Material.RED_STAINED_GLASS_PANE
                        : c.current() ? Material.LIME_STAINED_GLASS_PANE
                        : c.accounts() > 1 ? Material.ORANGE_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
                inventory.setItem(layout[i - start], MenuItems.item(icon, Lang.raw(viewer, "staff-identity.conn-name",
                        "id", LookupSupport.connection(viewer, c.hash(), c.ip())), lore));
            }
            inventory.setItem(45, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
            inventory.setItem(49, MenuNavigation.back(viewer));
            inventory.setItem(53, MenuNavigation.nextOrLast(viewer, page + 1, pages));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (!StaffAccess.has(player, "chatalyst.command.dupeip")) {
                player.closeInventory();
                return;
            }
            int pageSize = 45;
            int pages = Math.max(1, (data.connections().size() + pageSize - 1) / pageSize);
            if (slot == 49) {
                back.accept(player);
            } else if (slot == 45 && page > 0) {
                Menu.open(player, new ConnectionsMenu(module, data, back, page - 1));
            } else if (slot == 53 && page + 1 < pages) {
                Menu.open(player, new ConnectionsMenu(module, data, back, page + 1));
            } else if (slot >= 0 && slot < pageSize) {
                int local = dev.chatalyst.gui.MenuLayout.indexAt(slot,
                        Math.max(0, Math.min(data.connections().size() - page * pageSize, pageSize)), 0);
                if (local < 0) return;
                int index = page * pageSize + local;
                Conn c = data.connections().get(index);
                openConnection(module, player, c.hash(), c.ip(),
                        p -> Menu.open(p, new ConnectionsMenu(module, data, back, page)));
            }
        }
    }
}
