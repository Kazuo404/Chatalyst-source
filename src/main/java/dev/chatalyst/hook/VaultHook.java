package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Safe gateway to Vault, used as a FALLBACK for prefix/suffix/group when LuckPerms is not
 * installed (so ranks still work with GroupManager, PermissionsEx, etc. via Vault). If Vault
 * is absent everything returns "" (anti-NoClassDefFound: {@link VaultBridge} is only touched
 * when Vault is present).
 */
public final class VaultHook {

    private static Boolean available = null;

    private VaultHook() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("Vault") != null;
            if (available) {
                try {
                    Class.forName("net.milkbowl.vault.chat.Chat");
                    // "Vault esta" no es lo mismo que "ya hay proveedores": Chatalyst carga
                    // antes que Essentials, y memorizar aqui un false dejaba la economia como
                    // inexistente (alquileres, multas, baltop) hasta el siguiente reload.
                    // Los servicios se resuelven al usarlos; ver VaultBridge.
                    VaultBridge.setup();
                } catch (Throwable t) {
                    available = false;
                }
            }
        }
        return available;
    }

    public static void reset() {
        available = null;
    }

    public static String prefix(Player p) {
        return isAvailable() ? safe(() -> VaultBridge.prefix(p)) : "";
    }

    public static String suffix(Player p) {
        return isAvailable() ? safe(() -> VaultBridge.suffix(p)) : "";
    }

    public static String group(Player p) {
        return isAvailable() ? safe(() -> VaultBridge.group(p)) : "";
    }

    public static boolean hasEconomy() {
        return isAvailable() && VaultBridge.hasEconomy();
    }

    /** Nombre del plugin de economia detectado (o "" si no hay). */
    public static String economyName() {
        return hasEconomy() ? safe(() -> VaultBridge.economyName()) : "";
    }

    /** True si el proveedor deja consultar el saldo de jugadores offline (para el baltop). */
    public static boolean economySupportsOffline(org.bukkit.OfflinePlayer sample) {
        return hasEconomy() && VaultBridge.supportsOffline(sample);
    }

    /** Balance del jugador via Vault (0 si no hay economia). */
    public static double balance(org.bukkit.OfflinePlayer p) {
        if (!hasEconomy()) return 0;
        try {
            return VaultBridge.balance(p);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Retira dinero del jugador (multas de reglas). False si no hay economia o fallo. */
    public static boolean withdraw(org.bukkit.OfflinePlayer p, double amount) {
        if (!hasEconomy() || !validAmount(amount)) return false;
        try {
            return VaultBridge.withdraw(p, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ingresa dinero al jugador (pagos del mercado). False si no hay economia o fallo. */
    public static boolean deposit(org.bukkit.OfflinePlayer p, double amount) {
        if (!hasEconomy() || !validAmount(amount)) return false;
        try {
            return VaultBridge.deposit(p, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Formatea una cantidad con el formato del plugin de economia. */
    public static String format(double amount) {
        if (!hasEconomy()) return String.format(java.util.Locale.ROOT, "%.2f", amount);
        try {
            return VaultBridge.format(amount);
        } catch (Throwable t) {
            return String.format(java.util.Locale.ROOT, "%.2f", amount);
        }
    }

    private static String safe(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount > 0;
    }
}
