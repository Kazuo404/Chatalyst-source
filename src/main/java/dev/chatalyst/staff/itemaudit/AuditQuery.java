package dev.chatalyst.staff.itemaudit;

/** Que filas del registro pide cada forma de /itemaudit. */
record AuditQuery(Kind kind, String value) {

    enum Kind {
        /** Lo que hizo ese staff: lo que saco, lo que dio y sus cambios de gamemode. */
        STAFF,
        /** Lo que le llego a ese jugador, de quien sea. */
        PLAYER,
        /** El recorrido de una marca: de donde salio y por que manos paso. */
        ID,
        /** Todo, lo mas nuevo primero. */
        RECENT
    }

    AuditQuery {
        value = value == null ? "" : value.trim();
    }

    boolean matches(AuditEntry entry) {
        return switch (kind) {
            case STAFF -> entry.actorName().equalsIgnoreCase(value);
            case PLAYER -> entry.targetName().equalsIgnoreCase(value);
            case ID -> entry.id().equalsIgnoreCase(value);
            case RECENT -> true;
        };
    }
}
