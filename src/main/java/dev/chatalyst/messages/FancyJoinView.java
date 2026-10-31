package dev.chatalyst.messages;

import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

/** Per-viewer localization and Adventure presentation for Fancy Join. */
final class FancyJoinView {

    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750));

    private FancyJoinView() {
    }

    /**
     * Resolves every subject-dependent placeholder on the joining player's entity thread.
     * The resulting Adventure objects are immutable and can then be delivered safely from
     * each viewer's region thread without touching the subject across Folia regions.
     */
    static Localized prepare(Player subject, ConfigurationSection tier) {
        Map<String, String> placeholders = Placeholders.merged(subject, null);
        return new Localized(prepare(subject, tier, false, placeholders),
                prepare(subject, tier, true, placeholders));
    }

    private static Prepared prepare(Player subject, ConfigurationSection tier, boolean spanish,
            Map<String, String> placeholders) {
        String message = localizedValue(tier, "Message", spanish);
        String title = localizedValue(tier, "Title", spanish);
        String subtitle = localizedValue(tier, "Subtitle", spanish);
        Component renderedMessage = message.isEmpty() ? null
                : MM.full(Placeholders.applyPrepared(subject, message, placeholders));
        Title renderedTitle = title.isEmpty() && subtitle.isEmpty() ? null : Title.title(
                MM.full(Placeholders.applyPrepared(subject, title, placeholders)),
                MM.full(Placeholders.applyPrepared(subject, subtitle, placeholders)), TITLE_TIMES);
        return new Prepared(renderedMessage, renderedTitle);
    }

    record Localized(Prepared english, Prepared spanish) {
        Prepared forViewer(org.bukkit.command.CommandSender viewer) {
            return Lang.isSpanish(viewer) ? spanish : english;
        }
    }

    record Prepared(Component message, Title title) {
        void deliver(Player viewer, Sound sound) {
            if (message != null) viewer.sendMessage(message);
            if (title != null) viewer.showTitle(title);
            if (sound != null) viewer.playSound(sound);
        }
    }

    static float clamp(double value, float min, float max) {
        return Math.max(min, Math.min(max, (float) value));
    }

    static String localizedValue(ConfigurationSection tier, String field, boolean spanish) {
        if (tier == null || field == null || field.isEmpty()) return "";
        return dev.chatalyst.lang.LocalizedConfig.string(tier, field, spanish, "");
    }
}
