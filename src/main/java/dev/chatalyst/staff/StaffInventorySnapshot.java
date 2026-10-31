package dev.chatalyst.staff;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Crash-safe snapshot of the state temporarily replaced by staff mode. */
record StaffInventorySnapshot(ItemStack[] contents, boolean allowFlight, boolean flying,
                              int heldSlot, boolean vanishedBefore) {

    // El separador tiene que ser IMPRIMIBLE. La version 1 usaba U+001F, y SnakeYAML vuelca
    // como !!binary cualquier cadena con caracteres de control: al releer el fichero el valor
    // volvia como byte[], Bukkit lo pasaba por toString() y el snapshot quedaba convertido en
    // "[B@8208f80" — el inventario del staff perdido en el primer reinicio. ':' no aparece en
    // un booleano, un entero ni en Base64 URL-safe.
    private static final String SEP = ":";
    private static final String LEGACY_SEP = "\u001f";
    private static final String VERSION = "2";
    private static final String LEGACY_VERSION = "1";
    private static final int FIELDS = 6;

    static StaffInventorySnapshot capture(Player player, boolean vanishedBefore) {
        return new StaffInventorySnapshot(player.getInventory().getContents(),
                player.getAllowFlight(), player.isFlying(),
                player.getInventory().getHeldItemSlot(), vanishedBefore);
    }

    String encode() {
        String inventory = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ItemStack.serializeItemsAsBytes(contents));
        return frame(allowFlight, flying, heldSlot, vanishedBefore, inventory);
    }

    /** El sobre de texto del snapshot, sin tocar el servidor (lo cubre el test). */
    static String frame(boolean allowFlight, boolean flying, int heldSlot,
                        boolean vanishedBefore, String inventory) {
        return String.join(SEP, VERSION, Boolean.toString(allowFlight),
                Boolean.toString(flying), Integer.toString(heldSlot),
                Boolean.toString(vanishedBefore), inventory);
    }

    /**
     * Los seis campos de un snapshot guardado, o {@code null} si no es uno. Acepta tambien la
     * version 1: un backend SQL la guardo intacta, y YamlStore ya recupera la que quedo como
     * !!binary en disco.
     */
    static String[] fields(String stored) {
        if (stored == null) return null;
        boolean legacy = stored.startsWith(LEGACY_VERSION + LEGACY_SEP);
        String[] parts = stored.split(legacy ? LEGACY_SEP : SEP, -1);
        if (parts.length != FIELDS) return null;
        return (legacy ? LEGACY_VERSION : VERSION).equals(parts[0]) ? parts : null;
    }

    static StaffInventorySnapshot decode(String stored) {
        String[] parts = fields(stored);
        if (parts == null) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(parts[5].getBytes(StandardCharsets.UTF_8));
            return new StaffInventorySnapshot(ItemStack.deserializeItemsFromBytes(bytes),
                    Boolean.parseBoolean(parts[1]), Boolean.parseBoolean(parts[2]),
                    Integer.parseInt(parts[3]), Boolean.parseBoolean(parts[4]));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
