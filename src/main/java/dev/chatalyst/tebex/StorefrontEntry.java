package dev.chatalyst.tebex;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One card in the storefront, whatever catalog it came from.
 *
 * <p>Ranks and one-shot goods are sold side by side, and duplicating the renderer for each
 * would duplicate what actually matters: the HTTPS validation, the URL encoding, the
 * permission gate and the safe purchase button. The menu therefore knows only this contract,
 * and every security rule is enforced once for both.
 */
public interface StorefrontEntry {

    /** Stable id used by the click handler to resolve this entry again. */
    String id();

    /** Section this belongs to; blank falls into the default one rather than disappearing. */
    String category();

    String displayName();

    List<String> description();

    ItemStack iconItem();

    /** Displayed price, already bounded. Empty hides the price line. */
    String price();

    /** Human duration for ranks ("permanent", "30d"). Empty for goods, which do not expire. */
    String duration();

    /**
     * LuckPerms group this grants, or empty when nothing about it can be "already owned".
     * Goods return empty: buying a second kit is legitimate, so no ownership line is shown.
     */
    String ownedGroup();

    int order();

    boolean featured();

    /** Empty means visible to everyone. Checked on the card AND on the click. */
    String viewPermission();

    Optional<String> purchaseUrl(String playerName, UUID playerId);

    String selectionMessage();

    String buttonText();

    String buttonHover();
}
