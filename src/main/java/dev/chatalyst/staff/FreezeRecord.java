package dev.chatalyst.staff;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Reason and staff identity for a persisted freeze. */
public record FreezeRecord(String reason, String staff, String staffUuid) {

    private static final String SEP = "\u001f";

    public FreezeRecord(String reason, String staff) {
        this(reason, staff, "");
    }

    String encode() {
        return encodeText(reason) + SEP + encodeText(staff) + SEP + encodeText(staffUuid);
    }

    static FreezeRecord decode(String stored) {
        if (stored == null) return null;
        try {
            String[] parts = stored.split(SEP, -1);
            if (parts.length != 2 && parts.length != 3) return null;
            return new FreezeRecord(decodeText(parts[0]), decodeText(parts[1]),
                    parts.length == 3 ? decodeText(parts[2]) : "");
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

    /** UUID manda; nombre solo para registros legacy. "console" nunca enlaza a un jugador. */
    boolean assignedTo(UUID playerId, String playerName) {
        if (staffUuid != null && !staffUuid.isBlank()) {
            if ("console".equalsIgnoreCase(staffUuid)) return false;
            try {
                return playerId.equals(UUID.fromString(staffUuid));
            } catch (IllegalArgumentException corrupt) {
                return false;
            }
        }
        return playerName != null && staff.equalsIgnoreCase(playerName);
    }
}
