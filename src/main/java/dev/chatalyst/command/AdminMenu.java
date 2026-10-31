package dev.chatalyst.command;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.staff.StaffAccess;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.YamlFiles;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.List;

/**
 * Panel de administracion del plugin (/cht gui): interruptores en vivo de los modulos
 * principales (se guardan en config y aplican al momento) y accesos directos a las demas
 * GUIs y herramientas. Solo chatalyst.admin.
 */
public final class AdminMenu implements Menu {

    private enum Section {
        HOME("home"), CHAT("chat"), CONTENT("content"), MODERATION("moderation"),
        NETWORK("network"), SYSTEM("system");

        private final String key;

        Section(String key) {
            this.key = key;
        }
    }

    /** Un interruptor: ruta en config.yml, icono y clave lang de su nombre. */
    private record Toggle(Section section, int slot, String path, Material material,
                          String labelKey) {
    }

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle(Section.CHAT, 10, "Chat.Enabled", Material.PAPER, "admin-gui.chat"),
            new Toggle(Section.CHAT, 11, "Channels.Enabled", Material.BOOK, "admin-gui.channels"),
            new Toggle(Section.CHAT, 12, "Channels.Exclusive", Material.IRON_DOOR, "admin-gui.exclusive"),
            new Toggle(Section.CHAT, 13, "Channel_Router.Enabled", Material.EMERALD, "admin-gui.router"),
            new Toggle(Section.CHAT, 14, "Channels.History.Enabled", Material.WRITABLE_BOOK, "admin-gui.history"),
            new Toggle(Section.CONTENT, 20, "Announcer.Enabled", Material.BELL, "admin-gui.announcer"),
            new Toggle(Section.SYSTEM, 13, "Anti_Spam.Enabled", Material.SHIELD, "admin-gui.antispam"));

    private static final int SLOT_HOME = 45;
    private static final int SLOT_CLOSE = 53;   // barra simetrica en todas las secciones: inicio 45 | cerrar 53
    private static final int SLOT_FANCY_JOINS = 11;
    private static final int SLOT_DEATH = 13;
    private static final int SLOT_MENTIONS = 15;
    private static final int SLOT_DISCORD = 16;
    private static final int SLOT_MARKET = 15;
    private static final int SLOT_STAFF_MENTIONS = 20;
    private static final int SLOT_TRANSLATION = 22;
    private static final int SLOT_LOCK = 11;
    private static final int SLOT_SLOWMODE = 13;
    private static final int SLOT_CLEAR = 15;
    private static final int SLOT_CHANNELS_GUI = 24;
    private static final int SLOT_ANNOUNCE = 22;
    private static final int SLOT_REPORTS = 29;
    private static final int SLOT_PLAYERS = 31;
    private static final int SLOT_BOOKS = 24;
    private static final int SLOT_EVENTS = 31;
    private static final int SLOT_TAGS = 33;
    private static final int SLOT_TEBEX_PRESENTATIONS = 29;
    private static final int SLOT_DOCTOR = 11;
    private static final int SLOT_RELOAD = 15;
    private static final int SLOT_STAFF_DIRECTORY = 33;
    private static final int SLOT_NETWORK_PLAYERS = 11;
    private static final int SLOT_NETWORK_ANNOUNCE = 13;
    private static final int SLOT_NETWORK_CENTER = 15;
    private static final int SLOT_NETWORK_WATCH = 22;

    private static final int[] SLOWMODE_LADDER = {0, 3, 5, 10, 30};

    private final ChatalystPlugin plugin;
    private final Section section;

    public AdminMenu(ChatalystPlugin plugin) {
        this(plugin, Section.HOME);
    }

    private AdminMenu(ChatalystPlugin plugin, Section section) {
        this.plugin = plugin;
        this.section = section;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw(titleKey()));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, titleKey());
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        if (section == Section.HOME) {
            buildHome(inv, viewer);
            return;
        }
        for (Toggle t : TOGGLES) {
            if (t.section() != section) continue;
            boolean on = plugin.getConfig().getBoolean(t.path(), true);
            inv.setItem(t.slot(), toggleItem(viewer, t.material(), t.labelKey(), on));
        }
        switch (section) {
            case CHAT -> buildChat(inv, viewer);
            case CONTENT -> buildContent(inv, viewer);
            case MODERATION -> buildModeration(inv, viewer);
            case NETWORK -> buildNetwork(inv, viewer);
            case SYSTEM -> buildSystem(inv, viewer);
            case HOME -> { }
        }
        inv.setItem(SLOT_HOME, MenuNavigation.home(viewer));
        inv.setItem(SLOT_CLOSE, MenuNavigation.close(viewer));
    }

    private void buildHome(Inventory inv, Player viewer) {
        inv.setItem(11, sectionItem(viewer, Section.CHAT, Material.ECHO_SHARD));
        inv.setItem(13, sectionItem(viewer, Section.CONTENT, Material.AMETHYST_SHARD));
        inv.setItem(15, sectionItem(viewer, Section.MODERATION, Material.NETHERITE_SWORD));
        inv.setItem(30, sectionItem(viewer, Section.NETWORK, Material.RECOVERY_COMPASS));
        inv.setItem(32, sectionItem(viewer, Section.SYSTEM, Material.COMPARATOR));
    }

    private void buildChat(Inventory inv, Player viewer) {
        inv.setItem(SLOT_MENTIONS, toggleItem(viewer, Material.NOTE_BLOCK,
                "admin-gui.mentions", plugin.getConfig().getBoolean("Mentions.Enabled", true)));
        inv.setItem(SLOT_DISCORD, toggleItem(viewer, Material.ENDER_EYE,
                "admin-gui.discord", plugin.getConfig().getBoolean("Discord.Enabled", false)));
        inv.setItem(SLOT_STAFF_MENTIONS, toggleItem(viewer, Material.SCULK_SENSOR,
                "admin-gui.staff-mentions",
                plugin.getConfig().getBoolean("Staff_Mentions.Enabled", true)));
        inv.setItem(SLOT_TRANSLATION, translationItem(viewer));
        inv.setItem(SLOT_CHANNELS_GUI, actionItem(viewer, Material.COMPASS,
                "admin-gui.channels-gui", "admin-gui.open"));
    }

    private void buildContent(Inventory inv, Player viewer) {
        inv.setItem(SLOT_FANCY_JOINS, item(viewer, Material.LIGHTNING_ROD,
                Lang.raw(viewer, "admin-gui.fancy-joins"), List.of(Lang.raw(viewer, "admin-gui.open"))));
        inv.setItem(SLOT_DEATH, toggleItem(viewer, Material.SKELETON_SKULL,
                "admin-gui.deaths", deathEnabled()));
        inv.setItem(SLOT_MARKET, toggleItem(viewer, Material.GOLD_INGOT,
                "admin-gui.market", plugin.getConfig().getBoolean("Trade_Plugins.Announce.Enabled", true)));
        inv.setItem(SLOT_ANNOUNCE, actionItem(viewer, Material.BEACON,
                "admin-gui.announce", "admin-gui.open"));
        inv.setItem(SLOT_BOOKS, actionItem(viewer, Material.KNOWLEDGE_BOOK,
                "admin-gui.books", "admin-gui.open"));
        inv.setItem(SLOT_EVENTS, actionItem(viewer, Material.CLOCK,
                "admin-gui.events", "admin-gui.open"));
        inv.setItem(SLOT_TAGS, actionItem(viewer, Material.NAME_TAG,
                "admin-gui.tags", "admin-gui.open"));
        inv.setItem(SLOT_TEBEX_PRESENTATIONS, actionItem(viewer, Material.EMERALD,
                "admin-gui.tebex-presentations", "admin-gui.open"));
    }

    private void buildModeration(Inventory inv, Player viewer) {
        boolean locked = plugin.getChatModeration() != null && plugin.getChatModeration().isLocked();
        inv.setItem(SLOT_LOCK, toggleItem(viewer, Material.IRON_BARS, "admin-gui.chatlock", locked));
        int slow = plugin.getChatModeration() != null ? plugin.getChatModeration().getSlowmode() : 0;
        inv.setItem(SLOT_SLOWMODE, item(viewer, Material.SOUL_SAND,
                Lang.raw(viewer, "admin-gui.slowmode", "seconds", slow),
                List.of(Lang.raw(viewer, "admin-gui.slowmode-hint", "next", nextSlowmode(slow)))));
        inv.setItem(SLOT_CLEAR, item(viewer, Material.WATER_BUCKET,
                Lang.raw(viewer, "admin-gui.clear"),
                List.of(Lang.raw(viewer, "admin-gui.clear-hint"))));
        inv.setItem(SLOT_REPORTS, actionItem(viewer, Material.WRITTEN_BOOK,
                "admin-gui.reports", "admin-gui.open"));
        inv.setItem(SLOT_PLAYERS, actionItem(viewer, Material.PLAYER_HEAD,
                "admin-gui.players", "admin-gui.open"));
        boolean canViewStaff = StaffAccess.has(viewer, "chatalyst.admin.staff.directory")
                && plugin.getStaffDirectory() != null;
        inv.setItem(SLOT_STAFF_DIRECTORY, item(viewer,
                canViewStaff ? Material.ENDER_PEARL : Material.GRAY_DYE,
                Lang.raw(viewer, "admin-gui.staff-directory"),
                List.of(Lang.raw(viewer, canViewStaff
                        ? "admin-gui.staff-directory-hint" : "admin-gui.network-unavailable"))));
    }

    private void buildSystem(Inventory inv, Player viewer) {
        inv.setItem(SLOT_DOCTOR, actionItem(viewer, Material.LANTERN,
                "admin-gui.doctor", "admin-gui.run"));
        inv.setItem(SLOT_RELOAD, actionItem(viewer, Material.REPEATER,
                "admin-gui.reload", "admin-gui.run"));
    }

    private void buildNetwork(Inventory inv, Player viewer) {
        boolean canAnnounceNetwork = StaffAccess.has(viewer, "chatalyst.admin.network.announce")
                && plugin.getAnnounceDraftManager() != null
                && plugin.getAnnounceDraftManager().networkAvailable(viewer);
        inv.setItem(SLOT_NETWORK_ANNOUNCE, item(viewer,
                canAnnounceNetwork ? Material.BEACON : Material.GRAY_DYE,
                Lang.raw(viewer, "admin-gui.network-announce"),
                List.of(Lang.raw(viewer, canAnnounceNetwork
                        ? "admin-gui.network-announce-hint" : "admin-gui.network-unavailable"))));
        boolean canMonitor = StaffAccess.has(viewer, "chatalyst.admin.network.monitor")
                && plugin.getNetworkMonitor() != null && plugin.getNetworkMonitor().isEnabled();
        inv.setItem(SLOT_NETWORK_CENTER, item(viewer,
                canMonitor ? Material.RECOVERY_COMPASS : Material.GRAY_DYE,
                Lang.raw(viewer, "admin-gui.network-center"),
                List.of(Lang.raw(viewer, canMonitor
                        ? "admin-gui.network-center-hint" : "admin-gui.network-unavailable"))));
        inv.setItem(SLOT_NETWORK_WATCH, item(viewer,
                canMonitor ? Material.SPYGLASS : Material.GRAY_DYE,
                Lang.raw(viewer, "admin-gui.network-watch"),
                List.of(Lang.raw(viewer, canMonitor
                        ? "admin-gui.network-watch-hint" : "admin-gui.network-unavailable"))));
        boolean canViewPlayers = StaffAccess.has(viewer, "chatalyst.admin.players.directory")
                && plugin.getNetworkPlayerDirectory() != null;
        inv.setItem(SLOT_NETWORK_PLAYERS, item(viewer,
                canViewPlayers ? Material.PLAYER_HEAD : Material.GRAY_DYE,
                Lang.raw(viewer, "admin-gui.network-players"),
                List.of(Lang.raw(viewer, canViewPlayers
                        ? "admin-gui.network-players-hint" : "admin-gui.network-unavailable"))));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!StaffAccess.has(player, "chatalyst.admin")) return;
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (section == Section.HOME) {
            switch (slot) {
                case 11 -> openSection(player, Section.CHAT);
                case 13 -> openSection(player, Section.CONTENT);
                case 15 -> openSection(player, Section.MODERATION);
                case 30 -> openSection(player, Section.NETWORK);
                case 32 -> openSection(player, Section.SYSTEM);
                case SLOT_HOME -> Menu.open(player, new AdminMenu(plugin));
                default -> { }
            }
            return;
        }
        if (slot == SLOT_HOME) {
            Menu.open(player, new AdminMenu(plugin));
            return;
        }
        for (Toggle t : TOGGLES) {
            if (t.section() == section && t.slot() == slot) {
                flipConfig(player, t.path());
                Menu.open(player, this);
                return;
            }
        }
        switch (section) {
            case CHAT -> clickChat(player, slot);
            case CONTENT -> clickContent(player, slot);
            case MODERATION -> clickModeration(player, slot);
            case NETWORK -> clickNetwork(player, slot);
            case SYSTEM -> clickSystem(player, slot);
            case HOME -> { }
        }
    }

    private void clickChat(Player player, int slot) {
        switch (slot) {
            case SLOT_MENTIONS -> {
                flipConfig(player, "Mentions.Enabled");
                Menu.open(player, this);
            }
            case SLOT_DISCORD -> {
                flipConfig(player, "Discord.Enabled");
                Menu.open(player, this);
            }
            case SLOT_STAFF_MENTIONS -> {
                flipConfig(player, "Staff_Mentions.Enabled");
                Menu.open(player, this);
            }
            case SLOT_TRANSLATION -> {
                flipTranslation(player);
                Menu.open(player, this);
            }
            case SLOT_CHANNELS_GUI -> Menu.open(player, new dev.chatalyst.channel.ChannelMenu(
                    plugin.getChannelManager(), plugin.getChannelHistory(), plugin.getFrequencyManager()));
            default -> { }
        }
    }

    private void clickContent(Player player, int slot) {
        switch (slot) {
            case SLOT_FANCY_JOINS -> Menu.open(player,
                    new dev.chatalyst.messages.FancyJoinMenu(plugin));
            case SLOT_DEATH -> {
                flipDeath(player);
                Menu.open(player, this);
            }
            case SLOT_MARKET -> {
                flipConfig(player, "Trade_Plugins.Announce.Enabled");
                Menu.open(player, this);
            }
            case SLOT_ANNOUNCE -> {
                player.closeInventory();
                player.performCommand("announce");
            }
            case SLOT_BOOKS -> Menu.open(player, new dev.chatalyst.book.BookAdminMenu(
                    plugin, plugin.getBookManager(), 0));
            case SLOT_EVENTS -> {
                player.closeInventory();
                player.performCommand("events");
            }
            case SLOT_TAGS -> {
                if (plugin.getTagManager() != null
                        && player.hasPermission("chatalyst.tag.admin")) {
                    Menu.open(player, new dev.chatalyst.tag.TagAdminMenu(
                            plugin, plugin.getTagManager(), 0));
                } else {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                }
            }
            case SLOT_TEBEX_PRESENTATIONS -> {
                if (plugin.getTebexPresentations() != null
                        && (player.hasPermission("chatalyst.admin")
                        || player.hasPermission(
                        "chatalyst.admin.tebex.presentation"))) {
                    Menu.open(player, new dev.chatalyst.tebex.TebexPresentationMenu(
                            plugin, plugin.getTebexPresentations(), 0));
                } else {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                }
            }
            default -> { }
        }
    }

    private void clickModeration(Player player, int slot) {
        switch (slot) {
            case SLOT_LOCK -> {
                var mod = plugin.getChatModeration();
                if (mod != null) {
                    mod.setLocked(!mod.isLocked());
                    audit(player, "chatlock", mod.isLocked() ? "on" : "off");
                }
                Menu.open(player, this);
            }
            case SLOT_SLOWMODE -> {
                var mod = plugin.getChatModeration();
                if (mod != null) {
                    int next = nextSlowmode(mod.getSlowmode());
                    mod.setSlowmode(next);
                    audit(player, "slowmode", String.valueOf(next));
                }
                Menu.open(player, this);
            }
            case SLOT_CLEAR -> {
                player.closeInventory();
                player.performCommand("clearchat");
            }
            case SLOT_REPORTS -> {
                player.closeInventory();
                player.performCommand("reports");
            }
            case SLOT_PLAYERS -> {
                dev.chatalyst.staff.StaffDashboardMenu.open(
                        plugin.getStaffModeration(), player);
            }
            case SLOT_STAFF_DIRECTORY -> {
                if (StaffAccess.has(player, "chatalyst.admin.staff.directory")
                        && plugin.getStaffDirectory() != null) {
                    Menu.open(player, new dev.chatalyst.staff.StaffDirectoryMenu(
                            plugin.getStaffDirectory(), 0));
                } else {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                }
            }
            default -> { }
        }
    }

    private void clickSystem(Player player, int slot) {
        switch (slot) {
            case SLOT_DOCTOR -> {
                player.closeInventory();
                player.performCommand("chatalyst doctor");
            }
            case SLOT_RELOAD -> {
                player.closeInventory();
                player.performCommand("chatalyst reload");
            }
            default -> { }
        }
    }

    private void clickNetwork(Player player, int slot) {
        switch (slot) {
            case SLOT_NETWORK_ANNOUNCE -> {
                if (StaffAccess.has(player, "chatalyst.admin.network.announce")
                        && plugin.getAnnounceDraftManager() != null
                        && plugin.getAnnounceDraftManager().networkAvailable(player)) {
                    plugin.getAnnounceDraftManager().draft(player.getUniqueId()).network = true;
                    Menu.open(player, new dev.chatalyst.announce.AnnounceMenu(
                            plugin.getAnnounceDraftManager()));
                } else {
                    player.sendMessage(Lang.get(player, "admin-gui.network-unavailable"));
                }
            }
            case SLOT_NETWORK_CENTER -> {
                if (StaffAccess.has(player, "chatalyst.admin.network.monitor")
                        && plugin.getNetworkMonitor() != null && plugin.getNetworkMonitor().isEnabled()) {
                    plugin.getNetworkMonitor().requestRefresh();
                    Menu.open(player, new dev.chatalyst.network.NetworkOverviewMenu(
                            plugin, plugin.getNetworkMonitor()));
                } else {
                    player.sendMessage(Lang.get(player, "admin-gui.network-unavailable"));
                }
            }
            case SLOT_NETWORK_WATCH -> {
                if (StaffAccess.has(player, "chatalyst.admin.network.monitor")
                        && plugin.getNetworkMonitor() != null && plugin.getNetworkMonitor().isEnabled()) {
                    plugin.getNetworkMonitor().requestRefresh();
                    Menu.open(player, new dev.chatalyst.network.NetworkWatchMenu(
                            plugin, plugin.getNetworkMonitor()));
                } else {
                    player.sendMessage(Lang.get(player, "admin-gui.network-unavailable"));
                }
            }
            case SLOT_NETWORK_PLAYERS -> {
                if (StaffAccess.has(player, "chatalyst.admin.players.directory")
                        && plugin.getNetworkPlayerDirectory() != null) {
                    if (plugin.getNetworkMonitor() != null) {
                        plugin.getNetworkMonitor().requestRefresh();
                    }
                    player.getScheduler().runDelayed(plugin, task -> Menu.open(player,
                            new dev.chatalyst.staff.NetworkPlayerServerMenu(
                            plugin, plugin.getNetworkPlayerDirectory(), 0)), null, 10L);
                } else {
                    player.sendMessage(Lang.get(player, "common.no-permission"));
                }
            }
            default -> {
            }
        }
    }

    private void openSection(Player player, Section next) {
        Menu.open(player, new AdminMenu(plugin, next));
    }

    private String titleKey() {
        return section == Section.HOME ? "admin-gui.title"
                : "admin-gui.section-" + section.key + "-title";
    }

    private ItemStack sectionItem(Player viewer, Section target, Material material) {
        String key = "admin-gui.section-" + target.key;
        return dev.chatalyst.gui.MenuItems.item(material, Lang.raw(viewer, key + ".name"),
                Lang.rawList(viewer, key + ".lore"));
    }

    private ItemStack actionItem(Player viewer, Material material, String nameKey, String loreKey) {
        return item(viewer, material, Lang.raw(viewer, nameKey),
                List.of(Lang.raw(viewer, loreKey)));
    }

    /** Invierte un booleano de config.yml, guarda y recarga el modulo que lo cachea. */
    private void flipConfig(Player player, String path) {
        boolean next;
        synchronized (plugin.getConfig()) {
            boolean previous = plugin.getConfig().getBoolean(path, true);
            next = !previous;
            plugin.getConfig().set(path, next);
            if (!plugin.saveConfigChecked()) {
                plugin.getConfig().set(path, previous);
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                return;
            }
        }
        reloadFor(path);
        audit(player, "toggle", path + " -> " + next);
        player.sendMessage(Lang.get(player, next ? "admin-gui.turned-on" : "admin-gui.turned-off",
                "option", path));
    }

    /** Los modulos que cachean su config en load() se recargan en el acto. */
    private void reloadFor(String path) {
        if (path.startsWith("Chat.")) plugin.getConfigManager().load();
        else if (path.startsWith("Channel_Router")) plugin.getChannelRouter().load();
        else if (path.startsWith("Channels.History")) plugin.getChannelHistory().load();
        else if (path.startsWith("Channels")) plugin.getChannelManager().load();
        else if (path.startsWith("Anti_Spam")) plugin.getAntiSpam().load();
        else if (path.startsWith("Announcer")) {
            plugin.getAnnouncer().load();
            plugin.getAnnouncer().start();
        } else if (path.startsWith("Trade_Plugins")) plugin.getTradePluginsHook().load();
        else if (path.startsWith("Staff_Mentions") && plugin.getStaffMentionRouter() != null) {
            plugin.getStaffMentionRouter().load();
            if (plugin.getSupportConversations() != null) plugin.getSupportConversations().load();
        } else if (path.startsWith("Translation") && plugin.getTranslation() != null) {
            plugin.getTranslation().load(plugin.getConfig().getConfigurationSection("Translation"));
        } else if (path.startsWith("Discord")) {
            plugin.reloadDiscordBot();
        }
        // Mentions se leen en vivo: no cachean nada.
    }

    /**
     * Interruptor global del traductor. No permite dejarlo aparentemente activo sin endpoint;
     * al encenderse, la opcion personal aparece sola en /toggle, pero sigue apagada por jugador.
     */
    private void flipTranslation(Player player) {
        boolean current;
        String provider;
        synchronized (plugin.getConfig()) {
            current = plugin.getConfig().getBoolean("Translation.Enabled", false);
            provider = plugin.getConfig().getString("Translation.Provider_Url", "").trim();
        }
        if (provider.isEmpty()) {
            // Normaliza un Enabled=true editado a mano sin endpoint (inoperante de todos modos).
            if (current) {
                synchronized (plugin.getConfig()) {
                    plugin.getConfig().set("Translation.Enabled", false);
                    if (!plugin.saveConfigChecked()) {
                        plugin.getConfig().set("Translation.Enabled", true);
                        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                        return;
                    }
                }
                reloadFor("Translation.Enabled");
                audit(player, "toggle", "Translation.Enabled -> false (no provider)");
            }
            player.sendMessage(Lang.get(player, "admin-gui.translation-needs-provider"));
            return;
        }
        boolean next = !current;
        synchronized (plugin.getConfig()) {
            plugin.getConfig().set("Translation.Enabled", next);
            if (!plugin.saveConfigChecked()) {
                plugin.getConfig().set("Translation.Enabled", current);
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
                return;
            }
        }
        reloadFor("Translation.Enabled");
        audit(player, "toggle", "Translation.Enabled -> " + next);
        player.sendMessage(Lang.get(player, next ? "admin-gui.turned-on" : "admin-gui.turned-off",
                "option", "Translation.Enabled"));
    }

    private boolean deathEnabled() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        return YamlConfiguration.loadConfiguration(file).getBoolean("Death.Enabled", true);
    }

    /** Death.Enabled vive en messages.yml: se voltea alli y se recarga ServerMessages. */
    private void flipDeath(Player player) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        try {
            boolean next;
            // Carga estricta + escritura atomica bajo el lock del archivo: un messages.yml
            // roto no debe cargarse como vacio y quedar machacado con solo Death.Enabled.
            synchronized (YamlFiles.lock(file)) {
                YamlConfiguration yaml = YamlFiles.load(file);
                next = !yaml.getBoolean("Death.Enabled", true);
                yaml.set("Death.Enabled", next);
                YamlFiles.saveAtomic(yaml, file);
            }
            plugin.getServerMessages().load();
            audit(player, "toggle", "Death.Enabled -> " + next);
            player.sendMessage(Lang.get(player, next ? "admin-gui.turned-on" : "admin-gui.turned-off",
                    "option", "Death.Enabled"));
        } catch (Exception ex) {
            player.sendMessage(Lang.get(player, "plugin.reload-error", "error", ex.getMessage()));
        }
    }

    private void audit(Player player, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(player, "admin-gui-" + action, detail);
        }
    }

    private static int nextSlowmode(int current) {
        for (int step : SLOWMODE_LADDER) {
            if (step > current) return step;
        }
        return SLOWMODE_LADDER[0];
    }

    private ItemStack toggleItem(Player viewer, Material material, String labelKey, boolean on) {
        String state = Lang.raw(viewer, on ? "admin-gui.state-on" : "admin-gui.state-off");
        return item(viewer, on ? material : Material.GRAY_DYE,
                (on ? "<#FBF2FF>" : "<#7B68A0>") + Lang.raw(viewer, labelKey),
                List.of(state, Lang.raw(viewer, "admin-gui.click-toggle")));
    }

    private ItemStack translationItem(Player viewer) {
        boolean on = plugin.getTranslation() != null && plugin.getTranslation().enabled();
        String state = Lang.raw(viewer, on ? "admin-gui.state-on" : "admin-gui.state-off");
        String provider = plugin.getConfig().getString("Translation.Provider_Url", "").trim();
        List<String> lore = new java.util.ArrayList<>();
        lore.add(state);
        if (provider.isEmpty()) {
            lore.add(Lang.raw(viewer, "admin-gui.translation-needs-provider"));
        }
        lore.add(Lang.raw(viewer, "admin-gui.click-toggle"));
        return item(viewer, on ? Material.FILLED_MAP : Material.GRAY_DYE,
                (on ? "<#FBF2FF>" : "<#7B68A0>")
                        + Lang.raw(viewer, "admin-gui.translation"), lore);
    }

    private static ItemStack item(Player viewer, Material material, String name, List<String> lore) {
        return dev.chatalyst.gui.MenuItems.item(material, name,
                lore.stream().map(line -> "<#C0B3D9>" + line).toList());
    }
}
