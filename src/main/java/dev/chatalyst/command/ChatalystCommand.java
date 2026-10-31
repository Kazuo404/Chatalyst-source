package dev.chatalyst.command;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Stream;

public final class ChatalystCommand implements CommandExecutor, TabCompleter {

    private final ChatalystPlugin plugin;

    public ChatalystCommand(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("version")) {
            sender.sendMessage(Lang.get(sender, "plugin.version", "version", plugin.getPluginMeta().getVersion()));
            return true;
        }
        if (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("commands")
                || args[0].equalsIgnoreCase("comandos")) {
            sendHelp(sender, numericPage(args, 1));
            return true;
        }
        if (args[0].equalsIgnoreCase("emojis") || args[0].equalsIgnoreCase("emoji")) {
            sendEmojis(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("ranks")
                || args[0].equalsIgnoreCase("rank")
                || args[0].equalsIgnoreCase("shop")
                || args[0].equalsIgnoreCase("shops")
                || args[0].equalsIgnoreCase("tienda")
                || args[0].equalsIgnoreCase("rangos")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
                return true;
            }
            if (plugin.getTebexShop() != null) plugin.getTebexShop().open(player);
            return true;
        }
        // Entrega de bienes de un solo uso (objetos, comandos, dinero). Mismo endpoint de
        // consola que Tebex, pero con garantia "como mucho una vez" en vez de reconciliacion.
        // SIN alias "tienda": lo reclama antes la tienda de rangos de arriba, asi que aqui
        // era codigo muerto y "/cht tienda give ..." abria la vitrina en vez de entregar.
        if (args[0].equalsIgnoreCase("store")) {
            if (plugin.getStoreService() == null) return true;
            plugin.getStoreService().handle(sender,
                    java.util.Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        // Vinculo Minecraft<->Discord que pide la tienda web cuando ya verifico las dos cuentas
        // (Discord por OAuth, Minecraft con /codigo). SOLO consola o RCON: un jugador, aunque
        // sea admin, no puede vincular su Discord a la cuenta de otro.
        if (args[0].equalsIgnoreCase("discordlink")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)
                    && !(sender instanceof org.bukkit.command.RemoteConsoleCommandSender)) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            dev.chatalyst.discord.DiscordLinkManager links = plugin.getDiscordLinks();
            java.util.UUID target = null;
            try {
                if (args.length >= 3) target = java.util.UUID.fromString(args[1]);
            } catch (IllegalArgumentException ignored) {
                // falls through to the usage line
            }
            if (links == null || target == null) {
                sender.sendMessage("Usage: /chatalyst discordlink <minecraft-uuid> <discord-id>");
                return true;
            }
            String source = args.length >= 4 ? args[3] : "console";
            java.util.UUID minecraftId = target;
            links.forceLink(minecraftId, args[2], source).thenAccept(status ->
                    plugin.getLogger().info("discordlink " + minecraftId + " -> " + args[2]
                            + ": " + status));
            return true;
        }
        // Endpoint idempotente para los comandos de entrega del plugin oficial de Tebex.
        // La autenticacion real la hace Tebex; por defecto Chatalyst solo acepta la consola.
        if (args[0].equalsIgnoreCase("tebex")) {
            if (args.length >= 2 && (args[1].equalsIgnoreCase("gui")
                    || args[1].equalsIgnoreCase("presentation")
                    || args[1].equalsIgnoreCase("presentacion"))) {
                if (!sender.hasPermission("chatalyst.admin")
                        && !sender.hasPermission(
                        "chatalyst.admin.tebex.presentation")) {
                    sender.sendMessage(Lang.get(sender, "common.no-permission"));
                    return true;
                }
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return true;
                }
                if (plugin.getTebexPresentations() != null) {
                    dev.chatalyst.gui.Menu.open(player,
                            new dev.chatalyst.tebex.TebexPresentationMenu(
                                    plugin, plugin.getTebexPresentations(), 0));
                }
                return true;
            }
            if (plugin.getTebexRanks() == null) return true;
            plugin.getTebexRanks().handle(sender,
                    java.util.Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args[0].equalsIgnoreCase("doctor")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            // "doctor discord" asks Discord itself. The config doctor can only read files, and
            // the things that actually break here — a permission, a role's position, an intent
            // — live on the other side and cannot be seen from disk.
            if (args.length > 1 && args[1].equalsIgnoreCase("discord")) {
                new dev.chatalyst.discord.DiscordSelfTest(plugin).run(sender);
                return true;
            }
            java.util.List<String> problems = dev.chatalyst.config.ConfigDoctor.diagnose(plugin);
            if (problems.isEmpty()) {
                sender.sendMessage(Lang.get(sender, "plugin.doctor-healthy"));
            } else {
                sender.sendMessage(Lang.get(sender, "plugin.doctor-header",
                        "count", problems.size()));
                for (String p : problems) {
                    sender.sendMessage(Lang.get(sender, "plugin.doctor-entry",
                            "problem", dev.chatalyst.util.MM.escape(p)));
                }
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("perf") || args[0].equalsIgnoreCase("performance")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            double[] tps = plugin.getServer().getTPS();
            double mspt = plugin.getServer().getAverageTickTime();
            int online = plugin.getServer().getOnlinePlayers().size();
            int rules = plugin.getRuleEngine() != null ? plugin.getRuleEngine().ruleCount() : 0;
            boolean swear = plugin.getSwearFilter() != null && plugin.getSwearFilter().isEnabled();
            long budgetHits = dev.chatalyst.util.SafeRegex.budgetExceededCount();
            long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            sender.sendMessage(Lang.get(sender, "plugin.perf-title"));
            sender.sendMessage(Lang.get(sender, "plugin.perf-ticks",
                    "tps1", tps1(tps[0]), "tps5", tps1(tps[1]), "tps15", tps1(tps[2]),
                    "mspt", String.format(java.util.Locale.ROOT, "%.1f", mspt)));
            sender.sendMessage(Lang.get(sender, "plugin.perf-memory",
                    "used", usedMb, "max", maxMb, "online", online));
            sender.sendMessage(Lang.get(sender, "plugin.perf-rules",
                    "rules", rules, "swear", Lang.raw(sender,
                            swear ? "plugin.perf-state-on" : "plugin.perf-state-off"),
                    "hits", budgetHits));
            sender.sendMessage(Lang.get(sender, "plugin.perf-uptime",
                    "uptime", dev.chatalyst.util.TimeUtil.formatElapsed(uptimeMs)));
            if (budgetHits > 0) {
                sender.sendMessage(Lang.get(sender, "plugin.perf-regex-warning"));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("features") || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            var results = dev.chatalyst.config.FeatureReport.evaluate(plugin);
            long on = results.stream().filter(dev.chatalyst.config.FeatureReport.Result::on).count();
            var off = dev.chatalyst.config.FeatureReport.disabled(results);
            sender.sendMessage(dev.chatalyst.util.MM.full(
                    "<gradient:#240046:#9D4EDD:#E0AAFF><bold>Chatalyst features</bold></gradient> "
                    + "<#7B68A0>- <#3DFFA8>" + on + " on <#7B68A0>/ "
                    + (off.isEmpty() ? "<#3DFFA8>0 off" : "<#FF9F1C>" + off.size() + " off")));
            String onList = results.stream()
                    .filter(dev.chatalyst.config.FeatureReport.Result::on)
                    .map(dev.chatalyst.config.FeatureReport.Result::label)
                    .collect(java.util.stream.Collectors.joining("<#7B68A0>, <#3DFFA8>"));
            if (!onList.isEmpty()) {
                sender.sendMessage(dev.chatalyst.util.MM.full("<#3DFFA8>[on] " + onList));
            }
            if (off.isEmpty()) {
                sender.sendMessage(dev.chatalyst.util.MM.full("<#3DFFA8>Everything is enabled."));
            } else {
                sender.sendMessage(dev.chatalyst.util.MM.full(
                        "<#FF9F1C>Off (" + off.size() + ") - set each to true to enable:"));
                for (var r : off) {
                    var f = r.feature();
                    sender.sendMessage(dev.chatalyst.util.MM.full("  <#FF4D6D>[x] <#C0B3D9>"
                            + f.label() + " <#7B68A0>-> <#E0AAFF>" + f.configPath()
                            + ": true <#7B68A0>(" + f.file() + ")"));
                }
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("permissions") || args[0].equalsIgnoreCase("perms")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            // /cht permissions [categoria] [pagina] — cualquier argumento numerico es pagina.
            String filter = null;
            int page = 1;
            for (int i = 1; i < args.length; i++) {
                try {
                    page = Math.max(1, Integer.parseInt(args[i]));
                } catch (NumberFormatException notNumber) {
                    filter = args[i].toLowerCase(java.util.Locale.ROOT);
                }
            }
            sendPermissions(sender, filter, page);
            return true;
        }
        // Borrar un mensaje (lo dispara la X del formato). El borrado visible en el chat es
        // el feedback de exito; solo el fallo avisa. NUNCA cae al usage: un id ausente o
        // caducado no debe responder con la lista de subcomandos.
        if (args[0].equalsIgnoreCase("removemessage")) {
            if (sender.hasPermission("chatalyst.deletemessages") && args.length >= 2) {
                boolean deleted = plugin.getMessageDeleteManager().delete(args[1]);
                // Que tampoco reaparezca en el replay del historial de canal.
                if (plugin.getChannelHistory() != null) {
                    plugin.getChannelHistory().removeByMessageId(args[1]);
                }
                if (!deleted) {
                    sender.sendMessage(Lang.get(sender, "chat.delete-gone"));
                }
            }
            return true;
        }
        // Boton [Ir] del registro de contenedores / spy: teletransporte a coordenadas exactas.
        if (args[0].equalsIgnoreCase("tppos") && args.length >= 5) {
            if (!(sender instanceof org.bukkit.entity.Player p)) return true;
            // Tambien quien recibe las alertas del Item watch: su boton [Ir] usa este comando.
            if (!p.isOp() && !p.hasPermission(
                    dev.chatalyst.containerlog.ContainerLogManager.TELEPORT_PERMISSION)
                    && !p.hasPermission(plugin.getConfig().getString(
                            "Item_Watch.Alert_Permission", "chatalyst.itemwatch.alerts"))) {
                p.sendMessage(Lang.get(p, "common.no-permission"));
                return true;
            }
            // Los TRES ultimos argumentos son las coordenadas; todo lo de en medio es el
            // nombre del mundo (puede llevar espacios, y entonces args[1] seria solo su
            // primera palabra y el teletransporte fallaba en silencio).
            int last = args.length;
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(args[last - 3]);
                y = Integer.parseInt(args[last - 2]);
                z = Integer.parseInt(args[last - 1]);
            } catch (NumberFormatException bad) {
                return true;
            }
            String worldName = String.join(" ",
                    java.util.Arrays.copyOfRange(args, 1, last - 3));
            org.bukkit.World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                p.sendMessage(Lang.get(p, "containerlog.tppos-failed"));
                return true;
            }
            // Al lado del bloque, mirando hacia el: encima taparia un cofre con tapa.
            org.bukkit.Location destination = new org.bukkit.Location(
                    world, x + 0.5, y + 1.0, z + 0.5);
            dev.chatalyst.util.FoliaTasks.entity(plugin, p, current ->
                    current.teleportAsync(destination).thenAccept(success -> {
                        if (success) {
                            current.sendMessage(Lang.get(current, "containerlog.tppos-done",
                                    "world", dev.chatalyst.util.MM.escape(world.getName()),
                                    "x", x, "y", y, "z", z));
                        } else {
                            current.sendMessage(Lang.get(current, "containerlog.tppos-failed"));
                        }
                    }));
            return true;
        }
        // Boton [Ver] del registro de contenedores / spy: abre el contenido del cofre.
        // Formato: /chatalyst viewblock <jugador> <mundo...> <x> <y> <z> (el mundo puede
        // llevar espacios, asi que las TRES ultimas cifras son las coordenadas).
        if (args[0].equalsIgnoreCase("viewblock") && args.length >= 6) {
            if (!(sender instanceof org.bukkit.entity.Player p)) return true;
            if (!p.isOp() && !p.hasPermission(
                    dev.chatalyst.containerlog.ContainerLogManager.PEEK_PERMISSION)) {
                p.sendMessage(Lang.get(p, "common.no-permission"));
                return true;
            }
            int last = args.length;
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(args[last - 3]);
                y = Integer.parseInt(args[last - 2]);
                z = Integer.parseInt(args[last - 1]);
            } catch (NumberFormatException bad) {
                return true;
            }
            String worldName = String.join(" ",
                    java.util.Arrays.copyOfRange(args, 2, last - 3));
            org.bukkit.World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                p.sendMessage(Lang.get(p, "containerlog.peek-gone"));
                return true;
            }
            dev.chatalyst.containerlog.ContainerPeekMenu.open(plugin, p, args[1],
                    world, x, y, z);
            return true;
        }
        // Boton [TP] del registro de contenedores / spy: teletransporte al jugador.
        if (args[0].equalsIgnoreCase("tpplayer") && args.length >= 2) {
            if (!(sender instanceof org.bukkit.entity.Player p)) return true;
            if (!p.isOp() && !p.hasPermission(
                    dev.chatalyst.containerlog.ContainerLogManager.TELEPORT_PERMISSION)) {
                p.sendMessage(Lang.get(p, "common.no-permission"));
                return true;
            }
            org.bukkit.entity.Player target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null || (dev.chatalyst.util.Vanish.isVanished(target)
                    && !dev.chatalyst.staff.StaffAccess.canSeeVanished(p))) {
                p.sendMessage(Lang.get(p, "common.unknown-player", "player",
                        dev.chatalyst.util.MM.escape(args[1])));
                return true;
            }
            // Folia: el destino se lee en la region del objetivo y el viaje en la del staff.
            dev.chatalyst.util.FoliaTasks.entity(plugin, target, t -> {
                org.bukkit.Location destination = t.getLocation();
                String name = t.getName();
                dev.chatalyst.util.FoliaTasks.entity(plugin, p, current ->
                        current.teleportAsync(destination).thenAccept(success ->
                                current.sendMessage(Lang.get(current, success
                                                ? "containerlog.tpplayer-done"
                                                : "containerlog.tppos-failed",
                                        "player", dev.chatalyst.util.MM.escape(name)))));
            }, () -> dev.chatalyst.util.FoliaTasks.entity(plugin, p, current ->
                    current.sendMessage(Lang.get(current, "common.player-offline"))));
            return true;
        }
        // Boton "Abrir" de [inv]/[ec] compartidos en el chat: vista de solo lectura por
        // token opaco (nunca UUID: el JSON del chat no debe filtrar identidades), valida
        // solo mientras el permiso temporal del dueno siga vigente.
        if (args[0].equalsIgnoreCase("viewshare") && args.length >= 2) {
            if (!(sender instanceof org.bukkit.entity.Player p)) return true;
            if (!plugin.getConfig().getBoolean("Chat.Inventory_Share", true)
                    || plugin.getInventoryShareManager() == null) {
                return true;
            }
            var grant = plugin.getInventoryShareManager().resolve(args[1]);
            if (grant == null) {
                p.sendMessage(Lang.get(p, "chat.share-expired"));
                return true;
            }
            dev.chatalyst.chat.SharedInventoryMenu.open(plugin, p, grant.owner(), grant.kind());
            return true;
        }
        if (args[0].equalsIgnoreCase("reveal") && args.length >= 2) {
            // Boton "ver" del marcador de un mensaje ocultado por ignore: muestra el mensaje
            // real SOLO a quien clica, sin dejar de ignorar al emisor.
            if (sender instanceof org.bukkit.entity.Player p) {
                net.kyori.adventure.text.Component msg = plugin.getMessageDeleteManager()
                        .reveal(args[1], p.getUniqueId());
                if (msg != null) {
                    p.sendMessage(Lang.get(p, "chat.ignored-reveal-prefix").append(msg));
                } else {
                    p.sendMessage(Lang.get(p, "chat.ignored-reveal-gone"));
                }
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("sendformat")) {
            sendFormat(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            try {
                plugin.getConfigManager().load();
                dev.chatalyst.db.StorageManager.ReloadResult storageReload =
                        plugin.getStorageManager().reloadConfiguredConnection();
                if (storageReload.status() == dev.chatalyst.db.StorageManager.Status.RELOADED
                        && storageReload.connectionChanged()) {
                    reloadPersistentState();
                }
                if (plugin.getProxyManager() != null) plugin.getProxyManager().reload();
                if (plugin.getNetworkMonitor() != null) plugin.getNetworkMonitor().load();
                plugin.getChannelManager().load();
                if (plugin.getStaffMentionRouter() != null) plugin.getStaffMentionRouter().load();
                if (plugin.getSupportConversations() != null) plugin.getSupportConversations().load();
                if (plugin.getChannelRouter() != null) plugin.getChannelRouter().load();
                if (plugin.getTradePluginsHook() != null) plugin.getTradePluginsHook().load();
                if (plugin.getMarketManager() != null) {
                    plugin.getMarketManager().load();
                    plugin.getMarketManager().start();
                }
                if (plugin.getTradeManager() != null) plugin.getTradeManager().load();
                if (plugin.getChannelHistory() != null) plugin.getChannelHistory().load();
                if (plugin.getFrequencyManager() != null) plugin.getFrequencyManager().load();
                if (plugin.getGroupOverrides() != null) plugin.getGroupOverrides().load();
                if (plugin.getTagCatalog() != null) plugin.getTagCatalog().load();
                if (plugin.getTagManager() != null) plugin.getTagManager().load();
                if (plugin.getChatRegions() != null) plugin.getChatRegions().load();
                plugin.getRuleEngine().load();
                plugin.getSwearFilter().load();
                plugin.getBookManager().load();
                plugin.getAntiBotManager().load();
                plugin.getTabCompleteListener().load();
                plugin.getNewcomerListener().load();
                plugin.getCommandCooldownListener().load();
                plugin.getChatSanitizerListener().load();
                plugin.getCommandBlockerListener().load();
                if (plugin.getPacketEventsIntegration() != null) {
                    plugin.getPacketEventsIntegration().disable();
                    plugin.getPacketEventsIntegration().load();
                }
                if (plugin.getStaffSecurityListener() != null) {
                    plugin.getStaffSecurityListener().load();
                }
                plugin.getScriptManager().reset();
                plugin.loadCustomVariables();
                plugin.applyMentionHighlight();
                plugin.getHeldItemTracker().start();
                plugin.getServerMessages().load();
                plugin.getAnnouncer().load();
                plugin.getAnnouncer().start();
                plugin.getWarningPoints().load();
                plugin.getAntiSpam().load();
                plugin.getSpamPunisher().load();
                plugin.getGrammar().load();
                if (plugin.getAutoResponder() != null) plugin.getAutoResponder().load();
                // Sin esto habria que reiniciar el server para tocar un tag animado,
                // que es justo lo que uno prueba una y otra vez hasta dar con la velocidad.
                if (plugin.getTagEffects() != null) plugin.getTagEffects().load();
                if (plugin.getEventsManager() != null) plugin.getEventsManager().load();
                if (plugin.getTranslation() != null) {
                    plugin.getTranslation().load(plugin.getConfig().getConfigurationSection("Translation"));
                }
                if (plugin.getVoiceIntegration() != null) {
                    plugin.getVoiceIntegration().load();
                }
                // Recarga de modulos en orden de encendido (incluye el dynmap de reports).
                plugin.getModules().reloadAll();
                // Token, guild, canales e intents se aplican sin recargar todo el servidor.
                plugin.reloadDiscordBot();
                Placeholders.reset();
                dev.chatalyst.hook.LuckPermsHook.reset();
                dev.chatalyst.hook.ItemsAdderHook.reset();
                dev.chatalyst.hook.VaultHook.reset();
                dev.chatalyst.hook.DisguiseHook.reset();
                dev.chatalyst.hook.TabHook.reset();
                dev.chatalyst.hook.EssentialsHook.reset();
                Lang.load();
                // Relee protected-names.txt: editarlo debe valer sin reiniciar, igual que
                // la lista del dueño en players.yml.
                dev.chatalyst.nick.ProtectedNamesFile.install(plugin);
                sender.sendMessage(Lang.get(sender, "plugin.reloaded"));
                switch (storageReload.status()) {
                    case RELOADED -> sender.sendMessage(Lang.get(sender,
                            storageReload.connectionChanged()
                                    ? "plugin.reload-storage-reconnected"
                                    : "plugin.reload-storage-refresh"));
                    case RESTART_REQUIRED -> sender.sendMessage(Lang.get(sender,
                            "plugin.reload-storage-restart", "settings",
                            String.join(", ", storageReload.restartReasons())));
                    case FAILED -> sender.sendMessage(Lang.get(sender,
                            "plugin.reload-storage-failed"));
                    case UNCHANGED -> {
                        // El mensaje general de exito basta si storage no cambio.
                    }
                }
            } catch (Exception ex) {
                sender.sendMessage(Lang.get(sender, "plugin.reload-error", "error", ex.getMessage()));
                plugin.getLogger().warning("Reload failed: " + ex);
            }
            return true;
        }
        // Panel de administracion en GUI: interruptores de modulos + herramientas.
        if (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("admin")
                || args[0].equalsIgnoreCase("panel")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                sender.sendMessage(Lang.get(sender, "common.players-only"));
                return true;
            }
            dev.chatalyst.gui.Menu.open(p, new AdminMenu(plugin));
            return true;
        }
        // Purga de moderacion: quita del chat de todos los mensajes recientes de un jugador
        // (tambien funciona hasta 5 min despues de que se desconecte).
        if (args[0].equalsIgnoreCase("purge")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(Lang.get(sender, "purge.usage"));
                return true;
            }
            org.bukkit.OfflinePlayer target = dev.chatalyst.nick.NickManager.resolveOffline(args[1]);
            if (target == null) {
                sender.sendMessage(Lang.get(sender, "common.unknown-player", "player", args[1]));
                return true;
            }
            int purged = plugin.getMessageDeleteManager().purge(target.getUniqueId());
            // La purga tambien limpia el historial de canales (el replay no debe revivirlo).
            if (plugin.getChannelHistory() != null) {
                plugin.getChannelHistory().purgePlayer(target.getUniqueId());
            }
            if (plugin.getStaffSecurityListener() != null) {
                plugin.getStaffSecurityListener().audit(sender, "purge", args[1] + " (" + purged + ")");
            }
            sender.sendMessage(Lang.get(sender, purged > 0 ? "purge.done" : "purge.none",
                    "player", args[1], "count", purged));
            return true;
        }
        // Gestion de reglas en caliente: listar y encender/apagar sin editar rules.yml.
        if (args[0].equalsIgnoreCase("rule") || args[0].equalsIgnoreCase("rules")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            var rules = plugin.getRuleEngine();
            if (args.length >= 3 && args[1].equalsIgnoreCase("toggle")) {
                Boolean nowOn = rules.toggleRule(args[2]);
                if (nowOn == null) {
                    sender.sendMessage(Lang.get(sender, "rule-admin.unknown", "name", args[2]));
                } else {
                    if (plugin.getStaffSecurityListener() != null) {
                        plugin.getStaffSecurityListener().audit(sender, "rule-toggle",
                                args[2] + " -> " + (nowOn ? "on" : "off"));
                    }
                    sender.sendMessage(Lang.get(sender,
                            nowOn ? "rule-admin.enabled" : "rule-admin.disabled", "name", args[2]));
                }
                return true;
            }
            // list (por defecto): cada regla clicable para alternarla.
            var list = rules.rules();
            sender.sendMessage(Lang.get(sender, "rule-admin.header", "count", list.size()));
            for (dev.chatalyst.rules.Rule rule : list) {
                boolean off = rules.isRuleDisabled(rule.name);
                net.kyori.adventure.text.Component line = dev.chatalyst.util.MM.full(
                        Lang.raw(sender, off ? "rule-admin.entry-off" : "rule-admin.entry-on",
                                "name", dev.chatalyst.util.MM.escape(rule.name)))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                dev.chatalyst.util.MM.full(Lang.raw(sender, "rule-admin.hover",
                                        "pattern", dev.chatalyst.util.MM.escape(
                                                rule.pattern.pattern())))))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                "/chatalyst rule toggle " + rule.name));
                sender.sendMessage(line);
            }
            sender.sendMessage(Lang.get(sender, "rule-admin.footer"));
            return true;
        }
        // Regiones de chat propias (canal REGION sin WorldGuard): pos1/pos2/add/remove/list.
        if (args[0].equalsIgnoreCase("region")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            handleRegion(sender, args);
            return true;
        }
        // Estado del backend y importacion YAML -> SQL (consola o chatalyst.admin).
        if (args[0].equalsIgnoreCase("storage")) {
            if (!sender.hasPermission("chatalyst.admin")) {
                sender.sendMessage(Lang.get(sender, "common.no-permission"));
                return true;
            }
            handleStorage(sender, label, args);
            return true;
        }
        // Subcomando desconocido: una linea corta con acceso a la ayuda categorizada, no el
        // listado completo amontonado.
        sender.sendMessage(Lang.get(sender, "plugin.unknown-subcommand",
                "input", dev.chatalyst.util.MM.escape(args[0]), "label", label));
        return true;
    }

    /**
     * Nombres de CUENTA de los conectados, para subcomandos que los necesitan (Tebex, purge).
     * Solo a quien ya tiene permiso para el subcomando, y sin los anonimos salvo con
     * /realname: el completer no tenia ni lo uno ni lo otro, asi que cualquiera escribia
     * "/cht purge " y le salian las cuentas reales de todos.
     */
    /** Formatos instalados (formats/*.yml) que empiezan por lo escrito, para /cht sendformat. */
    private java.util.List<String> formatNames(String typed) {
        String p = typed.toLowerCase(java.util.Locale.ROOT);
        java.io.File[] files = new java.io.File(plugin.getDataFolder(), "formats")
                .listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return java.util.List.of();
        return java.util.Arrays.stream(files)
                .map(f -> f.getName().substring(0, f.getName().length() - 4))
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(p))
                .sorted().toList();
    }

    private java.util.List<String> accountNames(CommandSender sender, String typed) {
        String p = typed.toLowerCase(java.util.Locale.ROOT);
        boolean realNames = dev.chatalyst.staff.StaffAccess.has(sender,
                "chatalyst.command.realname");
        var anonymous = plugin.getAnonymousManager();
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(pl -> !dev.chatalyst.util.Vanish.isVanished(pl)
                        || dev.chatalyst.staff.StaffAccess.canSeeVanished(sender))
                .filter(pl -> realNames || anonymous == null
                        || !anonymous.isAnonymous(pl.getUniqueId()))
                .map(org.bukkit.entity.Player::getName)
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(p)).toList();
    }

    // Una importacion a la vez: dos seguidas competirian por el mismo lock exclusivo y la
    // segunda solo repetiria la planificacion de la primera.
    private static final java.util.concurrent.atomic.AtomicBoolean STORAGE_TASK_RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void handleStorage(CommandSender sender, String label, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        boolean dryRun = false;
        boolean includeChanged = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i].toLowerCase(java.util.Locale.ROOT)) {
                case "--dry-run" -> dryRun = true;
                case "--changed" -> includeChanged = true;
                default -> action = "usage";
            }
        }
        if (!action.equals("status") && !action.equals("import")) {
            sender.sendMessage(Lang.get(sender, "storage.usage", "label", label));
            return;
        }
        var storage = plugin.getStorageManager();
        if (!storage.usingDatabase()) {
            sender.sendMessage(Lang.get(sender, "storage.status-header",
                    "backend", Lang.raw(sender, "storage.backend-yaml"),
                    "mode", Lang.raw(sender, "storage.mode-local"),
                    "server", dev.chatalyst.util.MM.escape(storage.serverTag())));
            sender.sendMessage(Lang.get(sender, "storage.database-off"));
            return;
        }
        if (!STORAGE_TASK_RUNNING.compareAndSet(false, true)) {
            sender.sendMessage(Lang.get(sender, "storage.busy"));
            return;
        }
        boolean importNow = action.equals("import");
        var options = new dev.chatalyst.db.YamlSqlImporter.Options(dryRun, includeChanged);
        if (importNow) {
            sender.sendMessage(Lang.get(sender,
                    dryRun ? "storage.dry-run-started" : "storage.import-started"));
        }
        String requested = action;
        // Fuera del hilo principal: con MySQL remoto e inventarios grandes tarda segundos.
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    if (importNow) {
                        var report = storage.importYaml(options);
                        sendImportReport(sender, report);
                        if (!options.dryRun() && report.count(
                                dev.chatalyst.db.YamlSqlImporter.Outcome.IMPORTED) > 0) {
                            // Igual que un reload con reconexion: las segundas caches de
                            // mutes, nicks, toggles y staff se recargan en el hilo global.
                            plugin.getServer().getGlobalRegionScheduler()
                                    .execute(plugin, this::reloadPersistentState);
                            sender.sendMessage(Lang.get(sender, "storage.restart-advice"));
                        }
                    } else {
                        sendStorageStatus(sender, storage.importStatus());
                    }
                } catch (Exception failure) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "/chatalyst storage " + requested + " failed", failure);
                    sender.sendMessage(Lang.get(sender, "storage.failed"));
                } finally {
                    STORAGE_TASK_RUNNING.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            STORAGE_TASK_RUNNING.set(false);
            throw rejected;
        }
    }

    private void sendStorageStatus(CommandSender sender,
                                   java.util.List<dev.chatalyst.db.YamlSqlImporter.NamespaceStatus> rows) {
        var storage = plugin.getStorageManager();
        sender.sendMessage(Lang.get(sender, "storage.status-header",
                "backend", Lang.raw(sender, storage.usingMySql()
                        ? "storage.backend-mysql" : "storage.backend-sqlite"),
                "mode", Lang.raw(sender, storage.networkShared()
                        ? "storage.mode-shared" : "storage.mode-isolated"),
                "server", dev.chatalyst.util.MM.escape(storage.serverTag())));
        if (rows.isEmpty()) {
            sender.sendMessage(Lang.get(sender, "storage.status-none"));
            return;
        }
        for (var row : rows) {
            String state = switch (row.state()) {
                case IMPORTED -> Lang.raw(sender, "storage.state-imported",
                        "date", dev.chatalyst.util.MM.escape(row.importedAt()));
                case CHANGED -> Lang.raw(sender, "storage.state-changed",
                        "date", dev.chatalyst.util.MM.escape(row.importedAt()));
                case PENDING -> Lang.raw(sender, "storage.state-pending");
                case EMPTY -> Lang.raw(sender, "storage.state-empty");
                case INVALID -> Lang.raw(sender, "storage.state-invalid",
                        "problem", dev.chatalyst.util.MM.escape(row.problem()));
            };
            sender.sendMessage(Lang.get(sender, "storage.status-line",
                    "namespace", dev.chatalyst.util.MM.escape(row.yamlNamespace()),
                    "target", dev.chatalyst.util.MM.escape(row.sqlNamespace()),
                    "yaml", row.yamlRows() < 0 ? "?" : row.yamlRows(),
                    "sql", row.sqlRows(), "state", state));
        }
    }

    private void sendImportReport(CommandSender sender, dev.chatalyst.db.YamlSqlImporter.Report report) {
        for (var row : report.namespaces()) {
            String outcome = switch (row.outcome()) {
                case IMPORTED -> Lang.raw(sender, "storage.outcome-imported");
                case WOULD_IMPORT -> Lang.raw(sender, "storage.outcome-would-import");
                case ALREADY_IMPORTED -> Lang.raw(sender, "storage.outcome-already");
                case CHANGED_SKIPPED -> Lang.raw(sender, "storage.outcome-changed");
                case EMPTY -> Lang.raw(sender, "storage.outcome-empty");
                case IGNORED -> Lang.raw(sender, "storage.outcome-ignored");
                case FAILED -> Lang.raw(sender, "storage.outcome-failed",
                        "problem", dev.chatalyst.util.MM.escape(row.problem()));
            };
            sender.sendMessage(Lang.get(sender, "storage.result-line",
                    "namespace", dev.chatalyst.util.MM.escape(row.yamlNamespace()),
                    "outcome", outcome, "new", row.inserted(), "equal", row.equal(),
                    "conflicts", row.conflicts()));
        }
        var done = report.dryRun() ? dev.chatalyst.db.YamlSqlImporter.Outcome.WOULD_IMPORT
                : dev.chatalyst.db.YamlSqlImporter.Outcome.IMPORTED;
        sender.sendMessage(Lang.get(sender,
                report.dryRun() ? "storage.dry-run-summary" : "storage.import-summary",
                "imported", report.count(done),
                "failed", report.count(dev.chatalyst.db.YamlSqlImporter.Outcome.FAILED),
                "conflicts", report.conflicts()));
    }

    // Marcas pos1/pos2 por admin para /cht region add (solo memoria, se pierden al reiniciar).
    private static final java.util.Map<java.util.UUID, org.bukkit.Location[]> REGION_MARKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** /chatalyst sendformat <jugador> <formato> <texto>: en este servidor o en el de la red. */
    private void sendFormat(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chatalyst.command.sendformat")) {
            sender.sendMessage(Lang.get(sender, "common.no-permission"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Lang.get(sender, "plugin.sendformat-usage"));
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        org.bukkit.entity.Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target != null) {
            dev.chatalyst.util.FoliaTasks.entity(plugin, target, p ->
                    dev.chatalyst.chat.FormatSender.deliver(plugin, p, args[2], text));
        } else if (plugin.getProxyManager() != null && plugin.getProxyManager().isEnabled()) {
            plugin.getProxyManager().forwardFormat(args[1], args[2], text);
        } else {
            sender.sendMessage(Lang.get(sender, "common.player-offline"));
            return;
        }
        sender.sendMessage(Lang.get(sender, "plugin.sendformat-done", "player", args[1]));
    }

    private void handleRegion(CommandSender sender, String[] args) {
        var regions = plugin.getChatRegions();
        String sub = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof org.bukkit.entity.Player p)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return;
                }
                // Las marcas de quien ya no esta se sueltan aqui: si no, cada admin que marcaba
                // y se iba sin "add" dejaba su entrada (y la referencia a su mundo) para siempre.
                REGION_MARKS.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
                org.bukkit.Location[] marks = REGION_MARKS.computeIfAbsent(
                        p.getUniqueId(), u -> new org.bukkit.Location[2]);
                marks[sub.equals("pos1") ? 0 : 1] = p.getLocation();
                sender.sendMessage(Lang.get(sender, "region.mark-set", "pos", sub,
                        "x", (int) p.getLocation().getX(), "y", (int) p.getLocation().getY(),
                        "z", (int) p.getLocation().getZ()));
            }
            case "add" -> {
                if (!(sender instanceof org.bukkit.entity.Player p)) {
                    sender.sendMessage(Lang.get(sender, "common.players-only"));
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(Lang.get(sender, "region.usage"));
                    return;
                }
                org.bukkit.Location[] marks = REGION_MARKS.get(p.getUniqueId());
                if (marks == null || marks[0] == null || marks[1] == null
                        || marks[0].getWorld() == null
                        || !marks[0].getWorld().equals(marks[1].getWorld())) {
                    sender.sendMessage(Lang.get(sender, "region.need-marks"));
                    return;
                }
                try {
                    // 4o argumento opcional: canal de auto-switch al entrar en la region.
                    String channel = args.length >= 4 ? args[3] : "";
                    plugin.getChatRegions().add(dev.chatalyst.channel.ChatRegions.Region.of(
                            args[2].toLowerCase(java.util.Locale.ROOT), marks[0].getWorld().getName(),
                            marks[0].getX(), marks[0].getY(), marks[0].getZ(),
                            marks[1].getX(), marks[1].getY(), marks[1].getZ(), channel));
                    REGION_MARKS.remove(p.getUniqueId());
                    if (plugin.getStaffSecurityListener() != null) {
                        plugin.getStaffSecurityListener().audit(sender, "region-add", args[2]);
                    }
                    sender.sendMessage(Lang.get(sender, "region.added", "name", args[2]));
                } catch (java.io.IOException ex) {
                    sender.sendMessage(Lang.get(sender, "region.save-error", "error", ex.getMessage()));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(Lang.get(sender, "region.usage"));
                    return;
                }
                try {
                    boolean had = regions.remove(args[2]);
                    if (had && plugin.getStaffSecurityListener() != null) {
                        plugin.getStaffSecurityListener().audit(sender, "region-remove", args[2]);
                    }
                    sender.sendMessage(Lang.get(sender, had ? "region.removed" : "region.unknown",
                            "name", args[2]));
                } catch (java.io.IOException ex) {
                    sender.sendMessage(Lang.get(sender, "region.save-error", "error", ex.getMessage()));
                }
            }
            default -> {
                var all = regions.all();
                sender.sendMessage(Lang.get(sender, "region.list-header", "count", all.size()));
                for (var r : all) {
                    sender.sendMessage(Lang.get(sender, "region.list-entry",
                            "name", r.name() + (r.channel().isEmpty() ? "" : " → " + r.channel()),
                            "world", r.world(),
                            "min", (int) r.minX() + "," + (int) r.minY() + "," + (int) r.minZ(),
                            "max", (int) r.maxX() + "," + (int) r.maxY() + "," + (int) r.maxZ()));
                }
                sender.sendMessage(Lang.get(sender, "region.list-hint"));
            }
        }
    }

    // Tercera columna: clave lang (help.cmd-*) — la descripcion sale en el idioma de quien mira.
    private static final String[][] CHAT = {
            {"/channel", "chatalyst.command.channel", "help.cmd-channel"},
            {"/frequency", "chatalyst.command.frequency", "help.cmd-frequency"},
            {"/f", "chatalyst.command.frequency", "help.cmd-frequency-chat"},
            {"/msg", "chatalyst.command.msg", "help.cmd-msg"},
            {"/reply", "chatalyst.command.msg", "help.cmd-reply"},
            {"/ignore", "chatalyst.command.ignore", "help.cmd-ignore"},
            {"/inbox", "chatalyst.command.mail", "help.cmd-inbox"},
            {"/toggle", "chatalyst.command.toggle", "help.cmd-toggle"},
            {"/motd", "chatalyst.command.motd", "help.cmd-motd"},
            {"/chatcolor", "chatalyst.command.chatcolor", "help.cmd-chatcolor"},
            {"/book", "chatalyst.command.book", "help.cmd-book"},
            {"/report", "chatalyst.command.report", "help.cmd-report"},
            {"/ticket", "chatalyst.command.ticket", "help.cmd-ticket"},
            {"/afk", "chatalyst.command.afk", "help.cmd-afk"},
            {"/me", "chatalyst.command.me", "help.cmd-me"},
            {"/baltop", "chatalyst.command.baltop", "help.cmd-baltop"},
            {"/market", "chatalyst.command.market", "help.cmd-market"},
            {"/trade", "chatalyst.command.trade", "help.cmd-trade"},
            {"/nick", "chatalyst.nick", "help.cmd-nick"},
            {"/realname", "chatalyst.command.realname", "help.cmd-realname"},
            {"/nickcolor", "chatalyst.nick.color", "help.cmd-nickcolor"},
            {"/tag", "chatalyst.tag", "help.cmd-tag"},
            {"/itemname", "chatalyst.command.itemname", "help.cmd-itemname"},
            {"/events", "chatalyst.command.events", "help.cmd-events"},
            {"/cht ranks", "chatalyst.command.ranks", "help.cmd-ranks"},
            {"/cht emojis", "chatalyst.chat.emoji", "help.cmd-emojis"},
    };
    private static final String[][] MOD = {
            {"/reports", "chatalyst.report.manage", "help.cmd-reports"},
            {"/staffchat", "chatalyst.channel.staff", "help.cmd-staffchat"},
            {"/support", "chatalyst.support.staff", "help.cmd-support"},
            {"/players", "chatalyst.command.players", "help.cmd-players"},
            {"/mute", "chatalyst.command.mute", "help.cmd-mute"},
            {"/unmute", "chatalyst.command.mute", "help.cmd-unmute"},
            {"/clearchat", "chatalyst.command.clearchat", "help.cmd-clearchat"},
            {"/chatlock", "chatalyst.command.chatlock", "help.cmd-chatlock"},
            {"/chatunlock", "chatalyst.command.chatlock", "help.cmd-chatunlock"},
            {"/slowmode", "chatalyst.command.slowmode", "help.cmd-slowmode"},
            {"/warnings", "chatalyst.command.warnings", "help.cmd-warnings"},
            {"/spy", "chatalyst.spy", "help.cmd-spy"},
            {"/chatlog", "chatalyst.command.log", "help.cmd-chatlog"},
            {"/say", "chatalyst.command.say", "help.cmd-say"},
            {"/announce", "chatalyst.command.announce", "help.cmd-announce"},
            {"/broadcast", "chatalyst.command.broadcast", "help.cmd-broadcast"},
            {"/formatedit", "chatalyst.command.editor", "help.cmd-formatedit"},
    };
    // Solo se muestran cuando la suite opt-in esta activa: apagada ni siquiera se registran.
    private static final String[][] STAFF_SUITE = {
            {"/staff", "chatalyst.command.staff", "help.cmd-staff"},
            {"/kick", "chatalyst.command.kick", "help.cmd-kick"},
            {"/warn", "chatalyst.command.warn", "help.cmd-warn"},
            {"/note", "chatalyst.staff.notes", "help.cmd-note"},
            {"/ban", "chatalyst.command.ban", "help.cmd-ban"},
            {"/tempban", "chatalyst.command.ban", "help.cmd-tempban"},
            {"/unban", "chatalyst.command.ban", "help.cmd-unban"},
            {"/banip", "chatalyst.command.banip", "help.cmd-banip"},
            {"/unbanip", "chatalyst.command.banip", "help.cmd-unbanip"},
            {"/freeze", "chatalyst.command.freeze", "help.cmd-freeze"},
            {"/unfreeze", "chatalyst.command.freeze", "help.cmd-unfreeze"},
            {"/vanish", "chatalyst.command.vanish", "help.cmd-vanish"},
            {"/invsee", "chatalyst.command.invsee", "help.cmd-invsee"},
            {"/echest", "chatalyst.command.echest", "help.cmd-echest"},
            {"/seen", "chatalyst.command.seen", "help.cmd-seen"},
            {"/check", "chatalyst.command.check", "help.cmd-check"},
            {"/tpto", "chatalyst.command.tp", "help.cmd-tpto"},
            {"/tphere", "chatalyst.command.tp", "help.cmd-tphere"},
    };
    private static final String[][] ADMIN = {
            {"/cht gui", "chatalyst.admin", "help.cmd-gui"},
            {"/cht reload", "chatalyst.admin", "help.cmd-reload"},
            {"/cht permissions", "chatalyst.admin", "help.cmd-permissions"},
            {"/cht doctor", "chatalyst.admin", "help.cmd-doctor"},
            {"/cht perf", "chatalyst.admin", "help.cmd-perf"},
            {"/cht purge", "chatalyst.admin", "help.cmd-purge"},
            {"/cht rule", "chatalyst.admin", "help.cmd-rule"},
            {"/cht region", "chatalyst.admin", "help.cmd-region"},
            {"/cht storage", "chatalyst.admin", "help.cmd-storage"},
    };

    private static final int COMMANDS_PER_PAGE = 10;

    private record HelpEntry(String category, String command, String permission,
                             String description) {
    }

    private void sendHelp(CommandSender s, int requestedPage) {
        java.util.List<HelpEntry> visible = new java.util.ArrayList<>();
        collectVisible(s, visible, "help.category-chat", CHAT);
        collectVisible(s, visible, "help.category-moderation", MOD);
        if (plugin.getStaffModeration() != null && plugin.getStaffModeration().enabled()) {
            collectVisible(s, visible, "help.category-moderation", STAFF_SUITE);
        }
        collectVisible(s, visible, "help.category-admin", ADMIN);
        // Con la tienda apagada /ranks no existe: no anunciarla en la ayuda.
        boolean shopOpen = plugin.getTebexShop() != null && plugin.getTebexShop().enabled();
        if (!shopOpen) visible.removeIf(e -> "help.cmd-ranks".equals(e.description()));

        int pages = Math.max(1, (visible.size() + COMMANDS_PER_PAGE - 1) / COMMANDS_PER_PAGE);
        int page = Math.min(Math.max(1, requestedPage), pages);
        int fromIndex = (page - 1) * COMMANDS_PER_PAGE;
        var slice = visible.subList(fromIndex,
                Math.min(fromIndex + COMMANDS_PER_PAGE, visible.size()));

        s.sendMessage(dev.chatalyst.util.MM.full(Lang.raw(s, "help.title")));
        s.sendMessage(Lang.get(s, "help.summary", "count", visible.size(),
                "page", page, "pages", pages));

        String lastCategory = null;
        for (HelpEntry entry : slice) {
            if (!entry.category().equals(lastCategory)) {
                lastCategory = entry.category();
                s.sendMessage(dev.chatalyst.util.MM.full(Lang.raw(s, "help.category-line",
                        "category", Lang.raw(s, lastCategory))));
            }
            net.kyori.adventure.text.Component line = dev.chatalyst.util.MM.full(
                            Lang.raw(s, "help.command-line",
                                    "command", entry.command(),
                                    "description", Lang.raw(s, entry.description())))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            Lang.get(s, "help.click-to-insert")))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(
                            entry.command() + " "));
            s.sendMessage(line);
        }
        s.sendMessage(paginationFooter(s, "/chatalyst commands", page, pages));
    }

    private static void collectVisible(CommandSender s, java.util.List<HelpEntry> target,
                                       String category, String[][] cmds) {
        for (String[] c : cmds) {
            if (!s.hasPermission(c[1])) continue;
            target.add(new HelpEntry(category, c[0], c[1], c[2]));
        }
    }

    private static final int PERMS_PER_PAGE = 12;

    /**
     * Muestra los permisos de Chatalyst agrupados por categoria y PAGINADOS (el chat vanilla
     * no puede con 100+ lineas). Cada permiso es clicable: al pincharlo, rellena en el chat
     * el comando para concederlo (LuckPerms por defecto). El hover muestra la descripcion,
     * el default y si el que mira ya lo tiene. Filtro por categoria opcional; los botones
     * « / » del pie cambian de pagina.
     */
    private void sendPermissions(CommandSender s, String filter, int page) {
        // Plantilla del comando a rellenar al hacer click. {permission} = el nodo.
        String grantTemplate = plugin.getConfig().getString("Permissions_List.Grant_Command",
                "/lp group <group> permission set {permission} true");

        // Lista plana (categoria, permiso) ordenada por categoria y nombre, ya filtrada.
        java.util.Map<String, java.util.List<org.bukkit.permissions.Permission>> byCat =
                new java.util.TreeMap<>();
        int total = 0;
        for (org.bukkit.permissions.Permission perm : plugin.getPluginMeta().getPermissions()) {
            String[] parts = perm.getName().split("\\.");
            String cat = parts.length >= 3 ? parts[1] : "general";
            byCat.computeIfAbsent(cat, k -> new java.util.ArrayList<>()).add(perm);
            total++;
        }
        java.util.List<java.util.Map.Entry<String, org.bukkit.permissions.Permission>> flat =
                new java.util.ArrayList<>();
        for (var entry : byCat.entrySet()) {
            if (filter != null && !entry.getKey().equals(filter)) continue;
            entry.getValue().sort(java.util.Comparator.comparing(org.bukkit.permissions.Permission::getName));
            for (var perm : entry.getValue()) flat.add(java.util.Map.entry(entry.getKey(), perm));
        }
        if (flat.isEmpty()) {
            s.sendMessage(Lang.get(s, filter == null
                    ? "permissions-list.empty" : "permissions-list.empty-category",
                    "category", filter == null ? "" : filter));
            return;
        }

        int pages = (flat.size() + PERMS_PER_PAGE - 1) / PERMS_PER_PAGE;
        page = Math.min(Math.max(1, page), pages);
        int fromIndex = (page - 1) * PERMS_PER_PAGE;
        var slice = flat.subList(fromIndex, Math.min(fromIndex + PERMS_PER_PAGE, flat.size()));

        s.sendMessage(Lang.get(s, "permissions-list.title",
                "count", filter != null ? flat.size() : total,
                "page", page, "pages", pages));

        String lastCat = null;
        for (var item : slice) {
            if (!item.getKey().equals(lastCat)) {
                lastCat = item.getKey();
                var all = byCat.get(lastCat);
                long owned = all.stream().filter(p -> s.hasPermission(p.getName())).count();
                s.sendMessage(Lang.get(s, "permissions-list.category-line",
                        "category", capitalize(lastCat), "owned", owned, "total", all.size()));
            }
            var perm = item.getValue();
            boolean has = s.hasPermission(perm.getName());
            String desc = perm.getDescription() != null ? perm.getDescription() : "";
            String grant = grantTemplate.replace("{permission}", perm.getName());
            // El estado se muestra como texto; a la derecha va el default (on/op/off).
            net.kyori.adventure.text.Component line = dev.chatalyst.util.MM.full(
                    Lang.raw(s, has ? "permissions-list.entry-owned"
                                    : "permissions-list.entry-missing",
                            "permission", perm.getName(),
                            "default", defaultChip(s, perm.getDefault())))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            dev.chatalyst.util.MM.full(Lang.raw(s,
                                    has ? "permissions-list.hover-owned"
                                            : "permissions-list.hover-missing",
                                    "description", desc,
                                    "default", defaultChip(s, perm.getDefault())))))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(grant));
            s.sendMessage(line);
        }

        // Pie: anterior | pagina | siguiente clicables + pista de filtro.
        String base = "/chatalyst permissions" + (filter != null ? " " + filter : "");
        s.sendMessage(paginationFooter(s, base, page, pages));
        if (filter == null) {
            s.sendMessage(Lang.get(s, "permissions-list.filter-hint"));
        }
    }

    /** Barra de paginacion de chat. Los botones ejecutan la pagina: no hay que escribirla. */
    private static net.kyori.adventure.text.Component paginationFooter(
            CommandSender s, String baseCommand, int page, int pages) {
        var footer = net.kyori.adventure.text.Component.text();
        if (page > 1) {
            footer.append(dev.chatalyst.util.MM.full(Lang.raw(s, "pagination.previous"))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            baseCommand + " " + (page - 1)))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            Lang.get(s, "pagination.hover-page", "page", page - 1))));
            footer.append(net.kyori.adventure.text.Component.space());
        }
        footer.append(Lang.get(s, "pagination.page", "page", page, "pages", pages));
        if (page < pages) {
            footer.append(net.kyori.adventure.text.Component.space());
            footer.append(dev.chatalyst.util.MM.full(Lang.raw(s, "pagination.next"))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            baseCommand + " " + (page + 1)))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            Lang.get(s, "pagination.hover-page", "page", page + 1))));
        }
        return footer.build();
    }

    private static int numericPage(String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    // Default del permiso como chip corto y coloreado: on (todos), op (staff), off (nadie).
    private static String defaultChip(CommandSender sender,
                                      org.bukkit.permissions.PermissionDefault def) {
        return switch (def) {
            case TRUE -> Lang.raw(sender, "permissions-list.default-on");
            case OP -> Lang.raw(sender, "permissions-list.default-op");
            default -> Lang.raw(sender, "permissions-list.default-off");
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Muestra la lista de emojis :nombre: disponibles; clic en uno lo pone en el chat. */
    private void sendEmojis(CommandSender s) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>(
                dev.chatalyst.chat.EmojiRegistry.defaults());
        var custom = plugin.getConfig().getConfigurationSection("Chat.Emojis.Custom");
        if (custom != null) {
            for (String k : custom.getKeys(false)) {
                String v = custom.getString(k);
                if (v != null && !v.isEmpty()) map.put(k.toLowerCase(java.util.Locale.ROOT), v);
            }
        }
        s.sendMessage(Lang.get(s, "emojis.title", "count", map.size()));
        // Varios emojis por linea, cada uno clicable (rellena :nombre: en el chat).
        var line = net.kyori.adventure.text.Component.text();
        int inRow = 0;
        for (var e : map.entrySet()) {
            net.kyori.adventure.text.Component chip = net.kyori.adventure.text.Component
                    .text(" " + e.getValue() + " ", net.kyori.adventure.text.format.NamedTextColor.WHITE)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(":" + e.getKey() + ":"))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            Lang.get(s, "emojis.hover", "name", e.getKey())));
            line.append(chip);
            if (++inRow >= 10) {
                s.sendMessage(line.build());
                line = net.kyori.adventure.text.Component.text();
                inRow = 0;
            }
        }
        if (inRow > 0) s.sendMessage(line.build());
    }

    /** TPS capado a 20 (Paper puede devolver un pelin mas) con 1 decimal, para /chatalyst perf. */
    private static String tps1(double tps) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.min(20.0, tps));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            return Stream.of("help", "commands", "reload", "version", "permissions", "emojis",
                            "doctor", "perf", "features", "purge", "rule", "region", "gui",
                            "ranks", "shop", "tebex", "store", "storage", "sendformat")
                    .filter(s -> s.startsWith(p)).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("sendformat")) {
            if (!sender.hasPermission("chatalyst.command.sendformat")) return java.util.List.of();
            if (args.length == 2) return accountNames(sender, args[1]);
            if (args.length == 3) return formatNames(args[2]);
            // Desde el 4o argumento es texto libre: la guia de usage muestra <texto>.
            return java.util.List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("store")) {
            String p = args[1].toLowerCase(java.util.Locale.ROOT);
            return Stream.of("give", "status").filter(value -> value.startsWith(p)).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("store")
                && args[1].equalsIgnoreCase("give")) {
            String p = args[3].toLowerCase(java.util.Locale.ROOT);
            java.util.List<String> products = plugin.getStoreService() == null
                    ? java.util.List.of() : plugin.getStoreService().configuredProductIds();
            return products.stream().filter(value -> value.startsWith(p)).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("storage")) {
            if (!sender.hasPermission("chatalyst.admin")) return java.util.List.of();
            String p = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
            Stream<String> options = args.length == 2 ? Stream.of("status", "import")
                    : args[1].equalsIgnoreCase("import") ? Stream.of("--dry-run", "--changed")
                    : Stream.empty();
            return options.filter(value -> value.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("doctor")) {
            return Stream.of("discord")
                    .filter(value -> value.startsWith(args[1].toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tebex")) {
            String p = args[1].toLowerCase(java.util.Locale.ROOT);
            return Stream.of("gui", "status", "grant", "revoke")
                    .filter(value -> value.startsWith(p)).toList();
        }
        // El destinatario. Tebex manda {id} y aqui no hay nada que completar, pero una entrega
        // a mano (un pago cobrado fuera de la tienda) la escribe una persona, y el nombre del
        // jugador conectado es lo unico que esa persona tiene a mano.
        if (args.length == 3 && args[0].equalsIgnoreCase("tebex")
                && (args[1].equalsIgnoreCase("grant")
                || args[1].equalsIgnoreCase("revoke"))) {
            if (!dev.chatalyst.tebex.TebexRankService.esConsola(sender)
                    && !sender.hasPermission("chatalyst.admin.tebex")) {
                return java.util.List.of();
            }
            return accountNames(sender, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("tebex")
                && (args[1].equalsIgnoreCase("grant")
                || args[1].equalsIgnoreCase("revoke"))) {
            String p = args[3].toLowerCase(java.util.Locale.ROOT);
            java.util.List<String> ranks = plugin.getTebexRanks() == null
                    ? java.util.List.of() : plugin.getTebexRanks().configuredRankIds();
            java.util.Collection<String> options = ranks.isEmpty()
                    ? dev.chatalyst.hook.LuckPermsHook.loadedGroups() : ranks;
            return options.stream()
                    .filter(value -> value.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
            if (!sender.hasPermission("chatalyst.admin")) return java.util.List.of();
            return accountNames(sender, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("rule") || args[0].equalsIgnoreCase("rules"))) {
            return Stream.of("list", "toggle").filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("region")) {
            return Stream.of("pos1", "pos2", "add", "remove", "list")
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("region") && args[1].equalsIgnoreCase("remove")) {
            String p = args[2].toLowerCase();
            return plugin.getChatRegions().all().stream()
                    .map(dev.chatalyst.channel.ChatRegions.Region::name)
                    .filter(n -> n.startsWith(p)).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("rule") || args[0].equalsIgnoreCase("rules"))
                && args[1].equalsIgnoreCase("toggle")) {
            String p = args[2].toLowerCase();
            return plugin.getRuleEngine().rules().stream().map(r -> r.name)
                    .filter(n -> n.toLowerCase().startsWith(p)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("permissions") || args[0].equalsIgnoreCase("perms"))) {
            String p = args[1].toLowerCase();
            return Stream.of("chat", "command", "spy", "bypass", "report", "notify", "nick", "color", "channel")
                    .filter(c -> c.startsWith(p)).toList();
        }
        return List.of();
    }

    /**
     * Los SqlStore ya cambiaron sus caches de forma atomica. Aqui se actualizan las segundas
     * caches que algunos managers mantienen por rendimiento (mutes, nicks, toggles y staff).
     */
    private void reloadPersistentState() {
        if (plugin.getMuteManager() != null) plugin.getMuteManager().load();
        if (plugin.getStaffModeration() != null) {
            plugin.getStaffModeration().reloadPersistentState();
        }
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin, player -> {
            if (plugin.getToggleManager() != null) plugin.getToggleManager().loadPlayer(player);
            if (plugin.getPrivateMessages() != null) {
                plugin.getPrivateMessages().reloadPlayer(player);
            }
            if (plugin.getChatColorManager() != null) {
                plugin.getChatColorManager().reloadPlayer(player);
            }
            if (plugin.getNickManager() != null) plugin.getNickManager().reloadPlayer(player);
        });
    }
}
