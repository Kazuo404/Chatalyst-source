package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Enforces review freezes while still allowing the player to look around and contact staff. */
public final class FreezeListener implements Listener {

    private final StaffModerationModule module;
    private final JavaPlugin plugin;
    private final FreezeManager freezes;
    private final FreezeNotice notice;
    private final FreezeDisconnectGuard disconnectGuard;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPeek = new ConcurrentHashMap<>();
    // Staff que ya recibieron el aviso "tu chat está enlazado a la revisión" esta sesión.
    private final Set<UUID> reviewChatHinted = ConcurrentHashMap.newKeySet();

    public FreezeListener(StaffModerationModule module) {
        this.module = module;
        this.plugin = module.plugin();
        this.freezes = module.freezes();
        this.notice = module.freezeNotice();
        this.disconnectGuard = module.disconnectGuard();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || !restricted(event.getPlayer())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        boolean moved = !from.getWorld().equals(to.getWorld())
                || from.getX() != to.getX() || from.getY() != to.getY()
                || from.getZ() != to.getZ();
        boolean turned = from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();
        if (!moved && !turned) return;
        // Estatico TOTAL: cancelar revierte posicion Y camara. FreezeHold ya puso la
        // velocidad a cero, asi que el cliente ni acelera y no se ve el tiron elastico.
        event.setCancelled(true);
        if (moved) notifyBlocked(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!restricted(event.getPlayer())) return;
        event.setCancelled(true);
        notifyBlocked(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!restricted(player) || !plugin.getConfig()
                .getBoolean("Staff_Moderation.Freeze.Block_Commands", true)) return;
        String input = event.getMessage().substring(1).trim();
        if (input.isEmpty()) return;
        String root = input.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);
        if (allowedCommands().contains(root)) return;
        event.setCancelled(true);
        notifyBlocked(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        cancel(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) cancel(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) cancel(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) cancel(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) cancel(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && restricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = StaffActor.player(event.getDamager());
        if (attacker != null && restricted(attacker)) {
            event.setCancelled(true);
            notifyBlocked(attacker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!freezes.isFrozen(player.getUniqueId()) || opExempt(player)) {
            // Lo liberaron estando fuera: su playerdata guardo la velocidad 0 del freeze.
            FreezeHold.restoreIfHeld(plugin, player);
            return;
        }
        FreezeHold.apply(plugin, player);
        player.getScheduler().runDelayed(plugin, task -> {
            FreezeRecord current = freezes.get(player.getUniqueId());
            if (player.isOnline() && !opExempt(player) && current != null) {
                notice.show(player, current);
                FreezeAlerts.notifyStaff(module, player.getName(), current.reason(),
                        current.staff(), true);
            }
        }, null, 20L);
    }

    /**
     * El chat de un congelado no sale al publico: se entrega SOLO al staff que lo congelo
     * (y a la consola, para que quede constancia). Asi puede explicarse sin coordinarse
     * con nadie mas; si su revisor no esta conectado, se le indica usar /support.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getConfig()
                .getBoolean("Staff_Moderation.Freeze.Restrict_Chat", true)) return;
        if (!restricted(player)) {
            // El otro lado del canal privado: el chat del revisor va SOLO a sus congelados.
            routeReviewerChat(event, player);
            return;
        }
        FreezeRecord record = freezes.get(player.getUniqueId());
        if (record == null) return;
        event.setCancelled(true);
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(event.message()).trim();
        if (text.isEmpty()) return;
        // Cinturon y tirantes: el SwearChatListener corre antes SOLO por orden de registro
        // en la misma prioridad; si ese orden cambiara, este relay quedaria sin filtro.
        text = filterReviewChat(player, text);
        if (text == null || text.isEmpty()) return;
        String name = player.getName();
        // Auditoria: el evento se cancela y el log de chat (MONITOR) nunca lo ve.
        logReviewChat(name, text);
        String safeText = dev.chatalyst.util.MM.escape(text);
        FoliaTasks.entity(plugin, player, current -> current.sendMessage(Lang.get(current,
                "staff-moderation.freeze-chat-self", "message", safeText)));
        FoliaTasks.global(plugin, () -> {
            Player freezer = reviewer(record);
            if (freezer != null) {
                FoliaTasks.entity(plugin, freezer, current -> current.sendMessage(Lang.get(current,
                        "staff-moderation.freeze-chat",
                        "player", dev.chatalyst.util.MM.escape(name), "message", safeText)));
            } else {
                FoliaTasks.entity(plugin, player, current -> current.sendMessage(Lang.get(current,
                        "staff-moderation.freeze-chat-alone",
                        "staff", dev.chatalyst.util.MM.escape(record.staff()))));
            }
            plugin.getServer().getConsoleSender().sendMessage(Lang.get(
                    plugin.getServer().getConsoleSender(), "staff-moderation.freeze-chat",
                    "player", dev.chatalyst.util.MM.escape(name), "message", safeText));
        });
    }

    /**
     * Mientras el revisor tenga congelados online (los que ÉL congeló), su chat se entrega
     * SOLO a ellos y a la consola: la conversación completa queda privada sin dejar al
     * congelado hablando solo. Empezar el mensaje con {@code !} lo envía al chat público.
     * El enlace termina solo: al descongelar, banear o desconectarse el congelado.
     */
    private void routeReviewerChat(io.papermc.paper.event.player.AsyncChatEvent event,
                                   Player staff) {
        // El registro guarda el NOMBRE del revisor: sin este chequeo, un jugador que
        // coincida por nombre (freeze desde consola, nombre Mojang reciclado) capturaria
        // el canal sin ser staff. Solo un revisor real puede tener el chat enlazado.
        if (!StaffAccess.has(staff, "chatalyst.command.freeze")) return;
        List<Player> targets = onlineFrozenBy(staff);
        if (targets.isEmpty()) {
            reviewChatHinted.remove(staff.getUniqueId());
            return;
        }
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(event.message()).trim();
        if (text.startsWith("!")) {
            String publicText = text.substring(1).trim();
            if (publicText.isEmpty()) {
                event.setCancelled(true);
                return;
            }
            // Sin cancelar: sigue el flujo normal de canales/formato con el "!" quitado.
            event.message(net.kyori.adventure.text.Component.text(publicText));
            return;
        }
        event.setCancelled(true);
        if (text.isEmpty()) return;
        // Mismo filtro y auditoria que el lado del congelado: el canal privado no es un
        // hueco sin moderar ni sin rastro en /chatlog.
        text = filterReviewChat(staff, text);
        if (text == null || text.isEmpty()) return;
        String safeText = dev.chatalyst.util.MM.escape(text);
        String safeStaff = dev.chatalyst.util.MM.escape(staff.getName());
        StringBuilder names = new StringBuilder();
        for (Player target : targets) {
            if (!names.isEmpty()) names.append(", ");
            names.append(target.getName());
            FoliaTasks.entity(plugin, target, current -> current.sendMessage(Lang.get(current,
                    "staff-moderation.freeze-chat-from-staff",
                    "staff", safeStaff, "message", safeText)));
        }
        logReviewChat(staff.getName(), "[to " + names + "] " + text);
        String safeNames = dev.chatalyst.util.MM.escape(names.toString());
        boolean showHint = reviewChatHinted.add(staff.getUniqueId());
        FoliaTasks.entity(plugin, staff, current -> {
            current.sendMessage(Lang.get(current, "staff-moderation.freeze-chat-staff-self",
                    "player", safeNames, "message", safeText));
            if (showHint) current.sendMessage(Lang.get(current,
                    "staff-moderation.freeze-chat-staff-hint"));
        });
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(Lang.get(
                plugin.getServer().getConsoleSender(), "staff-moderation.freeze-chat",
                "player", safeStaff, "message", safeText)));
    }

    /**
     * Filtro de groserias del canal de revision. Idempotente: si el SwearChatListener ya
     * censuro el mensaje, aqui no vuelve a coincidir (ni avisa dos veces). Devuelve el
     * texto a entregar (censurado si procede) o null si la config es DENY y hubo match.
     * No suma puntos de aviso: castigar el canal de revision interferiria con el freeze.
     */
    private static String filterReviewChat(Player speaker, String text) {
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        dev.chatalyst.swear.SwearFilter filter =
                chatalyst == null ? null : chatalyst.getSwearFilter();
        if (filter == null || !filter.isEnabled() || filter.hasBypass(speaker)) return text;
        dev.chatalyst.swear.SwearFilter.Result result = filter.apply(text);
        if (!result.matched()) return text;
        dev.chatalyst.log.LogManager log = chatalyst.getLogManager();
        if (log != null && log.logsSwear()) {
            log.log("SWEAR", speaker.getName(),
                    (filter.getAction() == dev.chatalyst.swear.SwearFilter.Action.DENY
                            ? "[denied] " : "[censored] ") + text);
        }
        String warn = filter.getWarn(speaker);
        if (warn != null && !warn.isEmpty()) {
            FoliaTasks.entity(chatalyst, speaker,
                    current -> current.sendMessage(dev.chatalyst.util.MM.full(warn)));
        }
        return filter.getAction() == dev.chatalyst.swear.SwearFilter.Action.DENY
                ? null : result.text();
    }

    /** Deja el mensaje del canal privado de revision en el log auditable (tipo FREEZE). */
    private static void logReviewChat(String who, String message) {
        var chatalyst = dev.chatalyst.ChatalystPlugin.get();
        dev.chatalyst.log.LogManager log =
                chatalyst == null ? null : chatalyst.getLogManager();
        if (log != null && log.logsChat()) {
            log.log("FREEZE", who, message);
        }
    }

    /** Congelados online asignados al UUID del revisor (nombre solo para datos legacy). */
    private List<Player> onlineFrozenBy(Player staff) {
        List<Player> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, FreezeRecord> entry : freezes.active().entrySet()) {
            if (!entry.getValue().assignedTo(staff.getUniqueId(), staff.getName())) continue;
            Player online = plugin.getServer().getPlayer(entry.getKey());
            if (online == null || opExempt(online)) continue;
            // Confirmación contra storage: active() es solo la vista en memoria.
            if (freezes.get(entry.getKey()) != null) out.add(online);
        }
        return out;
    }

    private Player reviewer(FreezeRecord record) {
        Player candidate = null;
        if (record.staffUuid() != null && !record.staffUuid().isBlank()) {
            if ("console".equalsIgnoreCase(record.staffUuid())) return null;
            try {
                candidate = plugin.getServer().getPlayer(UUID.fromString(record.staffUuid()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        } else {
            candidate = plugin.getServer().getPlayerExact(record.staff());
        }
        return candidate != null && record.assignedTo(candidate.getUniqueId(), candidate.getName())
                && StaffAccess.has(candidate, "chatalyst.command.freeze") ? candidate : null;
    }

    /** Un congelado tampoco puede subirse a caballos, botes o cualquier montura. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(org.bukkit.event.entity.EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && restricted(player)) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    /**
     * Clic IZQUIERDO de un staff sobre un jugador congelado: muestra quien lo congelo y el
     * motivo en el chat. Se monta sobre el evento de golpe (que {@link #onDamage} ya
     * cancela para congelados, asi que nunca reciben el daño) y deja el clic derecho
     * completamente libre. Complementa la lista de congelados del dashboard.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPeekFrozen(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player clicked)) return;
        Player staff = StaffActor.player(event.getDamager());
        if (staff == null || staff.getUniqueId().equals(clicked.getUniqueId())) return;
        FreezeRecord record = freezes.get(clicked.getUniqueId());
        if (record == null || opExempt(clicked)) return;
        if (!StaffAccess.has(staff, "chatalyst.command.freeze")) return;
        long now = System.currentTimeMillis();
        Long previous = lastPeek.put(staff.getUniqueId(), now);
        if (previous != null && now - previous < 1_500L) return;
        staff.sendMessage(module.formatter().feedback(staff, "staff-moderation.freeze-peek",
                record.reason(), "player", dev.chatalyst.util.MM.escape(clicked.getName()),
                "staff", dev.chatalyst.util.MM.escape(record.staff())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
        lastPeek.remove(event.getPlayer().getUniqueId());
        reviewChatHinted.remove(event.getPlayer().getUniqueId());
        FreezeHold.forget(event.getPlayer().getUniqueId());
        disconnectGuard.schedule(event.getPlayer(), event.getReason());
    }

    private void cancel(Player player, Cancellable event) {
        if (!restricted(player)) return;
        event.setCancelled(true);
        notifyBlocked(player);
    }

    private boolean restricted(Player player) {
        return !opExempt(player) && freezes.isFrozen(player.getUniqueId());
    }

    private static boolean opExempt(Player player) {
        return player.isOp() && StaffAccess.exemptsOps();
    }

    private Set<String> allowedCommands() {
        Set<String> out = new HashSet<>();
        for (String command : plugin.getConfig()
                .getStringList("Staff_Moderation.Freeze.Allowed_Commands")) {
            String clean = command.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            int namespace = clean.indexOf(':');
            out.add(namespace >= 0 ? clean.substring(namespace + 1) : clean);
        }
        return out;
    }

    private void notifyBlocked(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastNotice.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 1_500L) return;
        player.sendActionBar(Lang.get(player, "staff-moderation.freeze-blocked"));
    }

}
