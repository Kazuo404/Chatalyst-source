package dev.chatalyst.staff;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Selector de motivo para una accion de moderacion: presets configurables en
 * {@code Staff_Moderation.Reason_Presets} mas un motivo personalizado escrito por chat.
 * Cada eleccion despacha el comando real, que re-verifica permisos, protege OPs,
 * funciona con objetivos offline (freeze/ban) y deja el motivo en la auditoria.
 */
public final class ModerationReasonMenu implements Menu {

    /** Accion que despacha este menu; el permiso corta el menu antes de ejecutar nada. */
    public enum Action {
        FREEZE("freeze", "chatalyst.command.freeze", Material.PACKED_ICE),
        BAN("ban", "chatalyst.command.ban", Material.RED_CONCRETE),
        KICK("kick", "chatalyst.command.kick", Material.YELLOW_CONCRETE),
        MUTE("mute", "chatalyst.command.mute", Material.ORANGE_DYE),
        WARN("warn", "chatalyst.command.warn", Material.PAPER);

        private final String command;
        private final String permission;
        private final Material icon;

        Action(String command, String permission, Material icon) {
            this.command = command;
            this.permission = permission;
            this.icon = icon;
        }
    }

    private static final int[] PRESET_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int CUSTOM = 21;
    private static final int PREVIOUS = 18;
    private static final int BACK = 23;
    private static final int NEXT = 26;

    private final StaffModerationModule module;
    private final Action action;
    private final String targetName;
    /** Argumentos entre el nombre y el motivo (p. ej. la duracion "10m" de un mute). */
    private final String extraArgs;
    private final Consumer<Player> back;
    private int page;

    public ModerationReasonMenu(StaffModerationModule module, Action action,
                                String targetName, Consumer<Player> back) {
        this(module, action, targetName, "", back, 0);
    }

    public ModerationReasonMenu(StaffModerationModule module, Action action,
                                String targetName, String extraArgs, Consumer<Player> back) {
        this(module, action, targetName, extraArgs, back, 0);
    }

    private ModerationReasonMenu(StaffModerationModule module, Action action,
                                 String targetName, String extraArgs, Consumer<Player> back,
                                 int page) {
        this.module = module;
        this.action = action;
        this.targetName = targetName;
        this.extraArgs = extraArgs == null ? "" : extraArgs.trim();
        this.back = back;
        this.page = Math.max(0, page);
    }

    private String commandPrefix() {
        // Namespaced: el despacho de la GUI siempre ejecuta el comando DE CHATALYST aunque
        // otro plugin le haya quitado la etiqueta plana (/ban, /mute...) tras un reload.
        return "chatalyst:" + action.command + " " + targetName
                + (extraArgs.isEmpty() ? "" : " " + extraArgs);
    }

    @Override
    public Component title() {
        return Lang.get("staff-moderation.reason-title",
                "action", Lang.raw("staff-moderation.action-" + name()),
                "player", MM.escape(targetName));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "staff-moderation.reason-title",
                "action", Lang.raw(viewer, "staff-moderation.action-" + name()),
                "player", MM.escape(targetName));
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();
        List<String> presets = presets(viewer);
        int pages = Math.max(1, (presets.size() + PRESET_SLOTS.length - 1) / PRESET_SLOTS.length);
        page = Math.min(page, pages - 1);
        int start = page * PRESET_SLOTS.length;
        int visible = Math.min(PRESET_SLOTS.length, presets.size() - start);
        for (int i = 0; i < visible; i++) {
            inventory.setItem(PRESET_SLOTS[i], MenuItems.item(action.icon, presets.get(start + i),
                    List.of(Lang.raw(viewer, "staff-moderation.reason-preset-lore"))));
        }
        inventory.setItem(PREVIOUS, MenuNavigation.previousOrFirst(viewer, page + 1, pages));
        inventory.setItem(CUSTOM, MenuItems.item(Material.WRITABLE_BOOK,
                Lang.raw(viewer, "staff-moderation.reason-custom"),
                List.of(Lang.raw(viewer, "staff-moderation.reason-custom-lore"))));
        inventory.setItem(BACK, MenuNavigation.back(viewer));
        inventory.setItem(NEXT, MenuNavigation.nextOrLast(viewer, page + 1, pages));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            if (back != null) back.accept(player);
            else player.closeInventory();
            return;
        }
        if (!StaffAccess.has(player, action.permission)) {
            player.sendMessage(Lang.get(player, "common.no-permission"));
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            Menu.open(player, new ModerationReasonMenu(module, action, targetName,
                    extraArgs, back, page - 1));
            return;
        }
        int pages = Math.max(1,
                (presets(player).size() + PRESET_SLOTS.length - 1) / PRESET_SLOTS.length);
        if (slot == NEXT && page + 1 < pages) {
            Menu.open(player, new ModerationReasonMenu(module, action, targetName,
                    extraArgs, back, page + 1));
            return;
        }
        if (slot == CUSTOM) {
            module.reasonPrompt().begin(player, commandPrefix(), targetName);
            return;
        }
        List<String> presets = presets(player);
        int start = page * PRESET_SLOTS.length;
        int visible = Math.min(PRESET_SLOTS.length, presets.size() - start);
        for (int i = 0; i < visible; i++) {
            if (PRESET_SLOTS[i] == slot) {
                player.closeInventory();
                player.performCommand(commandPrefix() + " " + presets.get(start + i));
                return;
            }
        }
    }

    private List<String> presets(Player viewer) {
        return dev.chatalyst.lang.LocalizedConfig.list(
                module.plugin().getConfig(), "Staff_Moderation.Reason_Presets", viewer);
    }

    private String name() {
        return action.name().toLowerCase(java.util.Locale.ROOT);
    }
}
