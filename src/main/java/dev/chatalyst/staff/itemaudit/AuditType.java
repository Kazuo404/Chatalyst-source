package dev.chatalyst.staff.itemaudit;

import java.util.Locale;

/** Que paso en cada fila del registro. El nombre es lo que se escribe en el archivo. */
public enum AuditType {

    /** Sacado del inventario creativo (selector, clic central, pick block). */
    CREATIVE,
    /** Dado por un comando y visto llegar al inventario del destinatario. */
    GIVE,
    /** Comando de dar escrito, pero sin poder ver lo que llego (otra region, @a, lleno...). */
    COMMAND,
    /** Un objeto con marca de origen acabo en manos de OTRO jugador. */
    TRANSFER,
    /** Cambio de gamemode que si ocurrio. */
    GAMEMODE,
    /** Cambio de gamemode cancelado (o corregido al entrar) por no tener permiso. */
    GAMEMODE_DENIED;

    /** Lectura tolerante: una fila escrita por una version futura no rompe la consulta. */
    static AuditType parse(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }

    /** Las filas que cuentan como "objeto sacado de la nada" por alguien. */
    boolean spawnsItems() {
        return this == CREATIVE || this == GIVE || this == COMMAND;
    }

    boolean isGamemode() {
        return this == GAMEMODE || this == GAMEMODE_DENIED;
    }
}
