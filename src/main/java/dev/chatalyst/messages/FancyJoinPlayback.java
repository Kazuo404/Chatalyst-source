package dev.chatalyst.messages;

import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.toggle.ToggleType;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.SoundUtil;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Executes visual/audio effects after {@link FancyJoinMatcher} has selected a tier. */
final class FancyJoinPlayback {

    private final JavaPlugin plugin;
    private final ToggleManager toggles;

    FancyJoinPlayback(JavaPlugin plugin, ToggleManager toggles) {
        this.plugin = plugin;
        this.toggles = toggles;
    }

    /** Must run on the joining player's entity scheduler (also safe on Folia). */
    void show(Player subject, ConfigurationSection tier, Player onlyViewer) {
        if (tier == null) return;
        // Lightning (bool) se mantiene por compatibilidad; Style es el nuevo show visual y
        // puede combinarse con el rayo. Ambos pintan en el hilo de region del sujeto.
        if (tier.getBoolean("Lightning", false)) {
            subject.getWorld().strikeLightningEffect(subject.getLocation());
        }
        FancyJoinStyle.byId(tier.getString("Style", "none")).play(subject);
        Sound sound = configuredSound(tier);
        FancyJoinView.Localized views = FancyJoinView.prepare(subject, tier);
        if (onlyViewer != null) {
            views.forViewer(onlyViewer).deliver(onlyViewer, sound);
            return;
        }
        broadcast(views, sound);
    }

    private Sound configuredSound(ConfigurationSection tier) {
        String key = tier.getString("Sound", "");
        if (key == null || key.isEmpty()) return null;
        float volume = FancyJoinView.clamp(tier.getDouble("Volume", 1.0), 0.0f, 10.0f);
        float pitch = FancyJoinView.clamp(tier.getDouble("Pitch", 1.0), 0.5f, 2.0f);
        return SoundUtil.sound(key, FancyJoin.DEFAULT_SOUND, volume, pitch);
    }

    /** Sound is global; message/title respect each viewer's personal joinquit toggle. */
    private void broadcast(FancyJoinView.Localized views, Sound sound) {
        FoliaTasks.onlinePlayers(plugin, viewer -> deliverViewer(viewer, views, sound));
        var consoleSender = plugin.getServer().getConsoleSender();
        var console = views.forViewer(consoleSender).message();
        if (console == null) return;
        FoliaTasks.global(plugin,
                () -> consoleSender.sendMessage(console));
    }

    private void deliverViewer(Player viewer, FancyJoinView.Localized views, Sound sound) {
        if (sound != null) viewer.playSound(sound);
        if (toggles == null || toggles.isEnabled(viewer, ToggleType.JOINQUIT)) {
            views.forViewer(viewer).deliver(viewer, null);
        }
    }
}
