package dev.chatalyst.staff;

import dev.chatalyst.util.TimeUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Persisted Chatalyst profile ban. */
public record BanRecord(String playerName, long until, String reason, String staff) {

    private static final String SEP = "\u001f";

    public boolean permanent() {
        return until == TimeUtil.PERMANENT;
    }

    public boolean expired(long now) {
        return !permanent() && now >= until;
    }

    String encode() {
        return until + SEP + encodeText(playerName) + SEP + encodeText(reason) + SEP
                + encodeText(staff);
    }

    static BanRecord decode(String stored) {
        if (stored == null) return null;
        try {
            String[] parts = stored.split(SEP, -1);
            if (parts.length != 4) return null;
            return new BanRecord(decodeText(parts[1]), Long.parseLong(parts[0]),
                    decodeText(parts[2]), decodeText(parts[3]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
