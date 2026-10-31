package dev.chatalyst.staff;

import dev.chatalyst.gui.MenuHolder;
import dev.chatalyst.lang.Lang;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanish es para MIRAR: mientras eres invisible, el mundo no puede notarte.
 *
 * <p>Un bloque que se rompe solo, un cofre cuya tapa se levanta sin nadie delante o un mob que
 * recibe un golpe de la nada delatan el vanish igual de rapido que verte. Pero el motivo de
 * fondo no es el disfraz, es la ventaja: invisible se puede minar en mitad de una base, vaciar
 * un cofre o rematar a alguien sin que nada de eso tenga respuesta posible. Asi que durante el
 * vanish no se CAMBIA nada — se observa, y punto.
 *
 * <p>Observar, sin embargo, incluye mirar dentro de un cofre: es media investigacion. Por eso
 * el contenedor no se prohibe, se desvia — el clic derecho se cancela antes de que el bloque
 * se entere y el contenido se muestra en una copia ({@link SilentContainerView}). Sin tapa,
 * sin sonido y sin "en uso": lo mismo que veria un espectador, hecho desde un jugador normal.
 *
 * <p>Lo que es SOLO tuyo se deja en paz: tu propio inventario, tu mesa de crafteo y las GUIs
 * de otros plugins (tienda, kits, menus) son inventarios virtuales que nadie mas puede ver, y
 * bloquearlos convertia el vanish en una camisa de fuerza sin proteger nada a cambio. La
 * frontera es "tiene sitio en el mundo": {@link Inventory#getLocation()} solo devuelve algo
 * para los contenedores reales.
 *
 * <p>El daño RECIBIDO se deja intacto a proposito. Un vanish invulnerable convierte cualquier
 * sitio peligroso en un mirador gratis: lava, void, una trampa que se quiere "comprobar". Si
 * se entra donde no se debe, se paga lo mismo que pagaria cualquiera, muerte y drop incluidos.
 *
 * <p>Las herramientas de staff siguen vivas: los items del kit los sirve
 * {@link StaffModeListener} en {@code LOWEST} y este listener corre en {@code HIGHEST} con
 * {@code ignoreCancelled}, asi que cuando el kit ya reclamo el evento aqui no se toca — y una
 * proteccion de region que ya cancelo el clic tampoco se convierte en una mirada gratis.
 */
public final class VanishObserverListener implements Listener {

    private final JavaPlugin plugin;
    private final VanishManager vanish;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public VanishObserverListener(StaffModerationModule module) {
        this.plugin = module.plugin();
        this.vanish = module.vanish();
    }

    // ---- bloques -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer(), "Blocks")) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer(), "Blocks")) deny(event.getPlayer(), event);
    }

    // ---- golpear -------------------------------------------------------------------

    /** Solo el daño que SALE del vanished. El que recibe se deja pasar entero. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = StaffActor.player(event.getDamager());
        if (attacker != null && restricted(attacker, "Attack")) deny(attacker, event);
    }

    // ---- cofres, botones, palancas, comer -------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getHand() == EquipmentSlot.HAND
                && peeking(player)) {
            Inventory container = containerOf(event.getClickedBlock(), player);
            if (container != null) {
                // Cancelar ANTES de que el bloque llegue a abrirse: la tapa y el sonido salen
                // de la apertura real, asi que en InventoryOpenEvent ya seria tarde.
                event.setCancelled(true);
                SilentContainerView.open(player, container);
                return;
            }
        }
        if (restricted(player, "Interact")) deny(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.HAND && peeking(player)) {
            // Vagoneta con cofre, barca con cofre, burro o llama con alforjas: contenedores
            // que ademas se mueven, asi que delatan igual o mas que un cofre quieto.
            Inventory container = containerOf(event.getRightClicked());
            if (container != null) {
                event.setCancelled(true);
                SilentContainerView.open(player, container);
                return;
            }
        }
        if (restricted(player, "Interact")) deny(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (restricted(event.getPlayer(), "Interact")) deny(event.getPlayer(), event);
    }

    /**
     * Si no puede comer, tampoco pasa hambre.
     *
     * <p>Bloquear el uso de objetos incluye comer y beber, y eso solo se nota en las guardias
     * largas: la barra baja igual mientras miras, no hay forma de rellenarla, y el daño
     * recibido esta intacto a proposito — asi que vigilar cuarenta minutos acababa en morir de
     * inanicion invisible, con drop y con una tumba apareciendo justo donde estabas mirando.
     * Todo lo que el modo observador existe para evitar, por inanicion.
     *
     * <p>No es una ventaja: no se puede comer, asi que no ganar hambre solo devuelve el
     * empate. Se cancela unicamente la BAJADA — recuperar comida sigue siendo imposible —, y
     * va atado al mismo interruptor que quita el comer: si el dueño permite interactuar, el
     * hambre vuelve a correr como para cualquiera.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return;
        if (restricted(player, "Interact")) event.setCancelled(true);
    }

    /**
     * Red de seguridad, no la puerta principal: los contenedores del mundo ya se desvian en el
     * clic. Aqui solo cae lo que abrio otro plugin por su cuenta, y unicamente si tiene sitio
     * en el mundo — una GUI virtual (tienda, kits, un menu cualquiera) no la ve nadie mas.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!worldContainer(event.getInventory())) return;
        if (restricted(player, "Interact")) deny(player, event);
    }

    // ---- items ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (restricted(event.getPlayer(), "Items")) deny(event.getPlayer(), event);
    }

    /** Recoger es la fuga mas silenciosa: el item desaparece del suelo delante de todos. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && restricted(player, "Items")) {
            event.setCancelled(true);
        }
    }

    /**
     * Mover items solo se vigila DENTRO de un contenedor del mundo. Reordenar la mochila
     * propia no lo ve nadie, y prohibirlo era lo que dejaba el vanish inservible: con la GUI
     * de la tienda o la de los kits abierta no se podia ni hacer clic en ella.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!worldContainer(topOf(event.getView()))) return;
        if (restricted(player, "Items")) deny(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!worldContainer(topOf(event.getView()))) return;
        if (restricted(player, "Items")) deny(player, event);
    }

    /**
     * El anti-spam del aviso no tiene por que sobrevivir a la sesion: una entrada por jugador
     * que se queda para siempre es poca cosa, pero en un server que lleva meses arriba son
     * todos los que alguna vez pasaron por vanish.
     */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    // ---- interno -------------------------------------------------------------------

    private static Inventory topOf(InventoryView view) {
        return view == null ? null : view.getTopInventory();
    }

    /**
     * Un inventario que OCUPA UN SITIO en el mundo: un cofre, un horno, una vagoneta. Es lo
     * unico que otro jugador puede ver, abrir o echar en falta.
     *
     * <p>Quedan fuera, y por tanto libres: los menus de Chatalyst, la sesion de edicion de
     * invsee/echest, la mochila y la mesa de crafteo propias, y toda GUI creada por un plugin
     * — inventarios virtuales, sin coordenadas, que existen solo para quien los abrio.
     */
    private static boolean worldContainer(Inventory inventory) {
        if (inventory == null) return false;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof MenuHolder) return false;
        if (InventoryEditSession.isEditSession(inventory)) return false;
        // Tu propio inventario (y su mesa 2x2) tambien "tiene sitio": el tuyo. getLocation()
        // devuelve donde estas, asi que sin esto el vanish te impedia hasta quitarte la armadura.
        if (holder instanceof org.bukkit.entity.HumanEntity) return false;
        if (inventory.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                || inventory.getType() == org.bukkit.event.inventory.InventoryType.PLAYER) {
            return false;
        }
        return inventory.getLocation() != null;
    }

    /** Contenido de un contenedor de bloque, o null si ahi no hay nada que mirar. */
    static Inventory containerOf(Block block, Player player) {
        if (block == null) return null;
        // El cofre de ender guarda lo tuyo, pero el BLOQUE es del mundo: su tapa se levanta
        // delante de todos igual que la de un cofre normal.
        if (block.getType() == Material.ENDER_CHEST) return player.getEnderChest();
        BlockState state = block.getState();
        return state instanceof Container container ? container.getInventory() : null;
    }

    /**
     * Contenido de una entidad-contenedor, o null si ahi no hay un alijo.
     *
     * <p>Tener inventario no basta: un aldeano lo tiene para comerciar y un caballo para la
     * silla. Guardan cosas de verdad las vagonetas y barcas con cofre —que no son seres
     * vivos— y las monturas que llevan cofre puesto.
     */
    static Inventory containerOf(Entity entity) {
        if (!(entity instanceof InventoryHolder holder)) return null;
        if (entity instanceof ChestedHorse horse) {
            return horse.isCarryingChest() ? holder.getInventory() : null;
        }
        if (entity instanceof LivingEntity) return null;
        Inventory inventory = holder.getInventory();
        return inventory.getSize() == 0 ? null : inventory;
    }

    /** Si a este jugador los contenedores se le muestran en copia en vez de abrirse de verdad. */
    /**
     * Quien lo tiene, en vanish actua como cualquiera: rompe, pone, abre el cofre real, mueve y
     * tira items. Es una decision del dueño para su gente de confianza, no un default.
     */
    public static final String BYPASS = "chatalyst.vanish.bypass";

    private boolean peeking(Player player) {
        return player != null && vanish.isOwned(player) && !StaffAccess.has(player, BYPASS)
                && plugin.getConfig().getBoolean("Staff_Moderation.Vanish.Silent_Containers", true);
    }

    private boolean restricted(Player player, String action) {
        if (player == null || !vanish.isOwned(player)) return false;
        if (StaffAccess.has(player, BYPASS)) return false;
        return plugin.getConfig().getBoolean(
                       "Staff_Moderation.Vanish.Observer_Only.Enabled", true)
                && plugin.getConfig().getBoolean(
                        "Staff_Moderation.Vanish.Observer_Only." + action, true);
    }

    private void deny(Player player, Cancellable event) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long previous = lastNotice.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 1_500L) return;
        player.sendActionBar(Lang.get(player, "staff-moderation.vanish-observer-blocked"));
    }
}
