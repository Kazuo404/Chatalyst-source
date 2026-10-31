package dev.chatalyst.hook;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.advancements.Advancement;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementDisplay;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementHolder;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementProgress;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAdvancements;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Envia un advancement exclusivamente al cliente. Esta clase queda aislada para que un
 * servidor sin PacketEvents nunca intente resolver sus tipos durante el class-load normal.
 */
public final class PacketToastBridge {

    private static final String CRITERION = "toast";
    private static final long REMOVE_DELAY_TICKS = 20L;

    private PacketToastBridge() {
    }

    public static boolean available() {
        try {
            return PacketEvents.getAPI() != null
                    && PacketEvents.getAPI().getPlayerManager() != null
                    && PacketEvents.getAPI().getServerManager() != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Agrega, completa y retira el advancement solo en el cliente indicado. No modifica el
     * registro global de Bukkit ni el archivo de progreso vanilla del jugador.
     */
    public static boolean show(JavaPlugin plugin, Player player, String keyPath,
                               Component title, Component description,
                               Material icon, String configuredFrame) {
        if (plugin == null || player == null || !player.isOnline() || !available()) {
            return false;
        }
        try {
            ResourceLocation key = new ResourceLocation(
                    plugin.getName().toLowerCase(java.util.Locale.ROOT), keyPath);
            org.bukkit.inventory.ItemStack bukkitIcon = new org.bukkit.inventory.ItemStack(
                    safeIcon(icon));
            AdvancementDisplay display = new AdvancementDisplay(
                    title, description,
                    SpigotConversionUtil.fromBukkitItemStack(bukkitIcon),
                    frame(configuredFrame), null, true, true, 0.0f, 0.0f);
            Advancement advancement = new Advancement(
                    null, display, List.of(List.of(CRITERION)), false);
            AdvancementHolder holder = new AdvancementHolder(key, advancement);
            AdvancementProgress progress = new AdvancementProgress(Map.of(
                    CRITERION,
                    new AdvancementProgress.CriterionProgress(
                            System.currentTimeMillis())));
            WrapperPlayServerUpdateAdvancements add =
                    new WrapperPlayServerUpdateAdvancements(
                            false, List.of(holder), Set.of(),
                            Map.of(key, progress), true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, add);

            player.getScheduler().runDelayed(plugin, task -> remove(player, key),
                    null, REMOVE_DELAY_TICKS);
            return true;
        } catch (Throwable failed) {
            return false;
        }
    }

    private static void remove(Player player, ResourceLocation key) {
        if (!player.isOnline() || !available()) return;
        try {
            WrapperPlayServerUpdateAdvancements remove =
                    new WrapperPlayServerUpdateAdvancements(
                            false, List.of(), Set.of(key), Map.of(), true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, remove);
        } catch (Throwable ignored) {
            // Al desconectarse el cliente descarta igualmente el advancement temporal.
        }
    }

    private static Material safeIcon(Material icon) {
        return icon == null || icon == Material.AIR
                || icon == Material.CAVE_AIR || icon == Material.VOID_AIR
                ? Material.PAPER : icon;
    }

    private static AdvancementType frame(String configured) {
        if (configured == null) return AdvancementType.TASK;
        return switch (configured.toLowerCase(java.util.Locale.ROOT)) {
            case "goal" -> AdvancementType.GOAL;
            case "challenge" -> AdvancementType.CHALLENGE;
            default -> AdvancementType.TASK;
        };
    }
}
