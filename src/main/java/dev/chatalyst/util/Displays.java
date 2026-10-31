package dev.chatalyst.util;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;

/**
 * Displays de pantalla compartidos (antes /broadcast, /announce y las reglas tenian cada
 * uno su copia). Seguros en Folia: se programan por entidad/global.
 */
public final class Displays {

    private Displays() {
    }

    /** Bossbar mostrada ~8 segundos a los destinatarios y retirada sola. */
    public static void bossbarTimed(JavaPlugin plugin, Collection<? extends Player> targets,
                                    Component text, BossBar.Color color) {
        List<? extends Player> snapshot = List.copyOf(targets);
        BossBar bar = BossBar.bossBar(text, 1.0f, color, BossBar.Overlay.PROGRESS);
        snapshot.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.showBossBar(bar)));
        FoliaTasks.globalLater(plugin, () ->
                snapshot.forEach(p -> FoliaTasks.entity(plugin, p, pl -> pl.hideBossBar(bar))), 160L);
    }
}
