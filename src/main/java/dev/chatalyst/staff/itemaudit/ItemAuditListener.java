package dev.chatalyst.staff.itemaudit;

import dev.chatalyst.util.PlayerNames;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Las tres fuentes del registro de objetos: lo que sale del inventario creativo, lo que dan
 * los comandos de dar y lo que, ya marcado, acaba en manos de otro jugador.
 *
 * <p>Folia: cada evento llega en el hilo de la region de su jugador/entidad y solo toca ese
 * jugador. Un /give a alguien de OTRA region no se le mira el inventario (seria ilegal): se
 * registra el comando tal cual, sin marca, y la fila lo dice.
 */
final class ItemAuditListener implements Listener {

    private final ItemAuditManager manager;
    private final Map<UUID, CreativeLedger<ItemStack>> ledgers = new ConcurrentHashMap<>();

    ItemAuditListener(ItemAuditManager manager) {
        this.manager = manager;
    }

    // ---- inventario creativo ---------------------------------------------------------------

    /**
     * HIGHEST: despues de los plugins que limitan el creativo (si lo cancelan, no se saco
     * nada) y antes de MONITOR, que no debe cambiar el objeto. Aqui se pone la marca.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        AuditSettings settings = manager.settings();
        if (!settings.enabled() || !settings.trackCreative() || ItemAuditManager.exempt(player)
                || settings.ignoresCreativeIn(player.getWorld().getName())) {
            return;
        }
        ItemStack before = event.getCurrentItem();
        ItemStack after = event.getCursor();
        boolean hadItem = before != null && !before.getType().isAir();
        boolean hasItem = after != null && !after.getType().isAir();
        long now = System.currentTimeMillis();
        CreativeLedger<ItemStack> ledger = ledgers.computeIfAbsent(player.getUniqueId(),
                ignored -> new CreativeLedger<>(ItemStack::isSimilar,
                        manager.settings().creativeMoveWindowMillis()));
        ItemMarks marks = manager.marks();
        if (!hasItem) {
            if (hadItem) ledger.removed(marks.unmarked(before), before.getAmount(), now);
            return;
        }
        if (marks.isKitItem(after)) return; // el kit del modo staff no es botin
        ItemStack afterKey = marks.unmarked(after);
        int created;
        if (!hadItem) {
            created = ledger.added(afterKey, after.getAmount(), now);
        } else {
            ItemStack beforeKey = marks.unmarked(before);
            if (beforeKey.isSimilar(afterKey)) {
                int diff = after.getAmount() - before.getAmount();
                if (diff < 0) ledger.removed(beforeKey, -diff, now);
                created = diff > 0 ? ledger.added(afterKey, diff, now) : 0;
            } else {
                ledger.removed(beforeKey, before.getAmount(), now);
                created = ledger.added(afterKey, after.getAmount(), now);
            }
        }
        if (created <= 0) return;
        ItemStack marked = after.clone();
        String id = markOrId(player.getUniqueId(), player.getName(), marked, now);
        if (manager.settings().markItems()) {
            event.setCursor(marked);
            // El cliente cree que tiene la version sin marcar: se le reenvia el inventario
            // para que lo siguiente que mande ya lleve la marca.
            player.getScheduler().run(manager.plugin(), task -> player.updateInventory(), null);
        }
        AuditEntry entry = manager.itemEntry(id, AuditType.CREATIVE, player.getUniqueId(),
                player.getName(), null, "", marked, created, "creative", player.getLocation());
        manager.record(entry, manager.alertsFor(entry));
    }

    /**
     * Clic central en un cofre estando en creativo: el juego clona el stack en el cursor sin
     * pasar por el inventario creativo. Se mira el cursor un tick despues.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClone(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) return;
        if (event.getAction() != InventoryAction.CLONE_STACK) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        AuditSettings settings = manager.settings();
        if (!settings.enabled() || !settings.trackCreative() || ItemAuditManager.exempt(player)
                || player.getGameMode() != GameMode.CREATIVE
                || settings.ignoresCreativeIn(player.getWorld().getName())) {
            return;
        }
        ItemStack source = event.getCurrentItem();
        if (source == null || source.getType().isAir() || manager.marks().isKitItem(source)) return;
        player.getScheduler().run(manager.plugin(), task -> {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor.getType().isAir() || !cursor.isSimilar(source)) return;
            long now = System.currentTimeMillis();
            String id = markOrId(player.getUniqueId(), player.getName(), cursor, now);
            if (manager.settings().markItems()) player.setItemOnCursor(cursor);
            AuditEntry entry = manager.itemEntry(id, AuditType.CREATIVE, player.getUniqueId(),
                    player.getName(), null, "", cursor, cursor.getAmount(), "creative",
                    player.getLocation());
            manager.record(entry, manager.alertsFor(entry));
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ledgers.remove(event.getPlayer().getUniqueId());
    }

    // ---- comandos de dar ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        AuditSettings settings = manager.settings();
        if (!settings.enabled() || !settings.trackCommands() || ItemAuditManager.exempt(player)) {
            return;
        }
        GiveCommandParser.match(event.getMessage(), settings.patterns())
                .ifPresent(give -> handleGive(player, player.getUniqueId(), player.getName(),
                        give, player.getLocation()));
    }

    /** Consola, RCON y bloques de comandos. {@code dispatchCommand} de plugins no pasa por aqui. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        AuditSettings settings = manager.settings();
        if (!settings.enabled() || !settings.trackCommands() || !settings.includeConsole()) return;
        Optional<GiveCommandParser.GiveCommand> give =
                GiveCommandParser.match(event.getCommand(), settings.patterns());
        if (give.isEmpty()) return;
        CommandSender sender = event.getSender();
        String name = ItemAuditManager.CONSOLE;
        Location where = null;
        if (sender instanceof BlockCommandSender block) {
            name = "@command_block";
            where = block.getBlock().getLocation();
        }
        handleGive(sender, null, name, give.get(), where);
    }

    private void handleGive(CommandSender sender, UUID actorId, String actorName,
                            GiveCommandParser.GiveCommand give, Location where) {
        List<Player> recipients = new ArrayList<>();
        var server = manager.plugin().getServer();
        String token = give.recipient();
        if (give.selfTarget() || "@p".equalsIgnoreCase(token)) {
            if (sender instanceof Player self) recipients.add(self);
        } else if ("@a".equalsIgnoreCase(token)) {
            recipients.addAll(server.getOnlinePlayers());
        } else if (!token.startsWith("@")) {
            Player found = server.getPlayer(token); // prefijo, como Essentials
            if (found != null) recipients.add(found);
        }
        if (recipients.isEmpty()) {
            // Selector raro, jugador offline o la consola con /i: queda lo escrito.
            commandOnly(actorId, actorName, give, null, give.recipient(), where, false);
            return;
        }
        for (Player recipient : recipients) {
            if (server.isOwnedByCurrentRegion(recipient)) {
                watch(actorId, actorName, give, recipient, where);
            } else {
                commandOnly(actorId, actorName, give, recipient.getUniqueId(), recipient.getName(),
                        where, false);
            }
        }
    }

    /**
     * Foto del inventario ahora (el comando aun no se ejecuto) y otra un tick despues: lo
     * que aparecio es lo que dio el comando, con su material y encantamientos reales.
     */
    private void watch(UUID actorId, String actorName, GiveCommandParser.GiveCommand give,
                       Player recipient, Location where) {
        List<ItemStack> before = snapshot(recipient);
        Material wanted = give.itemKey() == null ? null : Material.matchMaterial(give.itemKey());
        Predicate<ItemStack> filter = item -> !manager.marks().isKitItem(item)
                && manager.marks().read(item) == null
                && (wanted == null || item.getType() == wanted);
        recipient.getScheduler().run(manager.plugin(), task -> {
            List<ItemStack> after = snapshot(recipient);
            var added = InventoryDelta.added(before, after, ItemStack::isSimilar,
                    ItemStack::getAmount, filter);
            boolean self = actorId != null && actorId.equals(recipient.getUniqueId());
            UUID targetId = self ? null : recipient.getUniqueId();
            String targetName = self ? "" : recipient.getName();
            Location at = where != null ? where : recipient.getLocation();
            if (added.isEmpty()) {
                // Sin permiso, objeto inexistente o inventario lleno (lo dado cae al suelo).
                commandOnly(actorId, actorName, give, targetId, targetName, at, true);
                return;
            }
            long now = System.currentTimeMillis();
            for (InventoryDelta.Added<ItemStack> slot : added) {
                ItemStack stack = slot.now().clone();
                String id = markOrId(actorId, actorName, stack, now);
                // Si lo dado se fundio con un stack que ya tenia, la marca cubre el stack
                // entero: es el precio de no partirle el inventario.
                if (manager.settings().markItems()) {
                    recipient.getInventory().setItem(slot.slot(), stack);
                }
                AuditEntry entry = manager.itemEntry(id, AuditType.GIVE, actorId, actorName,
                        targetId, targetName, stack, slot.amount(), give.line(), at);
                manager.record(entry, manager.alertsFor(entry));
            }
        }, () -> commandOnly(actorId, actorName, give, recipient.getUniqueId(),
                recipient.getName(), where, false));
    }

    private static List<ItemStack> snapshot(Player player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            out.add(item == null || item.getType().isAir() ? null : item.clone());
        }
        return out;
    }

    /**
     * Lo escrito, con material y cantidad segun el propio comando.
     *
     * @param checked se miro el inventario y no llego nada: casi siempre un comando fallido,
     *                asi que solo avisa si lo pedido era valioso. Sin poder mirarlo
     *                ({@code false}: otra region, offline, selector) avisa como una entrega.
     */
    private void commandOnly(UUID actorId, String actorName, GiveCommandParser.GiveCommand give,
                             UUID targetId, String targetName, Location where, boolean checked) {
        String item = give.itemKey() == null ? "" : give.itemKey();
        boolean valuable = manager.valuable(ValuableRules.ItemFacts.of(item, give.amount()));
        boolean self = actorId != null && actorId.equals(targetId);
        AuditEntry entry = ItemAuditManager.entry(AuditIds.next(), AuditType.COMMAND, actorId,
                actorName, self ? null : targetId, self || targetName == null ? "" : targetName,
                item, give.amount(), checked ? AuditText.NOTHING_ARRIVED : "", give.line(), where,
                valuable);
        manager.record(entry, checked ? valuable || manager.settings().alertEverything()
                : manager.alertsFor(entry));
    }

    /** Pone la marca (si esta activa) y devuelve el id de la fila. */
    private String markOrId(UUID actorId, String actorName, ItemStack stack, long now) {
        AuditSettings settings = manager.settings();
        if (!settings.markItems()) return AuditIds.next();
        ItemMarks.Mark mark = manager.marks().markFor(actorId, actorName,
                stack.getType().getKey().toString(), now, settings.markReuseMillis());
        manager.marks().apply(stack, mark);
        return mark.id();
    }

    // ---- a quien le llega ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        ItemMarks.Mark mark = transferable(player, stack);
        if (mark == null) return;
        UUID thrower = event.getItem().getThrower();
        if (player.getUniqueId().equals(thrower)) return; // recoge lo que el mismo tiro
        String via = "pickup";
        if (thrower != null) {
            via = "pickup:" + PlayerNames.current(manager.plugin().getServer(), thrower, "?");
        }
        int amount = stack.getAmount() - event.getRemaining();
        transfer(mark, player, stack, Math.max(1, amount), via, event.getItem().getLocation());
    }

    /** Sacar de un contenedor (cofre, ender chest ajeno, cofre del matrimonio, invsee...). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTake(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top || top.getType() == InventoryType.CRAFTING) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;
        int amount = switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY, SWAP_WITH_CURSOR,
                 HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> current.getAmount();
            case PICKUP_HALF -> (current.getAmount() + 1) / 2;
            case PICKUP_ONE -> 1;
            default -> 0;
        };
        if (amount <= 0) return;
        ItemMarks.Mark mark = transferable(player, current);
        if (mark == null) return;
        Location where = top.getLocation() != null ? top.getLocation() : player.getLocation();
        transfer(mark, player, current, amount, "container:" + top.getType().name(), where);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        ItemStack taken = event.getArmorStandItem();
        ItemMarks.Mark mark = transferable(event.getPlayer(), taken);
        if (mark == null) return;
        transfer(mark, event.getPlayer(), taken, taken.getAmount(), "armor_stand",
                event.getRightClicked().getLocation());
    }

    /** La marca, si este jugador recibir esto cuenta como traspaso. */
    private ItemMarks.Mark transferable(Player receiver, ItemStack item) {
        AuditSettings settings = manager.settings();
        if (!settings.enabled() || !settings.trackTransfers()) return null;
        if (item == null || item.getType().isAir()) return null;
        ItemMarks.Mark mark = manager.marks().read(item);
        if (mark == null) return null;
        // De vuelta a quien lo saco no es un traspaso.
        return receiver.getUniqueId().equals(mark.staff()) ? null : mark;
    }

    private void transfer(ItemMarks.Mark mark, Player receiver, ItemStack item, int amount,
                          String via, Location where) {
        if (!manager.firstTransfer(mark.id(), receiver.getUniqueId(), System.currentTimeMillis())) {
            return;
        }
        AuditEntry entry = manager.itemEntry(mark.id(), AuditType.TRANSFER, mark.staff(),
                mark.staffName(), receiver.getUniqueId(), receiver.getName(), item, amount, via,
                where);
        manager.record(entry, manager.alertsFor(entry));
    }
}
