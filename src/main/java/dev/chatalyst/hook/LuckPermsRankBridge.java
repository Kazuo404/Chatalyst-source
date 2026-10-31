package dev.chatalyst.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LuckPerms mutation/event code isolated from Chatalyst's read-only formatting bridge.
 *
 * <p>This class is loaded only after {@link LuckPermsHook#isAvailable()} succeeds. Keeping
 * node/event bytecode here preserves Chatalyst's optional-dependency guarantee on servers
 * without LuckPerms.
 */
final class LuckPermsRankBridge {

    private LuckPermsRankBridge() {
    }

    static CompletableFuture<String> primaryGroup(UUID uuid) {
        LuckPerms lp = LuckPermsProvider.get();
        User loaded = lp.getUserManager().getUser(uuid);
        if (loaded != null) {
            return CompletableFuture.completedFuture(normalizeGroup(loaded.getPrimaryGroup()));
        }
        return lp.getUserManager().loadUser(uuid)
                .thenApply(user -> normalizeGroup(user.getPrimaryGroup()));
    }

    /**
     * Grupos que el jugador tiene DIRECTAMENTE (nodos de herencia propios, no los que heredan
     * sus grupos) y que no han caducado. Es lo que la sincronia con Discord refleja: mirar solo
     * el grupo principal escondia los demas (un founder con Nova comprado no tenia rol de Nova)
     * y quitar un rango que no era el principal no cambiaba nada en Discord.
     */
    static CompletableFuture<Set<String>> heldGroups(UUID uuid) {
        LuckPerms lp = LuckPermsProvider.get();
        User loaded = lp.getUserManager().getUser(uuid);
        CompletableFuture<User> user = loaded != null
                ? CompletableFuture.completedFuture(loaded)
                : lp.getUserManager().loadUser(uuid);
        return user.thenApply(LuckPermsRankBridge::directGroups);
    }

    private static Set<String> directGroups(User user) {
        Set<String> groups = new java.util.LinkedHashSet<>();
        for (InheritanceNode node : user.getNodes(net.luckperms.api.node.NodeType.INHERITANCE)) {
            if (node.getValue() && !node.hasExpired()) groups.add(normalizeGroup(node.getGroupName()));
        }
        return java.util.Collections.unmodifiableSet(groups);
    }

    /**
     * Da el grupo para siempre SOLO si el jugador no lo tiene ya de ninguna forma. Un rol puesto
     * a mano en Discord no debe convertir un Nova de 30 dias en uno permanente.
     *
     * @return true si se anadio, false si ya lo tenia o el grupo no existe
     */
    static CompletableFuture<Boolean> addGroupIfAbsent(UUID uuid, String group) {
        LuckPerms lp = LuckPermsProvider.get();
        if (lp.getGroupManager().getGroup(group) == null) {
            return CompletableFuture.completedFuture(false);
        }
        java.util.concurrent.atomic.AtomicBoolean added =
                new java.util.concurrent.atomic.AtomicBoolean();
        return lp.getUserManager().modifyUser(uuid, user -> {
            if (directGroups(user).contains(group)) return;
            added.set(user.data().add(InheritanceNode.builder(group).build()).wasSuccessful());
        }).thenApply(ignored -> added.get());
    }

    static CompletableFuture<Boolean> grantGroup(UUID uuid, String group, long expiresAtMillis) {
        LuckPerms lp = LuckPermsProvider.get();
        if (lp.getGroupManager().getGroup(group) == null) {
            return CompletableFuture.completedFuture(false);
        }
        java.util.concurrent.atomic.AtomicBoolean applied =
                new java.util.concurrent.atomic.AtomicBoolean();
        return lp.getUserManager().modifyUser(uuid, user -> {
            InheritanceNode.Builder builder = InheritanceNode.builder(group);
            if (expiresAtMillis > 0L) {
                builder.expiry(java.time.Instant.ofEpochMilli(expiresAtMillis));
            }
            applied.set(add(user, builder.build(), expiresAtMillis > 0L));
        }).thenApply(ignored -> applied.get());
    }

    /**
     * Añade el nodo y dice si de verdad quedo puesto.
     *
     * <p>{@code user.data().add(node)} a secas NO renueva: si el jugador ya tiene ese mismo
     * nodo temporal, LuckPerms responde FAIL_ALREADY_HAS y deja la caducidad vieja. Ignorar ese
     * resultado hacia que alquilar otra vez algo aun vigente (o un doble clic en la carta)
     * cobrara el precio entero sin alargar ni un segundo. Un nodo temporal ahora SUMA su
     * duracion a lo que quedaba, y cualquier otro rechazo (ya lo tiene permanente) se devuelve
     * como fallo para que quien cobro pueda reembolsar.
     */
    private static boolean add(User user, net.luckperms.api.node.Node node, boolean temporary) {
        if (!temporary) return user.data().add(node).wasSuccessful();
        return user.data().add(node, net.luckperms.api.model.data
                .TemporaryNodeMergeStrategy.ADD_NEW_DURATION_TO_EXISTING)
                .getResult().wasSuccessful();
    }

    /**
     * Un permiso suelto, con caducidad opcional. Hermano de {@link #grantGroup}: mismo camino,
     * mismo modifyUser, misma forma de expresar "para siempre" (expiresAtMillis <= 0).
     */
    static CompletableFuture<Boolean> grantPermission(UUID uuid, String permission,
                                                      long expiresAtMillis) {
        LuckPerms lp = LuckPermsProvider.get();
        java.util.concurrent.atomic.AtomicBoolean applied =
                new java.util.concurrent.atomic.AtomicBoolean();
        return lp.getUserManager().modifyUser(uuid, user -> {
            PermissionNode.Builder builder = PermissionNode.builder(permission).value(true);
            if (expiresAtMillis > 0L) {
                builder.expiry(java.time.Instant.ofEpochMilli(expiresAtMillis));
            }
            applied.set(add(user, builder.build(), expiresAtMillis > 0L));
        }).thenApply(ignored -> applied.get());
    }

    static CompletableFuture<Boolean> removeGroup(UUID uuid, String group) {
        LuckPerms lp = LuckPermsProvider.get();
        return lp.getUserManager().modifyUser(uuid, user ->
                user.data().clear(node -> node instanceof InheritanceNode inheritance
                        && inheritance.getGroupName().equalsIgnoreCase(group)))
                .thenApply(ignored -> true);
    }

    /**
     * Replaces only groups explicitly managed by the Discord mapping. Permissions, contexts
     * and every unrelated LuckPerms group remain untouched.
     */
    static CompletableFuture<Boolean> replaceMappedGroup(UUID uuid, Set<String> mappedGroups,
                                                          String desiredGroup) {
        LuckPerms lp = LuckPermsProvider.get();
        if (desiredGroup != null && !desiredGroup.isBlank()
                && lp.getGroupManager().getGroup(desiredGroup) == null) {
            return CompletableFuture.completedFuture(false);
        }
        return lp.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(node -> node instanceof InheritanceNode inheritance
                    && mappedGroups.contains(normalizeGroup(inheritance.getGroupName())));
            if (desiredGroup != null && !desiredGroup.isBlank()) {
                user.data().add(InheritanceNode.builder(desiredGroup).build());
            }
        }).thenApply(ignored -> true);
    }

    /**
     * Declarative repair: makes {@code managed} groups match {@code desired} exactly, leaving
     * every other group, permission and context untouched.
     *
     * <p>{@code managed} is intentionally per-player — the groups Chatalyst itself delivered
     * to them — so a rank an administrator granted by hand is never treated as drift and
     * removed.
     *
     * <p>Un nodo PERMANENTE sin contexto solo se toca si Chatalyst llego a entregar ese grupo
     * para siempre ({@code permanentlyManaged}). Sin esa distincion, un {@code vip} que un
     * admin puso a mano con {@code /lp user X parent add vip} era indistinguible del nuestro:
     * comprar "VIP 30d" encima lo sustituia por uno de 30 dias, y la autoprueba de Tebex lo
     * borraba al deshacerse. Un permanente ya cubre cualquier caducidad, asi que dejarlo
     * quieto nunca deja al jugador sin lo que pago.
     *
     * @param desired group -&gt; absolute expiry in millis, 0 meaning permanent
     * @param permanentlyManaged groups Chatalyst ever delivered with no expiry
     * @return false when a desired group does not exist, so the caller keeps retrying
     */
    static CompletableFuture<Boolean> reconcileGroups(UUID uuid, Map<String, Long> desired,
                                                      Set<String> managed,
                                                      Set<String> permanentlyManaged) {
        LuckPerms lp = LuckPermsProvider.get();
        Map<String, Long> applicable = new java.util.LinkedHashMap<>();
        boolean complete = true;
        for (var wanted : desired.entrySet()) {
            if (lp.getGroupManager().getGroup(wanted.getKey()) == null) {
                complete = false; // misconfigured or not loaded yet; retry later
                continue;
            }
            applicable.put(wanted.getKey(), wanted.getValue());
        }
        boolean fullyApplied = complete;
        return lp.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(node -> node instanceof InheritanceNode inheritance
                    && removable(inheritance, permanentlyManaged)
                    && managed.contains(normalizeGroup(inheritance.getGroupName()))
                    && !applicable.containsKey(normalizeGroup(inheritance.getGroupName())));
            for (var wanted : applicable.entrySet()) {
                if (hasExactly(user, wanted.getKey(), wanted.getValue())) continue;
                // Un permanente puesto a mano ya da el grupo para siempre: no hay nada que
                // entregar y, sobre todo, nada que sustituir por un nodo con caducidad.
                if (wanted.getValue() > 0L && hasForeignPermanent(user, wanted.getKey(),
                        permanentlyManaged)) {
                    continue;
                }
                user.data().clear(node -> node instanceof InheritanceNode inheritance
                        && removable(inheritance, permanentlyManaged)
                        && normalizeGroup(inheritance.getGroupName()).equals(wanted.getKey()));
                InheritanceNode.Builder builder = InheritanceNode.builder(wanted.getKey());
                if (wanted.getValue() > 0L) {
                    builder.expiry(java.time.Instant.ofEpochMilli(wanted.getValue()));
                }
                user.data().add(builder.build());
            }
        }).thenApply(ignored -> fullyApplied);
    }

    /**
     * Grupos que Chatalyst le tiene puestos a este jugador, con su caducidad absoluta
     * (0 = permanente). Para comprobar que una entrega llego de verdad a LuckPerms en vez
     * de fiarse de que la llamada no lanzo.
     */
    static CompletableFuture<java.util.Map<String, Long>> ourGroups(UUID uuid) {
        LuckPerms lp = LuckPermsProvider.get();
        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            java.util.Map<String, Long> held = new java.util.LinkedHashMap<>();
            for (var node : user.data().toCollection()) {
                if (!(node instanceof InheritanceNode inheritance) || !ours(inheritance)) continue;
                long expiry = inheritance.getExpiry() == null
                        ? 0L : inheritance.getExpiry().toEpochMilli();
                held.put(normalizeGroup(inheritance.getGroupName()), expiry);
            }
            return held;
        });
    }

    /**
     * True only for nodes shaped like the ones Chatalyst grants: no context.
     *
     * <p>{@code NodeMap.clear(predicate)} removes EVERY node matching it, and LuckPerms keeps
     * a separate node per context set. Matching on the group name alone therefore deleted an
     * administrator's context-scoped grant — a {@code vip} limited to {@code server=survival}
     * — as soon as an unrelated purchase of the same rank expired.
     */
    private static boolean ours(InheritanceNode node) {
        return node.getContexts().isEmpty();
    }

    /**
     * Si este nodo lo puede quitar o sustituir la reconciliacion: sin contexto y, si es
     * permanente, solo cuando Chatalyst entrego ese grupo para siempre alguna vez.
     */
    private static boolean removable(InheritanceNode node, Set<String> permanentlyManaged) {
        if (!ours(node)) return false;
        return node.getExpiry() != null
                || permanentlyManaged.contains(normalizeGroup(node.getGroupName()));
    }

    /** El jugador tiene este grupo para siempre por una via que no es una compra nuestra. */
    private static boolean hasForeignPermanent(User user, String group,
                                               Set<String> permanentlyManaged) {
        if (permanentlyManaged.contains(group)) return false;
        for (InheritanceNode node : user.getNodes(net.luckperms.api.node.NodeType.INHERITANCE)) {
            if (ours(node) && node.getExpiry() == null
                    && normalizeGroup(node.getGroupName()).equals(group)) {
                return true;
            }
        }
        return false;
    }

    /** True when the node already carries this group with exactly this expiry. */
    private static boolean hasExactly(User user, String group, long expiresAtMillis) {
        for (InheritanceNode node : user.getNodes(net.luckperms.api.node.NodeType.INHERITANCE)) {
            if (!ours(node) || !normalizeGroup(node.getGroupName()).equals(group)) continue;
            java.time.Instant expiry = node.getExpiry();
            long current = expiry == null ? 0L : expiry.toEpochMilli();
            // Second precision: LuckPerms stores expiries in whole seconds, so comparing
            // millis exactly would rewrite the node on every single reconciliation pass.
            if (current / 1_000L == expiresAtMillis / 1_000L) return true;
        }
        return false;
    }

    static boolean groupExists(String group) {
        return group != null && LuckPermsProvider.get()
                .getGroupManager().getGroup(group) != null;
    }

    static AutoCloseable subscribeUserChanges(Object owner, Consumer<UUID> listener) {
        return LuckPermsProvider.get().getEventBus().subscribe(owner,
                net.luckperms.api.event.user.UserDataRecalculateEvent.class,
                event -> listener.accept(event.getUser().getUniqueId()));
    }

    private static String normalizeGroup(String group) {
        return group == null ? "" : group.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
