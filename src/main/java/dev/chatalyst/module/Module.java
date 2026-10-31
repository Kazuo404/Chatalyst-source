package dev.chatalyst.module;

/**
 * Unidad de funcionalidad con ciclo de vida propio (Storage, Mail, Reports...). El plugin
 * principal solo decide EN QUE ORDEN se enciende cada modulo; construir sus servicios,
 * registrar sus listeners/comandos y apagarse limpio es responsabilidad del modulo.
 *
 * <p>Migracion gradual: los servicios se van moviendo del monolito de ChatalystPlugin a
 * modulos, manteniendo el orden de arranque actual. {@link ModuleRegistry} apaga en orden
 * inverso al de encendido.
 */
public interface Module {

    /** Nombre corto para logs (p. ej. "storage", "mail"). */
    String name();

    /** Construye los servicios del modulo y registra listeners/comandos. */
    void enable();

    /** Apaga el modulo (flush + liberar recursos). Debe tolerar un enable() parcial. */
    default void disable() {
    }

    /** Recarga la configuracion del modulo sin reconstruirlo. */
    default void reload() {
    }
}
