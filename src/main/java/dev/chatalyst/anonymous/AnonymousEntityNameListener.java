package dev.chatalyst.anonymous;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * La tumba con tu nombre encima.
 *
 * <p>Un plugin de tumbas —GravesX y todos sus parientes— planta donde moriste una entidad con
 * un nombre flotante que dice de quien es. Si el muerto iba de incognito, ese cartel vale por
 * todo lo demas: lo lee cualquiera que pase, se queda ahi horas y esta clavado en el sitio
 * exacto donde el alias acababa de morir.
 *
 * <p>Chatalyst no puede arreglar el plugin de tumbas ni sabe cual habra instalado mañana, asi
 * que corrige el resultado: si el nombre flotante de una entidad recien aparecida nombra a
 * alguien disfrazado, se reescribe con su alias. El mensaje sigue teniendo sentido — la tumba
 * es de quien todos creen que es.
 *
 * <p>Se mira al aparecer y OTRA VEZ un tick despues, porque casi ninguno de esos plugins pone
 * el nombre en el mismo instante del spawn: primero crean la entidad y luego la visten. Con
 * nadie disfrazado no se hace ni una comprobacion.
 *
 * <p>Limite honesto: esto alcanza a las entidades de verdad. Un holograma dibujado con
 * paquetes no pasa por aqui — para eso esta el guardian de los mensajes.
 */
public final class AnonymousEntityNameListener implements Listener {

    private final JavaPlugin plugin;
    private final AnonymousManager anonymous;

    public AnonymousEntityNameListener(JavaPlugin plugin, AnonymousManager anonymous) {
        this.plugin = plugin;
        this.anonymous = anonymous;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (!enabled() || !anonymous.anyDisguised()) return;
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;
        rename(entity);
        // Segunda pasada: la mayoria de plugins nombran la entidad DESPUES de crearla.
        dev.chatalyst.util.FoliaTasks.globalLater(plugin, () -> {
            if (entity.isValid()) rename(entity);
        }, 1L);
    }

    private void rename(Entity entity) {
        Component name = entity.customName();
        if (name == null) return;
        Map<String, String> disguised = anonymous.disguisedNames();
        if (disguised.isEmpty()) return;
        Component rewritten = AnonymousNameGuard.rewrite(name, disguised);
        if (rewritten != name) entity.customName(rewritten);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("Anonymous.Guard_Other_Plugins", true);
    }
}
