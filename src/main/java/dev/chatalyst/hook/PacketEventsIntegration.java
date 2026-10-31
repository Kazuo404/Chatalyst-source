package dev.chatalyst.hook;

import dev.chatalyst.security.CommandBlockerListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Puente OPCIONAL con PacketEvents para bloquear comandos a nivel de red (antes de que lleguen
 * a Bukkit). Esta clase NO referencia ninguna clase de PacketEvents: toda esa parte vive en
 * {@link PacketEventsBridge}, que solo se carga si PacketEvents esta instalado. Asi un server
 * sin PacketEvents no provoca NoClassDefFoundError (patron Hook+Bridge, como los demas hooks).
 */
public final class PacketEventsIntegration {

    private final JavaPlugin plugin;
    private final CommandBlockerListener blocker;
    private final dev.chatalyst.vanilla.VanillaMessages vanilla;
    private final dev.chatalyst.anonymous.AnonymousManager anonymous;
    private final dev.chatalyst.vanilla.MessageReplacements replacements;
    private final dev.chatalyst.vanilla.ServerBrand brand;
    private PacketEventsBridge bridge;

    public PacketEventsIntegration(JavaPlugin plugin, CommandBlockerListener blocker,
                                   dev.chatalyst.vanilla.VanillaMessages vanilla,
                                   dev.chatalyst.anonymous.AnonymousManager anonymous,
                                   dev.chatalyst.vanilla.MessageReplacements replacements,
                                   dev.chatalyst.vanilla.ServerBrand brand) {
        this.plugin = plugin;
        this.blocker = blocker;
        this.vanilla = vanilla;
        this.anonymous = anonymous;
        this.replacements = replacements;
        this.brand = brand;
    }

    public void load() {
        // Aqui y no en el reload general: este metodo decide con ellas (que avisar, que marca
        // reenviar) y el reload lo llama ANTES de refrescar el resto de la configuracion.
        if (replacements != null) replacements.load();
        if (brand != null) brand.load();
        boolean available = plugin.getConfig().getBoolean("PacketEvents.Enabled", false)
                && plugin.getServer().getPluginManager().getPlugin("packetevents") != null;
        if (!available) {
            // El embellecido de mensajes vanilla NECESITA paquetes: sin PacketEvents no hay
            // forma de tocar el feedback de /tp, /xp... Se avisa una vez, sin ser pesado.
            if (vanilla != null && vanilla.isEnabled()
                    && plugin.getConfig().getBoolean("Vanilla_Messages.Enabled", true)) {
                plugin.getLogger().info("Vanilla_Messages needs PacketEvents installed and"
                        + " PacketEvents.Enabled: true to restyle vanilla command feedback"
                        + " (/tp, /xp...). Everything else works without it.");
            }
            // Lo mismo para las dos funciones que viven en paquetes: encenderlas sin
            // PacketEvents no falla, simplemente no hace nada, y eso hay que decirlo.
            if (replacements != null && replacements.isActive()) {
                plugin.getLogger().info("Message_Replacements needs PacketEvents installed and"
                        + " PacketEvents.Enabled: true; no message is being replaced.");
            }
            if (brand != null && brand.payload() != null) {
                plugin.getLogger().info("Server_Brand needs PacketEvents installed and"
                        + " PacketEvents.Enabled: true; F3 still shows the real software.");
            }
            return;
        }
        try {
            // Solo aqui, con PacketEvents presente, se carga la clase puente y sus dependencias.
            PacketEventsBridge b = new PacketEventsBridge(plugin, blocker, vanilla, anonymous,
                    replacements, brand);
            b.start();
            bridge = b;
            plugin.getLogger().info("Hooked into PacketEvents (packet-level command blocking"
                    + " and vanilla message restyling active).");
        } catch (Throwable t) {
            bridge = null;
            plugin.getLogger().warning("Could not hook into PacketEvents: " + t.getMessage());
        }
    }

    public void disable() {
        if (bridge != null) {
            try {
                bridge.stop();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }

    public boolean isEnabled() {
        return bridge != null;
    }
}
