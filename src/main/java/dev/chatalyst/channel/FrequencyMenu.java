package dev.chatalyst.channel;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * GUI para sintonizar la frecuencia del chat local (canal de tipo FREQUENCY). Botones +/- para
 * cada paso (10 · 1 · 0.10 · 0.01) alrededor del display central con la frecuencia actual, mas
 * un boton para escribirla exacta por chat. Cada ajuste se aplica AL INSTANTE (te sintoniza y
 * ya oyes ese "canal"); el boton de cerrar solo cierra.
 */
public final class FrequencyMenu implements Menu {

    private static final int DISPLAY = 13;
    // Fila de subir (arriba del display) y bajar (debajo), alineadas por columna.
    private static final int UP_TEN = 2, UP_ONE = 3, UP_TENTH = 5, UP_UNIT = 6;
    private static final int DOWN_TEN = 20, DOWN_ONE = 21, DOWN_TENTH = 23, DOWN_UNIT = 24;
    // Trio central siempre simetrico (11 · display 13 · 15); el "desactivar" (condicional)
    // va centrado en la fila inferior (22), asi el menu queda simetrico este activa o no.
    private static final int SLOT_TYPE = 11;   // escribir por chat
    private static final int SLOT_CLOSE = 15;
    private static final int SLOT_OFF = 22;    // desactivar la radio (solo si esta activa)

    private final FrequencyManager frequencies;

    public FrequencyMenu(FrequencyManager frequencies) {
        this.frequencies = frequencies;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("frequency.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return MM.full(Lang.raw(viewer, "frequency.gui-title"));
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        int cur = FrequencyManager.tuned(viewer.getUniqueId());

        inv.setItem(UP_TEN, plus(viewer, FrequencyManager.STEP_TEN));
        inv.setItem(UP_ONE, plus(viewer, FrequencyManager.STEP_ONE));
        inv.setItem(UP_TENTH, plus(viewer, FrequencyManager.STEP_TENTH));
        inv.setItem(UP_UNIT, plus(viewer, FrequencyManager.STEP_UNIT));
        inv.setItem(DOWN_TEN, minus(viewer, FrequencyManager.STEP_TEN));
        inv.setItem(DOWN_ONE, minus(viewer, FrequencyManager.STEP_ONE));
        inv.setItem(DOWN_TENTH, minus(viewer, FrequencyManager.STEP_TENTH));
        inv.setItem(DOWN_UNIT, minus(viewer, FrequencyManager.STEP_UNIT));

        boolean active = cur != FrequencyManager.OFF;
        inv.setItem(DISPLAY, MenuItems.item(
                active ? Material.HEART_OF_THE_SEA : Material.GLASS_BOTTLE,
                active ? Lang.raw(viewer, "frequency.gui-current", "freq", FrequencyManager.format(cur))
                        : Lang.raw(viewer, "frequency.gui-off"),
                Lang.rawList(viewer, active ? "frequency.gui-current-lore" : "frequency.gui-off-lore")));
        inv.setItem(SLOT_TYPE, MenuItems.item(Material.NAME_TAG,
                Lang.raw(viewer, "frequency.gui-type"),
                List.of(Lang.raw(viewer, "frequency.gui-type-hint"))));
        if (active) {
            inv.setItem(SLOT_OFF, MenuItems.item(Material.REDSTONE_TORCH,
                    Lang.raw(viewer, "frequency.gui-disable"),
                    List.of(Lang.raw(viewer, "frequency.gui-disable-hint"))));
        }
        inv.setItem(SLOT_CLOSE, dev.chatalyst.gui.MenuNavigation.close(viewer));
    }

    private static org.bukkit.inventory.ItemStack plus(Player viewer, int step) {
        return MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                Lang.raw(viewer, "frequency.gui-plus", "step", stepLabel(step)), List.of());
    }

    private static org.bukkit.inventory.ItemStack minus(Player viewer, int step) {
        return MenuItems.item(Material.RED_STAINED_GLASS_PANE,
                Lang.raw(viewer, "frequency.gui-minus", "step", stepLabel(step)), List.of());
    }

    private static String stepLabel(int step) {
        return switch (step) {
            case FrequencyManager.STEP_TEN -> "10";
            case FrequencyManager.STEP_ONE -> "1";
            case FrequencyManager.STEP_TENTH -> "0.10";
            default -> "0.01";
        };
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        int cur = FrequencyManager.tuned(player.getUniqueId());
        switch (slot) {
            case UP_TEN -> apply(player, cur + FrequencyManager.STEP_TEN);
            case UP_ONE -> apply(player, cur + FrequencyManager.STEP_ONE);
            case UP_TENTH -> apply(player, cur + FrequencyManager.STEP_TENTH);
            case UP_UNIT -> apply(player, cur + FrequencyManager.STEP_UNIT);
            case DOWN_TEN -> apply(player, cur - FrequencyManager.STEP_TEN);
            case DOWN_ONE -> apply(player, cur - FrequencyManager.STEP_ONE);
            case DOWN_TENTH -> apply(player, cur - FrequencyManager.STEP_TENTH);
            case DOWN_UNIT -> apply(player, cur - FrequencyManager.STEP_UNIT);
            case SLOT_TYPE -> {
                frequencies.startComposing(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(Lang.get(player, "frequency.type-prompt"));
            }
            case SLOT_OFF -> {
                if (cur != FrequencyManager.OFF) {
                    if (!frequencies.set(player.getUniqueId(), FrequencyManager.OFF)) {
                        storageError(player);
                        return;
                    }
                    player.sendMessage(Lang.get(player, "frequency.disabled"));
                    var top = player.getOpenInventory().getTopInventory();
                    if (top.getHolder() instanceof MenuHolder h && h.getMenu() == this) build(top, player);
                }
            }
            case SLOT_CLOSE -> {
                // Confirmacion final (una sola vez, no en cada clic +/-) si quedo activa.
                int now = FrequencyManager.tuned(player.getUniqueId());
                if (now != FrequencyManager.OFF) {
                    player.sendMessage(Lang.get(player, "frequency.tuned", "freq", FrequencyManager.format(now)));
                }
                player.closeInventory();
            }
            default -> {
            }
        }
    }

    // Aplica la nueva frecuencia (clamp a rango) y repinta el display en sitio. SIN mensaje de
    // chat por clic: el display ya muestra la frecuencia; el aviso va una vez al cerrar.
    private void apply(Player player, int cents) {
        int clamped = Math.max(FrequencyManager.MIN, Math.min(FrequencyManager.MAX, cents));
        if (!frequencies.set(player.getUniqueId(), clamped)) {
            storageError(player);
            return;
        }
        var top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof MenuHolder holder && holder.getMenu() == this) {
            build(top, player);
        }
    }

    private static void storageError(Player player) {
        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
    }
}
