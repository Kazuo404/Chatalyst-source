package dev.chatalyst.command;

import dev.chatalyst.gui.ChatColorPalette;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.gui.TextStyleOption;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paleta que el STAFF aplica a OTRO jugador: color de apodo o color de chat.
 *
 * <p>Misma rejilla que /chatcolor y /nickcolor, pero el objetivo es otro y el filtro de
 * permisos tambien: aqui manda el permiso del STAFF ({@code .others}), no lo que el
 * objetivo tenga desbloqueado — si un admin quiere premiar a alguien con un degradado que
 * su rango aun no alcanza, debe poder.
 */
public final class TargetPaletteMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 47;
    private static final int RESET = 49;
    private static final int CLOSE = 51;   // espejo de BACK
    private static final int NEXT = 53;

    private final dev.chatalyst.ChatalystPlugin plugin;
    private final UUID targetId;
    private final String targetName;
    private final boolean nick;
    private final int listPage;
    private final Map<Integer, ChatColorPalette.Option> slots = new HashMap<>();
    private int palettePage;

    public TargetPaletteMenu(dev.chatalyst.ChatalystPlugin plugin, UUID targetId, String targetName,
                             boolean nick, int listPage) {
        this(plugin, targetId, targetName, nick, listPage, 0);
    }

    private TargetPaletteMenu(dev.chatalyst.ChatalystPlugin plugin, UUID targetId,
                              String targetName, boolean nick, int listPage, int palettePage) {
        this.plugin = plugin;
        this.targetId = targetId;
        this.targetName = targetName;
        this.nick = nick;
        this.listPage = listPage;
        this.palettePage = Math.max(0, palettePage);
    }

    private String key(String sufijo) {
        return (nick ? "nickcolor." : "color.") + sufijo;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw(key("gui-title-other"), "player", targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, key("gui-title-other"), "player", targetName);
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        List<ChatColorPalette.Option> options = ChatColorPalette.options(plugin);
        int pages = Math.max(1, (options.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        palettePage = Math.min(palettePage, pages - 1);
        int start = palettePage * PAGE_SIZE;
        int[] layout = dev.chatalyst.gui.MenuLayout.slots(
                Math.max(0, Math.min(options.size() - start, PAGE_SIZE)), 0);
        for (int index = start; index < Math.min(options.size(), start + PAGE_SIZE); index++) {
            int slot = layout[index - start];
            ChatColorPalette.Option option = options.get(index);
            slots.put(slot, option);
            List<String> lore = new ArrayList<>();
            lore.add(Lang.raw(viewer, "players.manage.palette-apply", "player", targetName));
            inv.setItem(slot, MenuItems.item(option.material(),
                    option.tag() + option.displayLabel(viewer), lore));
        }
        inv.setItem(RESET, MenuItems.item(Material.BARRIER,
                Lang.raw(viewer, key("reset-item")),
                List.of(Lang.raw(viewer, "players.manage.palette-reset", "player", targetName))));
        inv.setItem(PREVIOUS,
                MenuNavigation.previousOrFirst(viewer, palettePage + 1, pages));
        inv.setItem(BACK, MenuNavigation.back(viewer));
        inv.setItem(CLOSE, MenuNavigation.close(viewer));
        inv.setItem(NEXT,
                MenuNavigation.nextOrLast(viewer, palettePage + 1, pages));
    }

    @Override
    public void onClick(Player staff, int slot, ClickType click) {
        String permission = nick ? "chatalyst.nick.color.others"
                : "chatalyst.chat.color.others";
        if (!staff.hasPermission(permission)) {
            staff.closeInventory();
            staff.sendMessage(Lang.get(staff, "common.no-permission"));
            return;
        }
        if (slot == BACK) {
            PlayerManageMenu.open(plugin, staff, targetId, listPage);
            return;
        }
        if (slot == CLOSE) {
            staff.closeInventory();
            return;
        }
        if (slot == PREVIOUS && palettePage > 0) {
            Menu.open(staff, new TargetPaletteMenu(plugin, targetId, targetName,
                    nick, listPage, palettePage - 1));
            return;
        }
        int pages = Math.max(1,
                (ChatColorPalette.options(plugin).size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (slot == NEXT && palettePage + 1 < pages) {
            Menu.open(staff, new TargetPaletteMenu(plugin, targetId, targetName,
                    nick, listPage, palettePage + 1));
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            staff.sendMessage(Lang.get(staff, "common.player-offline"));
            return;
        }
        if (nick && dev.chatalyst.hook.Placeholders.isAnonymous(targetId)) {
            staff.closeInventory();
            staff.sendMessage(Lang.get(staff, "nick.anonymous-other"));
            return;
        }
        if (slot == RESET) {
            aplicar(staff, target, null);
            return;
        }
        ChatColorPalette.Option option = slots.get(slot);
        if (option != null) aplicar(staff, target, option);
    }

    private void aplicar(Player staff, Player target, ChatColorPalette.Option option) {
        boolean ok;
        if (nick) {
            var nicks = plugin.getNickManager();
            String actual = nicks.getNick(target.getUniqueId());
            String plano = MM.strip(actual != null && !actual.isEmpty() ? actual : target.getName());
            if (option == null) {
                // Sin color y con su propio nombre: se quita el apodo en vez de dejar uno
                // "invisible" que solo envuelve el nombre real.
                ok = plano.equalsIgnoreCase(target.getName())
                        ? nicks.removeNick(target.getUniqueId())
                        : nicks.setNick(target.getUniqueId(), MM.escape(plano));
            } else {
                ok = nicks.setNick(target.getUniqueId(), TextStyleOption.decorate(
                        option.tag(), TextStyleOption.detect(actual), plano));
            }
        } else {
            var colors = plugin.getChatColorManager();
            // Igual que en /chatcolor: restablecer se lleva color Y estilos.
            ok = option == null
                    ? colors.clearColor(target.getUniqueId())
                            && colors.clearStyles(target.getUniqueId())
                    : colors.setColor(target.getUniqueId(), option.tag());
        }
        if (!ok) {
            staff.sendMessage(Lang.get(staff, "plugin.storage-unavailable"));
            return;
        }
        staff.sendMessage(option == null
                ? Lang.get(staff, key("reset-other"), "player", target.getName())
                : Lang.get(staff, key("set-other"),
                        "player", target.getName(), "color", option.displayLabel(staff)));
        staff.closeInventory();
    }
}
