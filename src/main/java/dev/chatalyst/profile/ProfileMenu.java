package dev.chatalyst.profile;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.PlayerHeads;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.hook.PartiesHook;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /perfil: cabecera con los datos del jugador, los interruptores de /toggle (mas el de /tpa)
 * y accesos directos a bloqueados, nick, tags, color de chat y Discord.
 *
 * <p>Bedrock ve este mismo menu: Chatalyst no tiene formularios de Floodgate y el resto de
 * sus GUIs ya se disenan para verse bien con Geyser (sin relleno, simetricas).
 */
public final class ProfileMenu implements Menu {

    /** Accesos directos, en el orden en que se pintan. */
    enum Shortcut {
        BLOCKED(Material.BARRIER, null),
        NICK(Material.NAME_TAG, "Profile.Shortcuts.Nick_Color"),
        TAGS(Material.OAK_HANGING_SIGN, "Profile.Shortcuts.Tags"),
        CHAT_COLOR(Material.WRITABLE_BOOK, "Profile.Shortcuts.Chat_Color"),
        DISCORD(Material.AMETHYST_SHARD, "Profile.Shortcuts.Discord");

        final Material material;
        final String commandPath;

        Shortcut(Material material, String commandPath) {
            this.material = material;
            this.commandPath = commandPath;
        }

        String langKey() {
            return "profile.shortcut." + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private final ChatalystPlugin plugin;
    private final Map<Integer, ProfileSetting> settingSlots = new HashMap<>();
    private final Map<Integer, Shortcut> shortcutSlots = new HashMap<>();

    public ProfileMenu(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("profile.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "profile.gui-title");
    }

    @Override
    public int size() {
        return ProfileLayout.PROFILE_SIZE;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        settingSlots.clear();
        shortcutSlots.clear();

        inv.setItem(ProfileLayout.HEAD, header(viewer));
        PlayerHeads.completeLater(viewer, inv, ProfileLayout.HEAD, viewer.getUniqueId());

        List<ProfileSetting> settings = ProfileSetting.visible(
                translationAvailable(), TeleportRequests.available(viewer));
        int[] slots = ProfileLayout.settingSlots(settings.size());
        for (int i = 0; i < slots.length; i++) {
            ProfileSetting setting = settings.get(i);
            settingSlots.put(slots[i], setting);
            inv.setItem(slots[i], settingItem(viewer, setting));
        }

        Shortcut[] shortcuts = Shortcut.values();
        int[] shortcutPositions = ProfileLayout.shortcutSlots(shortcuts.length);
        for (int i = 0; i < shortcutPositions.length; i++) {
            shortcutSlots.put(shortcutPositions[i], shortcuts[i]);
            inv.setItem(shortcutPositions[i], shortcutItem(viewer, shortcuts[i]));
        }

        inv.setItem(ProfileLayout.CLOSE, MenuNavigation.close(viewer));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == ProfileLayout.CLOSE) {
            player.closeInventory();
            return;
        }
        ProfileSetting setting = settingSlots.get(slot);
        if (setting != null) {
            toggle(player, setting);
            return;
        }
        Shortcut shortcut = shortcutSlots.get(slot);
        if (shortcut != null) open(player, shortcut, click);
    }

    // ---- Cabecera -------------------------------------------------------------

    private ItemStack header(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "profile.info.rank", "rank", rank(viewer)));
        lore.add(Lang.raw(viewer, "profile.info.nick", "nick", NickManager.safeName(viewer)));
        if (VaultHook.hasEconomy()) {
            lore.add(Lang.raw(viewer, "profile.info.balance",
                    "balance", MM.escape(VaultHook.format(VaultHook.balance(viewer)))));
        }
        lore.add(Lang.raw(viewer, "profile.info.kills",
                "kills", statistic(viewer, Statistic.PLAYER_KILLS),
                "deaths", statistic(viewer, Statistic.DEATHS)));
        lore.add(Lang.raw(viewer, "profile.info.clan", "clan", clan(viewer)));
        var marriage = plugin.getMarriageManager();
        if (marriage != null && marriage.isEnabled()) {
            String partner = marriage.partnerName(viewer.getUniqueId());
            lore.add(Lang.raw(viewer, "profile.info.partner", "partner",
                    partner.isEmpty() ? Lang.raw(viewer, "profile.info.no-partner") : partner));
        }
        if (discordAvailable()) {
            boolean linked = plugin.getDiscordLinks().discordId(viewer.getUniqueId()) != null;
            lore.add(Lang.raw(viewer, "profile.info.discord", "status",
                    Lang.raw(viewer, linked ? "profile.info.discord-yes" : "profile.info.discord-no")));
        }
        ItemStack head = MenuItems.item(Material.PLAYER_HEAD,
                Lang.raw(viewer, "profile.info.name", "player", MM.escape(viewer.getName())), lore);
        head.editMeta(SkullMeta.class, meta -> PlayerHeads.apply(meta, viewer.getUniqueId()));
        return head;
    }

    private static String rank(Player viewer) {
        String group = LuckPermsHook.group(viewer.getUniqueId());
        if (group == null || group.isBlank()) group = VaultHook.group(viewer);
        if (group == null || group.isBlank()) return Lang.raw(viewer, "profile.info.no-rank");
        String display = LuckPermsHook.groupDisplayName(group);
        String shown = display == null || display.isBlank() ? group : display;
        // El nombre para mostrar de LuckPerms suele traer codigos legacy (&d...): se pinta plano.
        return MM.escape(MM.strip(dev.chatalyst.util.Legacy.toMini(shown)));
    }

    private static String statistic(Player viewer, Statistic statistic) {
        try {
            return String.valueOf(viewer.getStatistic(statistic));
        } catch (RuntimeException unavailable) {
            return "0";
        }
    }

    /**
     * Clan: placeholder de PlaceholderAPI configurable (Chatalyst no tiene hook de clanes) y,
     * si no hay o no resuelve, la party de Parties.
     */
    private String clan(Player viewer) {
        String placeholder = plugin.getConfig().getString("Profile.Clan_Placeholder", "");
        if (placeholder != null && !placeholder.isBlank()) {
            String value = Placeholders.apply(viewer, placeholder.trim());
            if (value != null && !value.isBlank() && !value.equals(placeholder.trim())) {
                return value;
            }
        }
        String party = PartiesHook.currentPartyName(viewer);
        return party == null || party.isBlank()
                ? Lang.raw(viewer, "profile.info.no-clan") : MM.escape(party);
    }

    private boolean discordAvailable() {
        return plugin.getDiscordLinks() != null
                && plugin.getConfig().getBoolean("Discord.Enabled", false);
    }

    // ---- Ajustes --------------------------------------------------------------

    private ItemStack settingItem(Player viewer, ProfileSetting setting) {
        ProfileSetting.State state = ProfileSetting.State.of(currentState(viewer, setting));
        Material material = switch (state) {
            case ON -> Material.LIME_DYE;
            case OFF -> Material.GRAY_DYE;
            case UNKNOWN -> Material.ENDER_PEARL;
        };
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, state.langKey()));
        lore.add(Lang.raw(viewer, setting.langKey() + ".lore"));
        lore.add(Lang.raw(viewer, "profile.click-toggle"));
        return MenuItems.item(material, Lang.raw(viewer, setting.langKey() + ".name"), lore);
    }

    private Boolean currentState(Player viewer, ProfileSetting setting) {
        if (setting == ProfileSetting.TELEPORT) return TeleportRequests.enabled(viewer);
        ToggleManager toggles = plugin.getToggleManager();
        return toggles == null ? null : toggles.isEnabled(viewer, setting.toggle());
    }

    private void toggle(Player player, ProfileSetting setting) {
        if (setting == ProfileSetting.TELEPORT) {
            TeleportRequests.Result result = TeleportRequests.toggle(player);
            if (!result.done()) {
                player.sendMessage(Lang.get(player, "profile.teleport-failed"));
            } else if (result.enabled() != null) {
                player.sendMessage(Lang.get(player,
                        result.enabled() ? "profile.teleport-on" : "profile.teleport-off"));
            }
            Menu.open(player, new ProfileMenu(plugin));
            return;
        }
        // Mismo camino que /toggle y su menu: se guarda con ToggleManager.
        if (setting == ProfileSetting.TRANSLATE && !translationAvailable()) return;
        ToggleManager toggles = plugin.getToggleManager();
        if (toggles == null) return;
        ToggleManager.ToggleResult result = toggles.toggleResult(player, setting.toggle());
        if (!result.persisted()) {
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return;
        }
        Menu.open(player, new ProfileMenu(plugin));
    }

    private boolean translationAvailable() {
        return plugin.getTranslation() != null && plugin.getTranslation().enabled();
    }

    // ---- Accesos directos -----------------------------------------------------

    private ItemStack shortcutItem(Player viewer, Shortcut shortcut) {
        List<String> lore = new ArrayList<>();
        if (shortcut == Shortcut.BLOCKED) {
            int count = plugin.getPrivateMessages() == null ? 0
                    : plugin.getPrivateMessages().ignoredBy(viewer.getUniqueId()).size();
            lore.addAll(Lang.rawList(viewer, shortcut.langKey() + ".lore", "count", count));
        } else {
            lore.addAll(Lang.rawList(viewer, shortcut.langKey() + ".lore"));
        }
        return MenuItems.item(shortcut.material, Lang.raw(viewer, shortcut.langKey() + ".name"), lore);
    }

    private void open(Player player, Shortcut shortcut, ClickType click) {
        if (shortcut == Shortcut.BLOCKED) {
            Menu.open(player, new BlockedMenu(plugin, 0));
            return;
        }
        player.closeInventory();
        if (shortcut == Shortcut.NICK && !click.isRightClick()) {
            // /nick no tiene menu: se le deja el comando listo para escribir en el chat.
            player.sendMessage(Lang.get(player, "profile.nick-hint"));
            return;
        }
        String command = plugin.getConfig().getString(shortcut.commandPath, "");
        if (command == null || command.isBlank()) return;
        String line = command.strip().startsWith("/") ? command.strip().substring(1) : command.strip();
        // Un tick despues: abrir otro inventario dentro del click del actual es fragil.
        FoliaTasks.entity(plugin, player, p -> p.performCommand(line));
    }
}
