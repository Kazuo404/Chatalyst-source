package dev.chatalyst.chestlock;

import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.util.MM;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /candado}: sobre el cofre que se mira. Ver quien tiene acceso, agregar o quitar gente,
 * o quitar el candado. Cambiarlo es solo del dueno (o de quien tenga el permiso de saltarselo).
 */
public final class LockCommand implements RuntimeCommandRegistrar.Handler {

    private final LockService locks;

    public LockCommand(LockService locks) {
        this.locks = locks;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        LockSettings s = locks.settings();
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        if (!s.enabled()) return send(p, s.message("Disabled", dev.chatalyst.lang.Lang.isSpanish(p)));
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.isEmpty()) return send(p, s.message("Usage", dev.chatalyst.lang.Lang.isSpanish(p)));
        Block block = p.getTargetBlockExact(5);
        if (block == null || !locks.isLockable(block)) return send(p, s.message("Not_Container", dev.chatalyst.lang.Lang.isSpanish(p)));
        Optional<Lock> found = locks.activeLock(block);
        if (found.isEmpty()) return send(p, s.message("Not_Locked", dev.chatalyst.lang.Lang.isSpanish(p)));
        Lock lock = found.get();
        boolean owner = lock.owner().equals(p.getUniqueId()) || p.hasPermission(LockListener.BYPASS);
        switch (sub) {
            case "info" -> {
                String trusted = lock.trusted().isEmpty() ? "-" : lock.trusted().stream().map(LockCommand::name)
                        .collect(Collectors.joining(", "));
                return send(p, s.message("Info", dev.chatalyst.lang.Lang.isSpanish(p)).replace("{owner}", MM.escape(lock.ownerName()))
                        .replace("{trusted}", MM.escape(trusted)));
            }
            case "agregar", "add" -> {
                if (!owner) return send(p, s.message("Not_Owner", dev.chatalyst.lang.Lang.isSpanish(p)));
                if (args.length < 2) return send(p, s.message("Usage", dev.chatalyst.lang.Lang.isSpanish(p)));
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) return send(p, s.message("Unknown_Player", dev.chatalyst.lang.Lang.isSpanish(p)).replace("{player}", MM.escape(args[1])));
                if (lock.trusted().size() >= s.maxTrusted() && !lock.trusted().contains(target.getUniqueId())) {
                    return send(p, s.message("Too_Many", dev.chatalyst.lang.Lang.isSpanish(p)).replace("{max}", Integer.toString(s.maxTrusted())));
                }
                locks.write(block, lock.withTrusted(target.getUniqueId()));
                return send(p, s.message("Added", dev.chatalyst.lang.Lang.isSpanish(p)).replace("{player}", MM.escape(name(target.getUniqueId()))));
            }
            case "quitar", "remove" -> {
                if (!owner) return send(p, s.message("Not_Owner", dev.chatalyst.lang.Lang.isSpanish(p)));
                if (args.length < 2) return send(p, s.message("Usage", dev.chatalyst.lang.Lang.isSpanish(p)));
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) return send(p, s.message("Unknown_Player", dev.chatalyst.lang.Lang.isSpanish(p)).replace("{player}", MM.escape(args[1])));
                Lock updated = lock.withoutTrusted(target.getUniqueId());
                locks.write(block, updated);
                locks.closeUnauthorized(block, updated);
                return send(p, s.message("Removed", dev.chatalyst.lang.Lang.isSpanish(p)).replace("{player}", MM.escape(name(target.getUniqueId()))));
            }
            case "eliminar", "unlock" -> {
                if (!owner) return send(p, s.message("Not_Owner", dev.chatalyst.lang.Lang.isSpanish(p)));
                locks.remove(block);
                return send(p, s.message("Unlocked", dev.chatalyst.lang.Lang.isSpanish(p)));
            }
            default -> {
                return send(p, s.message("Usage", dev.chatalyst.lang.Lang.isSpanish(p)));
            }
        }
    }

    private static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    private static boolean send(CommandSender sender, String text) {
        if (text != null && !text.isBlank()) sender.sendMessage(MM.full(text));
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("info", "agregar", "quitar", "eliminar");
        if (args.length == 2 && (args[0].equalsIgnoreCase("agregar") || args[0].equalsIgnoreCase("quitar"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
