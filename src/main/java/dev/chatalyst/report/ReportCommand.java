package dev.chatalyst.report;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.hook.DynmapIntegration;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import dev.chatalyst.util.SoundUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /report &lt;player&gt; &lt;reason&gt;  Lets players report others; notifies online staff with the
 * receive permission. Has a per-player cooldown to prevent report spam.
 */
public final class ReportCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final ReportManager reports;
    private final DynmapIntegration dynmapIntegration;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    // Sin poda quedaria una entrada por cada jugador que reporto alguna vez (fuga lenta).
    private static final int SOFT_CAP = 2000;

    public ReportCommand(JavaPlugin plugin, ReportManager reports, DynmapIntegration dynmapIntegration) {
        this.plugin = plugin;
        this.reports = reports;
        this.dynmapIntegration = dynmapIntegration;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!plugin.getConfig().getBoolean("Reports.Enabled", true)) return true;
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(Lang.get(p, "report.usage"));
            return true;
        }
        // Acepta nombre real o apodo; el reporte guarda siempre el nombre REAL.
        Player target = dev.chatalyst.nick.NickManager.online(args[0]);
        if (target == null) {
            p.sendMessage(Lang.get(p, "common.player-offline"));
            return true;
        }
        if (target.equals(p)) {
            p.sendMessage(Lang.get(p, "report.self"));
            return true;
        }

        long cooldownMs = Math.max(0L, Math.min(86_400L,
                plugin.getConfig().getLong("Reports.Cooldown", 60))) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        pruneStale(now, cooldownMs);
        if (last != null && now - last < cooldownMs && !p.hasPermission("chatalyst.report.bypass")) {
            long left = (cooldownMs - (now - last) + 999) / 1000;
            p.sendMessage(Lang.get(p, "report.cooldown", "seconds", left));
            return true;
        }

        String rawReason = Sanitize.stripInvisible(
                String.join(" ", Arrays.copyOfRange(args, 1, args.length))).trim();
        int maxReason = Math.max(32, Math.min(ReportManager.MAX_REASON_LENGTH,
                plugin.getConfig().getInt("Reports.Max_Reason_Length", 512)));
        String reason = Sanitize.limit(rawReason, maxReason);
        if (reason.isBlank()) {
            p.sendMessage(Lang.get(p, "report.usage"));
            return true;
        }
        // Persistimos el reporte (id incremental) para poder atenderlo luego, in-game o Discord.
        ReportManager.Report report;
        String targetDisplay = dev.chatalyst.nick.NickManager.plainName(target);
        try {
            report = reports.create(p.getName(), p.getUniqueId().toString(), target.getName(),
                    reason, p.getLocation(), target.getUniqueId().toString(), targetDisplay);
        } catch (RuntimeException storageFailure) {
            p.sendMessage(Lang.get(p, "plugin.storage-unavailable"));
            return true;
        }
        cooldowns.put(p.getUniqueId(), now);
        if (dynmapIntegration != null) {
            dynmapIntegration.upsert(report);
        }

        String perm = plugin.getConfig().getString("Reports.Notify_Permission", "chatalyst.report.receive");
        Sound notifySound = SoundUtil.sound(
                plugin.getConfig().getString("Reports.Notify_Sound.Key", "minecraft:block.note_block.chime"),
                "minecraft:block.note_block.chime",
                (float) plugin.getConfig().getDouble("Reports.Notify_Sound.Volume", 0.65),
                (float) plugin.getConfig().getDouble("Reports.Notify_Sound.Pitch", 1.25));
        boolean soundEnabled = plugin.getConfig().getBoolean("Reports.Notify_Sound.Enabled", true);
        String reporterName = p.getName();
        String targetName = target.getName();
        // Tanto hasPermission como la entrega pertenecen a la region de cada espectador.
        // El contador termina cuando todos los entity schedulers respondieron; solo entonces
        // se informa al denunciante si habia staff conectado.
        java.util.concurrent.atomic.AtomicInteger staff = new java.util.concurrent.atomic.AtomicInteger();
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin,
                st -> st.hasPermission(perm), st -> {
                    staff.incrementAndGet();
                    st.sendMessage(alertFor(st, report, reporterName, targetName, reason));
                    st.sendMessage(Lang.get(st, "report.handle-hint", "id", report.id()));
                    if (soundEnabled) SoundUtil.play(plugin, st, notifySound);
                }, () -> dev.chatalyst.util.FoliaTasks.entity(plugin, p, current -> {
                current.sendMessage(Lang.get(current, staff.get() > 0
                        ? "report.sent" : "report.sent-offline", "target",
                        MM.escape(targetDisplay)));
                current.sendMessage(Lang.get(current, "report.followup", "id", report.id()));
            }));
        dev.chatalyst.util.FoliaTasks.global(plugin, () ->
                plugin.getServer().getConsoleSender().sendMessage(alertFor(
                        plugin.getServer().getConsoleSender(), report,
                        reporterName, targetName, reason)));
        // Espejo al canal de staff en Discord (si esta configurado). Guard de null por si
        // el plugin esta a medio arrancar o apagarse.
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        if (chatalyst != null && chatalyst.getStaffNotifier() != null) {
            chatalyst.getStaffNotifier().reportCreated(report.id(), reporterName, targetName,
                    reason, report.reporterUuid(), report.targetUuid());
        }
        return true;
    }

    /** Poda cooldowns ya vencidos cuando el mapa crece; nunca borra uno todavia activo. */
    private void pruneStale(long now, long cooldownMs) {
        if (cooldowns.size() < SOFT_CAP) return;
        long cutoff = now - Math.max(cooldownMs, 60_000L);
        cooldowns.values().removeIf(t -> t < cutoff);
    }

    private Component alertFor(CommandSender viewer, ReportManager.Report report, String reporter,
                               String target, String reason) {
        String fmt = plugin.getConfig().getBoolean("Reports.Use_Player_Language", true)
                ? Lang.raw(viewer, "report.notify")
                : plugin.getConfig().getString("Reports.Format",
                "<#7B68A0>[<#FF4D6D>Report #{id}<#7B68A0>] <#FFD166>{reporter} <#C0B3D9>reported <#FFD166>{target}<#C0B3D9>: <#FBF2FF><chatalyst_reason>");
        return MM.full(fmt
                        .replace("{id}", String.valueOf(report.id()))
                        .replace("{reporter}", reporter)
                        .replace("{target}", target)
                        .replace("{reason}", "<chatalyst_reason>"),
                Placeholder.unparsed("chatalyst_reason", reason));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (dev.chatalyst.util.Vanish.isVanished(p) && !dev.chatalyst.staff.StaffAccess.canSeeVanished(sender)) continue;
                // El alias sigue siendo reportable; staff recibe internamente el UUID/nombre real.
                String shown = dev.chatalyst.nick.NickManager.suggestedName(p);
                if (shown.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(shown);
            }
            return out;
        }
        return List.of();
    }
}
