package dev.chatalyst.giveaway;

import dev.chatalyst.nick.NickManager;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.PlayerNames;
import org.bukkit.Server;

import java.util.UUID;

/**
 * How a player is NAMED on the wheel and in the announcement.
 *
 * <p>This exists because of a trap in {@link NickManager#safeName(String, String)}: its second
 * argument is not a "fallback for when we cannot find them", it is <b>the real name</b>, and it
 * is what gets returned whenever the player has no nickname — which is almost everybody. Passing
 * a placeholder there quietly announces the winner of every giveaway as "?".
 *
 * <p>So the real name is resolved first ({@link PlayerNames}: the connected player, then the
 * server's own profile cache, never the network), and the nick layer is applied on top. A
 * player in a nickname or behind an anonymous alias is still named by that alias — the draw
 * must not be the one screen on the server that prints who they really are.
 */
final class GiveawayNames {

    private GiveawayNames() {
    }

    /**
     * Escaped, ready to be dropped into a MiniMessage template (a lang message, an item name).
     * A nickname is chosen by the player, so without the escape one containing tags would
     * inject formatting into the announcement the whole server is reading.
     */
    static String shown(Server server, UUID id) {
        return NickManager.safeName(id.toString(), real(server, id));
    }

    /** Plain text, for the console log and the staff Discord channel — no markup either way. */
    static String plain(Server server, UUID id) {
        return MM.strip(NickManager.shownName(id.toString(), real(server, id)));
    }

    /** "?" only when the server genuinely has never seen this account. */
    private static String real(Server server, UUID id) {
        return PlayerNames.current(server, id, "?");
    }
}
