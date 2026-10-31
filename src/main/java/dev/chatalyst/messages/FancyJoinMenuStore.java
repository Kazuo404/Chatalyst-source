package dev.chatalyst.messages;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/** Disk access and small config transformations for the Fancy Join admin menu. */
final class FancyJoinMenuStore {

    private final ChatalystPlugin plugin;
    private final File file;

    FancyJoinMenuStore(ChatalystPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    YamlConfiguration load() {
        synchronized (YamlFiles.lock(file)) {
            try {
                return YamlFiles.load(file);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not read messages.yml: "
                        + ex.getMessage(), ex);
            }
        }
    }

    Boolean toggle(Player player, String path, boolean fallback) {
        synchronized (YamlFiles.lock(file)) {
            try {
                YamlConfiguration yaml = YamlFiles.load(file);
                boolean next = !yaml.getBoolean(path, fallback);
                yaml.set(path, next);
                YamlFiles.saveAtomic(yaml, file);
                afterSave(player, path + " -> " + next);
                return next;
            } catch (Exception ex) {
                failure(player, ex);
                return null;
            }
        }
    }

    boolean set(Player player, String path, Object value) {
        synchronized (YamlFiles.lock(file)) {
            try {
                YamlConfiguration yaml = YamlFiles.load(file);
                yaml.set(path, value);
                YamlFiles.saveAtomic(yaml, file);
                afterSave(player, path + " -> " + value);
                return true;
            } catch (Exception ex) {
                failure(player, ex);
                return false;
            }
        }
    }

    Double updateDouble(Player player, String path, double fallback, DoubleUnaryOperator update) {
        synchronized (YamlFiles.lock(file)) {
            try {
                YamlConfiguration yaml = YamlFiles.load(file);
                double next = update.applyAsDouble(yaml.getDouble(path, fallback));
                yaml.set(path, next);
                YamlFiles.saveAtomic(yaml, file);
                afterSave(player, path + " -> " + next);
                return next;
            } catch (Exception ex) {
                failure(player, ex);
                return null;
            }
        }
    }

    private void afterSave(Player player, String auditDetail) {
        plugin.getServerMessages().load();
        audit(player, auditDetail);
    }

    private void failure(Player player, Exception ex) {
        String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        player.sendMessage(Lang.get(player, "plugin.reload-error", "error", detail));
    }

    private void audit(Player player, String detail) {
        if (plugin.getStaffSecurityListener() == null) return;
        plugin.getStaffSecurityListener().audit(player, "admin-gui-fancyjoin", detail);
    }

    List<String> tierKeys(YamlConfiguration yaml) {
        ConfigurationSection tiers = yaml.getConfigurationSection("Join.Fancy.Tiers");
        if (tiers == null) return List.of();
        List<String> keys = new ArrayList<>(tiers.getKeys(false));
        int limit = Math.min(keys.size(), FancyJoinMenu.MAX_TIERS);
        return List.copyOf(keys.subList(0, limit));
    }

    String prettySound(String sound, Player viewer) {
        if (sound == null || sound.isEmpty()) {
            return Lang.raw(viewer, "fancy-join-gui.no-sound");
        }
        return sound.startsWith("minecraft:") ? sound.substring("minecraft:".length()) : sound;
    }

    static String nextSound(String current) {
        String normalized = current == null ? "" : current.trim().toLowerCase(Locale.ROOT);
        int index = FancyJoinMenu.SOUND_PRESETS.indexOf(normalized);
        return FancyJoinMenu.SOUND_PRESETS.get((index + 1) % FancyJoinMenu.SOUND_PRESETS.size());
    }
}
