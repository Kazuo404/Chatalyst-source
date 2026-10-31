package dev.chatalyst.marriage;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /marry [jugador|accept|deny] y /divorce. La peticion llega como un mensaje con botones
 * (aceptar / rechazar): escribir el subcomando a mano tambien vale, pero el click es lo que
 * hace que la propuesta se sienta bonita en vez de burocratica.
 */
public final class MarriageCommand implements CommandExecutor, TabCompleter {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final MarriageManager manager;
    private final MarriageStorage chest;
    private final MarriageBank bank;
    private final MarriageCeremony ceremony;
    private final WeddingRite rite;
    private final dev.chatalyst.nick.NickManager nicks;

    public MarriageCommand(org.bukkit.plugin.java.JavaPlugin plugin, MarriageManager manager,
                           MarriageStorage chest, MarriageBank bank,
                           MarriageCeremony ceremony, WeddingRite rite,
                           dev.chatalyst.nick.NickManager nicks) {
        this.rite = rite;
        this.plugin = plugin;
        this.manager = manager;
        this.chest = chest;
        this.bank = bank;
        this.ceremony = ceremony;
        this.nicks = nicks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        boolean divorcing = command.getName().equalsIgnoreCase("divorce");
        // Con la feature apagada en caliente, quien SIGUE casado puede divorciarse; lo que
        // se bloquea es casarse. Si no, un matrimonio quedaria grabado para siempre.
        if (!manager.isEnabled() && !(divorcing && manager.isMarried(player.getUniqueId()))) {
            player.sendMessage(Lang.get(player, "marriage.disabled"));
            return true;
        }
        if (divorcing) {
            return divorce(player);
        }
        if (args.length == 0) {
            return status(player);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help", "ayuda", "?" -> help(player);
            case "accept", "aceptar", "si" -> accept(player);
            case "deny", "rechazar", "no" -> deny(player);
            case "storage", "almacen", "almacén", "cofre", "chest" -> storage(player);
            case "bank", "banco" -> bank(player, args);
            case "gift", "regalo", "regalar" -> gift(player);
            case "ceremony", "ceremonia", "boda" -> ceremony(player);
            case "join", "asistir", "ir" -> join(player);
            case "vow", "acepto", "si2" -> vow(player);
            case "tp", "viajar" -> teleport(player);
            // /marry <jugador> [boda] — el segundo argumento es la eleccion de la PAREJA:
            // boda publica (musica + invitacion) o matrimonio discreto.
            default -> propose(player, args[0],
                    args.length > 1 && isCeremonyWord(args[1]));
        };
    }

    /** /marry — con quien estas casado y desde cuando (o que estas soltero). */
    private boolean status(Player player) {
        MarriageManager.Marriage current = manager.marriage(player.getUniqueId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "marriage.status-single"));
            return true;
        }
        // A TI si se te dice con quien estas casado aunque tu pareja este en modo anonimo o
        // la feature se haya apagado: ya lo sabes, y un "Casado con  " es un mensaje roto.
        // partnerName() se vacia para el resto del mundo, no para el propio conyuge.
        String partnerName = manager.partnerName(player.getUniqueId());
        if (partnerName.isEmpty()) partnerName = MM.escape(current.partnerName());
        player.sendMessage(Lang.get(player, "marriage.status",
                "partner", partnerName,
                "time", TimeUtil.formatElapsed(System.currentTimeMillis() - current.since())));
        return true;
    }

    /** Palabras que piden boda publica al proponer, en los dos idiomas. */
    private static boolean isCeremonyWord(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "boda", "ceremony", "ceremonia", "public", "publica", "publico" -> true;
            default -> false;
        };
    }

    /** /marry help — todo lo que se puede hacer, en el idioma de quien pregunta. */
    private boolean help(Player player) {
        for (String line : dev.chatalyst.lang.Lang.rawList(player, "marriage.help")) {
            player.sendMessage(MM.full(line));
        }
        return true;
    }

    private boolean propose(Player player, String targetName, boolean withCeremony) {
        // resolveOnline acepta nombre real O apodo: para quien propone, el apodo ES el
        // nombre de la otra persona.
        Player target = nicks.resolveOnline(targetName);
        // Un vanish que este jugador no puede ver no existe para el: "propuesta enviada" a
        // alguien que no esta en la lista confirmaba que el staff oculto estaba conectado.
        if (target == null || !target.isOnline() || hiddenFrom(player, target)) {
            player.sendMessage(Lang.get(player, "marriage.player-not-found",
                    "player", MM.escape(targetName)));
            return true;
        }
        String error = manager.proposeError(player.getUniqueId(), target.getUniqueId());
        if (error != null) {
            player.sendMessage(Lang.get(player, error));
            return true;
        }
        String seconds = String.valueOf(manager.timeoutSeconds());
        // /ignore tambien frena las propuestas: eran la ultima via para que un ignorado le
        // mandara un mensaje con botones a quien no quiere leerle. Silencioso, como en los
        // privados: quien propone ve "enviada" y la propuesta nunca existe.
        if (ignores(target, player)) {
            player.sendMessage(Lang.get(player,
                    withCeremony ? "marriage.sent-ceremony" : "marriage.sent",
                    "player", shown(target), "seconds", seconds));
            return true;
        }
        manager.propose(player.getUniqueId(), player.getName(), target.getUniqueId(),
                withCeremony);
        String from = shown(player);
        player.sendMessage(Lang.get(player,
                withCeremony ? "marriage.sent-ceremony" : "marriage.sent",
                "player", shown(target), "seconds", seconds));
        // Quien recibe la propuesta ve SI le estan pidiendo boda publica: aceptar sin
        // saberlo lo metaria en una ceremonia delante de todo el servidor.
        target.sendMessage(Lang.get(target,
                withCeremony ? "marriage.request-ceremony" : "marriage.request",
                "player", from, "seconds", seconds));
        return true;
    }

    private boolean accept(Player player) {
        MarriageManager.Proposal proposal = manager.active(player.getUniqueId());
        if (proposal == null) {
            player.sendMessage(Lang.get(player, "marriage.no-request"));
            return true;
        }
        Player other = MarriageManager.online(proposal.from());
        if (other == null) {
            // Se fue mientras lo pensabas: la propuesta muere con el, no se guarda a medias.
            manager.cancel(player.getUniqueId());
            player.sendMessage(Lang.get(player, "marriage.proposer-left"));
            return true;
        }
        // Se revalida TODO: entre la propuesta y el "si" cualquiera de los dos pudo casarse
        // con otra persona.
        String error = manager.proposeError(other.getUniqueId(), player.getUniqueId());
        if (error != null) {
            manager.cancel(player.getUniqueId());
            player.sendMessage(Lang.get(player, error));
            return true;
        }
        // Boda PUBLICA: no se casan aun. Arranca la ceremonia por pasos (convocatoria ->
        // votos -> sello) y el matrimonio se guarda al final, cuando los dos digan "acepto".
        if (proposal.ceremony()
                && rite.begin(other, player, shown(other), shown(player))) {
            // La propuesta ya esta consumida: solo la borra marry(), y por este camino aun
            // faltan 90 segundos para casarse. Dejarla viva permitiria re-aceptar a mitad
            // de la ceremonia.
            manager.cancel(player.getUniqueId());
            return true;
        }
        if (proposal.ceremony()) {
            // Ya habia otra boda en curso: se casan igual, pero sin ceremonia, en vez de
            // quedarse esperando a que el servidor se libere.
            player.sendMessage(Lang.get(player, "marriage.ceremony-busy"));
            other.sendMessage(Lang.get(other, "marriage.ceremony-busy"));
        }
        if (!manager.marry(other.getUniqueId(), other.getName(),
                player.getUniqueId(), player.getName())) {
            player.sendMessage(Lang.get(player, "marriage.error"));
            other.sendMessage(Lang.get(other, "marriage.error"));
            return true;
        }
        player.sendMessage(Lang.get(player, "marriage.married", "player", shown(other)));
        other.sendMessage(Lang.get(other, "marriage.married", "player", shown(player)));
        manager.announceWedding(other, player, false);
        return true;
    }

    private boolean deny(Player player) {
        MarriageManager.Proposal proposal = manager.active(player.getUniqueId());
        if (proposal == null) {
            player.sendMessage(Lang.get(player, "marriage.no-request"));
            return true;
        }
        manager.cancel(player.getUniqueId());
        player.sendMessage(Lang.get(player, "marriage.denied-self"));
        Player other = MarriageManager.online(proposal.from());
        if (other != null) {
            other.sendMessage(Lang.get(other, "marriage.denied", "player", shown(player)));
        }
        return true;
    }

    /** /marry storage — el cofre compartido de la pareja. */
    private boolean storage(Player player) {
        if (!chest.isEnabled()) {
            player.sendMessage(Lang.get(player, "marriage.storage-disabled"));
            return true;
        }
        if (!manager.isMarried(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return true;
        }
        chest.open(player);
        return true;
    }

    /** /marry bank [deposit|withdraw <cantidad|all>] — la cuenta de dinero de la pareja. */
    private boolean bank(Player player, String[] args) {
        if (!bank.isEnabled()) {
            player.sendMessage(Lang.get(player, "marriage.bank-disabled"));
            return true;
        }
        MarriageManager.Marriage current = manager.marriage(player.getUniqueId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Lang.get(player, "marriage.bank-balance",
                    "amount", bank.format(bank.balance(player.getUniqueId()))));
            player.sendMessage(Lang.get(player, "marriage.bank-usage"));
            return true;
        }
        boolean deposit;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "deposit", "depositar", "meter", "ingresar", "d" -> deposit = true;
            case "withdraw", "retirar", "sacar", "w" -> deposit = false;
            default -> {
                player.sendMessage(Lang.get(player, "marriage.bank-usage"));
                return true;
            }
        }
        if (args.length < 3) {
            player.sendMessage(Lang.get(player, "marriage.bank-usage"));
            return true;
        }
        boolean everything = isAllWord(args[2]);
        long cents = !everything ? MarriageBank.parseCents(args[2])
                : deposit ? bank.walletCents(player) : bank.balance(player.getUniqueId());
        if (cents <= 0L) {
            // "all" sin nada que mover cae aqui tambien: no hay cantidad valida.
            String key = !everything ? "marriage.bank-invalid-amount"
                    : deposit ? "marriage.bank-player-funds" : "marriage.bank-account-empty";
            player.sendMessage(Lang.get(player, key));
            return true;
        }
        MarriageBank.Result result = deposit
                ? bank.deposit(player, cents) : bank.withdraw(player, cents);
        String amount = bank.format(cents);
        String balance = bank.format(bank.balance(player.getUniqueId()));
        switch (result) {
            case OK -> {
                player.sendMessage(Lang.get(player,
                        deposit ? "marriage.bank-deposited" : "marriage.bank-withdrawn",
                        "amount", amount, "balance", balance));
                Player partner = MarriageManager.online(current.partner());
                if (partner != null) {
                    // Al conyuge se le nombra por el nombre REAL si quien opera va disfrazado:
                    // misma regla que /marry tp, para no entregarle el alias.
                    String who = dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())
                            ? MM.escape(player.getName()) : shown(player);
                    partner.sendMessage(Lang.get(partner,
                            deposit ? "marriage.bank-partner-deposited"
                                    : "marriage.bank-partner-withdrew",
                            "player", who, "amount", amount, "balance", balance));
                }
            }
            case NOT_MARRIED -> player.sendMessage(Lang.get(player, "marriage.not-married"));
            case PLAYER_FUNDS -> player.sendMessage(Lang.get(player, "marriage.bank-player-funds"));
            case ACCOUNT_FUNDS -> player.sendMessage(Lang.get(player,
                    "marriage.bank-account-funds", "balance", balance));
            case LIMIT -> player.sendMessage(Lang.get(player, "marriage.bank-limit"));
            case FAILED -> player.sendMessage(Lang.get(player, "marriage.bank-failed"));
        }
        return true;
    }

    /** "todo" en los dos idiomas: ingresar todo lo que llevas o retirar todo lo que hay. */
    private static boolean isAllWord(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "all", "todo", "max" -> true;
            default -> false;
        };
    }

    /** /marry gift — le das a tu pareja el objeto que llevas en la mano. */
    private boolean gift(Player player) {
        if (!plugin.getConfig().getBoolean("Marriage.Perks.Gift.Enabled", true)) {
            player.sendMessage(Lang.get(player, "marriage.gift-disabled"));
            return true;
        }
        MarriageManager.Marriage current = manager.marriage(player.getUniqueId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return true;
        }
        Player partner = MarriageManager.online(current.partner());
        // Mismo criterio que /marry tp: a un vanish que no puedes ver no se le regala nada,
        // o el "regalo entregado" confirmaria que esta conectado.
        if (partner == null || hiddenFrom(player, partner)) {
            player.sendMessage(Lang.get(player, "marriage.partner-offline"));
            return true;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Lang.get(player, "marriage.gift-empty-hand"));
            return true;
        }
        org.bukkit.inventory.ItemStack present = held.clone();
        // Se quita de la mano ANTES de entregarlo. Al reves, cualquier fallo entre los dos pasos
        // dejaria el objeto en los dos inventarios: un dupe con solo desconectarse a tiempo.
        player.getInventory().setItemInMainHand(null);
        String amount = String.valueOf(present.getAmount());
        String item = MM.escape(itemLabel(present));
        String giverName = dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())
                ? MM.escape(player.getName()) : shown(player);
        String partnerName = shown(partner);
        // La entrega corre en la region de quien recibe (Folia): su inventario es suyo.
        dev.chatalyst.util.FoliaTasks.entity(plugin, partner, target -> {
            var leftover = target.getInventory().addItem(present);
            // Lo que no cabe cae a sus pies: ni se pierde ni vuelve a cruzar de region.
            for (org.bukkit.inventory.ItemStack rest : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
            target.sendMessage(Lang.get(target, "marriage.gift-received",
                    "player", giverName, "amount", amount, "item", item));
            if (!leftover.isEmpty()) {
                target.sendMessage(Lang.get(target, "marriage.gift-dropped"));
            }
            dev.chatalyst.util.FoliaTasks.entity(plugin, player, giver -> giver.sendMessage(
                    Lang.get(giver, "marriage.gift-sent",
                            "player", partnerName, "amount", amount, "item", item)));
        }, () -> returnGift(player, present));
        return true;
    }

    /** La pareja se fue antes de recibirlo: el objeto vuelve a quien lo regalaba. */
    private void returnGift(Player giver, org.bukkit.inventory.ItemStack present) {
        dev.chatalyst.util.FoliaTasks.entity(plugin, giver, back -> {
            for (org.bukkit.inventory.ItemStack rest : back.getInventory().addItem(present).values()) {
                back.getWorld().dropItemNaturally(back.getLocation(), rest);
            }
            back.sendMessage(Lang.get(back, "marriage.gift-returned"));
        }, () -> plugin.getLogger().severe("A marriage gift (" + present.getAmount() + "x "
                + present.getType().getKey() + ") from " + giver.getName() + " could not be"
                + " delivered or returned: both spouses left at the same moment."));
    }

    /** Nombre legible del objeto: el personalizado si lo tiene; si no, su id sin guiones. */
    static String itemLabel(org.bukkit.inventory.ItemStack stack) {
        var meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            var name = meta.displayName();
            if (name != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(name).trim();
                if (!plain.isEmpty()) return plain;
            }
        }
        return stack.getType().getKey().getKey().replace('_', ' ');
    }

    /** /marry ceremony — los casados convocan (otra vez) su boda: musica e invitacion. */
    private boolean ceremony(Player player) {
        MarriageManager.Marriage current = manager.marriage(player.getUniqueId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return true;
        }
        Player partner = MarriageManager.online(current.partner());
        // La ceremonia se anuncia a TODO el servidor con los dos nombres: con la pareja en
        // vanish delataba que estaba conectada, y aunque quien convoca si pueda verla, el
        // resto del servidor no. Para este comando cuenta como desconectada.
        if (partner == null || dev.chatalyst.util.Vanish.isVanished(partner)) {
            player.sendMessage(Lang.get(player, "marriage.partner-offline"));
            return true;
        }
        // Mismo motivo que proposeError: el anuncio ataria el ALIAS de un anonimo a su pareja.
        if (dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())
                || dev.chatalyst.hook.Placeholders.isAnonymous(partner.getUniqueId())) {
            player.sendMessage(Lang.get(player, "marriage.anonymous"));
            return true;
        }
        // Con una boda por pasos en curso NO se convoca otra: las dos marchas sonarian
        // encima y los anuncios de una pisarian los votos de la otra. El cooldown por
        // pareja no cubria esto, porque quien convoca aqui es OTRA pareja distinta.
        if (rite.busy()) {
            player.sendMessage(Lang.get(player, "marriage.ceremony-busy"));
            return true;
        }
        // Sin cooldown, /marry ceremony es un altavoz: musica y mensaje a todo el servidor
        // tantas veces como quieras.
        String coupleKey = MarriageStorage.key(player.getUniqueId(), current.partner());
        long left = ceremony.cooldownMinutesLeft(coupleKey);
        if (left > 0) {
            player.sendMessage(Lang.get(player, "marriage.ceremony-cooldown",
                    "minutes", String.valueOf(left)));
            return true;
        }
        manager.announceCeremony(partner, player);
        return true;
    }

    /** /marry vow — decir "acepto" en tu propia ceremonia (lo lanza el boton). */
    private boolean vow(Player player) {
        String reply = rite.vow(player);
        // null = los dos han dicho que si y el sello ya mando sus mensajes.
        if (reply != null) {
            player.sendMessage(Lang.get(player, reply));
        }
        return true;
    }

    /** /marry join — viajar a la boda abierta (es lo que ejecuta el boton de la invitacion). */
    private boolean join(Player player) {
        if (!ceremony.guestTeleportEnabled()) {
            player.sendMessage(Lang.get(player, "marriage.ceremony-disabled"));
            return true;
        }
        if (!ceremony.teleportGuest(player)) {
            player.sendMessage(Lang.get(player, "marriage.ceremony-over"));
        }
        return true;
    }

    /** /marry tp — viajar hasta la pareja. */
    private boolean teleport(Player player) {
        if (!manager.teleportEnabled()) {
            player.sendMessage(Lang.get(player, "marriage.tp-disabled"));
            return true;
        }
        MarriageManager.Marriage current = manager.marriage(player.getUniqueId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return true;
        }
        Player partner = MarriageManager.online(current.partner());
        // Viajar hasta un vanish que no puedes ver te planta a su lado y lo delata.
        if (partner == null || hiddenFrom(player, partner)) {
            player.sendMessage(Lang.get(player, "marriage.partner-offline"));
            return true;
        }
        long left = manager.teleportCooldownLeft(player.getUniqueId());
        if (left > 0) {
            player.sendMessage(Lang.get(player, "marriage.tp-cooldown",
                    "time", TimeUtil.formatRemaining(left)));
            return true;
        }
        String partnerName = shown(partner);
        // Viajando disfrazado, al conyuge se le nombra por el nombre REAL: ya sabe con quien
        // esta casado, pero decirle el alias le entregaria el disfraz para que lo cuente.
        String travellerName = dev.chatalyst.hook.Placeholders.isAnonymous(player.getUniqueId())
                ? MM.escape(player.getName()) : shown(player);
        // Doble salto de region (Folia): la posicion de la pareja SOLO se puede leer desde su
        // propia region, y el teleport solo se puede pedir desde la del que viaja. Leerla
        // directamente aqui funciona en Paper pero es una carrera en Folia.
        dev.chatalyst.util.FoliaTasks.entity(plugin, partner, target -> {
            org.bukkit.Location destination = target.getLocation().clone();
            dev.chatalyst.util.FoliaTasks.entity(plugin, player, traveller ->
                    // El cooldown se marca solo si el viaje SALE bien.
                    traveller.teleportAsync(destination).thenAccept(success -> {
                        if (!success) return;
                        manager.markTeleport(traveller.getUniqueId());
                        traveller.sendMessage(Lang.get(traveller, "marriage.tp-done",
                                "player", partnerName));
                        target.sendMessage(Lang.get(target, "marriage.tp-incoming",
                                "player", travellerName));
                    }));
        });
        return true;
    }

    private boolean divorce(Player player) {
        MarriageManager.Marriage current = manager.marriage(player.getUniqueId());
        if (current == null) {
            player.sendMessage(Lang.get(player, "marriage.not-married"));
            return true;
        }
        // Con items dentro, divorciarse los haria desaparecer: se exige vaciarlo antes.
        // Repartirlos por nuestra cuenta seria peor (nadie estaria de acuerdo con el reparto).
        if (chest.hasItems(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "marriage.storage-not-empty"));
            return true;
        }
        // Lo mismo con el dinero de la cuenta: se retira antes, no se reparte ni se pierde.
        // Con el banco apagado no se bloquea, igual que el almacen: nadie podria retirarlo.
        if (bank.isEnabled() && bank.hasMoney(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "marriage.bank-not-empty"));
            return true;
        }
        String partnerName = manager.partnerName(player.getUniqueId());
        // Apagada la feature, partnerName() va vacio a proposito: para ESTE mensaje se cae
        // al nombre guardado, o el adios saldria sin nombre.
        if (partnerName.isEmpty()) partnerName = MM.escape(current.partnerName());
        Player partner = MarriageManager.online(current.partner());
        // El anuncio se compone ANTES de romper el vinculo (despues, partnerName() ya
        // devuelve vacio) pero se envia DESPUES, y solo si el divorcio se guardo.
        Runnable announcement = manager.divorceAnnouncement(player);
        if (!manager.divorce(player.getUniqueId())) {
            player.sendMessage(Lang.get(player, "marriage.error"));
            return true;
        }
        announcement.run();
        chest.clear(player.getUniqueId(), current.partner());
        bank.clear(player.getUniqueId(), current.partner());
        player.sendMessage(Lang.get(player, "marriage.divorced", "player", partnerName));
        if (partner != null) {
            partner.sendMessage(Lang.get(partner, "marriage.divorced-by",
                    "player", shown(player)));
        }
        return true;
    }

    private static boolean isBankWord(String word) {
        return word.equalsIgnoreCase("bank") || word.equalsIgnoreCase("banco");
    }

    /** True si {@code target} esta en vanish y {@code viewer} no puede ver a los ocultos. */
    private static boolean hiddenFrom(Player viewer, Player target) {
        return dev.chatalyst.util.Vanish.isVanished(target)
                && !dev.chatalyst.staff.StaffAccess.canSeeVanished(viewer);
    }

    /** True si {@code target} tiene a {@code sender} en /ignore. */
    private static boolean ignores(Player target, Player sender) {
        var running = dev.chatalyst.ChatalystPlugin.get();
        var pm = running == null ? null : running.getPrivateMessages();
        return pm != null && pm.isIgnoring(target.getUniqueId(), sender.getUniqueId());
    }

    /** Nombre mostrado (apodo si tiene), plano y escapado para meterlo en una plantilla. */
    private static String shown(Player player) {
        return dev.chatalyst.nick.NickManager.safeName(player);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!manager.isEnabled() || command.getName().equalsIgnoreCase("divorce")) {
            return List.of();
        }
        // /marry bank <deposit|withdraw> <cantidad|all>
        if (args.length >= 2 && isBankWord(args[0])) {
            String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> options = args.length == 2 ? List.of("deposit", "withdraw")
                    : args.length == 3 ? List.of("all") : List.of();
            return options.stream().filter(option -> option.startsWith(typed)).toList();
        }
        // Segundo argumento: el estilo de boda, para que se descubra sin leer la wiki.
        if (args.length == 2) {
            return "boda".startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of("boda") : List.of();
        }
        if (args.length != 1) return List.of();
        String pre = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        // Con una peticion abierta lo util es responderla, no proponer a otra persona.
        if (sender instanceof Player p && manager.active(p.getUniqueId()) != null) {
            for (String option : List.of("accept", "deny")) {
                if (option.startsWith(pre)) out.add(option);
            }
            return out;
        }
        if ("help".startsWith(pre)) out.add("help");
        // "join" solo mientras hay una boda abierta a la que ir.
        if (ceremony.guestTeleportEnabled() && !ceremony.couple().isEmpty()
                && "join".startsWith(pre)) {
            out.add("join");
        }
        if (sender instanceof Player p && manager.isMarried(p.getUniqueId())) {
            if ("ceremony".startsWith(pre)) out.add("ceremony");
            // Ya casado: proponer no aplica, lo util son los perks — pero solo se sugieren
            // los que estan ENCENDIDOS, o el tab-complete ofrece comandos que responden
            // "esta desactivado".
            if (chest.isEnabled() && "storage".startsWith(pre)) out.add("storage");
            if (bank.isEnabled() && "bank".startsWith(pre)) out.add("bank");
            if ("gift".startsWith(pre)) out.add("gift");
            if (manager.teleportEnabled() && "tp".startsWith(pre)) out.add("tp");
            return out;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player p && online.getUniqueId().equals(p.getUniqueId())) continue;
            // Un anonimo no se sugiere: es incognito y ademas no se puede proponer matrimonio
            // a alguien disfrazado (proposeError lo bloquea), asi que delataria su nombre en vano.
            if (dev.chatalyst.hook.Placeholders.isAnonymous(online.getUniqueId())) continue;
            if (sender instanceof Player p && hiddenFrom(p, online)) continue;
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(online.getName());
        }
        return out;
    }
}
