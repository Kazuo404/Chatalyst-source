package dev.chatalyst.util;

import dev.chatalyst.hook.PacketToastBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Muestra la notificacion nativa de logro. Prefiere un advancement dirigido solo al cliente
 * mediante PacketEvents y conserva un fallback Bukkit acotado para instalaciones sin el hook.
 */
public final class ToastUtil {

    private static final AtomicBoolean PACKET_FALLBACK_LOGGED = new AtomicBoolean();

    private ToastUtil() {
    }

    /** Toast para un solo jugador. */
    public static void show(JavaPlugin plugin, Player player, String title, String description) {
        show(plugin, List.of(player), title, description);
    }

    /** Un mismo toast para varios jugadores (broadcast): un solo advancement compartido. */
    public static void show(JavaPlugin plugin, Collection<? extends Player> players,
                            String title, String description) {
        show(plugin, players, title, description, Material.PAPER, "task", false);
    }

    /**
     * Toast con MiniMessage, icono y marco configurables. Los eventos de interaccion se
     * eliminan antes de serializar los componentes JSON del advancement.
     */
    public static void showStyled(JavaPlugin plugin, Player player,
                                  String title, String description,
                                  Material icon, String frame) {
        show(plugin, List.of(player), title, description, icon, frame, true);
    }

    private static void show(JavaPlugin plugin, Collection<? extends Player> players,
                             String title, String description, Material icon,
                             String frame, boolean styled) {
        if (plugin == null || players == null || players.isEmpty()) return;
        // Copia defensiva: la coleccion original puede cambiar antes de que corra la tarea.
        List<Player> targets = List.copyOf(players);
        String keyText = "toast/" + Long.toUnsignedString(
                System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        Component titleComponent = displayComponent(title, styled);
        Component descriptionComponent = displayComponent(description, styled);
        if (packetEventsAvailable(plugin)) {
            for (Player player : targets) {
                FoliaTasks.entity(plugin, player, current -> {
                    boolean sent;
                    try {
                        sent = PacketToastBridge.show(plugin, current, keyText,
                                titleComponent, descriptionComponent, icon, frame);
                    } catch (Throwable unavailable) {
                        sent = false;
                    }
                    if (!sent) {
                        logPacketFallback(plugin);
                        BukkitToastBridge.show(plugin, List.of(current), title, description,
                                icon, frame, styled);
                    }
                });
            }
            return;
        }
        BukkitToastBridge.show(plugin, targets, title, description, icon, frame, styled);
    }

    private static boolean packetEventsAvailable(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("packetevents") == null) {
            return false;
        }
        try {
            return PacketToastBridge.available();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static void logPacketFallback(JavaPlugin plugin) {
        if (PACKET_FALLBACK_LOGGED.compareAndSet(false, true)) {
            plugin.getLogger().warning("PacketEvents could not deliver a toast; "
                    + "using the bounded Bukkit advancement fallback.");
        }
    }

    static String cachedIdentity(String advancementJson) {
        String safe = advancementJson == null ? "" : advancementJson;
        return UUID.nameUUIDFromBytes(
                safe.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String json(String title, String description, Material material,
                       String configuredFrame, boolean styled,
                       boolean modernIcon) {
        Material safeIcon = material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR
                ? Material.PAPER : material;
        String iconKey = "minecraft:"
                + safeIcon.name().toLowerCase(Locale.ROOT);
        String icon = modernIcon
                ? "\"id\":\"" + escape(iconKey) + "\""
                : "\"item\":\"" + escape(iconKey) + "\"";
        String frame = switch (configuredFrame == null
                ? "" : configuredFrame.toLowerCase(Locale.ROOT)) {
            case "goal" -> "goal";
            case "challenge" -> "challenge";
            default -> "task";
        };
        String titleJson = styled ? component(title) : textComponent(title);
        String descriptionJson = styled
                ? component(description) : textComponent(description);
        return "{"
                + "\"criteria\":{\"toast\":{\"trigger\":\"minecraft:impossible\"}},"
                + "\"display\":{"
                + "\"icon\":{" + icon + "},"
                + "\"title\":" + titleJson + ","
                + "\"description\":" + descriptionJson + ","
                + "\"frame\":\"" + frame + "\","
                + "\"show_toast\":true,"
                + "\"announce_to_chat\":false,"
                + "\"hidden\":true"
                + "}"
                + "}";
    }

    private static String component(String miniMessage) {
        try {
            return GsonComponentSerializer.gson().serialize(
                    displayComponent(miniMessage, true));
        } catch (RuntimeException invalid) {
            return textComponent(MM.strip(miniMessage));
        }
    }

    private static Component displayComponent(String value, boolean styled) {
        String safe = value == null ? "" : value;
        if (!styled) return Component.text(safe);
        try {
            return MM.full(MM.sanitizeInteraction(safe));
        } catch (RuntimeException invalid) {
            return Component.text(MM.strip(safe));
        }
    }

    private static String textComponent(String plain) {
        return "{\"text\":\"" + escape(plain == null ? "" : plain) + "\"}";
    }

    private static String escape(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
