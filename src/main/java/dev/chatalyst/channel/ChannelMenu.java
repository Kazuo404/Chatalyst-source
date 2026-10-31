package dev.chatalyst.channel;

import dev.chatalyst.gui.Menu;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI del /channel: un item por canal accesible con su estado (escribiendo / escuchando /
 * silenciado), su alcance y los alias. Click = escribir ahi;
 * click derecho = escuchar si/no; shift+click = silenciar si/no.
 */
public final class ChannelMenu implements Menu {

    private final ChannelManager channels;
    private final ChannelHistory history;
    private final FrequencyManager frequencies;
    // Slot -> canal, reconstruido en cada build para este espectador.
    private final Map<Integer, Channel> slots = new HashMap<>();

    public ChannelMenu(ChannelManager channels, ChannelHistory history, FrequencyManager frequencies) {
        this.channels = channels;
        this.history = history;
        this.frequencies = frequencies;
    }

    @Override
    public Component title() {
        return MM.full(Lang.raw("channel.gui-title"));
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "channel.gui-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        slots.clear();
        Channel current = channels.getWriteChannel(viewer);
        List<Channel> visible = channels.all().stream()
                .filter(channel -> channel.canUse(viewer))
                .limit(7)
                .toList();
        List<Integer> positions = centeredSlots(visible.size());
        for (int index = 0; index < visible.size(); index++) {
            Channel ch = visible.get(index);
            int slot = positions.get(index);
            slots.put(slot, ch);
            inv.setItem(slot, item(viewer, ch, current));
        }
    }

    /** Slots contiguos centrados en la fila interior 10..16 de una GUI de 27 espacios. */
    static List<Integer> centeredSlots(int count) {
        int visible = Math.max(0, Math.min(7, count));
        int first = 10 + (7 - visible) / 2;
        List<Integer> out = new ArrayList<>(visible);
        for (int index = 0; index < visible; index++) out.add(first + index);
        return List.copyOf(out);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Channel ch = slots.get(slot);
        if (ch == null || !ch.canUse(player)) return;
        if (click.isShiftClick()) {
            ChannelManager.StateChange changed = channels.toggleMutedResult(player, ch);
            if (!changed.persisted()) {
                storageError(player);
                return;
            }
            player.sendMessage(Lang.get(player,
                    changed.state() ? "channel.muted" : "channel.unmuted", "name", ch.name));
        } else if (click.isRightClick()) {
            ChannelManager.StateChange changed = channels.toggleListeningResult(player, ch);
            if (!changed.persisted()) {
                storageError(player);
                return;
            }
            boolean nowOn = changed.state();
            player.sendMessage(Lang.get(player,
                    nowOn ? "channel.listen-on" : "channel.listen-off", "name", ch.name));
        } else {
            // Mismo camino que /channel <nombre>: evento de API cancelable incluido.
            Channel current = channels.getWriteChannel(player);
            boolean alreadyHere = current != null && current.name.equals(ch.name);
            if (!alreadyHere) {
                var apiEvent = new dev.chatalyst.api.ChannelSwitchEvent(player,
                        current != null ? current.name : "", ch.name);
                player.getServer().getPluginManager().callEvent(apiEvent);
                if (apiEvent.isCancelled()) return;
                if (!channels.setWriteChannel(player, ch)) {
                    storageError(player);
                    return;
                }
                player.sendMessage(Lang.get(player, "channel.changed", "name", ch.name));
            }
            // Canal por radio: en vez de cerrar, abre la GUI para elegir tu frecuencia.
            if (ch.type == ChannelType.FREQUENCY && frequencies != null) {
                Menu.open(player, new FrequencyMenu(frequencies));
                return;
            }
            if (!alreadyHere) {
                player.closeInventory();
                if (history != null && history.showOnSwitch() > 0) {
                    // Replay del historial al entrar, como con el comando (render compartido).
                    history.replayTo(player, ch.name, history.showOnSwitch());
                }
                return;
            }
        }
        Menu.open(player, this);
    }

    private static void storageError(Player player) {
        player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
    }

    private ItemStack item(Player viewer, Channel ch, Channel current) {
        boolean active = current != null && current.name.equals(ch.name);
        boolean muted = channels.isMuted(viewer, ch);
        boolean listening = channels.isListening(viewer, ch);

        String color = active ? "<#B56CFF><bold>" : muted ? "<#7B68A0>" : listening ? "<#3DFFA8>" : "<#FBF2FF>";
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, active ? "channel.state-active"
                : muted ? "channel.state-muted"
                : listening ? "channel.state-listening"
                : "channel.state-available"));
        if (ch.type == ChannelType.FREQUENCY) {
            lore.add(Lang.raw(viewer, "channel.gui-frequency",
                    "freq", FrequencyManager.format(FrequencyManager.tuned(viewer.getUniqueId()))));
        }
        if (ch.proxy) {
            lore.add(Lang.raw(viewer, "channel.gui-proxy"));
        }
        lore.add("<#7B68A0>" + String.join(", ", ch.aliases));
        lore.add("");
        if (ch.type == ChannelType.FREQUENCY) {
            lore.add(Lang.raw(viewer, "channel.gui-click-frequency"));
        } else {
            lore.add(Lang.raw(viewer, "channel.gui-click-write"));
        }
        lore.add(Lang.raw(viewer, "channel.gui-click-listen"));
        lore.add(Lang.raw(viewer, "channel.gui-click-mute"));

        return dev.chatalyst.gui.MenuItems.item(material(ch), color + ch.name,
                lore.stream().map(line -> "<#C0B3D9>" + line).toList());
    }

    /** Icono por canal: nombres conocidos primero, si no por tipo. */
    private static Material material(Channel ch) {
        switch (ch.name.toLowerCase(Locale.ROOT)) {
            case "trade": return Material.EMERALD;
            case "auction": return Material.GOLD_INGOT;
            case "staff": return Material.COMMAND_BLOCK;
            default: break;
        }
        return switch (ch.type) {
            case FREQUENCY -> Material.HEART_OF_THE_SEA;
            case REGION -> Material.FILLED_MAP;
            case PARTY -> Material.CAKE;
            case WORLD -> Material.GRASS_BLOCK;
            default -> Material.PAPER;
        };
    }
}
