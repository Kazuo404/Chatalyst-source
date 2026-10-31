package dev.chatalyst.item;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.rules.RuleEngine;
import dev.chatalyst.swear.SwearFilter;
import dev.chatalyst.util.Legacy;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.Sanitize;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * /itemname &lt;name&gt;   Renames the held item with colors (&amp;4&amp;lDARK SWORD or MiniMessage).
 * /itemname reset      Removes the custom name (does not consume a use).
 *
 * Rank-gated (chatalyst.command.itemname) and usage-limited: each player gets
 * Item_Name.Uses renames (persisted in the storage backend), unless they have
 * chatalyst.bypass.itemname. Named items show up in death messages ({item}).
 */
public final class ItemNameCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final RuleEngine rules;
    private final SwearFilter swear;
    private final Store store;

    public ItemNameCommand(JavaPlugin plugin, RuleEngine rules, SwearFilter swear,
                           StorageManager storage) {
        this.plugin = plugin;
        this.rules = rules;
        this.swear = swear;
        this.store = storage.store("itemname");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (!plugin.getConfig().getBoolean("Item_Name.Enabled", true)) return true;
        if (args.length == 0) {
            p.sendMessage(Lang.get(p, "itemname.usage"));
            return true;
        }

        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            p.sendMessage(Lang.get(p, "itemname.no-item"));
            return true;
        }

        // Quitar el nombre no consume usos. OJO: getItemInMainHand() devuelve una COPIA,
        // hay que volver a ponerla en la mano para que el cambio sea real.
        if (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("off")) {
            held.editMeta(meta -> meta.displayName(null));
            p.getInventory().setItemInMainHand(held);
            p.sendMessage(Lang.get(p, "itemname.reset"));
            return true;
        }

        String raw = Sanitize.stripInvisible(String.join(" ", args)).trim();
        String mini = Legacy.toMini(raw);
        String plain = MM.strip(mini);
        int maxLength = normalizeMaxLength(
                plugin.getConfig().getInt("Item_Name.Max_Length", 40));
        if (plain.isEmpty() || plain.length() > maxLength) {
            p.sendMessage(Lang.get(p, "itemname.too-long", "max", maxLength));
            return true;
        }
        // Mismos filtros que el chat y los nicks: reglas (anti-publicidad) + insultos.
        if (rules != null && rules.check(p, plain).cancelled) {
            p.sendMessage(Lang.get(p, "itemname.blocked"));
            return true;
        }
        if (swear != null && swear.isEnabled() && swear.apply(plain).matched()) {
            p.sendMessage(Lang.get(p, "itemname.blocked"));
            return true;
        }

        // Limite de usos por jugador (persistente). Uses <= 0 = ilimitado.
        int maxUses = plugin.getConfig().getInt("Item_Name.Uses", 1);
        boolean unlimited = maxUses <= 0 || p.hasPermission("chatalyst.bypass.itemname");
        UseClaim claim = null;
        if (!unlimited) {
            claim = claimUse(store, p.getUniqueId(), maxUses);
            if (claim.status() == UseClaimStatus.EXHAUSTED) {
                p.sendMessage(Lang.get(p, "itemname.no-uses", "max", maxUses));
                return true;
            }
            if (claim.status() == UseClaimStatus.STORAGE_FAILED) {
                p.sendMessage(Lang.get(p, "plugin.storage-unavailable"));
                return true;
            }
        }

        // Como el yunque: nombre sin cursiva por defecto (los custom names son italic en MC).
        Component name = MM.safe(mini).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        held.editMeta(meta -> meta.displayName(name));
        p.getInventory().setItemInMainHand(held); // getItemInMainHand() es una copia

        if (!unlimited) {
            p.sendMessage(Lang.get(p, "itemname.renamed",
                    "left", maxUses - claim.usedAfter()));
        } else {
            p.sendMessage(Lang.get(p, "itemname.renamed-unlimited"));
        }
        return true;
    }

    static UseClaim claimUse(Store store, UUID playerId, int maxUses) {
        String key = playerId.toString();
        for (int attempt = 0; attempt < dev.chatalyst.db.StoreCas.DEFAULT_ATTEMPTS; attempt++) {
            String observed = store.get(key);
            int used = parseUsed(observed);
            if (used >= maxUses) return new UseClaim(UseClaimStatus.EXHAUSTED, used);
            int next = used + 1;
            if (store.compareAndPutDurably(key, observed, String.valueOf(next))) {
                return new UseClaim(UseClaimStatus.CLAIMED, next);
            }
        }
        return new UseClaim(UseClaimStatus.STORAGE_FAILED, 0);
    }

    static int normalizeMaxLength(int configured) {
        return Math.max(1, Math.min(256, configured));
    }

    private static int parseUsed(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    enum UseClaimStatus { CLAIMED, EXHAUSTED, STORAGE_FAILED }

    record UseClaim(UseClaimStatus status, int usedAfter) {
    }
}
