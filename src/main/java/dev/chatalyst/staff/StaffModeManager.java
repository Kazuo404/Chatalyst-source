package dev.chatalyst.staff;

import dev.chatalyst.db.Store;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff-mode inventory lifecycle. The original inventory is flushed before it is replaced,
 * so a crash/reload can restore it on the next enable or join instead of losing items.
 */
public final class StaffModeManager {

    public static final String INSPECT = "inspect";
    public static final String FREEZE = "freeze";
    public static final String DASHBOARD = "dashboard";
    public static final String VANISH = "vanish";
    public static final String EXIT = "exit";

    private final JavaPlugin plugin;
    private final Store snapshots;
    private final VanishManager vanish;
    private final NamespacedKey actionKey;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public StaffModeManager(JavaPlugin plugin, StorageManager storage, VanishManager vanish) {
        this.plugin = plugin;
        this.snapshots = storage.perServerStore("staff_inventories");
        this.vanish = vanish;
        this.actionKey = new NamespacedKey(plugin, "staff_action");
    }

    public void load() {
        // A leftover snapshot means the previous session ended before a clean restore.
        FoliaTasks.onlinePlayers(plugin, online -> {
            if (snapshots.get(online.getUniqueId().toString()) != null) {
                restoreStale(online);
            }
        });
    }

    private boolean suiteEnabled() {
        return plugin.getConfig().getBoolean("Staff_Moderation.Enabled", false);
    }

    public boolean isActive(Player player) {
        return active.contains(player.getUniqueId());
    }

    /**
     * Devuelve a todo el mundo su inventario. Se llama al APAGAR la suite en un reload: sin
     * esto, quien estuviera dentro se quedaba con el kit puesto, su inventario real guardado
     * en disco y sin {@code /staff off} con el que salir, porque el comando acababa de
     * desregistrarse.
     */
    public void exitAll() {
        for (UUID id : Set.copyOf(active)) {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null) continue;
            FoliaTasks.entity(plugin, online, staff -> {
                if (exit(staff)) {
                    staff.sendMessage(Lang.get(staff, "staff-moderation.staff-mode-off"));
                }
            });
        }
    }

    public boolean isActive(UUID id) {
        return active.contains(id);
    }

    /**
     * Entrar en modo staff cambia el inventario REAL del jugador por el kit. Es lo mas
     * invasivo que hace la suite, asi que con {@code Staff_Moderation.Enabled} apagado se
     * rechaza aqui — el ultimo sitio por el que pasan todas las puertas (el comando, el panel
     * y la estrella del kit), y por tanto el unico que no se puede rodear.
     *
     * <p>{@link #restoreStale} NO comprueba esto a proposito: apagar la suite jamas puede
     * dejar a nadie sin poder recuperar su inventario.
     */
    public boolean enter(Player player) {
        if (!suiteEnabled() || isActive(player)) return false;
        String id = player.getUniqueId().toString();
        // Un snapshot ilegible se aparta en restore(): solo bloquea si sigue ahi (uno valido
        // que no se pudo restaurar), nunca por uno que ya no se puede leer.
        if (snapshots.get(id) != null && !restoreStale(player) && snapshots.get(id) != null) {
            // Nunca en silencio: el menu ya se cerro, y un /staff que no hace nada ni dice nada
            // se acaba tecleando veinte veces.
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }

        StaffInventorySnapshot snapshot = StaffInventorySnapshot.capture(player,
                vanish.isOwned(player));
        // Durability boundary: never clear a real inventory before its snapshot reached disk/DB.
        if (!snapshots.compareAndPutDurably(id, null, snapshot.encode())) {
            plugin.getLogger().severe("Could not durably save the staff inventory for "
                    + player.getName() + "; staff mode was not enabled.");
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }

        active.add(player.getUniqueId());
        clearInventory(player);
        if (plugin.getConfig().getBoolean("Staff_Moderation.Staff_Mode.Allow_Flight", true)) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        if (plugin.getConfig().getBoolean("Staff_Moderation.Staff_Mode.Vanish_On_Enter", true)
                && StaffAccess.has(player, "chatalyst.command.vanish")) {
            if (!vanish.set(player, true)) {
                player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            }
        }
        giveKit(player);
        return true;
    }

    public boolean exit(Player player) {
        return restore(player, false);
    }

    /** Restores a snapshot left by a crash/reload without treating the player as active. */
    public boolean restoreStale(Player player) {
        if (isActive(player)) return false;
        return restore(player, true);
    }

    private boolean restore(Player player, boolean stale) {
        String id = player.getUniqueId().toString();
        String raw = snapshots.get(id);
        if (raw == null) {
            if (!stale) active.remove(player.getUniqueId());
            return false;
        }
        StaffInventorySnapshot snapshot = StaffInventorySnapshot.decode(raw);
        if (snapshot == null) {
            // Se APARTA bajo otra clave en vez de dejarlo donde estaba: ahi impedia volver a
            // entrar en modo staff para siempre (enter() exige que no quede snapshot) y
            // repetia este error en cada entrada al servidor.
            String quarantine = "corrupt-" + id + "-" + System.currentTimeMillis();
            boolean moved = snapshots.putDurably(quarantine, raw) && snapshots.removeDurably(id);
            plugin.getLogger().severe("Could not decode staff inventory snapshot for "
                    + player.getName() + (moved
                    ? "; it was moved to '" + quarantine + "' for manual recovery."
                    : "; it could not be moved aside and still blocks staff mode."));
            if (!stale) active.remove(player.getUniqueId());
            return false;
        }

        // Botin recogido durante el modo staff (p. ej. items sacados de un invsee/echest
        // editado): se conserva y se re-entrega tras restaurar; NUNCA se destruye con el kit.
        List<ItemStack> loot = nonKitItems(player);
        clearInventory(player);
        player.getInventory().setContents(snapshot.contents());
        int slot = Math.max(0, Math.min(8, snapshot.heldSlot()));
        player.getInventory().setHeldItemSlot(slot);

        // La restauracion es idempotente (setContents reemplaza, no suma). Guardamos primero
        // el playerdata y solo entonces consumimos el snapshot; una caida en cualquier lado
        // deja al menos una copia durable y repetir la restauracion no duplica objetos.
        try {
            player.saveData();
        } catch (RuntimeException saveFailure) {
            plugin.getLogger().severe("Could not save restored staff inventory for "
                    + player.getName() + ": " + saveFailure.getMessage());
            enterRecoveryMode(player);
            returnLoot(player, loot);
            return false;
        }

        // El estado de vanish tambien forma parte del snapshot: debe alcanzar storage antes
        // de consumir la ultima copia de la intencion de restauracion.
        // Salir del modo staff es volver a jugar: por defecto el vanish se va con el. Quedarse
        // invisible tras salir dejaba al staff con las restricciones del modo observador sin
        // entender por que (no podia ni quitarse la armadura). Keep_Vanish_On_Exit: true
        // recupera lo de antes (vuelves como entraste).
        boolean keepVanish = snapshot.vanishedBefore() && plugin.getConfig()
                .getBoolean("Staff_Moderation.Staff_Mode.Keep_Vanish_On_Exit", false);
        boolean vanishRestored = keepVanish
                ? vanish.set(player, true)
                : (!vanish.isOwned(player) || vanish.set(player, false));
        if (!vanishRestored) {
            enterRecoveryMode(player);
            returnLoot(player, loot);
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }

        // Delete only after the restored playerdata and vanish crossed durability boundaries.
        if (!snapshots.compareAndRemoveDurably(id, raw)) {
            plugin.getLogger().severe("Could not durably consume the staff inventory for "
                    + player.getName() + "; the player remains locked in staff mode.");
            enterRecoveryMode(player);
            returnLoot(player, loot);
            player.sendMessage(Lang.get(player, "plugin.storage-unavailable"));
            return false;
        }

        player.setFlying(false);
        player.setAllowFlight(snapshot.allowFlight());
        if (snapshot.allowFlight()) player.setFlying(snapshot.flying());

        active.remove(player.getUniqueId());
        returnLoot(player, loot);
        player.updateInventory();
        if (stale) player.sendMessage(Lang.get(player, "staff-moderation.staff-mode-recovered"));
        return true;
    }

    /** Items del inventario de staff que NO son del kit (botin de un invsee/echest editado). */
    private List<ItemStack> nonKitItems(Player player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            if (action(item) == null) out.add(item.clone());
        }
        return out;
    }

    /** Re-entrega el botin conservado; lo que no quepa cae al suelo, jamas se pierde. */
    private static void returnLoot(Player player, List<ItemStack> loot) {
        for (ItemStack item : loot) {
            dev.chatalyst.util.InventoryUtil.giveOrDrop(player, item);
        }
    }

    public void giveKit(Player player) {
        player.getInventory().setItem(0, kitItem(player, Material.COMPASS, INSPECT,
                "staff-moderation.kit-inspect", "staff-moderation.kit-inspect-lore"));
        player.getInventory().setItem(1, kitItem(player, Material.PACKED_ICE, FREEZE,
                "staff-moderation.kit-freeze", "staff-moderation.kit-freeze-lore"));
        player.getInventory().setItem(4, kitItem(player, Material.NETHER_STAR, DASHBOARD,
                "staff-moderation.kit-dashboard", "staff-moderation.kit-dashboard-lore"));
        if (StaffAccess.has(player, "chatalyst.command.vanish")) {
            player.getInventory().setItem(7, kitItem(player,
                    vanish.isOwned(player) ? Material.LIME_DYE : Material.GRAY_DYE, VANISH,
                    "staff-moderation.kit-vanish", "staff-moderation.kit-vanish-lore"));
        }
        player.getInventory().setItem(8, kitItem(player, Material.RED_DYE, EXIT,
                "staff-moderation.kit-exit", "staff-moderation.kit-exit-lore"));
        player.getInventory().setHeldItemSlot(4);
        player.updateInventory();
    }

    public String action(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
    }

    public void shutdown() {
        // Folia detiene los region schedulers antes de onDisable. Tocar inventarios aqui
        // violaria ownership; los snapshots ya son durables y load()/onJoin los restaura
        // idempotentemente en la region del jugador durante el siguiente enable/login.
        snapshots.flushNow();
    }

    private ItemStack kitItem(Player viewer, Material material, String action,
                              String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Lang.get(viewer, nameKey).decoration(TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(Lang.get(viewer, loreKey)
                    .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        });
        return item;
    }

    private static void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setExtraContents(new ItemStack[1]);
    }

    private void enterRecoveryMode(Player player) {
        active.add(player.getUniqueId());
        clearInventory(player);
        if (plugin.getConfig().getBoolean("Staff_Moderation.Staff_Mode.Allow_Flight", true)) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        if (plugin.getConfig().getBoolean("Staff_Moderation.Staff_Mode.Vanish_On_Enter", true)
                && StaffAccess.has(player, "chatalyst.command.vanish")) {
            vanish.set(player, true);
        }
        giveKit(player);
    }
}
