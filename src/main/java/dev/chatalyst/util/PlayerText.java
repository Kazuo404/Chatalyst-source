package dev.chatalyst.util;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Convierte el texto que escribe un jugador en un Component seguro:
 * texto plano por defecto, o colores/decoraciones (MiniMessage restringido) si tiene
 * el permiso {@code chatalyst.chat.color}. Nunca permite click/hover/insertion.
 */
public final class PlayerText {

    public static final String COLOR_PERMISSION = "chatalyst.chat.color";

    private PlayerText() {
    }

    /** Hex escrito a mano: el mismo permiso que los colores extra de /chatcolor. */
    public static boolean canUseHex(org.bukkit.permissions.Permissible who) {
        return who.hasPermission("chatalyst.color.*");
    }

    /** Degradados escritos a mano: el mismo permiso que los de /chatcolor. */
    public static boolean canUseGradients(org.bukkit.permissions.Permissible who) {
        return who.hasPermission("chatalyst.gradient.*")
                || who.hasPermission(dev.chatalyst.gui.ChatGradientBuilderMenu.PERMISSION);
    }

    public static Component styled(Player sender, String raw) {
        if (raw == null) return Component.empty();
        if (sender.hasPermission(COLOR_PERMISSION)) {
            // MM.safe ya neutraliza el caracter de seccion y limita los tags a color/decoracion.
            return dev.chatalyst.anonymous.AnonymousNameGuard.authored(
                    MM.safe(raw, canUseHex(sender), canUseGradients(sender)));
        }
        // Sin permiso: texto tal cual, pero sin el caracter de seccion (evita colores inyectados).
        return dev.chatalyst.anonymous.AnonymousNameGuard.authored(
                Component.text(raw.replace('§', ' ')));
    }
}
