package dev.chatalyst.pm;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Menu de control para el spy: toggle general y estado de permisos por tipo. */
public final class SpyMenu implements Menu {

    // Layout simetrico:
    //   fila 0: toggle general, centrado (4)
    //   fila 1: pm(9) command(11) sign(13) book(15) anvil(17)   (cols 0,2,4,6,8)
    //   fila 2: contenedor(20)  [volver(22, solo desde staff)]  alcance(24)
    private static final int TOGGLE = 4;
    private static final int BACK = 22;   // volver al menu anterior (solo si se abrio desde staff)
    private static final int SCOPE = 24;
    private static final SpyType[] TYPES = {
            new SpyType("pm", Material.PAPER, 9),
            new SpyType("command", Material.COMMAND_BLOCK, 11),
            new SpyType("sign", Material.OAK_SIGN, 13),
            new SpyType("book", Material.WRITABLE_BOOK, 15),
            new SpyType("anvil", Material.ANVIL, 17),
            new SpyType("container", Material.CHEST, 20),
            new SpyType("channel", Material.ENDER_EYE, 18)
    };

    private final PrivateMessages pm;
    // Si != null, se muestra un boton de volver que lo invoca (para regresar al panel de staff).
    private final java.util.function.Consumer<Player> back;

    public SpyMenu(PrivateMessages pm) {
        this(pm, null);
    }

    public SpyMenu(PrivateMessages pm, java.util.function.Consumer<Player> back) {
        this.pm = pm;
        this.back = back;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("spy.menu.title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "spy.menu.title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        inv.setItem(TOGGLE, toggleItem(viewer));
        for (SpyType type : TYPES) {
            inv.setItem(type.slot(), typeItem(viewer, type));
        }
        inv.setItem(SCOPE, scopeItem(viewer));
        if (back != null) {
            inv.setItem(BACK, dev.chatalyst.gui.MenuNavigation.back(viewer));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            if (back != null) back.accept(player);
            return;
        }
        if (!PrivateMessages.canSpyAny(player)) {
            if (slot == TOGGLE || slot == SCOPE) {
                player.sendMessage(Lang.get(player, "common.no-permission"));
            }
            return;
        }
        if (slot == TOGGLE) {
            boolean on = pm.toggleSpy(player);
            player.sendMessage(Lang.get(player, on ? "pm.spy-on" : "pm.spy-off"));
            Menu.open(player, new SpyMenu(pm, back));
            return;
        }
        if (slot == SCOPE) {
            // Click = volver al global; con el spy ya global no hay nada que limpiar.
            player.sendMessage(Lang.get(player, pm.clearSpyTargets(player)
                    ? "pm.spy-target-cleared" : "pm.spy-already-global"));
            Menu.open(player, new SpyMenu(pm, back));
        }
    }

    /** Muestra a quien se vigila (o "todos") y el modo de volver al alcance global. */
    private ItemStack scopeItem(Player viewer) {
        java.util.Set<String> targets = pm.spyTargets(viewer);
        boolean global = targets.isEmpty();
        List<Component> lore = new java.util.ArrayList<>();
        if (global) {
            lore.add(Lang.get(viewer, "spy.menu.scope-global-lore"));
        } else {
            for (String name : targets.stream().sorted().toList()) {
                lore.add(Lang.get(viewer, "spy.menu.scope-entry",
                        "player", dev.chatalyst.util.MM.escape(name)));
            }
            lore.add(Lang.get(viewer, "spy.menu.scope-clear"));
        }
        lore.add(Lang.get(viewer, "spy.menu.scope-hint"));
        return icon(global ? Material.SPYGLASS : Material.TARGET,
                Lang.get(viewer, global ? "spy.menu.scope-global" : "spy.menu.scope-targeted",
                        "count", targets.size()),
                lore);
    }

    private ItemStack toggleItem(Player viewer) {
        boolean on = pm.isSpy(viewer);
        Material material = on ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
        Component name = Lang.get(viewer, on ? "spy.menu.toggle-on" : "spy.menu.toggle-off");
        List<Component> lore = List.of(
                Lang.get(viewer, on ? "spy.menu.toggle-disable" : "spy.menu.toggle-enable"),
                Lang.get(viewer, "spy.menu.click-toggle")
        );
        return icon(material, name, lore);
    }

    private ItemStack typeItem(Player viewer, SpyType type) {
        boolean allowed = PrivateMessages.canSpy(viewer, type.id());
        String permission = PrivateMessages.SPY_PERMISSION + "." + type.id();
        List<Component> lore = List.of(
                Lang.get(viewer, allowed ? "spy.menu.allowed" : "spy.menu.denied"),
                Lang.get(viewer, "spy.menu.permission", "permission", permission),
                Lang.get(viewer, allowed ? "spy.menu.type-active" : "spy.menu.type-locked"),
                Lang.get(viewer, "spy.menu.info-only")
        );
        return icon(type.material(), Lang.get(viewer, "spy.menu.type-" + type.id()), lore);
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(dev.chatalyst.gui.MenuItems.noItalic(name));
            meta.lore(lore.stream().map(dev.chatalyst.gui.MenuItems::noItalic).toList());
        });
        return item;
    }

    private record SpyType(String id, Material material, int slot) {
    }
}
