package dev.chatalyst.hook;

import com.alessiodp.parties.api.Parties;
import com.alessiodp.parties.api.interfaces.PartiesAPI;
import com.alessiodp.parties.api.interfaces.PartyPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Aislado a proposito: solo se carga cuando Parties (AlessioDP) esta presente (lo comprueba
 * {@link PartiesHook}). Devuelve el id de party de un jugador, para el canal de party.
 */
final class PartiesBridge {

    private PartiesBridge() {
    }

    /** Id del party del jugador, o null si no esta en ninguno. */
    static UUID partyId(Player player) {
        PartiesAPI api = Parties.getApi();
        if (api == null) return null;
        PartyPlayer pp = api.getPartyPlayer(player.getUniqueId());
        return pp != null ? pp.getPartyId() : null;
    }

    /** Nombre del party del jugador, o null si no esta en ninguno. */
    static String partyName(Player player) {
        PartiesAPI api = Parties.getApi();
        if (api == null) return null;
        PartyPlayer pp = api.getPartyPlayer(player.getUniqueId());
        if (pp == null || pp.getPartyId() == null) return null;
        var party = api.getParty(pp.getPartyId());
        return party != null ? party.getName() : null;
    }
}
