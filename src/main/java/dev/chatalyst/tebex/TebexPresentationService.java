package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.SoundUtil;
import dev.chatalyst.util.ToastUtil;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivers and edits the owner-defined presentation shown after a successful Tebex grant.
 *
 * <p>Visual work is scheduled on the correct player/global scheduler. Buyer-only presentations
 * are persisted when the buyer is offline and claimed atomically on their next join.
 */
public final class TebexPresentationService implements Listener {

    private static final char US = '\u001F';
    private static final long MAX_PENDING_AGE = Duration.ofDays(30).toMillis();
    private static final int MAX_PENDING_PER_PLAYER = 12;
    private static final String DEFAULT_SOUND = "minecraft:ui.toast.challenge_complete";

    private final ChatalystPlugin plugin;
    private final Store pending;
    private final Set<UUID> cosmeticFireworks = ConcurrentHashMap.newKeySet();

    public TebexPresentationService(ChatalystPlugin plugin, StorageManager storage) {
        this.plugin = plugin;
        this.pending = storage.store("tebex_rank_presentations");
    }

    public List<String> rankIds() {
        ConfigurationSection catalog =
                plugin.getConfig().getConfigurationSection("Rank_Catalog");
        if (catalog == null) return List.of();
        return catalog.getKeys(false).stream()
                .filter(id -> id.matches("[a-z0-9_\\-]{1,64}"))
                .filter(id -> catalog.getBoolean(id + ".Tebex_Enabled", false))
                .sorted()
                .toList();
    }

    public TebexPresentation presentation(String rankId) {
        return TebexPresentation.resolve(plugin.getConfig(), normalizeRank(rankId));
    }

    public boolean inherited(String rankId) {
        return rankId != null && presentation(rankId).inherited();
    }

    private static String shownName(UUID id, String accountName) {
        return MM.strip(dev.chatalyst.nick.NickManager.shownName(id.toString(), accountName));
    }

    /** Called only after the rank and its durable DONE receipt were committed. */
    public void deliver(UUID buyerId, String rankId, String group, String duration,
                        String transaction) {
        if (buyerId == null) return;
        String safeRank = normalizeRank(rankId);
        FoliaTasks.global(plugin, () -> {
            TebexPresentation snapshot = presentation(safeRank);
            if (snapshot.silent()) return;
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(buyerId);
            // El nombre MOSTRADO: con Audience GLOBAL esto lo lee todo el servidor, y el de
            // la cuenta delataba a quien compraba estando en /anonymous o con apodo.
            String playerName = shownName(buyerId, offline.getName() == null
                    ? buyerId.toString() : offline.getName());
            Context context = new Context(buyerId, playerName, safeRank,
                    group, duration, transaction);
            if (snapshot.audience() == TebexPresentation.Audience.GLOBAL) {
                deliverGlobal(snapshot, context);
                return;
            }
            Player online = plugin.getServer().getPlayer(buyerId);
            if (online != null && online.isOnline()) {
                FoliaTasks.entity(plugin, online,
                        player -> show(player, snapshot, context, true));
            } else if (plugin.getConfig().getBoolean(
                    TebexPresentation.GLOBAL_PATH + ".Offline_Delivery", true)) {
                queueOffline(context);
            }
        });
    }

    /** Preview is always private and never creates a pending delivery. */
    public void preview(Player viewer, String rankId) {
        String safeRank = rankId == null ? "global" : normalizeRank(rankId);
        TebexPresentation snapshot = presentation(rankId);
        Context context = new Context(viewer.getUniqueId(), viewer.getName(), safeRank,
                rankId == null ? "example" : configuredGroup(rankId),
                rankId == null ? "permanent" : configuredDuration(rankId), "preview");
        if (snapshot.silent()) {
            viewer.sendMessage(Lang.get(viewer, "tebex-presentation.preview-silent"));
            return;
        }
        show(viewer, snapshot, context, true);
    }

    public boolean set(Player editor, String rankId, String relativePath, Object value,
                       boolean markCustom) {
        String base = base(rankId);
        String path = base + "." + relativePath;
        Object previous;
        Object previousPreset;
        Object previousUseGlobal;
        synchronized (plugin.getConfig()) {
            previous = plugin.getConfig().get(path);
            previousPreset = plugin.getConfig().get(base + ".Preset");
            previousUseGlobal = rankId == null ? null
                    : plugin.getConfig().get(base + ".Use_Global");
            plugin.getConfig().set(path, value);
            if (rankId != null && !relativePath.equals("Use_Global")) {
                plugin.getConfig().set(base + ".Use_Global", false);
            }
            if (markCustom) plugin.getConfig().set(base + ".Preset", "CUSTOM");
            if (!plugin.saveConfigChecked()) {
                plugin.getConfig().set(path, previous);
                plugin.getConfig().set(base + ".Preset", previousPreset);
                if (rankId != null) {
                    plugin.getConfig().set(base + ".Use_Global", previousUseGlobal);
                }
                editor.sendMessage(Lang.get(editor, "plugin.storage-unavailable"));
                return false;
            }
        }
        audit(editor, path + " -> " + value);
        return true;
    }

    public boolean setInherited(Player editor, String rankId, boolean inherited) {
        if (rankId == null) return false;
        return set(editor, rankId, "Use_Global", inherited, false);
    }

    public boolean applyPreset(Player editor, String rankId,
                               TebexPresentation.Preset preset) {
        String base = base(rankId);
        Map<String, Object> values = presetValues(preset);
        Map<String, Object> previous = new LinkedHashMap<>();
        synchronized (plugin.getConfig()) {
            previous.put("Use_Global", plugin.getConfig().get(base + ".Use_Global"));
            for (String key : values.keySet()) {
                previous.put(key, plugin.getConfig().get(base + "." + key));
            }
            if (rankId != null) plugin.getConfig().set(base + ".Use_Global", false);
            values.forEach((key, value) -> plugin.getConfig().set(base + "." + key, value));
            if (!plugin.saveConfigChecked()) {
                if (rankId != null) {
                    plugin.getConfig().set(base + ".Use_Global", previous.get("Use_Global"));
                }
                previous.forEach((key, value) -> {
                    if (!key.equals("Use_Global")) plugin.getConfig().set(base + "." + key, value);
                });
                editor.sendMessage(Lang.get(editor, "plugin.storage-unavailable"));
                return false;
            }
        }
        audit(editor, base + ".Preset -> " + preset.name());
        return true;
    }

    public boolean reset(Player editor, String rankId) {
        if (rankId != null) return setInherited(editor, rankId, true);
        String base = base(null);
        Map<String, Object> defaults = globalDefaults();
        Map<String, Object> previous = new LinkedHashMap<>();
        synchronized (plugin.getConfig()) {
            for (String key : defaults.keySet()) {
                previous.put(key, plugin.getConfig().get(base + "." + key));
            }
            defaults.forEach((key, value) -> plugin.getConfig().set(base + "." + key, value));
            if (!plugin.saveConfigChecked()) {
                previous.forEach((key, value) -> plugin.getConfig().set(base + "." + key, value));
                editor.sendMessage(Lang.get(editor, "plugin.storage-unavailable"));
                return false;
            }
        }
        audit(editor, base + " -> defaults");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                task -> deliverPending(id, player));
    }

    private void deliverPending(UUID id, Player joined) {
        String prefix = id + "_";
        long cutoff = System.currentTimeMillis() - MAX_PENDING_AGE;
        List<Map.Entry<String, Pending>> entries = new ArrayList<>();
        for (var entry : pending.all().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            Pending decoded = decodePending(entry.getValue());
            if (decoded == null || decoded.createdAt() < cutoff) {
                pending.compareAndRemoveDurably(entry.getKey(), entry.getValue());
            } else {
                entries.add(Map.entry(entry.getKey(), decoded));
            }
        }
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().createdAt()));
        int deliveryIndex = 0;
        for (var entry : entries.stream().limit(MAX_PENDING_PER_PLAYER).toList()) {
            String raw = pending.get(entry.getKey());
            if (raw == null || !pending.compareAndRemoveDurably(entry.getKey(), raw)) continue;
            Pending value = entry.getValue();
            Context context = new Context(id, shownName(id, joined.getName()), value.rankId(),
                    value.group(), value.duration(), value.transaction());
            TebexPresentation snapshot = presentation(value.rankId());
            if (!snapshot.silent()) {
                long delay = 20L + 40L * deliveryIndex++;
                Runnable restore = () ->
                        pending.compareAndPutDurably(entry.getKey(), null, raw);
                try {
                    joined.getScheduler().runDelayed(plugin,
                            task -> show(joined, snapshot, context, true),
                            restore, delay);
                } catch (RuntimeException ignored) {
                    // The player left before scheduling; retain delivery for their next join.
                    restore.run();
                }
            }
        }
    }

    private void queueOffline(Context context) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            String key = context.buyerId() + "_"
                    + digest(context.rankId() + US + context.transaction());
            pending.putDurably(key, encodePending(new Pending(
                    System.currentTimeMillis(), context.rankId(), context.group(),
                    context.duration(), context.transaction())));
            trimPending(context.buyerId());
        });
    }

    private void trimPending(UUID id) {
        String prefix = id + "_";
        List<Map.Entry<String, Pending>> rows = new ArrayList<>();
        for (var entry : pending.all().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            Pending decoded = decodePending(entry.getValue());
            if (decoded != null) rows.add(Map.entry(entry.getKey(), decoded));
        }
        rows.sort(Comparator.<Map.Entry<String, Pending>>comparingLong(
                entry -> entry.getValue().createdAt()).reversed());
        for (var entry : rows.stream().skip(MAX_PENDING_PER_PLAYER).toList()) {
            String raw = pending.get(entry.getKey());
            if (raw != null) pending.compareAndRemoveDurably(entry.getKey(), raw);
        }
    }

    private void deliverGlobal(TebexPresentation presentation, Context context) {
        // Cada jugador lee el anuncio en SU idioma: el mismo texto se resuelve por espectador,
        // no una vez para toda la sala. Por eso la presentación viaja con los dos idiomas.
        FoliaTasks.onlinePlayers(plugin,
                viewer -> show(viewer, presentation, context,
                        viewer.getUniqueId().equals(context.buyerId())));
        if (presentation.chatEnabled()) {
            var console = plugin.getServer().getConsoleSender();
            Component message = component(
                    render(presentation.chatMessage().forViewer(console), context));
            FoliaTasks.global(plugin, () -> console.sendMessage(message));
        }
    }

    private void show(Player viewer, TebexPresentation presentation,
                      Context context, boolean purchaser) {
        if (presentation.chatEnabled()) {
            viewer.sendMessage(component(
                    render(presentation.chatMessage().forViewer(viewer), context)));
        }
        if (presentation.toastEnabled()) {
            ToastUtil.showStyled(plugin, viewer,
                    render(presentation.toastTitle().forViewer(viewer), context),
                    render(presentation.toastDescription().forViewer(viewer), context),
                    presentation.toastIconMaterial(), presentation.toastFrame());
        }
        if (presentation.titleEnabled()) {
            viewer.showTitle(Title.title(
                    component(render(presentation.title().forViewer(viewer), context)),
                    component(render(presentation.subtitle().forViewer(viewer), context)),
                    Title.Times.times(
                            Duration.ofMillis(presentation.fadeInTicks() * 50L),
                            Duration.ofMillis(presentation.stayTicks() * 50L),
                            Duration.ofMillis(presentation.fadeOutTicks() * 50L))));
        }
        if (presentation.actionBarEnabled()) {
            viewer.sendActionBar(component(
                    render(presentation.actionBarMessage().forViewer(viewer), context)));
        }
        if (presentation.soundEnabled()) {
            Sound sound = SoundUtil.sound(presentation.soundKey(), DEFAULT_SOUND,
                    presentation.soundVolume(), presentation.soundPitch());
            viewer.playSound(sound);
        }
        if (purchaser && presentation.fireworkEnabled()) {
            spawnFirework(viewer);
        }
    }

    private void spawnFirework(Player player) {
        Firework firework = player.getWorld().spawn(
                player.getLocation().add(0.0, 0.5, 0.0), Firework.class);
        var meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.fromRGB(181, 108, 255), Color.fromRGB(228, 168, 255))
                .withFade(Color.WHITE)
                .trail(true)
                .flicker(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
        cosmeticFireworks.add(firework.getUniqueId());
        FoliaTasks.globalLater(plugin,
                () -> cosmeticFireworks.remove(firework.getUniqueId()), 200L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCosmeticFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && cosmeticFireworks.contains(firework.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private String render(String template, Context context) {
        String value = template == null ? "" : template;
        return value.replace("{player}", MM.escape(context.playerName()))
                // El anuncio de una compra tambien es texto de cara al jugador: ahi va el
                // nombre legible, preguntando al servidor antes de adivinarlo.
                .replace("{rank_id}",
                        MM.escape(RankNames.of(context.group(), context.rankId())))
                .replace("{rank}",
                        MM.escape(RankNames.of(context.group(), context.rankId())))
                .replace("{group}", MM.escape(context.group()))
                .replace("{duration}", MM.escape(context.duration()))
                .replace("{transaction}", MM.escape(context.transaction()));
    }

    private Component component(String miniMessage) {
        try {
            return MM.full(MM.sanitizeInteraction(miniMessage));
        } catch (RuntimeException invalid) {
            return Component.text(MM.strip(miniMessage));
        }
    }

    private String configuredGroup(String rankId) {
        return plugin.getConfig().getString(
                "Rank_Catalog." + normalizeRank(rankId) + ".LuckPerms_Group", rankId);
    }

    private String configuredDuration(String rankId) {
        return plugin.getConfig().getString(
                "Rank_Catalog." + normalizeRank(rankId) + ".Tebex_Duration", "permanent");
    }

    private String base(String rankId) {
        return rankId == null ? TebexPresentation.GLOBAL_PATH
                : "Rank_Catalog." + normalizeRank(rankId) + ".Presentation";
    }

    private static String normalizeRank(String rankId) {
        if (rankId == null) return null;
        String normalized = rankId.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_\\-]{1,64}") ? normalized : "unknown";
    }

    private static Map<String, Object> presetValues(TebexPresentation.Preset preset) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("Preset", preset.name());
        switch (preset) {
            case FANCY -> {
                values.put("Enabled", true);
                values.put("Chat.Enabled", true);
                values.put("Toast.Enabled", true);
                values.put("Title.Enabled", true);
                values.put("ActionBar.Enabled", true);
                values.put("Sound.Enabled", true);
                values.put("Firework.Enabled", true);
            }
            case SUBTLE -> {
                values.put("Enabled", true);
                values.put("Chat.Enabled", true);
                values.put("Toast.Enabled", false);
                values.put("Title.Enabled", false);
                values.put("ActionBar.Enabled", false);
                values.put("Sound.Enabled", true);
                values.put("Firework.Enabled", false);
            }
            case SILENT -> {
                values.put("Enabled", true);
                values.put("Chat.Enabled", false);
                values.put("Toast.Enabled", false);
                values.put("Title.Enabled", false);
                values.put("ActionBar.Enabled", false);
                values.put("Sound.Enabled", false);
                values.put("Firework.Enabled", false);
            }
            case CUSTOM -> values.put("Enabled", true);
        }
        return values;
    }

    private static Map<String, Object> globalDefaults() {
        Map<String, Object> values = new LinkedHashMap<>(presetValues(
                TebexPresentation.Preset.SUBTLE));
        values.put("Audience", "PURCHASER");
        values.put("Offline_Delivery", true);
        values.put("Chat.Message",
                "<#B56CFF>✦ <#FBF2FF>{player} <#C0B3D9>received "
                        + "<#E4A8FF>{rank_id}<#C0B3D9>.");
        values.put("Toast.Title", "<gradient:#B56CFF:#E4A8FF>Rank received</gradient>");
        values.put("Toast.Description", "<#FBF2FF>{rank_id} <#C0B3D9>is now active");
        values.put("Toast.Icon", "EMERALD");
        values.put("Toast.Frame", "goal");
        values.put("Title.Title",
                "<gradient:#B56CFF:#E4A8FF><bold>NEW RANK</bold></gradient>");
        values.put("Title.Subtitle",
                "<#FBF2FF>{player} <#C0B3D9>obtained <#E4A8FF>{rank_id}");
        values.put("Title.Fade_In_Ticks", 10);
        values.put("Title.Stay_Ticks", 60);
        values.put("Title.Fade_Out_Ticks", 20);
        values.put("ActionBar.Message",
                "<#E4A8FF>Thank you! <#FBF2FF>{rank_id} is active.");
        values.put("Sound.Key", DEFAULT_SOUND);
        values.put("Sound.Volume", 1.0);
        values.put("Sound.Pitch", 1.0);
        return values;
    }

    private void audit(Player player, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(
                    player, "admin-gui-tebex-presentation", detail);
        }
    }

    private static String encodePending(Pending value) {
        return value.createdAt() + String.valueOf(US) + value.rankId() + US
                + value.group() + US + value.duration() + US + value.transaction();
    }

    private static Pending decodePending(String raw) {
        if (raw == null) return null;
        String[] fields = raw.split(String.valueOf(US), -1);
        if (fields.length != 5) return null;
        try {
            return new Pending(Long.parseLong(fields[0]), fields[1], fields[2],
                    fields[3], fields[4]);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (Exception impossible) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
        }
    }

    private record Context(UUID buyerId, String playerName, String rankId,
                           String group, String duration, String transaction) {
    }

    private record Pending(long createdAt, String rankId, String group,
                           String duration, String transaction) {
    }
}
