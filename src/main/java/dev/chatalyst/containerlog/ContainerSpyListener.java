package dev.chatalyst.containerlog;

import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

/**
 * Vigila la actividad de los jugadores: contenedores (cofres, barriles, shulkers, hornos,
 * ender chests, vagonetas/barcas con cofre), mecanismos (palancas, botones, puertas...) y
 * acciones de banco de trabajo — crafteos, encantamientos y tratos con aldeanos. Cada
 * evento queda en el {@link ContainerLogManager} con coordenadas exactas y se emite en
 * vivo a los espias con el tipo granular {@code container}.
 *
 * <p>Las repeticiones se agrupan ({@link dev.chatalyst.util.RepeatThrottle}): abrir el mismo
 * cofre veinte veces seguidas produce UNA linea, y la siguiente indica cuantas se ocultaron. Para
 * objetivos que son ENTIDADES (aldeanos, vagonetas) la clave es su UUID y no sus
 * coordenadas: si no, cada paso del aldeano contaba como un objetivo nuevo y el spy se
 * llenaba de lineas identicas.
 */
public final class ContainerSpyListener implements Listener {

    private final JavaPlugin plugin;
    private final PrivateMessages pm;
    private final ContainerLogManager manager;
    private final dev.chatalyst.util.RepeatThrottle throttle =
            new dev.chatalyst.util.RepeatThrottle();

    public ContainerSpyListener(JavaPlugin plugin, PrivateMessages pm,
                                ContainerLogManager manager) {
        this.plugin = plugin;
        this.pm = pm;
        this.manager = manager;
    }

    // ---- Aperturas de contenedores ---------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!manager.containersEnabled()) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        // Los menus del propio plugin (y de otros) no son cofres del mundo.
        if (holder instanceof dev.chatalyst.gui.MenuHolder) return;
        if (holder instanceof DoubleChest doubleChest) {
            record(player, "OPEN", "DOUBLE_CHEST", doubleChest.getLocation(), null);
        } else if (holder instanceof BlockInventoryHolder blockHolder) {
            Block block = blockHolder.getBlock();
            record(player, "OPEN", block.getType().name(), block.getLocation(), null);
        } else if (holder instanceof Entity entity && !(entity instanceof Player)) {
            // Vagoneta con cofre, aldeano, caballo...: la clave de repeticion es su UUID,
            // porque la entidad se MUEVE y sus coordenadas cambian a cada paso.
            record(player, "OPEN", entity.getType().name(), entity.getLocation(),
                    entity.getUniqueId());
        }
        // ender chest y menus virtuales se cubren por interaccion
    }

    // ---- Ender chest y mecanismos ----------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // evita el doble disparo off-hand
        if (event.useInteractedBlock() == Event.Result.DENY) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material material = block.getType();
        if (material == Material.ENDER_CHEST) {
            if (!manager.containersEnabled()) return;
            record(event.getPlayer(), "OPEN", material.name(), block.getLocation(), null);
        } else if (isMechanism(material)) {
            if (!manager.mechanismsEnabled()) return;
            record(event.getPlayer(), "USE", material.name(), block.getLocation(), null);
        }
    }

    // ---- Colocar y romper contenedores -----------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!manager.containersEnabled()) return;
        Material material = event.getBlockPlaced().getType();
        if (!isTrackedContainer(material)) return;
        record(event.getPlayer(), "PLACE", material.name(),
                event.getBlockPlaced().getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!manager.containersEnabled()) return;
        Material material = event.getBlock().getType();
        if (!isTrackedContainer(material)) return;
        record(event.getPlayer(), "BREAK", material.name(), event.getBlock().getLocation(),
                null);
    }

    // ---- Mesa de trabajo: crafteos, encantamientos y tratos ---------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!manager.craftingEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType().isAir()) return;
        record(player, "CRAFT", result.getType().name(), player.getLocation(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!manager.craftingEnabled()) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;
        record(event.getEnchanter(), "ENCHANT", item.getType().name(),
                event.getEnchantBlock().getLocation(), null);
    }

    /**
     * Trato cerrado con un aldeano: el click sobre el slot de resultado (2) del mercader.
     * Se registra QUE se llevo y QUE pago — un trato solo es revisable si se ve el
     * intercambio completo (detectar precios rotos, granjas de esmeraldas, etc.).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(InventoryClickEvent event) {
        if (!manager.tradingEnabled()) return;
        if (!(event.getInventory() instanceof MerchantInventory merchant)) return;
        if (event.getRawSlot() != 2) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) return;
        String cost = "";
        try {
            org.bukkit.inventory.MerchantRecipe recipe = merchant.getSelectedRecipe();
            if (recipe != null) cost = encodeItems(recipe.getIngredients());
        } catch (Throwable ignored) {
            // Sin receta seleccionada (o API distinta): se registra solo el resultado.
        }
        // "3xEMERALD/20xWHEAT+1xBOOK": sin espacios, para que la linea del log siga
        // siendo re-parseable; ContainerLogManager lo traduce al mostrarlo.
        String target = encodeItem(result);
        if (!cost.isEmpty()) target = target + "/" + cost;
        record(player, "TRADE", target, player.getLocation(), null);
    }

    private static String encodeItem(ItemStack item) {
        return item.getAmount() + "x" + item.getType().name();
    }

    private static String encodeItems(java.util.List<ItemStack> items) {
        StringBuilder out = new StringBuilder();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            if (out.length() > 0) out.append('+');
            out.append(encodeItem(item));
        }
        return out.toString();
    }

    // ---- Nucleo ----------------------------------------------------------------------

    /**
     * Registra la accion si no es una repeticion reciente. {@code entityId} no nulo hace
     * que la agrupacion ignore las coordenadas (objetivos que se mueven).
     */
    private void record(Player player, String action, String target, Location location,
                        UUID entityId) {
        if (location == null || location.getWorld() == null) return;
        if (player.hasPermission(ContainerLogManager.EXEMPT_PERMISSION)) return;
        UUID id = player.getUniqueId();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        String key = id + ":" + action + ":" + (entityId != null
                ? "E" + entityId
                : target + ":" + location.getWorld().getName() + ":" + x + ":" + y + ":" + z);
        int hidden = throttle.register(key, manager.repeatWindowMillis());
        if (hidden < 0) return; // repeticion dentro de la ventana: se calla y se cuenta
        ContainerRecord record = new ContainerRecord(System.currentTimeMillis(), id,
                player.getName(), action, target, location.getWorld().getName(), x, y, z,
                hidden);
        manager.add(record);
        broadcastToSpies(record);
    }

    // Mismo patron que SpyContentListener: despacho por entidad (Folia) y mensaje por-espia
    // para que cada uno lo vea en su idioma y con botones solo si puede teletransportarse.
    private void broadcastToSpies(ContainerRecord record) {
        UUID sourceId = record.playerId();
        FoliaTasks.onlinePlayers(plugin,
                spy -> !spy.getUniqueId().equals(sourceId)
                        && pm.isSpy(spy) && PrivateMessages.canSpy(spy, "container")
                        && pm.spyWatches(spy, record.playerName()),
                spy -> spy.sendMessage(ContainerLogManager.render(spy, record, "spy.container")));
    }

    // ---- Clasificacion (por nombre: compila igual en cualquier version de la API) ----

    static boolean isMechanism(Material material) {
        String n = material.name();
        return n.equals("LEVER") || n.equals("REPEATER") || n.equals("COMPARATOR")
                || n.equals("DAYLIGHT_DETECTOR")
                || n.endsWith("_BUTTON") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR")
                || n.endsWith("_FENCE_GATE");
    }

    static boolean isTrackedContainer(Material material) {
        String n = material.name().toUpperCase(Locale.ROOT);
        return n.equals("CHEST") || n.equals("TRAPPED_CHEST") || n.equals("BARREL")
                || n.equals("ENDER_CHEST") || n.endsWith("SHULKER_BOX")
                || n.equals("FURNACE") || n.equals("BLAST_FURNACE") || n.equals("SMOKER")
                || n.equals("HOPPER") || n.equals("DROPPER") || n.equals("DISPENSER")
                || n.equals("BREWING_STAND") || n.equals("LECTERN") || n.equals("JUKEBOX")
                || n.equals("CHISELED_BOOKSHELF") || n.equals("DECORATED_POT")
                || n.equals("CRAFTER");
    }
}
