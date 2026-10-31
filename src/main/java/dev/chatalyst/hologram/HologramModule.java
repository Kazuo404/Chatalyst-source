package dev.chatalyst.hologram;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.module.Module;

import java.util.List;
import java.util.Locale;

/**
 * Carteles flotantes ({@code /cartel}): TextDisplay e ItemDisplay creados por el staff desde
 * el juego, con los estilos y animaciones de Chatalyst, clic, editor en menu y respaldo para
 * Bedrock. Ajustes en holograms.yml; los carteles de cada servidor en data/holograms.yml.
 *
 * <p>El comando se registra en tiempo de ejecucion (como /sorteo): {@code /holo} y
 * {@code /hologram} son de DecentHolograms y FancyHolograms, y una etiqueta ajena nunca se
 * pisa — si esta cogida queda {@code /chatalyst:cartel}.
 */
public final class HologramModule implements Module {

    private final ChatalystPlugin plugin;
    private HologramService service;
    private ImageLibrary images;
    private MuralService murals;
    private RuntimeCommandRegistrar commands;

    public HologramModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "holograms";
    }

    @Override
    public void enable() {
        HoloSettings settings = HoloSettings.load(plugin.getConfig());
        if (settings.enabled()) start(settings);
        commands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Holograms.Command", "Holograms.Command_Aliases",
                        "cartel", List.of("holo", "holograma", "hologram", "carteles"),
                        "Floating holograms", HologramCommand.PERMISSION,
                        () -> service != null),
                new HologramCommand(this));
        plugin.getServer().getPluginManager().registerEvents(commands, plugin);
        commands.apply();
    }

    @Override
    public void reload() {
        HoloSettings settings = HoloSettings.load(plugin.getConfig());
        if (service != null) {
            service.reloadSettings(settings);
        } else if (settings.enabled()) {
            // Apagado al arrancar y encendido ahora: se monta sin reiniciar.
            start(settings);
        }
        if (images != null) images.reloadSettings(ImageSettings.load(plugin.getConfig()));
        if (commands != null) commands.apply();
    }

    @Override
    public void disable() {
        if (murals != null) murals.stop();
        if (service != null) service.stop();
        if (images != null) images.shutdown();
    }

    /** Carteles, imagenes y murales, con sus listeners. */
    private void start(HoloSettings settings) {
        images = new ImageLibrary(plugin, ImageSettings.load(plugin.getConfig()));
        service = new HologramService(plugin, settings);
        service.images(images);
        plugin.getServer().getPluginManager().registerEvents(new HologramListener(service), plugin);
        service.start();
        murals = new MuralService(plugin, images);
        plugin.getServer().getPluginManager().registerEvents(new MuralListener(murals), plugin);
        murals.start();
    }

    HologramService service() {
        return service;
    }

    MuralService murals() {
        return murals;
    }

    ImageLibrary images() {
        return images;
    }

    /** Etiqueta principal configurada, para los clics que ejecutan comandos. */
    String label() {
        String configured = plugin.getConfig().getString("Holograms.Command", "cartel");
        String label = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return label.matches("[a-z0-9_-]{1,32}") ? label : "cartel";
    }
}
