package dev.chatalyst.moderation;

import dev.chatalyst.util.TimeUtil;

/**
 * Un silenciamiento activo: hasta cuando dura, quien lo puso y por que.
 */
public final class Mute {

    public final long until;    // epoch millis, o TimeUtil.PERMANENT
    public final String reason;
    public final String by;     // nombre de quien lo aplico
    public final boolean soft;  // shadow mute: el muteado ve sus mensajes, nadie mas los recibe

    public Mute(long until, String reason, String by) {
        this(until, reason, by, false);
    }

    public Mute(long until, String reason, String by, boolean soft) {
        this.until = until;
        this.reason = reason == null ? "" : reason;
        this.by = by == null ? "" : by;
        this.soft = soft;
    }

    public boolean isPermanent() {
        return until == TimeUtil.PERMANENT;
    }

    public boolean isExpired() {
        return !isPermanent() && System.currentTimeMillis() >= until;
    }

    public String remaining() {
        return TimeUtil.formatRemaining(until);
    }
}
