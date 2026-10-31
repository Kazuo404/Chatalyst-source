package dev.chatalyst.hook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Isolated: only loaded when Vault is present ({@link VaultHook} checks). Reads prefix/suffix
 * from Vault's Chat service and the primary group from its Permission service, converting the
 * legacy (&amp;) codes to MiniMessage so they slot into Chatalyst formats.
 */
final class VaultBridge {

    private static Chat chat;
    private static Permission perms;
    private static Economy economy;

    private VaultBridge() {
    }

    static boolean setup() {
        RegisteredServiceProvider<Chat> chatRsp = Bukkit.getServicesManager().getRegistration(Chat.class);
        RegisteredServiceProvider<Permission> permRsp = Bukkit.getServicesManager().getRegistration(Permission.class);
        RegisteredServiceProvider<Economy> ecoRsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (chatRsp != null) chat = chatRsp.getProvider();
        if (permRsp != null) perms = permRsp.getProvider();
        if (ecoRsp != null) economy = ecoRsp.getProvider();
        return chat != null || perms != null || economy != null;
    }

    static boolean hasEconomy() {
        // Chatalyst carga antes que Essentials para conservar la prioridad de comandos.
        // Vault puede existir ya, pero Essentials registra su Economy unos instantes despues.
        // Refrescamos la referencia de forma perezosa para no conservar un null de arranque.
        if (economy == null || !economy.isEnabled()) {
            RegisteredServiceProvider<Economy> ecoRsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = ecoRsp != null ? ecoRsp.getProvider() : null;
        }
        return economy != null && economy.isEnabled();
    }

    /** Nombre del plugin de economia detectado (EssentialsX, CMI, XConomy...). */
    static String economyName() {
        return economy != null ? economy.getName() : "";
    }

    /** ¿El proveedor soporta consultar el saldo de jugadores desconectados? (para el baltop) */
    static boolean supportsOffline(OfflinePlayer sample) {
        try {
            economy.getBalance(sample);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static double balance(OfflinePlayer p) {
        return economy != null ? economy.getBalance(p) : 0;
    }

    static String format(double amount) {
        return economy != null ? economy.format(amount) : String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    /** Retira dinero (multas de reglas). Devuelve true si la transaccion se aplico. */
    static boolean withdraw(OfflinePlayer p, double amount) {
        if (economy == null || !Double.isFinite(amount) || amount <= 0) return false;
        try {
            return economy.withdrawPlayer(p, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ingresa dinero (pagos del mercado). Devuelve true si la transaccion se aplico. */
    static boolean deposit(OfflinePlayer p, double amount) {
        if (economy == null || !Double.isFinite(amount) || amount <= 0) return false;
        try {
            return economy.depositPlayer(p, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Vuelve a mirar los servicios que aun faltan: su plugin pudo registrarlos despues. */
    private static void resolveMissing() {
        if (chat == null) {
            RegisteredServiceProvider<Chat> rsp =
                    Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) chat = rsp.getProvider();
        }
        if (perms == null) {
            RegisteredServiceProvider<Permission> rsp =
                    Bukkit.getServicesManager().getRegistration(Permission.class);
            if (rsp != null) perms = rsp.getProvider();
        }
    }

    static String prefix(Player p) {
        resolveMissing();
        return chat != null ? legacyToMini(chat.getPlayerPrefix(p)) : "";
    }

    static String suffix(Player p) {
        resolveMissing();
        return chat != null ? legacyToMini(chat.getPlayerSuffix(p)) : "";
    }

    static String group(Player p) {
        resolveMissing();
        try {
            return perms != null && perms.getPrimaryGroup(p) != null ? perms.getPrimaryGroup(p) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String legacyToMini(String legacy) {
        if (legacy == null || legacy.isEmpty()) return "";
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy.replace('§', '&'));
        return MiniMessage.miniMessage().serialize(c);
    }
}
