package dev.chatalyst.sign;

import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/** Applies {@link FancySignFormatter} after moderation and other sign-formatting plugins. */
public final class FancySignListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private volatile FancySignFormatter formatter;

    public FancySignListener(FancySignFormatter formatter) {
        this.formatter = formatter;
    }

    public void reload(FancySignFormatter next) {
        formatter = next;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        FancySignFormatter current = formatter;
        if (!current.isEnabled()) return;

        Player player = event.getPlayer();
        FancySignFormatter.Result notice = null;
        for (int line = 0; line < event.lines().size(); line++) {
            String raw = PLAIN.serialize(event.line(line));
            // LuckPerms may answer an undefined default-op permission differently depending on
            // its Bukkit compatibility settings. Operators must remain an explicit bypass.
            FancySignFormatter.Result result = current.render(raw,
                    permission -> player.isOp() || player.hasPermission(permission));
            if (result.changesLine()) event.line(line, result.component());
            boolean denied = result.status() != FancySignFormatter.Status.UNCHANGED
                && result.status() != FancySignFormatter.Status.FORMATTED;
            if (notice == null && denied) {
                notice = result;
            }
        }
        if (notice != null) sendNotice(player, notice);
    }

    private static void sendNotice(Player player, FancySignFormatter.Result result) {
        switch (result.status()) {
            case DENIED -> player.sendMessage(Lang.get(player, "fancy-signs.denied"));
            case INVALID -> player.sendMessage(Lang.get(player, "fancy-signs.invalid"));
            default -> {
            }
        }
    }
}
