package dev.chatalyst.staff;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Central permission and target-protection rules for every staff action. */
public final class StaffAccess {

    private StaffAccess() {
    }

    /** Console and operators are always superusers, even if a permissions plugin denies a node. */
    public static boolean has(CommandSender sender, String permission) {
        return !(sender instanceof Player player) || player.isOp() || sender.hasPermission(permission);
    }

    /**
     * Whether operators are exempt from moderation: they cannot be punished or frozen and
     * freeze enforcement skips them. Configurable so small servers where every trusted
     * player keeps OP can still moderate each other.
     */
    public static boolean exemptsOps() {
        return config("Staff_Moderation.Exempt_Ops", true);
    }

    /**
     * Operators see vanished users by default. With {@code Vanish.Ops_See_Vanished} disabled,
     * only an explicitly granted {@code chatalyst.seevanished} node reveals them; the console
     * always sees. The plain {@code hasPermission} check is reliable because
     * {@link #applySeeVanishedDefault} flips the node default to {@code false} in that mode
     * (Bukkit's {@code isPermissionSet} would still count a {@code default: op} node for OPs).
     */
    public static boolean canSeeVanished(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (config("Staff_Moderation.Vanish.Ops_See_Vanished", true)) {
            return has(sender, "chatalyst.seevanished");
        }
        return player.hasPermission("chatalyst.seevanished");
    }

    /**
     * Sincroniza el default del nodo {@code chatalyst.seevanished} con la config: con
     * {@code Ops_See_Vanished} apagado, el default {@code op} de plugin.yml dejaria de valer
     * y solo cuentan los grants explicitos. {@code setDefault} ya recalcula los permissibles.
     * Llamar al habilitar el modulo y en cada reload.
     */
    public static void applySeeVanishedDefault(org.bukkit.plugin.Plugin plugin) {
        boolean opsSee = plugin.getConfig()
                .getBoolean("Staff_Moderation.Vanish.Ops_See_Vanished", true);
        org.bukkit.permissions.Permission node =
                plugin.getServer().getPluginManager().getPermission("chatalyst.seevanished");
        if (node == null) return;
        org.bukkit.permissions.PermissionDefault next = opsSee
                ? org.bukkit.permissions.PermissionDefault.OP
                : org.bukkit.permissions.PermissionDefault.FALSE;
        if (node.getDefault() != next) node.setDefault(next);
    }

    private static boolean config(String path, boolean fallback) {
        dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
        return plugin == null ? fallback : plugin.getConfig().getBoolean(path, fallback);
    }

    /**
     * Resuelve la proteccion en la region de la entidad cuando el objetivo esta conectado.
     * Leer {@code isOp}/{@code hasPermission} del objetivo desde la region del moderador no
     * es valido en Folia. Para usuarios offline se consulta LuckPerms cuando esta disponible.
     */
    public static java.util.concurrent.CompletableFuture<Boolean> canAffectAsync(
            CommandSender actor, OfflinePlayer target, String bypassPermission) {
        if (target == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        boolean actorOverrides = !(actor instanceof Player actorPlayer) || actorPlayer.isOp();
        Player online = target.getPlayer();
        if (online != null) {
            java.util.concurrent.CompletableFuture<Boolean> result =
                    new java.util.concurrent.CompletableFuture<>();
            dev.chatalyst.ChatalystPlugin plugin = dev.chatalyst.ChatalystPlugin.get();
            if (plugin == null) {
                result.complete(false);
                return result;
            }
            dev.chatalyst.util.FoliaTasks.entity(plugin, online, current -> {
                try {
                    boolean blank = bypassPermission == null || bypassPermission.isBlank();
                    boolean opProtected = exemptsOps() && current.isOp();
                    result.complete(!opProtected && (blank
                            || !current.hasPermission(bypassPermission) || actorOverrides));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }, () -> result.complete(false));
            return result;
        }
        OfflineVerdict verdict = offlineVerdict(
                exemptsOps() && target.isOp(),
                actorOverrides,
                bypassPermission == null || bypassPermission.isBlank(),
                dev.chatalyst.hook.LuckPermsHook.isAvailable());
        if (verdict != OfflineVerdict.ASK_AUTHORITY) {
            return java.util.concurrent.CompletableFuture
                    .completedFuture(verdict == OfflineVerdict.ALLOW);
        }
        // Ante un error de la fuente autoritativa se falla cerrado: no se puede demostrar
        // que el objetivo offline carezca del bypass.
        return dev.chatalyst.hook.LuckPermsHook
                .hasPermissionAsync(target.getUniqueId(), bypassPermission)
                .thenApply(hasBypass -> !hasBypass)
                .exceptionally(failure -> false);
    }

    enum OfflineVerdict { ALLOW, DENY, ASK_AUTHORITY }

    /**
     * La decision para un objetivo OFFLINE, sin tocar Bukkit para poder fijarla con un test.
     *
     * <p>Sin LuckPerms no hay NADA que consultar: los permisos de Bukkit solo existen
     * mientras el jugador esta conectado. Antes ese caso devolvia "permitido", asi que el
     * bypass de un admin protegia unicamente mientras estaba dentro — bastaba esperar a que
     * saliera para banearlo. Se falla CERRADO, igual que ante un error de LuckPerms: no se
     * puede demostrar que carezca del bypass. Consola y OP siguen pudiendo (actorOverrides),
     * que es la salida para un servidor sin plugin de permisos.
     */
    static OfflineVerdict offlineVerdict(boolean opProtected, boolean actorOverrides,
                                         boolean noBypassNode, boolean authorityAvailable) {
        if (opProtected) return OfflineVerdict.DENY;
        if (actorOverrides || noBypassNode) return OfflineVerdict.ALLOW;
        return authorityAvailable ? OfflineVerdict.ASK_AUTHORITY : OfflineVerdict.DENY;
    }
}
