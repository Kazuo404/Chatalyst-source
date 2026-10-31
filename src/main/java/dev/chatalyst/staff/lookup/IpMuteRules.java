package dev.chatalyst.staff.lookup;

import dev.chatalyst.util.TimeUtil;

/**
 * Cuando un silencio de conexion (/ipmute) debe aplicarse a una cuenta y cuando un mute de
 * cuenta es "el suyo". Sin Bukkit.
 *
 * <p>Las cuentas se silencian hasta el MISMO instante que la conexion. Los mutes puestos por
 * versiones anteriores usaban "lo que le queda", y su final quedaba unos milisegundos
 * desplazado: por eso dos finales a menos de {@link #SAME_END_TOLERANCE_MS} cuentan como el
 * mismo. Sin esa holgura, /unipmute no levantaba esos mutes y cada vez que la cuenta volvia a
 * entrar se la silenciaba otra vez (fila nueva en el historial y aviso al staff).
 */
final class IpMuteRules {

    /** Margen para reconocer el mismo final (incluye los mutes del formato anterior). */
    static final long SAME_END_TOLERANCE_MS = 5_000L;

    private IpMuteRules() {
    }

    /** Si dos finales son el mismo (los dos permanentes, o a menos del margen). */
    static boolean sameEnd(long a, long b) {
        if (a == TimeUtil.PERMANENT || b == TimeUtil.PERMANENT) return a == b;
        return Math.abs(a - b) <= SAME_END_TOLERANCE_MS;
    }

    /**
     * Si hay que silenciar a una cuenta cuyo mute actual acaba en {@code current} con un
     * silencio que acaba en {@code until}: solo si el suyo dura claramente menos. Un mute
     * permanente o igual de largo no se toca (un /ipmute de una hora no acorta nada).
     */
    static boolean lastsLess(long current, long until) {
        if (current == TimeUtil.PERMANENT) return false;
        if (until == TimeUtil.PERMANENT) return true;
        return until > current + SAME_END_TOLERANCE_MS;
    }

    /** Si el mute de una cuenta lo puso ese silencio de conexion (mismo final y motivo). */
    static boolean belongsTo(long muteUntil, String muteReason, long ipUntil, String ipReason) {
        return sameEnd(muteUntil, ipUntil)
                && java.util.Objects.equals(muteReason == null ? "" : muteReason,
                ipReason == null ? "" : ipReason);
    }
}
