package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El control de gamemode: nadie QUEDA en un modo para el que no tiene
 * {@code chatalyst.gamemode.<modo>}, venga el cambio de donde venga (/gamemode, Essentials,
 * otro plugin, la consola, la API). Y cada cambio que si ocurre queda registrado.
 *
 * <p>Convive con el modo staff: {@code StaffModeManager} solo toca el vuelo, nunca el
 * gamemode, asi que entrar o salir del modo staff no genera aqui ni filas ni avisos.
 */
final class GamemodeGuard implements Listener {

    private final ItemAuditManager manager;
    /** Jugadores a los que ESTA clase les esta cambiando el modo ahora mismo. */
    private final Set<UUID> enforcing = ConcurrentHashMap.newKeySet();

    GamemodeGuard(ItemAuditManager manager) {
        this.manager = manager;
        manager.onRecheck(() -> FoliaTasks.onlinePlayers(manager.plugin(),
                player -> enforce(player, "RECHECK")));
    }

    /**
     * HIGHEST: decide el ultimo, despues de quien haya querido cancelar o permitir. La muerte
     * en hardcore (pasar a espectador) no se toca: impedirla rompe el modo de juego.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(PlayerGameModeChangeEvent event) {
        AuditSettings settings = manager.settings();
        Player player = event.getPlayer();
        if (!settings.enabled() || enforcing.contains(player.getUniqueId())) return;
        if (event.getCause() == PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH) return;
        String to = event.getNewGameMode().name();
        if (settings.gamemode().allowed(to, player.getWorld().getName(), player::hasPermission)) {
            return;
        }
        event.setCancelled(true);
        // cancelMessage le llega a quien escribio el /gamemode (un admin que se lo pone a otro).
        event.cancelMessage(Lang.get(player, "itemaudit.gamemode-denied-other",
                "player", MM.escape(player.getName()), "mode", AuditText.mode(player, to)));
        player.sendMessage(Lang.get(player, "itemaudit.gamemode-denied",
                "mode", AuditText.mode(player, to)));
        if (ItemAuditManager.exempt(player)) return;
        manager.record(gamemodeEntry(player, AuditType.GAMEMODE_DENIED,
                player.getGameMode().name(), to, event.getCause().name()), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChanged(PlayerGameModeChangeEvent event) {
        AuditSettings settings = manager.settings();
        Player player = event.getPlayer();
        if (!settings.enabled() || !settings.logGamemode() || ItemAuditManager.exempt(player)) {
            return;
        }
        // Las correcciones propias ya dejan su fila GAMEMODE_DENIED: no se duplican.
        if (enforcing.contains(player.getUniqueId())) return;
        String from = player.getGameMode().name();
        String to = event.getNewGameMode().name();
        if (from.equals(to)) return;
        boolean alert = settings.alertGamemodes().contains(from)
                || settings.alertGamemodes().contains(to);
        manager.record(gamemodeEntry(player, AuditType.GAMEMODE, from, to,
                event.getCause().name()), alert);
    }

    /** Entrar en un modo que ya no le corresponde: se corrige en cuanto esta dentro. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(manager.plugin(), task -> enforce(player, "JOIN"),
                null, 2L);
    }

    /** Corre en la region del jugador. Tambien lo usa la revision periodica (permiso quitado). */
    void enforce(Player player, String reason) {
        AuditSettings settings = manager.settings();
        if (!settings.enabled() || !settings.gamemode().restrict() || !player.isOnline()) return;
        GameMode current = player.getGameMode();
        if (settings.gamemode().allowed(current.name(), player.getWorld().getName(),
                player::hasPermission)) {
            return;
        }
        GameMode fallback = GameMode.valueOf(settings.gamemode().defaultMode());
        enforcing.add(player.getUniqueId());
        try {
            player.setGameMode(fallback);
        } finally {
            enforcing.remove(player.getUniqueId());
        }
        if (player.getGameMode() != fallback) return; // otro plugin lo impidio: no se miente
        player.sendMessage(Lang.get(player, "itemaudit.gamemode-reset",
                "mode", AuditText.mode(player, fallback.name()),
                "old", AuditText.mode(player, current.name())));
        if (ItemAuditManager.exempt(player)) return;
        manager.record(gamemodeEntry(player, AuditType.GAMEMODE_DENIED, current.name(),
                fallback.name(), reason), true);
    }

    private static AuditEntry gamemodeEntry(Player player, AuditType type, String from, String to,
                                            String cause) {
        return ItemAuditManager.entry(AuditIds.next(), type, player.getUniqueId(),
                player.getName(), null, "", "", 0, from + ">" + to, cause,
                player.getLocation(), false);
    }
}
