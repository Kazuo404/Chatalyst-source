package dev.chatalyst.tebex;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.discord.DiscordRankSync;
import dev.chatalyst.hook.LuckPermsHook;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Safe Tebex-to-LuckPerms rank delivery endpoint.
 *
 * <p>Tebex's official plugin executes this command with {@code {id}} and
 * {@code {transaction}}. The transaction is persisted before touching LuckPerms. Temporary
 * grants also persist their absolute expiry, so a command retry cannot extend the purchase.
 */
public final class TebexRankService {

    private static final char US = '\u001F';
    private static final long STALE_PENDING_MILLIS = 120_000L;
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_\\-]{1,64}");
    private static final Pattern TRANSACTION =
            Pattern.compile("[A-Za-z0-9._:@\\-]{3,128}");

    private final ChatalystPlugin plugin;
    private final Store receipts;
    private final DiscordRankSync rankSync;
    private final TebexPresentationService presentations;
    private final TebexEntitlements entitlements;
    private final TebexReconciler reconciler;

    public TebexRankService(ChatalystPlugin plugin, StorageManager storage,
                            DiscordRankSync rankSync,
                            TebexPresentationService presentations,
                            TebexEntitlements entitlements,
                            TebexReconciler reconciler) {
        this.plugin = plugin;
        this.receipts = storage.store("tebex_rank_receipts");
        this.rankSync = rankSync;
        this.presentations = presentations;
        this.entitlements = entitlements;
        this.reconciler = reconciler;
    }

    /**
     * La consola del servidor o RCON. RCON cuenta como consola porque exige la contraseña del
     * servidor: quien la tiene ya puede hacer cualquier cosa. Es por donde entrega la tienda
     * propia (Tools/tienda-web), que no pasa por Tebex. Un jugador, aunque sea OP, no entra.
     */
    public static boolean esConsola(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    /** Handles arguments after {@code /chatalyst tebex}. */
    public void handle(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        // Quién puede hablar se decide ANTES que nada, incluido antes de mirar si la
        // integración está encendida: a partir de aquí un rechazo grita en el log y avisa al
        // staff, y eso no puede provocarlo cualquiera escribiendo el comando a mano.
        if (plugin.getConfig().getBoolean("Tebex.Console_Only", true) && !esConsola(sender)) {
            send(sender, "<#FF4D6D>Por seguridad, este comando solamente puede ejecutarlo "
                    + "la consola/Tebex.");
            return;
        }
        if (!esConsola(sender) && !sender.hasPermission("chatalyst.admin.tebex")) {
            send(sender, "<#FF4D6D>No tienes permiso.");
            return;
        }
        if (!plugin.getConfig().getBoolean("Tebex.Enabled", false)) {
            String disabled = "<#FF9F1C>La entrega de rangos Tebex está desactivada "
                    + "(Tebex.Enabled: false).";
            // Una entrega que llega con la integración apagada no es un aviso de configuración:
            // es una compra cobrada que nadie va a recibir. Tebex da el comando por ejecutado.
            if (isDelivery(action)) {
                reject(sender, disabled, describe(args) + ": Tebex.Enabled es false");
            } else {
                send(sender, disabled);
            }
            return;
        }
        // LuckPerms missing is NOT a rejection any more. Tebex treats a dispatched command as
        // executed and never resends it, so refusing here silently destroyed a paid delivery
        // whenever it arrived during a restart. The delivery is queued and retried instead.
        if (action.equals("test") || action.equals("probar") || action.equals("selftest")) {
            org.bukkit.entity.Player target = args.length > 2
                    ? plugin.getServer().getPlayerExact(args[2])
                    : (sender instanceof org.bukkit.entity.Player self ? self : null);
            if (target == null) {
                send(sender, "<#FF4D6D>Indica un jugador conectado: "
                        + "<#FBF2FF>/cht tebex test <jugador> [rango]");
                return;
            }
            send(sender, "<#C0B3D9>Comprando y devolviendo un rango de prueba sobre "
                    + "<#FBF2FF>" + target.getName() + "<#C0B3D9>...");
            new TebexSelfTest(plugin, entitlements, reconciler, plugin.getTebexShop())
                    .run(sender, target.getUniqueId(), target.getName(),
                            args.length > 3 ? args[3] : null);
            return;
        }
        if (action.equals("status")) {
            List<String> rankIds = configuredRankIds();
            send(sender, "<#3DFFA8>Tebex rank delivery activa. <#C0B3D9>LuckPerms: "
                    + (LuckPermsHook.isAvailable() ? "<#3DFFA8>OK" : "<#FF4D6D>no disponible")
                    + "<#C0B3D9>, IDs de rango: <#FBF2FF>"
                    + (rankIds.isEmpty()
                    ? (hasCentralCatalog() ? "ninguno habilitado" : "ninguno (modo legacy)")
                    : String.join(", ", rankIds))
                    + "<#C0B3D9>, grupos permitidos: <#FBF2FF>"
                    + allowedGroupsDescription());
            send(sender, "<#C0B3D9>Compras registradas: <#FBF2FF>"
                    + reconciler.trackedEntitlements()
                    + "<#C0B3D9> (los rangos y roles se derivan de ellas y se reparan solos)");
            return;
        }
        deliver(sender, args, action);
    }

    private static final CompletableFuture<Boolean> FAILED =
            CompletableFuture.completedFuture(false);

    /**
     * Concede un rango del catalogo por la misma puerta que una compra de Tebex (recibo,
     * renovacion, reconciliacion, presentacion) y dice si quedo REGISTRADO.
     *
     * <p>Para los sorteos: antes despachaban {@code chatalyst tebex grant} como comando y daban
     * el premio por entregado sin saber si se habia rechazado. El futuro se completa con true
     * solo cuando la compra esta escrita (o ya lo estaba: un replay es una entrega hecha).
     */
    public CompletableFuture<Boolean> grantFor(UUID target, String rankId, String transaction) {
        if (target == null || rankId == null || transaction == null) return FAILED;
        CommandSender console = plugin.getServer().getConsoleSender();
        if (!plugin.getConfig().getBoolean("Tebex.Enabled", false)) {
            reject(console, "<#FF9F1C>La entrega de rangos Tebex está desactivada.",
                    "grant " + target + " " + rankId + ": Tebex.Enabled es false");
            return FAILED;
        }
        return deliver(console, new String[]{"grant", target.toString(),
                rankId.toLowerCase(Locale.ROOT), transaction}, "grant");
    }

    /** grant/revoke ya autorizados: valida, reserva el recibo y registra. */
    private CompletableFuture<Boolean> deliver(CommandSender sender, String[] args,
                                               String action) {
        boolean grant = isGrant(action);
        boolean revoke = isRevoke(action);
        if ((!grant && !revoke) || args.length < 4) {
            usage(sender);
            return FAILED;
        }

        UUID target = dev.chatalyst.util.BuyerId.resolve(plugin.getServer(), args[1]);
        if (target == null) {
            reject(sender, "<#FF4D6D>No se pudo identificar al jugador <#FBF2FF>"
                            + MM.escape(args[1]) + "<#FF4D6D>. En Tebex usa la variable "
                            + "<#FBF2FF>{id}<#FF4D6D>.",
                    describe(args) + ": \"" + args[1] + "\" no es un UUID ni una cuenta "
                            + "que este servidor conozca");
            return FAILED;
        }

        String rankOrGroup = args[2].toLowerCase(Locale.ROOT);
        RankDefinition configured = configuredRank(rankOrGroup);
        boolean catalogMode = hasRankCatalog();
        String group;
        String durationText;
        String transaction;
        if (grant && args.length == 4) {
            if (configured == null) {
                reject(sender, "<#FF4D6D>ID de rango Tebex desconocido: <#FBF2FF>"
                                + MM.escape(rankOrGroup)
                                + "<#FF4D6D>. Configúralo en Rank_Catalog.",
                        describe(args) + ": el ID de rango \"" + rankOrGroup
                                + "\" no existe en Rank_Catalog (o tiene Tebex_Enabled: false)");
                return FAILED;
            }
            group = configured.group();
            durationText = configured.duration();
            transaction = args[3];
        } else if (grant) {
            // Compatibilidad con v32:
            // /chatalyst tebex grant {id} <group> <duration> {transaction}
            // It is accepted only while no rank catalog exists. Once the owner opts into
            // stable IDs, the old raw-group form must not bypass Tebex_Enabled.
            if (catalogMode) {
                reject(sender, "<#FF4D6D>Usa el ID seguro de Rank_Catalog y elimina la "
                                + "duración del comando de Tebex.",
                        describe(args) + ": la forma antigua con duración ya no se acepta"
                                + " porque hay Rank_Catalog; la duración sale de Tebex_Duration");
                usage(sender);
                return FAILED;
            }
            group = rankOrGroup;
            durationText = args[3];
            transaction = args[4];
        } else {
            if (configured != null) {
                group = configured.group();
            } else if (catalogMode) {
                reject(sender, "<#FF4D6D>ID de rango Tebex desconocido: <#FBF2FF>"
                                + MM.escape(rankOrGroup) + "<#FF4D6D>.",
                        describe(args) + ": el ID de rango \"" + rankOrGroup
                                + "\" no existe en Rank_Catalog");
                return FAILED;
            } else {
                group = rankOrGroup; // v32 legacy config without a rank catalog
            }
            durationText = "permanent";
            transaction = args[3];
        }
        // Existence can only be checked while LuckPerms is loaded; the allow-list and the
        // catalog are config-driven and validate the same intent without it.
        if (!isAllowedGroup(group)
                || (LuckPermsHook.isAvailable() && !LuckPermsHook.groupExists(group))) {
            reject(sender, "<#FF4D6D>Grupo LuckPerms no permitido o inexistente: <#FBF2FF>"
                            + MM.escape(group),
                    describe(args) + ": el grupo \"" + group + "\" no existe en LuckPerms"
                            + " o no está en Tebex.Allowed_Groups");
            return FAILED;
        }
        if (transaction.toLowerCase(Locale.ROOT)
                .startsWith(TebexSelfTest.TRANSACTION_PREFIX)) {
            // El prefijo lo reserva la autoprueba. Aceptarlo por esta puerta permitiria
            // colar una entrega que luego se limpia sola, o confundir el registro.
            reject(sender, "<#FF4D6D>Ese identificador esta reservado para "
                            + "<#FBF2FF>/cht tebex test<#FF4D6D>.",
                    describe(args) + ": el identificador de pago usa el prefijo reservado"
                            + " de la autoprueba");
            return FAILED;
        }
        if (!TRANSACTION.matcher(transaction).matches()) {
            reject(sender, "<#FF4D6D>Transacción inválida. En Tebex usa "
                            + "<#FBF2FF>{transaction}<#FF4D6D>.",
                    describe(args) + ": el identificador de pago no tiene una forma válida");
            return FAILED;
        }

        long expiry = 0L;
        if (grant) {
            long duration = TimeUtil.parseDuration(durationText);
            if (duration == 0L) {
                reject(sender, "<#FF4D6D>Duración inválida. Usa permanent, 30d, 12h, etc.",
                        describe(args) + ": la duración \"" + durationText + "\" no se entiende");
                return FAILED;
            }
            if (duration != TimeUtil.PERMANENT) {
                // Una renovacion SUMA. Contar desde ahora tira el tiempo que le quedaba: quien
                // renovaba 30 dias en el dia 10 de otros 30 pagaba 60 y recibia 40. Se extiende
                // desde lo que ya tiene, y si ya es permanente no hay nada que extender.
                long from = renewalStart(target, group);
                if (from == PERMANENT_ALREADY) {
                    expiry = 0L;
                } else {
                    long deadline = TimeUtil.deadline(from, duration);
                    expiry = deadline == TimeUtil.PERMANENT ? 0L : deadline;
                }
            }
        }
        Operation operation = new Operation(grant ? "grant" : "revoke", target, group,
                expiry, normalizeTransaction(transaction));
        return submit(sender, operation, rankOrGroup, durationText);
    }

    /** Marca de "ya es permanente": no hay caducidad que extender. */
    private static final long PERMANENT_ALREADY = -1L;

    /**
     * Desde cuando cuenta una compra nueva de este grupo: desde su caducidad vigente si
     * todavia le queda tiempo, o desde ahora si no tiene nada.
     *
     * <p>Se calcula UNA vez, aqui, y viaja dentro de la operacion persistida — asi un reintento
     * o un replay reutilizan la misma fecha absoluta y no regalan tiempo extra.
     */
    private long renewalStart(java.util.UUID owner, String group) {
        long now = System.currentTimeMillis();
        if (owner == null || group == null) return now;
        Long current = TebexEntitlements
                .desiredGroups(entitlements.of(owner), now)
                .get(group.toLowerCase(Locale.ROOT));
        if (current == null) return now;
        if (current == 0L) return PERMANENT_ALREADY;
        return Math.max(now, current);
    }

    /**
     * Records the purchase; reconciliation delivers it.
     *
     * <p>Writing the entitlement is the only step that must succeed here. The LuckPerms group
     * and the Discord role are <em>derived</em> from that record, so if either cannot be
     * applied right now — LuckPerms still loading during a restart, the bot offline, the buyer
     * not linked yet — {@link TebexReconciler} converges later instead of the purchase being
     * lost. That matters because Tebex considers the command executed the moment it dispatches
     * it and never resends it.
     */
    private CompletableFuture<Boolean> submit(CommandSender sender, Operation operation,
                                              String rankId, String duration) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin,
                    task -> outcome.complete(process(sender, operation, rankId, duration)));
        } catch (RuntimeException rejected) {
            outcome.complete(false); // apagandose: nada quedo registrado
        }
        return outcome;
    }

    private boolean process(CommandSender sender, Operation operation,
                            String rankId, String duration) {
        try {
            Claim claim = claim(operation);
            switch (claim.status()) {
                case DUPLICATE -> {
                    send(sender, "<#3DFFA8>Entrega ya procesada; no se duplicó "
                            + "el rango " + operation.group() + ".");
                    return true;
                }
                case CONFLICT -> reject(sender, "<#FF4D6D>La misma transacción ya fue usada "
                                + "con otros datos. No se realizó ningún cambio.",
                        describe(operation) + ": ese identificador de pago ya se usó para"
                                + " otro jugador o para otro grupo");
                case PROCESSING -> send(sender,
                        "<#FF9F1C>Esta transacción ya se está procesando.");
                case STORAGE_FAILED -> reject(sender, "<#FF4D6D>No se pudo asegurar la "
                                + "transacción en el almacenamiento; el rango no fue modificado.",
                        describe(operation) + ": el almacenamiento no aceptó el recibo");
                case CLAIMED -> {
                    return record(sender, claim, rankId, duration);
                }
                default -> {
                    // Unreachable; every claim status is handled explicitly above.
                }
            }
            return false;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Tebex delivery failed: " + failure);
            return false;
        }
    }

    private boolean record(CommandSender sender, Claim claim, String rankId, String duration) {
        Operation operation = claim.operation();
        boolean grant = operation.action().equals("grant");
        // A revoke ends the purchase made by THIS transaction. Tebex sends refunds and
        // chargebacks with the original payment's id, so a late refund of last month cannot
        // take away the renewal the player has already paid for.
        boolean recorded = grant
                ? entitlements.grant(operation.minecraftId(), rankId, operation.group(),
                        operation.expiresAt(), operation.transaction(), duration)
                : entitlements.revoke(operation.minecraftId(), rankId,
                        operation.transaction());
        if (!recorded) {
            // Nothing was written down, so release the claim: a resend must be able to retry.
            if (claim.receiptValue() != null) {
                receipts.compareAndRemoveDurably(receiptKey(operation), claim.receiptValue());
            }
            send(sender, "<#FF4D6D>No se pudo registrar la compra; no se modificó ningún "
                    + "rango. Revisa el almacenamiento y reintenta.");
            return false;
        }

        String key = receiptKey(operation);
        String done = encode("DONE", System.currentTimeMillis(), operation);
        boolean persisted = claim.receiptValue() != null
                && receipts.compareAndPutDurably(key, claim.receiptValue(), done);
        String observed = receipts.get(key);
        if (!persisted && observed != null && observed.startsWith("DONE" + US)) {
            persisted = true; // another safe replay already committed the same delivery
        }
        if (!persisted) {
            plugin.getLogger().warning("The Tebex purchase " + operation.transaction()
                    + " was recorded, but its DONE receipt could not be persisted."
                    + " A replay stays idempotent through the stored absolute expiry.");
        }

        // Apply it immediately; anything unavailable now is repaired by the sweep.
        reconciler.reconcileSoon(operation.minecraftId());
        rankSync.onRankGranted(operation.minecraftId());
        if (persisted && grant && presentations != null) {
            presentations.deliver(operation.minecraftId(), rankId,
                    operation.group(), duration, operation.transaction());
        }
        String verb = grant ? "entregado" : "retirado";
        send(sender, "<#3DFFA8>Rango <#FBF2FF>" + operation.group()
                + " <#3DFFA8>" + verb + " a " + operation.minecraftId()
                + " (transacción " + MM.escape(operation.transaction()) + ").");
        if (plugin.getConfig().getBoolean("Tebex.Notify_Discord", true)
                && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Tebex: rank " + operation.group() + " "
                    + verb + " for " + operation.minecraftId() + " ("
                    + operation.transaction() + ")");
        }
        return true;
    }

    private Claim claim(Operation requested) {
        String key = receiptKey(requested);
        long now = System.currentTimeMillis();
        for (int attempt = 0; attempt < 5; attempt++) {
            String current = receipts.get(key);
            if (current == null) {
                String pending = encode("PENDING", now, requested);
                if (receipts.compareAndPutDurably(key, null, pending)) {
                    return new Claim(ClaimStatus.CLAIMED, requested, pending);
                }
                continue;
            }
            Decoded decoded = decode(current, requested);
            if (decoded == null || !decoded.operation().sameIdentity(requested)) {
                return new Claim(ClaimStatus.CONFLICT, requested, null);
            }
            if (decoded.state().equals("DONE")) {
                return new Claim(ClaimStatus.DUPLICATE, decoded.operation(), null);
            }
            if (now - decoded.updatedAt() < STALE_PENDING_MILLIS) {
                return new Claim(ClaimStatus.PROCESSING, decoded.operation(), null);
            }
            // Recover a command interrupted after receipt persistence. The encoded absolute
            // expiry is reused, so recovery never grants extra paid time.
            String refreshed = encode("PENDING", now, decoded.operation());
            if (receipts.compareAndPutDurably(key, current, refreshed)) {
                return new Claim(ClaimStatus.CLAIMED, decoded.operation(), refreshed);
            }
        }
        return new Claim(ClaimStatus.STORAGE_FAILED, requested, null);
    }

    private String encode(String state, long updatedAt, Operation operation) {
        return state + US + updatedAt + US + operation.minecraftId() + US
                + operation.group() + US + operation.expiresAt();
    }

    private Decoded decode(String encoded, Operation requested) {
        String[] fields = encoded.split(String.valueOf(US), -1);
        if (fields.length != 5) return null;
        try {
            String state = fields[0];
            if (!state.equals("PENDING") && !state.equals("DONE")) return null;
            long updated = Long.parseLong(fields[1]);
            UUID uuid = UUID.fromString(fields[2]);
            long expiry = Long.parseLong(fields[4]);
            return new Decoded(state, updated,
                    new Operation(requested.action(), uuid, fields[3], expiry,
                            requested.transaction()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * One receipt per PACKAGE. Tebex's {@code {transaction}} identifies the payment, not the
     * package, so a cart holding two ranks runs two commands with the same id; keyed on the
     * transaction alone the second was dismissed as an already-processed duplicate and the
     * rank was never delivered.
     */
    private String receiptKey(Operation operation) {
        return dev.chatalyst.db.StoreKeys.bounded(operation.action() + ":"
                + operation.group() + ":"
                + safeReceiptTransaction(operation.transaction()));
    }

    private boolean isAllowedGroup(String group) {
        if (!GROUP.matcher(group).matches()) return false;
        List<String> configured = plugin.getConfig().getStringList("Tebex.Allowed_Groups");
        if (configured.isEmpty()) return true;
        return configured.stream().map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(group::equals);
    }

    private String allowedGroupsDescription() {
        List<String> configured = plugin.getConfig().getStringList("Tebex.Allowed_Groups");
        return configured.isEmpty() ? "todos los grupos existentes" : String.join(", ", configured);
    }

    /** Stable IDs exposed to command completion and documentation tooling. */
    public List<String> configuredRankIds() {
        ConfigurationSection ranks = configuredCatalog();
        if (ranks == null) return List.of();
        TreeSet<String> result = new TreeSet<>();
        for (String rawId : ranks.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (GROUP.matcher(id).matches() && configuredRank(id) != null) result.add(id);
        }
        return List.copyOf(result);
    }

    private boolean hasRankCatalog() {
        ConfigurationSection ranks = configuredCatalog();
        return ranks != null && !ranks.getKeys(false).isEmpty();
    }

    private RankDefinition configuredRank(String requestedId) {
        if (requestedId == null || !GROUP.matcher(requestedId).matches()) return null;
        boolean central = hasCentralCatalog();
        ConfigurationSection ranks = configuredCatalog();
        if (ranks == null) return null;
        String actualKey = ranks.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase(requestedId))
                .findFirst().orElse(null);
        if (actualKey == null) return null;
        ConfigurationSection rank = ranks.getConfigurationSection(actualKey);
        if (rank == null) return null;
        if (central && !rank.getBoolean("Tebex_Enabled", false)) return null;
        String group = rank.getString("LuckPerms_Group", "").trim()
                .toLowerCase(Locale.ROOT);
        String duration = rank.getString(
                        central ? "Tebex_Duration" : "Duration", "permanent").trim()
                .toLowerCase(Locale.ROOT);
        if (!GROUP.matcher(group).matches() || TimeUtil.parseDuration(duration) == 0L) {
            return null;
        }
        return new RankDefinition(requestedId, group, duration);
    }

    private ConfigurationSection configuredCatalog() {
        ConfigurationSection central =
                plugin.getConfig().getConfigurationSection("Rank_Catalog");
        if (central != null && !central.getKeys(false).isEmpty()) return central;
        return plugin.getConfig().getConfigurationSection("Tebex.Ranks");
    }

    private boolean hasCentralCatalog() {
        ConfigurationSection central =
                plugin.getConfig().getConfigurationSection("Rank_Catalog");
        return central != null && !central.getKeys(false).isEmpty();
    }

    private void usage(CommandSender sender) {
        send(sender, "<#C0B3D9>Entrega: <#FBF2FF>/chatalyst tebex grant "
                + "{id} <rank_id> {transaction}");
        send(sender, "<#C0B3D9>Expiración/reembolso: <#FBF2FF>/chatalyst tebex revoke "
                + "{id} <rank_id> {transaction}");
        send(sender, "<#7B68A0>A mano (pago fuera de la tienda) puedes escribir el nombre "
                + "del jugador en lugar del UUID; la duración sale de Rank_Catalog.");
    }

    private static boolean isGrant(String action) {
        return action.equals("grant") || action.equals("add") || action.equals("dar");
    }

    private static boolean isRevoke(String action) {
        return action.equals("revoke") || action.equals("remove") || action.equals("quitar");
    }

    private static boolean isDelivery(String action) {
        return isGrant(action) || isRevoke(action);
    }

    /**
     * Rechaza una entrega dejando rastro donde alguien lo vea.
     *
     * <p>Contestarle a la consola no basta: quien ejecuta esto es Tebex, nadie está mirando, y
     * el comando no se reenvía. Un rechazo silencioso es una compra cobrada que el jugador no
     * recibe y de la que no queda constancia hasta que se queja. El log SEVERE sale siempre;
     * el aviso a Discord respeta {@code Tebex.Notify_Discord} como el resto.
     */
    private void reject(CommandSender sender, String message, String detail) {
        send(sender, message);
        String line = oneLine(detail);
        plugin.getLogger().severe("ENTREGA TEBEX RECHAZADA -- " + line
                + ". Tebex no reenvía el comando: revisa el paquete y entrégalo a mano con"
                + " /cht tebex grant <uuid|jugador> <rank_id> <id-del-pago>.");
        if (plugin.getConfig().getBoolean("Tebex.Notify_Discord", true)
                && plugin.getStaffNotifier() != null) {
            plugin.getStaffNotifier().alert("Tebex: DELIVERY REJECTED -- " + line);
        }
    }

    /**
     * El detalle aplanado, aquí y no en cada sitio que lo construye.
     *
     * <p>Casi todos llevan dentro lo que llegó en el comando — el nombre del comprador, el id
     * del rango, la referencia del pago — y acaban en dos sitios donde una línea de más es una
     * línea falsa: el log de la consola, que es el rastro de por qué no se entregó una compra
     * cobrada, y un aviso al staff en Discord, que además interpreta el markdown. Saneado en el
     * único punto por el que pasan todos, el próximo motivo de rechazo nace ya defendido.
     */
    private static String oneLine(String detail) {
        if (detail == null) return "";
        String flat = detail.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    /** Lo mismo, para cuando el intento ya se convirtió en una operación. */
    private static String describe(Operation operation) {
        return "/cht tebex " + operation.action() + " " + operation.minecraftId() + " "
                + operation.group() + " " + operation.transaction();
    }

    /** El intento tal y como llegó, en una línea que se pueda leer en un log. */
    private static String describe(String[] args) {
        StringBuilder out = new StringBuilder("/cht tebex");
        for (String arg : args) {
            out.append(' ').append(arg == null ? "" : arg.replaceAll("[\\r\\n]", " "));
        }
        return out.length() > 200 ? out.substring(0, 200) + "..." : out.toString();
    }

    private void send(CommandSender sender, String miniMessage) {
        FoliaTasks.global(plugin, () -> sender.sendMessage(MM.full(miniMessage)));
    }

    /** Ver {@link dev.chatalyst.util.BuyerId#parseUuid}: un nombre nunca es un UUID. */
    static UUID parseUuid(String raw) {
        return dev.chatalyst.util.BuyerId.parseUuid(raw);
    }

    static String normalizeTransaction(String transaction) {
        return transaction.trim().toLowerCase(Locale.ROOT);
    }

    static String safeReceiptTransaction(String transaction) {
        // Bukkit YAML treats dots as path separators. Escape them so one Tebex
        // transaction always occupies one atomic receipt key after a restart.
        return transaction.replace(".", "%2E");
    }

    private static String safeFailure(Throwable failure) {
        if (failure == null) return "";
        Throwable shown = failure;
        while (shown.getCause() != null && shown.getCause() != shown) shown = shown.getCause();
        String message = shown.getMessage();
        return message == null ? shown.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }

    private enum ClaimStatus {
        CLAIMED, DUPLICATE, CONFLICT, PROCESSING, STORAGE_FAILED
    }

    private record Claim(ClaimStatus status, Operation operation, String receiptValue) {
    }

    private record Decoded(String state, long updatedAt, Operation operation) {
    }

    private record Operation(String action, UUID minecraftId, String group,
                             long expiresAt, String transaction) {
        boolean sameIdentity(Operation other) {
            return other != null && action.equals(other.action)
                    && minecraftId.equals(other.minecraftId)
                    && group.equals(other.group);
        }
    }

    private record RankDefinition(String rankId, String group, String duration) {
    }
}
