package dev.chatalyst.hook;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Fail-closed policy for report-management commands received from Discord.
 *
 * <p>A command is accepted only from an explicitly allowed channel and from an
 * explicitly allowed user or role. Empty allowlists deliberately authorize nobody.
 */
public final class DiscordStaffAuthorization {

    private DiscordStaffAuthorization() {
    }

    public static boolean isAuthorized(String channelId, String userId,
                                       Collection<String> roleIds,
                                       Collection<String> allowedChannelIds,
                                       Collection<String> allowedUserIds,
                                       Collection<String> allowedRoleIds) {
        Set<String> channels = normalizedIds(allowedChannelIds);
        Set<String> users = normalizedIds(allowedUserIds);
        Set<String> roles = normalizedIds(allowedRoleIds);

        // A private channel is not an identity check; require both boundaries.
        if (channels.isEmpty() || (users.isEmpty() && roles.isEmpty())) return false;
        if (!channels.contains(normalizeId(channelId))) return false;
        if (users.contains(normalizeId(userId))) return true;

        if (roleIds == null || roleIds.isEmpty()) return false;
        for (String roleId : roleIds) {
            if (roles.contains(normalizeId(roleId))) return true;
        }
        return false;
    }

    private static Set<String> normalizedIds(Collection<String> rawIds) {
        Set<String> ids = new HashSet<>();
        if (rawIds == null) return ids;
        for (String rawId : rawIds) {
            String id = normalizeId(rawId);
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }
}
