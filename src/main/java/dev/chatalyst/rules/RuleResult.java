package dev.chatalyst.rules;

/** Resultado de pasar un mensaje por el motor de reglas. */
public final class RuleResult {

    /** Texto tras aplicar posibles reemplazos. */
    public String message;
    /** True si alguna regla cancelo el mensaje. */
    public boolean cancelled;
    /** True si la cancelacion es silenciosa: el emisor debe ver su mensaje como si nada. */
    public boolean silent;
    /** True si el texto fue modificado (reemplazo / minusculas). */
    public boolean modified;

    public RuleResult(String message) {
        this.message = message;
    }
}
