package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Rich, localized player presentation for an active staff review. */
public final class FreezeNotice {

    private static final String ROOT = "Staff_Moderation.Freeze.";
    private static final String DEFAULT_NOTICE_SOUND = "minecraft:block.beacon.activate";
    private final JavaPlugin plugin;
    private final ModerationFormatter formatter;

    public FreezeNotice(JavaPlugin plugin, ModerationFormatter formatter) {
        this.plugin = plugin;
        this.formatter = formatter;
    }

    public void show(Player player, FreezeRecord record) {
        if (player == null || record == null) return;
        FoliaTasks.entity(plugin, player, viewer -> showNow(viewer, record));
    }

    public void clear(Player player) {
        if (player == null) return;
        FoliaTasks.entity(plugin, player, viewer -> {
            viewer.clearTitle();
            viewer.sendActionBar(Component.empty());
        });
    }

    private void showNow(Player player, FreezeRecord record) {
        // El respeto a OP es configurable: con Exempt_Ops apagado el aviso SI se muestra
        // (antes un isOp() fijo lo silenciaba en servers donde todos tienen OP).
        if (!player.isOnline() || (player.isOp() && StaffAccess.exemptsOps())) return;
        Component reason = shownReason(player, record.reason());
        Component discord = discordButton(player);
        Component channel = Component.text(plugin.getConfig()
                .getString(ROOT + "Discord_Channel", "#staff-review"));
        Component staff = Component.text(record.staff());

        player.showTitle(Title.title(
                render(player, localized(player, "Notice.Title",
                "staff-moderation.freeze-notice-title"),
                reason, staff, channel, discord),
                render(player, localized(player, "Notice.Subtitle",
                "staff-moderation.freeze-notice-subtitle"),
                reason, staff, channel, discord),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(5),
                Duration.ofMillis(750))));
        player.sendMessage(panel(player, reason, staff, channel, discord));
        player.sendActionBar(Lang.get(player, "staff-moderation.freeze-warning-bar"));
        playSound(player);
    }

    private Component panel(Player viewer, Component reason, Component staff,
            Component channel, Component discord) {
        List<String> lines = dev.chatalyst.lang.LocalizedConfig.list(
                plugin.getConfig(), ROOT + "Notice.Lines", viewer);
        if (lines.isEmpty()) {
            lines = Lang.rawList(viewer, "staff-moderation.freeze-notice-lines");
        }
        TextComponent.Builder panel = Component.text();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) panel.append(Component.newline());
            panel.append(render(viewer, lines.get(index), reason, staff, channel, discord));
        }
        return panel.build();
    }

    private Component render(Player viewer, String raw, Component reason, Component staff,
            Component channel, Component discord) {
        String template = formatter.visualTemplate(raw)
                .replace("{reason}", "<freeze_reason>")
                .replace("{staff}", "<freeze_staff>")
                .replace("{channel}", "<freeze_channel>")
                .replace("{discord}", "<freeze_discord>");
        try {
            return MM.full(template,
                Placeholder.component("freeze_reason", reason),
                Placeholder.component("freeze_staff", staff),
                Placeholder.component("freeze_channel", channel),
                Placeholder.component("freeze_discord", discord));
        } catch (RuntimeException ex) {
            return Component.text(MM.strip(raw == null ? "" : raw));
        }
    }

    private Component shownReason(Player viewer, String stored) {
        if (plugin.getConfig().getBoolean(ROOT + "Show_Reason_In_Game", true)) {
            return formatter.reasonComponent(viewer, stored);
        }
        return formatter.visual(localized(viewer, "Hidden_Reason_Text",
                "staff-moderation.freeze-notice-hidden-reason"));
    }

    private Component discordButton(Player viewer) {
        Component label = formatter.visual(localized(viewer, "Discord_Button",
                "staff-moderation.freeze-notice-discord-button"));
        Component hover = formatter.visual(localized(viewer, "Discord_Hover",
                "staff-moderation.freeze-notice-discord-hover"));
        String configured = plugin.getConfig().getString(ROOT + "Discord_Invite_URL", "");
        String url = configured == null ? "" : configured.trim();
        ClickEvent click = validHttpsUrl(url)
                ? ClickEvent.openUrl(url) : ClickEvent.suggestCommand("/discord");
        return label.clickEvent(click).hoverEvent(HoverEvent.showText(hover));
    }

    private String localized(Player viewer, String path, String fallbackKey) {
        String configured = dev.chatalyst.lang.LocalizedConfig.string(
                plugin.getConfig(), ROOT + path, viewer, "");
        return configured.isBlank() ? Lang.raw(viewer, fallbackKey) : configured;
    }

    private void playSound(Player player) {
        // Por SoundUtil como el resto del plugin: era el unico campo de sonido que solo
        // aceptaba el nombre estilo enum, asi que un dueño que escribiera aqui la clave
        // "minecraft:..." — la forma que usan todos los demas campos — se quedaba con el
        // sonido por defecto sin enterarse.
        String configured = plugin.getConfig().getString(ROOT + "Notice.Sound",
                DEFAULT_NOTICE_SOUND);
        float volume = clamp((float) plugin.getConfig().getDouble(
                ROOT + "Notice.Volume", 1.0), 0.0F, 4.0F);
        float pitch = clamp((float) plugin.getConfig().getDouble(
                ROOT + "Notice.Pitch", 0.75), 0.5F, 2.0F);
        player.playSound(dev.chatalyst.util.SoundUtil.sound(
                configured, DEFAULT_NOTICE_SOUND, volume, pitch));
    }

    static boolean validHttpsUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

}
