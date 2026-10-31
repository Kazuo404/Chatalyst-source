package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * El cofre donde se meten los objetos de un premio personalizado ({@code /sorteo editar <id>}).
 *
 * <p>Lo que hay dentro al CERRAR es el premio: se guarda cada objeto entero y el cofre se vacia
 * (los objetos pasan al premio, no se duplican). Sacar algo del cofre lo devuelve a quien edita.
 * Solo lo abre quien tiene el permiso de administrar sorteos.
 */
final class GiveawayCustomEditor implements InventoryHolder {

    private final String prizeId;
    private Inventory inventory;

    private GiveawayCustomEditor(String prizeId) {
        this.prizeId = prizeId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /**
     * Premios con el cofre abierto ahora mismo. Dos admins editando el mismo premio a la vez
     * se pisaban: el ultimo en cerrar guardaba lo suyo y los objetos del otro se perdian.
     */
    private static final java.util.Set<String> OPEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Objetos del premio que este servidor no sabe leer: no se enseñan, pero se conservan. */
    private List<String> unreadable = List.of();
    /** El cofre ya se guardo (o se esta guardando): un segundo cierre no hace nada. */
    private boolean settled;

    static void open(Player player, GiveawayCustomPrizes.Custom custom) {
        if (!OPEN.add(custom.id)) {
            player.sendMessage(Lang.get(player, "giveaway-custom.editor-busy", "id", custom.id));
            return;
        }
        boolean opened = false;
        try {
            GiveawayCustomEditor holder = new GiveawayCustomEditor(custom.id);
            holder.unreadable = custom.unreadableItems();
            holder.inventory = player.getServer().createInventory(holder, 54,
                    Lang.get(player, "giveaway-custom.editor-title", "id", custom.id));
            int slot = 0;
            for (ItemStack item : custom.itemStacks()) {
                if (slot >= 54) break;
                holder.inventory.setItem(slot++, item);
            }
            opened = player.openInventory(holder.inventory) != null;
        } finally {
            // Otro plugin cancelo la apertura (o fallo): no habra cierre que suelte el candado,
            // y el premio se quedaba "lo esta editando otro" hasta reiniciar el servidor.
            if (!opened) OPEN.remove(custom.id);
        }
        if (opened) {
            player.sendMessage(Lang.get(player, "giveaway-custom.editor-opened", "id", custom.id));
        }
    }

    /** Lo que hay en el cofre, sacado de el: a partir de aqui el cofre esta vacio. */
    private static List<ItemStack> drain(Inventory inventory) {
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) contents.add(item.clone());
        }
        inventory.clear();
        return contents;
    }

    /** La lista que se guarda: lo del cofre y, detras, lo que no se pudo leer al abrirlo. */
    static List<String> merged(List<String> encoded, List<String> unreadable) {
        if (unreadable.isEmpty()) return encoded;
        List<String> out = new ArrayList<>(encoded);
        out.addAll(unreadable);
        return out;
    }

    private static void giveBack(Player target, List<ItemStack> contents) {
        for (ItemStack back : target.getInventory()
                .addItem(contents.toArray(ItemStack[]::new)).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), back);
        }
    }

    /**
     * Al apagar el plugin los cofres abiertos se guardan AQUI y en el acto. El cierre normal no
     * sirve: con el plugin ya deshabilitado Bukkit no le entrega el evento de cierre, y aunque
     * se lo entregara no se puede programar la tarea de guardado. Sin esto, lo que el staff
     * hubiera metido en el cofre desaparecia con el reinicio.
     */
    static void closeAll(ChatalystPlugin plugin, GiveawayCustomPrizes prizes) {
        if (FoliaTasks.isFolia()) return; // al apagar Folia no se pueden tocar jugadores
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof GiveawayCustomEditor editor)) continue;
            List<ItemStack> contents = drain(top);
            // Ya guardado aqui: si el cierre de abajo llegara a avisar al listener, este no
            // debe volver a guardar un cofre que ya esta vacio (borraria el premio).
            editor.settled = true;
            try {
                persist(prizes, editor, contents, saved -> {
                    if (!saved) giveBack(player, contents);
                });
            } catch (RuntimeException failure) {
                giveBack(player, contents);
            }
            player.closeInventory();
        }
    }

    /** Guarda el cofre en el premio y dice si quedo guardado. Puede tocar la base de datos. */
    private static boolean persist(GiveawayCustomPrizes prizes, GiveawayCustomEditor editor,
                                   List<ItemStack> contents,
                                   java.util.function.Consumer<Boolean> done) {
        List<String> encoded = new ArrayList<>();
        for (ItemStack item : contents) {
            String b64 = GiveawayCustomPrizes.encode(item);
            if (b64 != null) encoded.add(b64);
        }
        GiveawayCustomPrizes.Update result;
        try {
            // Borrado mientras estaba abierto: no se resucita, los objetos vuelven a quien edita.
            result = prizes.update(editor.prizeId, custom -> {
                custom.items = merged(encoded, editor.unreadable);
                return true;
            });
        } finally {
            OPEN.remove(editor.prizeId);
        }
        boolean saved = result == GiveawayCustomPrizes.Update.SAVED;
        done.accept(saved);
        return result != GiveawayCustomPrizes.Update.MISSING;
    }

    /** Guarda al cerrar. Si el guardado falla, los objetos vuelven a quien edita. */
    static final class Listener implements org.bukkit.event.Listener {

        private final ChatalystPlugin plugin;
        private final GiveawayCustomPrizes prizes;

        Listener(ChatalystPlugin plugin, GiveawayCustomPrizes prizes) {
            this.plugin = plugin;
            this.prizes = prizes;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            if (!(event.getInventory().getHolder() instanceof GiveawayCustomEditor editor)) return;
            if (!(event.getPlayer() instanceof Player player)) return;
            if (editor.settled) return;
            editor.settled = true;
            List<ItemStack> contents = drain(event.getInventory());
            // MySQL fuera del hilo principal; el aviso y la devolucion, de vuelta en el jugador.
            Runnable save = () -> {
                boolean[] saved = new boolean[1];
                boolean exists;
                try {
                    exists = persist(prizes, editor, contents, ok -> saved[0] = ok);
                } catch (RuntimeException failure) {
                    plugin.getLogger().warning("Could not save giveaway prize '"
                            + editor.prizeId + "': " + failure);
                    exists = true;
                }
                final boolean existed = exists;
                FoliaTasks.entity(plugin, player, target -> {
                    if (saved[0]) {
                        target.sendMessage(Lang.get(target, "giveaway-custom.editor-saved",
                                "id", editor.prizeId, "count", contents.size()));
                        return;
                    }
                    target.sendMessage(Lang.get(target, existed ? "giveaway-custom.save-failed"
                            : "giveaway-custom.editor-deleted", "id", editor.prizeId));
                    giveBack(target, contents);
                }, () -> {
                    if (saved[0]) return;
                    // Cerro el cofre desconectandose y encima no se pudo guardar: los objetos
                    // no tienen a quien volver. Que al menos conste quien y cuantos.
                    plugin.getLogger().severe("Giveaway prize '" + editor.prizeId + "' could not"
                            + " be saved and " + player.getName() + " is offline: "
                            + contents.size() + " item stack(s) from its editor chest were lost.");
                });
            };
            try {
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> save.run());
            } catch (RuntimeException disabling) {
                save.run();
            }
        }
    }
}
