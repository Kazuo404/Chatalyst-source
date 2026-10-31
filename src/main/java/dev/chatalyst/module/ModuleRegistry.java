package dev.chatalyst.module;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Enciende modulos en el orden en que se registran y los apaga en orden INVERSO (las
 * dependencias se apagan despues de quienes las usan; p. ej. storage se apaga al final
 * para que los flushes de los demas modulos aun encuentren la base de datos abierta).
 * Un fallo al apagar un modulo se registra y NO impide apagar los demas.
 */
public final class ModuleRegistry {

    private final Logger logger;
    private final List<Module> enabled = new ArrayList<>();

    public ModuleRegistry(JavaPlugin plugin) {
        this.logger = plugin.getLogger();
    }

    /** Enciende el modulo y lo registra para el apagado en orden inverso. */
    public void enable(Module module) {
        module.enable();
        enabled.add(module);
    }

    /** Recarga todos los modulos en orden de encendido. */
    public void reloadAll() {
        for (Module module : enabled) {
            module.reload();
        }
    }

    public void disableAll() {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            Module module = enabled.get(i);
            try {
                module.disable();
            } catch (Throwable t) {
                logger.warning("Failed to disable module '" + module.name() + "': " + t.getMessage());
            }
        }
        enabled.clear();
    }
}
