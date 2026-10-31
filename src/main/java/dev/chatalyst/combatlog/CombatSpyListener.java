package dev.chatalyst.combatlog;

import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/**
 * Anota el PvP: el golpe que ABRE cada pelea, opcionalmente los intermedios, y la muerte.
 *
 * <p>Lo que este listener existe para responder es una sola pregunta: <b>quien pego primero</b>.
 * El log de Minecraft dice quien mato a quien, y en una agresion esa linea acusa justo al
 * agredido -- el que se defendio y gano. Sin la apertura registrada, la regla del servidor
 * ("el PvP es valido en cuanto alguien da el primer golpe") no se puede arbitrar.
 *
 * <p>Va en {@code MONITOR} + {@code ignoreCancelled}: un golpe que otro plugin cancelo (zona
 * protegida, PvP desactivado, companero de party) NO ocurrio y no debe constar contra nadie.
 */
public final class CombatSpyListener implements Listener {

    private final JavaPlugin plugin;
    private final PrivateMessages pm;
    private final CombatLogManager manager;

    public CombatSpyListener(JavaPlugin plugin, PrivateMessages pm, CombatLogManager manager) {
        this.plugin = plugin;
        this.pm = pm;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!manager.enabled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (attacker.hasPermission(CombatLogManager.EXEMPT_PERMISSION)) return;

        long now = System.currentTimeMillis();
        Encounters.Hit hit = manager.encounters().hit(attacker.getUniqueId(), attacker.getName(),
                victim.getUniqueId(), now, manager.encounterWindowMillis());

        // La apertura SIEMPRE se registra, aunque los golpes sueltos esten apagados: es la
        // unica linea imprescindible. Los intermedios son opcionales y ademas se agrupan.
        int hidden = 0;
        if (!hit.first()) {
            if (!manager.logsHits()) return;
            hidden = manager.throttleHit(attacker.getName(), victim.getName());
            if (hidden < 0) return; // repeticion dentro de la ventana: se calla y se cuenta
        }

        Location where = victim.getLocation();
        CombatRecord record = new CombatRecord(now, attacker.getName(), victim.getName(),
                hit.first() ? "FIRST" : "HIT", weaponOf(event.getDamager(), attacker),
                tenths(event.getFinalDamage()), hit.starterName(), hit.sinceMillis(),
                where.getWorld() == null ? "?" : where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ(), Math.max(0, hidden));
        manager.add(record);
        // A los espias solo la apertura: los golpes intermedios convertirian el spy en ruido.
        if (hit.first()) broadcastToSpies(record, attacker.getUniqueId());
    }

    // ignoreCancelled por lo mismo que el golpe: una muerte que otro plugin anulo (arena,
    // duelo con resurreccion) no ocurrio, y anotarla acusaria a alguien de matar.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!manager.enabled()) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        if (killer.hasPermission(CombatLogManager.EXEMPT_PERMISSION)) return;

        long now = System.currentTimeMillis();
        Encounters.Encounter encounter = manager.encounters().peek(killer.getUniqueId(),
                victim.getUniqueId(), now, manager.encounterWindowMillis());
        // Cerrar ANTES de salir por cualquier via: lo que venga despues es otra pelea.
        manager.encounters().close(killer.getUniqueId(), victim.getUniqueId());

        Location where = victim.getLocation();
        CombatRecord record = new CombatRecord(now, killer.getName(), victim.getName(), "KILL",
                killWeapon(victim, killer), 0,
                encounter == null ? "" : encounter.starterName(),
                encounter == null ? 0L : now - encounter.startedAt(),
                where.getWorld() == null ? "?" : where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ());
        manager.add(record);
        broadcastToSpies(record, killer.getUniqueId());
    }

    /** Al desconectar se olvidan sus peleas: volver a entrar no arrastra una culpa vieja. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.encounters().forget(event.getPlayer().getUniqueId());
    }

    // ---- Resolucion del agresor y del arma -------------------------------------------

    /**
     * Quien pego de verdad: el jugador que golpea cuerpo a cuerpo, o el que disparo la flecha,
     * lanzo la perla o tiro la pocion. Sin esto, un arquero no aparece en el registro y basta
     * con matar a distancia para no dejar rastro.
     */
    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player direct) return direct;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player owner) return owner;
        }
        return null;
    }

    /** Arma con la que llego el dano: el proyectil si lo hubo, si no lo que tenga en la mano. */
    private static String weaponOf(Entity damager, Player attacker) {
        if (damager instanceof Projectile projectile) return projectile.getType().name();
        return weaponInHand(attacker);
    }

    /**
     * Con que llego el golpe que mato. Una muerte a flechazos se anotaba con lo que el
     * arquero tuviera en la mano AL MORIR la victima (a menudo ya otra cosa, o la espada a la
     * que cambio), y ademas leyendo el inventario de alguien que puede estar en otra region
     * (Folia). El ultimo daño de la victima dice si fue un proyectil.
     */
    private static String killWeapon(Player victim, Player killer) {
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent last
                && last.getDamager() instanceof Projectile projectile) {
            return projectile.getType().name();
        }
        return weaponInHand(killer);
    }

    private static String weaponInHand(Player attacker) {
        ItemStack held = attacker.getInventory().getItemInMainHand();
        // La mano vacia es un arma tan valida como otra: pegar a punos abre la pelea igual.
        return held.getType() == Material.AIR ? "FIST" : held.getType().name();
    }

    /** Dano en decimas de punto, acotado para que un plugin con danos absurdos no desborde. */
    private static int tenths(double damage) {
        if (Double.isNaN(damage) || damage <= 0) return 0;
        return (int) Math.min(999_999L, Math.round(damage * 10.0));
    }

    // Mismo patron que el spy de contenedores: despacho por entidad (Folia) y mensaje
    // por-espia, para que cada uno lo lea en su idioma y con botones solo si puede usarlos.
    private void broadcastToSpies(CombatRecord record, UUID attackerId) {
        FoliaTasks.onlinePlayers(plugin,
                spy -> !spy.getUniqueId().equals(attackerId)
                        && pm.isSpy(spy) && PrivateMessages.canSpy(spy, "combat")
                        && (pm.spyWatches(spy, record.attacker())
                            || pm.spyWatches(spy, record.victim())),
                spy -> spy.sendMessage(CombatLogManager.render(spy, record, "spy.combat")));
    }
}
