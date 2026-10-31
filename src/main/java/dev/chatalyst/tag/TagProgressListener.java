package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

/**
 * Convierte cambios de estadisticas vanilla en desbloqueos. Se difiere un tick para leer el
 * valor ya confirmado por el servidor. El control periodico recupera cambios sin evento
 * directo (permisos, inventario externo y tiempo) sin depender de una unica ruta de entrada.
 */
public final class TagProgressListener implements Listener {

    private static final long PERIOD_TICKS = 1_200L;

    private final ChatalystPlugin plugin;
    private final TagManager tags;
    private final java.util.Set<java.util.UUID> queued =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TagProgressListener(ChatalystPlugin plugin, TagManager tags) {
        this.plugin = plugin;
        this.tags = tags;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
        // Premios de titulo que no llegaron a ejecutarse (apagado justo tras desbloquear).
        TagRewardExecutor.replay(plugin, event.getPlayer());
    }

    /**
     * PLACEHOLDER (nivel de oficio, pase, Astrales...) y COUNTER cambiado desde OTRO servidor
     * de la red no tienen evento propio: se miran mas seguido que el resto, y solo si el
     * catalogo los usa. Un /tagcontador en este servidor ya re-evalua al momento.
     */
    static final long EXTERNAL_PERIOD_TICKS = 400L;

    /** Inicia el control tambien para jugadores presentes durante un hot-enable. */
    public void track(Player player) {
        player.getScheduler().runAtFixedRate(plugin, task -> check(player), null,
                40L, PERIOD_TICKS);
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (usesExternalProgress()) later(player);
        }, null, EXTERNAL_PERIOD_TICKS, EXTERNAL_PERIOD_TICKS);
    }

    private boolean usesExternalProgress() {
        TagCatalog catalog = tags.catalog();
        return catalog != null
                && (catalog.usesRequirement(TagRequirementType.PLACEHOLDER)
                || catalog.usesRequirement(TagRequirementType.COUNTER));
    }

    @EventHandler
    public void onStatistic(PlayerStatisticIncrementEvent event) {
        if (!relevant(event.getStatistic(), event.getNewValue())) return;
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (tags.catalog() != null
                && tags.catalog().usesRequirement(TagRequirementType.ITEMS_OWNED)
                && event.getEntity() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (tags.catalog() != null
                && tags.catalog().usesRequirement(TagRequirementType.ITEMS_OWNED)
                && event.getWhoClicked() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (tags.catalog() != null
                && tags.catalog().usesRequirement(TagRequirementType.ITEMS_OWNED)
                && event.getWhoClicked() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (tags.catalog() != null
                && tags.catalog().usesRequirement(TagRequirementType.ADVANCEMENT)) {
            later(event.getPlayer());
        }
    }

    private void later(Player player) {
        java.util.UUID id = player.getUniqueId();
        if (!queued.add(id)) return;
        player.getScheduler().runDelayed(plugin, task -> {
            queued.remove(id);
            check(player);
        }, () -> queued.remove(id), 1L);
    }

    private void check(Player player) {
        TagUnlockProcessor.refresh(plugin, tags, player);
    }

    private boolean relevant(Statistic statistic, int newValue) {
        TagCatalog catalog = tags.catalog();
        if (catalog == null) return false;
        if (statistic == Statistic.MOB_KILLS || statistic == Statistic.KILL_ENTITY) {
            return catalog.usesRequirement(TagRequirementType.MOB_KILLS);
        }
        if (statistic == Statistic.PLAYER_KILLS) {
            return catalog.usesRequirement(TagRequirementType.PLAYER_KILLS);
        }
        if (statistic == Statistic.MINE_BLOCK) {
            return catalog.usesRequirement(TagRequirementType.BLOCKS_MINED);
        }
        if (statistic == Statistic.CRAFT_ITEM) {
            return catalog.usesRequirement(TagRequirementType.ITEMS_CRAFTED);
        }
        if (statistic == Statistic.PICKUP) {
            return catalog.usesRequirement(TagRequirementType.ITEMS_PICKED_UP)
                    || catalog.usesRequirement(TagRequirementType.ITEMS_OWNED);
        }
        if (statistic == Statistic.FISH_CAUGHT) {
            return catalog.usesRequirement(TagRequirementType.FISH_CAUGHT);
        }
        if (statistic == Statistic.ANIMALS_BRED) {
            return catalog.usesRequirement(TagRequirementType.ANIMALS_BRED);
        }
        return statistic == Statistic.PLAY_ONE_MINUTE
                && catalog.usesRequirement(TagRequirementType.PLAYTIME_MINUTES)
                && newValue % 1_200 == 0;
    }
}
