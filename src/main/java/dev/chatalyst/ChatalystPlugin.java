package dev.chatalyst;

import dev.chatalyst.channel.ChannelCommand;
import dev.chatalyst.channel.ChannelManager;
import dev.chatalyst.chat.ChatListener;
import dev.chatalyst.command.ChatalystCommand;
import dev.chatalyst.command.PlayerListCommand;
import dev.chatalyst.config.ConfigManager;
import dev.chatalyst.discord.DiscordBot;
import dev.chatalyst.discord.DiscordListener;
import dev.chatalyst.gui.ChatColorCommand;
import dev.chatalyst.gui.ChatColorListener;
import dev.chatalyst.gui.ChatColorManager;
import dev.chatalyst.gui.FormatEditChatListener;
import dev.chatalyst.gui.FormatEditCommand;
import dev.chatalyst.gui.FormatEditManager;
import dev.chatalyst.gui.MenuListener;
import dev.chatalyst.chat.ChatRenderer;
import dev.chatalyst.chat.MessageDeleteManager;
import dev.chatalyst.announce.AnnounceCommands;
import dev.chatalyst.announce.Announcer;
import dev.chatalyst.antispam.AntiSpam;
import dev.chatalyst.antispam.ChatGuardListener;
import dev.chatalyst.antispam.Grammar;
import dev.chatalyst.messages.MessagesListener;
import dev.chatalyst.messages.ServerMessages;
import dev.chatalyst.moderation.ChatModCommands;
import dev.chatalyst.moderation.ChatModListener;
import dev.chatalyst.moderation.ChatModeration;
import dev.chatalyst.moderation.MuteChatListener;
import dev.chatalyst.moderation.MuteCommands;
import dev.chatalyst.moderation.MuteManager;
import dev.chatalyst.warnings.WarningsCommand;
import dev.chatalyst.pm.PmCommands;
import dev.chatalyst.pm.PrivateMessages;
import dev.chatalyst.proxy.ProxyManager;
import dev.chatalyst.mail.MailComposeManager;
import dev.chatalyst.mail.MailManager;
import dev.chatalyst.mail.MailNotifier;
import dev.chatalyst.mention.MentionListener;
import dev.chatalyst.nick.NickCommand;
import dev.chatalyst.nick.NickListener;
import dev.chatalyst.nick.NickManager;
import dev.chatalyst.nick.RealNameCommand;
import dev.chatalyst.hook.Placeholders;
import dev.chatalyst.rules.RuleChatListener;
import dev.chatalyst.rules.RuleEngine;
import dev.chatalyst.tablist.NametagManager;
import dev.chatalyst.tablist.TabListManager;
import dev.chatalyst.toggle.ToggleCommand;
import dev.chatalyst.toggle.ToggleManager;
import dev.chatalyst.warnings.WarningPoints;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChatalystPlugin extends JavaPlugin {

    private static ChatalystPlugin instance;
    private ConfigManager configManager;
    private ChannelManager channelManager;
    private PrivateMessages privateMessages;
    private RuleEngine ruleEngine;
    private dev.chatalyst.antibot.AntiBotManager antiBotManager;
    private dev.chatalyst.log.LogManager logManager;
    private dev.chatalyst.tabcomplete.TabCompleteListener tabCompleteListener;
    private dev.chatalyst.newcomer.NewcomerListener newcomerListener;
    private dev.chatalyst.cooldown.CommandCooldownListener commandCooldownListener;
    private dev.chatalyst.security.ChatSanitizerListener chatSanitizerListener;
    private dev.chatalyst.security.CommandBlockerListener commandBlockerListener;
    private dev.chatalyst.security.UnknownCommandListener unknownCommandListener;
    private dev.chatalyst.channel.RegionPartyTracker regionPartyTracker;
    private MuteManager muteManager;
    private ServerMessages serverMessages;
    private dev.chatalyst.messages.FancyJoin fancyJoin;
    private MessagesListener messagesListener;
    private Announcer announcer;
    private ToggleManager toggleManager;
    private final dev.chatalyst.translate.TranslationService translation =
            new dev.chatalyst.translate.TranslationService();
    private WarningPoints warningPoints;
    private AntiSpam antiSpam;
    private dev.chatalyst.antispam.SpamPunisher spamPunisher;
    private dev.chatalyst.swear.SwearFilter swearFilter;
    private dev.chatalyst.book.BookManager bookManager;
    private dev.chatalyst.tagfx.TagEffects tagEffects;
    private dev.chatalyst.script.ScriptManager scriptManager;
    private dev.chatalyst.db.StorageManager storageManager;
    private Grammar grammar;
    private dev.chatalyst.autoreply.AutoResponder autoResponder;
    private dev.chatalyst.events.EventsManager eventsManager;
    private MailManager mailManager;
    private MailComposeManager mailComposeManager;
    private MailNotifier mailNotifier;
    private NickManager nickManager;
    private ChatModeration chatModeration;
    private ProxyManager proxyManager;
    private dev.chatalyst.network.NetworkMonitor networkMonitor;
    private dev.chatalyst.announce.AnnounceDraftManager announceDraftManager;
    private dev.chatalyst.channel.ChannelRouterListener channelRouter;
    private dev.chatalyst.channel.StaffMentionRouter staffMentionRouter;
    private dev.chatalyst.support.SupportConversationManager supportConversations;
    private dev.chatalyst.staff.StaffDirectoryManager staffDirectory;
    private dev.chatalyst.staff.NetworkPlayerDirectory networkPlayerDirectory;
    private dev.chatalyst.hook.TradePluginsHook tradePluginsHook;
    private dev.chatalyst.market.MarketManager marketManager;
    private dev.chatalyst.market.TradeManager tradeManager;
    private dev.chatalyst.channel.ChannelHistory channelHistory;
    private dev.chatalyst.channel.FrequencyManager frequencyManager;
    private dev.chatalyst.chat.GroupOverrides groupOverrides;
    private dev.chatalyst.tag.TagCatalog tagCatalog;
    private dev.chatalyst.tag.TagManager tagManager;
    private dev.chatalyst.tag.TitleCounters titleCounters;
    private dev.chatalyst.tag.TagAdminPrompt tagAdminPrompt;
    private dev.chatalyst.api.ChatalystApi chatalystApi;
    private dev.chatalyst.tablist.NametagTitleManager nametagTitleManager;
    private dev.chatalyst.tablist.NametagTitleEditor nametagTitleEditor;
    private dev.chatalyst.marriage.MarriageModule marriage;
    private dev.chatalyst.channel.ChatRegions chatRegions;
    private ChatColorManager chatColorManager;
    private TabListManager tabListManager;
    private NametagManager nametagManager;
    private dev.chatalyst.tablist.FancyNametagManager fancyNametagManager;
    private MessageDeleteManager messageDeleteManager;
    private DiscordBot discordBot;
    // Discord-links + Tebex + tienda: once servicios que solo se entienden juntos, en
    // dev.chatalyst.monetisation. Los getters de abajo delegan, asi que quien los usaba no
    // se entera del cambio.
    private dev.chatalyst.monetisation.MonetisationModule monetisation;
    private dev.chatalyst.command.RuntimeCommandRegistrar storeCommands;
    private dev.chatalyst.alias.CommandAliasManager commandAliases;
    private dev.chatalyst.discord.StaffNotifier staffNotifier;
    private dev.chatalyst.chat.HeldItemTracker heldItemTracker;
    private dev.chatalyst.containerlog.ContainerLogManager containerLogManager;
    private dev.chatalyst.combatlog.CombatLogManager combatLogManager;
    private dev.chatalyst.chat.InventoryShareManager inventoryShareManager;
    private dev.chatalyst.anonymous.AnonymousManager anonymousManager;
    private dev.chatalyst.vanilla.VanillaMessages vanillaMessages;
    private dev.chatalyst.vanilla.MessageReplacements messageReplacements;
    private dev.chatalyst.vanilla.ServerBrand serverBrand;
    private dev.chatalyst.update.UpdateChecker updateChecker;
    private dev.chatalyst.report.ReportManager reportManager;
    private dev.chatalyst.report.ReportActions reportActions;
    private dev.chatalyst.report.TicketConversation ticketConversation;
    private dev.chatalyst.afk.AfkManager afkManager;
    private dev.chatalyst.giveaway.GiveawayModule giveaways;
    private dev.chatalyst.economy.BalanceTop balanceTop;
    private dev.chatalyst.hook.VoiceIntegration voiceIntegration;
    private dev.chatalyst.hook.DynmapIntegration dynmapIntegration;
    private dev.chatalyst.hook.PacketEventsIntegration packetEventsIntegration;
    private dev.chatalyst.security.StaffSecurityListener staffSecurityListener;
    private dev.chatalyst.staff.StaffModerationModule staffModeration;
    private dev.chatalyst.discord.DiscordPunishmentRoles punishmentRoles;
    // Modulos con ciclo de vida propio (migracion gradual del monolito). Se encienden en
    // orden dentro de onEnable() y se apagan en orden inverso en onDisable().
    private dev.chatalyst.module.ModuleRegistry modules;

    public static ChatalystPlugin get() {
        return instance;
    }

    public ToggleManager getToggleManager() {
        return toggleManager;
    }

    public dev.chatalyst.translate.TranslationService getTranslation() {
        return translation;
    }

    private dev.chatalyst.license.LicenseManager.License license;
    private dev.chatalyst.license.ActivationReporter activationReporter;

    /**
     * Config is split across per-domain files on disk (chat.yml, moderation.yml, economy.yml,
     * players.yml, bot.yml, network.yml, integrations.yml). We keep exposing ONE merged config
     * so the rest of the plugin uses {@code getConfig()} unchanged: reload merges the files,
     * save distributes changes back to them.
     */
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        dev.chatalyst.config.ConfigFiles.merge(this, getConfig());
        dev.chatalyst.chat.ChatRankTags.load(getConfig());
    }

    @Override
    public void saveConfig() {
        dev.chatalyst.config.ConfigFiles.save(this, getConfig());
    }

    /** Same split-config save as {@link #saveConfig()}, but reports persistence failure to GUIs. */
    public boolean saveConfigChecked() {
        return dev.chatalyst.config.ConfigFiles.save(this, getConfig());
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        // Validate and recover YAML BEFORE any upgrader/merger can interpret invalid input as
        // an empty configuration and overwrite the owner's file.
        dev.chatalyst.config.ConfigDoctor.check(this);
        if (dev.chatalyst.config.ConfigDoctor.storageConfigUnreadable()) {
            // El doctor ya explico el porque en la consola. Arrancar aqui seria abrir el
            // almacen de fabrica y esconder todos los datos del servidor.
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Upgrade the config BEFORE it is split. ConfigUpgrader's per-version migrations assume a
        // single config.yml (e.g. moving Channels.List.local from RANGED to FREQUENCY), so they
        // must run while every section still lives there. ensureSplit then distributes the
        // already-migrated values into the per-domain files.
        dev.chatalyst.config.ConfigUpgrader.upgrade(this);
        // Create the per-domain files on first run (or migrate an existing monolithic config.yml).
        dev.chatalyst.config.ConfigFiles.ensureSplit(this);
        // Un archivo en disco siempre gana al del jar, asi que una instalacion antigua conserva
        // los cierres de color que salen impresos en pantalla por mucho que se arreglen aqui.
        // Se hace despues de repartir los archivos, para alcanzar tambien los recien creados.
        // Y se relee: ConfigDoctor ya toco getConfig() mas arriba, asi que la configuracion
        // esta en memoria con el texto ANTIGUO. Sin esto la reparacion no se veria hasta el
        // arranque siguiente — el peor resultado posible, porque el log diria que se arreglo.
        if (dev.chatalyst.config.OrphanTagRepair.run(this) > 0) reloadConfig();

        // Integridad: si alguien reempaqueto el jar para saltarse la licencia, el manifiesto
        // firmado no cuadra y el plugin no arranca (no toca la maquina, solo no funciona).
        if (!dev.chatalyst.license.IntegrityChecker.verify()) {
            getLogger().severe("==============================================================");
            getLogger().severe(" Integrity check failed: this jar has been modified.");
            getLogger().severe(" Use the original file from your purchase. This copy is blocked.");
            getLogger().severe(" Support: Discord devkazuo");
            getLogger().severe("==============================================================");
            // Aviso best-effort al autor (via relay) de que corrio una copia modificada, con IP.
            dev.chatalyst.license.ActivationReporter.fireTamper(this);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Licencia. Dos vias:
        //  (1) Clave firmada Ed25519 en config (owner / regalos / pruebas): offline, sin internet.
        //  (2) Watermark del marketplace incrustado en el jar (compradores): verificacion online
        //      FAIL-CLOSED (sin respuesta del marketplace el plugin NO arranca; no hay cache
        //      local falsificable). Ver MarketplaceVerifier.
        getLogger().info("Validating license...");
        license = dev.chatalyst.license.LicenseManager.verify(getConfig().getString("License.Key", ""));
        if (license != null) {
            getLogger().info("License valid (signed key). Licensed to " + license.buyer()
                    + " (" + license.id() + "). Enjoy Chatalyst!");
        } else {
            dev.chatalyst.license.MarketplaceVerifier.Result result =
                    dev.chatalyst.license.MarketplaceVerifier.check(this);
            // FAIL-CLOSED: solo arranca si la compra se verifica online. Sin internet no arranca.
            if (result.status() != dev.chatalyst.license.MarketplaceVerifier.Status.VALID) {
                boolean invalid = result.status()
                        == dev.chatalyst.license.MarketplaceVerifier.Status.INVALID;
                getLogger().severe("==============================================================");
                if (invalid) {
                    getLogger().severe(" This copy is not a valid purchase (" + result.marketplace() + ").");
                    getLogger().severe(" If you bought Chatalyst, re-download it from your purchases.");
                } else {
                    getLogger().severe(" Could not verify your license online (" + result.detail() + ").");
                    getLogger().severe(" Chatalyst needs an internet connection to start.");
                    getLogger().severe(" Make sure the server can reach the marketplace and restart.");
                }
                getLogger().severe(" Support: Discord devkazuo");
                getLogger().severe("==============================================================");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            license = result.toLicense();
            getLogger().info("License verified via " + result.marketplace()
                    + ". Licensed to " + license.buyer() + ". Enjoy Chatalyst!");
        }

        // Aviso de activacion al autor (via su relay, ver license-tool/relay) y comprobacion de
        // revocacion. Va en su propio hilo y es fail-open: sin internet el plugin arranca igual.
        activationReporter = new dev.chatalyst.license.ActivationReporter(this, license,
                getConfig().getString("License.Key", ""));
        activationReporter.start();

        dev.chatalyst.lang.Lang.init(this);

        configManager = new ConfigManager(this);
        configManager.load();

        // Una sola conexion controla chat bidireccional, reportes y alertas de Discord.
        // Se construye pronto para que los modulos puedan obtener el notifier, pero el login
        // se inicia al final, cuando todas las dependencias de los callbacks ya existen.
        discordBot = new DiscordBot(this);
        staffNotifier = new dev.chatalyst.discord.StaffNotifier(this, discordBot);

        modules = new dev.chatalyst.module.ModuleRegistry(this);

        // Backend de persistencia (YAML o base de datos segun config); debe ir antes de los
        // managers que guardan datos de jugador.
        dev.chatalyst.db.StorageModule storageModule = new dev.chatalyst.db.StorageModule(this);
        modules.enable(storageModule);
        storageManager = storageModule.storage();

        // Identidad de Discord, derechos de Tebex y entrega de la tienda. Se enciende aqui,
        // justo despues de storage, porque todo lo que vende se persiste.
        monetisation = new dev.chatalyst.monetisation.MonetisationModule(
                this, storageManager, discordBot);
        modules.enable(monetisation);

        // Lista de nombres reservados que mantiene el plugin. Va aparte de la config a
        // proposito: asi una version nueva la actualiza sin reescribir players.yml, que es
        // del dueño. Se instala pronto para que /nick y el titulo la vean desde el primer
        // jugador que entre.
        dev.chatalyst.nick.ProtectedNamesFile.install(this);

        channelManager = new ChannelManager(this, storageManager);
        channelManager.load();
        channelHistory = new dev.chatalyst.channel.ChannelHistory(this);
        channelHistory.load();
        // Frecuencias del chat local por radio (reemplaza el alcance por bloques).
        frequencyManager = new dev.chatalyst.channel.FrequencyManager(this, storageManager);
        frequencyManager.load();
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.channel.FrequencyListener(this, frequencyManager), this);
        getServer().getOnlinePlayers().forEach(p -> frequencyManager.loadPlayer(p.getUniqueId()));
        chatRegions = new dev.chatalyst.channel.ChatRegions(this);
        chatRegions.load();

        muteManager = new MuteManager(storageManager);
        muteManager.load();
        voiceIntegration = new dev.chatalyst.hook.VoiceIntegration(this);
        voiceIntegration.load();
        muteManager.setVoiceIntegration(voiceIntegration);

        toggleManager = new ToggleManager(storageManager);
        getServer().getOnlinePlayers().forEach(toggleManager::loadPlayer);

        privateMessages = new PrivateMessages(this, configManager, muteManager, toggleManager, storageManager);

        warningPoints = new WarningPoints(this, storageManager);
        warningPoints.load();

        ruleEngine = new RuleEngine(this, warningPoints, storageManager);
        ruleEngine.load();

        antiSpam = new AntiSpam(this);
        antiSpam.load();
        grammar = new Grammar(this);
        grammar.load();
        swearFilter = new dev.chatalyst.swear.SwearFilter(this);
        swearFilter.load();
        bookManager = new dev.chatalyst.book.BookManager(this);
        bookManager.load();
        tagEffects = new dev.chatalyst.tagfx.TagEffects(this);
        tagEffects.load();
        ruleEngine.bindBooks(bookManager);

        serverMessages = new ServerMessages(this);
        serverMessages.load();
        fancyJoin = new dev.chatalyst.messages.FancyJoin(this, serverMessages, toggleManager);
        messagesListener = new MessagesListener(this, serverMessages, toggleManager, fancyJoin);
        getServer().getPluginManager().registerEvents(messagesListener, this);

        // Auto-respuestas del chat (FAQ bot): responde a preguntas frecuentes sin staff. Su
        // listener se registra MAS ABAJO (despues del router de canales) para que, en modo de
        // preguntas privadas, cancele el mensaje despues de la moderacion y antes de ChatListener.
        autoResponder = new dev.chatalyst.autoreply.AutoResponder(this);
        autoResponder.load();

        // Eventos programados: GUI de admin, aviso al entrar y recordatorio antes de cada evento.
        eventsManager = new dev.chatalyst.events.EventsManager(this);
        eventsManager.load();
        eventsManager.start();
        dev.chatalyst.events.EventPrompt eventPrompt =
                new dev.chatalyst.events.EventPrompt(this, eventsManager);
        getServer().getPluginManager().registerEvents(eventPrompt, this);
        registerCommand("events",
                new dev.chatalyst.events.EventsCommand(this, eventsManager, eventPrompt));

        announcer = new Announcer(this, toggleManager);
        announcer.load();
        announcer.start();
        // Listener del modo inteligente: cuenta quien chatea para decidir si anunciar.
        getServer().getPluginManager().registerEvents(announcer, this);

        getServer().getPluginManager().registerEvents(new MentionListener(this, toggleManager), this);
        getServer().getPluginManager().registerEvents(new dev.chatalyst.toggle.ToggleListener(toggleManager), this);

        dev.chatalyst.mail.MailModule mailModule = new dev.chatalyst.mail.MailModule(this, storageManager);
        modules.enable(mailModule);
        mailManager = mailModule.manager();
        mailComposeManager = mailModule.compose();
        mailNotifier = mailModule.notifier();
        // Puente opcional /msg -> correo (Mail.Msg_Fallback), decidido por el dueño en config.
        privateMessages.setMailBridge(mailManager, mailNotifier);
        // Mute.Block_Mail: un muteado no puede enviar correo (todos los caminos de envio).
        mailManager.setMuteManager(muteManager);
        // Mute mas alla del chat: carteles, libros y comandos de la lista Mute.Blocked_Commands.
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.moderation.MuteContentListener(this, muteManager, mailComposeManager), this);

        // Carteles fisicos con colores/gradientes por permiso de rango. Es un modulo para
        // que /cht reload vuelva a leer inmediatamente los estilos de players.yml.
        modules.enable(new dev.chatalyst.sign.FancySignsModule(this));
        modules.enable(new dev.chatalyst.rental.RentalModule(this));
        // Cajas de regalo. Va la ultima de las cuatro porque es el tramo final de todas:
        // tienda, sorteo y kits deciden a QUIEN le llega algo, y la caja decide QUE es, en el
        // momento de abrirla. Ninguno de los tres necesita saber lo que hay dentro.
        modules.enable(new dev.chatalyst.gift.GiftModule(this));
        modules.enable(new dev.chatalyst.chatgame.ChatGameModule(this));
        modules.enable(new dev.chatalyst.chestlock.ChestLockModule(this));

        nickManager = new NickManager(this, storageManager);
        Placeholders.setNickProvider(nickManager::getNick);
        // El /list vanilla imprime el nombre de la CUENTA, no el mostrado: era la ultima
        // superficie por la que se leia entero el nombre de un anonimo.
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.nick.ListCommandListener(this), this);
        // Y el anuncio de un logro lo arma el servidor con ese mismo nombre de cuenta.
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.nick.AdvancementNameListener(), this);
        // Catalogo de titulos seleccionables: definiciones en tags.yml, seleccion y
        // desbloqueos en el backend de StorageManager.
        tagCatalog = new dev.chatalyst.tag.TagCatalog(this);
        tagCatalog.load();
        tagManager = new dev.chatalyst.tag.TagManager(storageManager, tagCatalog);
        tagManager.load();
        // Contadores de las reglas COUNTER (/tagcontador, %chatalyst_counter_<clave>%).
        titleCounters = dev.chatalyst.tag.TitleCounters.fromStorage(storageManager);
        dev.chatalyst.tag.TitleCounters.install(titleCounters);
        chatalystApi = new dev.chatalyst.tag.DefaultChatalystApi(
                this, tagManager, tagCatalog);
        getServer().getServicesManager().register(
                dev.chatalyst.api.ChatalystApi.class, chatalystApi, this,
                org.bukkit.plugin.ServicePriority.Normal);
        Placeholders.setTagProviders(tagManager::prefix, tagManager::suffix);
        tagAdminPrompt = new dev.chatalyst.tag.TagAdminPrompt(this, tagManager);
        getServer().getPluginManager().registerEvents(tagAdminPrompt, this);
        dev.chatalyst.tag.TagProgressListener tagProgress =
                new dev.chatalyst.tag.TagProgressListener(this, tagManager);
        getServer().getPluginManager().registerEvents(tagProgress, this);
        getServer().getOnlinePlayers().forEach(tagProgress::track);
        // Titulo flotante + iconos del nametag fancy, por jugador ({nametag_title},
        // {nametag_icon_left/right}).
        nametagTitleManager = new dev.chatalyst.tablist.NametagTitleManager(storageManager);
        nametagTitleManager.load();
        Placeholders.setNametagProviders(nametagTitleManager::title,
                nametagTitleManager::iconLeft, nametagTitleManager::iconRight);
        getServer().getPluginManager().registerEvents(new NickListener(nickManager), this);
        getServer().getOnlinePlayers().forEach(nickManager::loadPlayer);

        // Modo anonimo para streamers: apodo aleatorio + sin rango/tags + skin de incognito.
        anonymousManager = new dev.chatalyst.anonymous.AnonymousManager(
                this, nickManager, storageManager);
        anonymousManager.load();
        Placeholders.setAnonymousCheck(anonymousManager::isAnonymous);
        // Para que NickManager no aplique al entrar el alias de la sesion ANTERIOR de
        // un anonimo: ese apodo esta a punto de ser sustituido, y verlo un instante
        // enlazaba las dos identidades.
        dev.chatalyst.nick.NickManager.setRotationCheck(anonymousManager::willRotateOnJoin);
        Placeholders.setAnonymousLook(anonymousManager::look);
        // Efecto "reconexion": al ponerse/quitarse el anon estando conectado, se simula una
        // salida (nombre real) y una entrada (alias), en vez de un cambio de nombre en sitio.
        anonymousManager.setReconnectSimulators(
                messagesListener::broadcastSimulatedQuit,
                messagesListener::broadcastSimulatedJoin);
        // Y entre esa salida y esa entrada el jugador esta, para todos, fuera del server: si
        // se desconecta de verdad ahi, su marcha ya se conto.
        messagesListener.setAlreadyGoneCheck(anonymousManager::departureAnnounced);
        getServer().getPluginManager().registerEvents(anonymousManager, this);
        // Y lo que escriben OTROS plugins: la tumba con tu nombre encima es tan buena pista
        // como cualquier mensaje del servidor.
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.anonymous.AnonymousEntityNameListener(this, anonymousManager),
                this);
        getServer().getOnlinePlayers().forEach(anonymousManager::restoreSession);
        register("anonymous", new dev.chatalyst.anonymous.AnonymousCommand(anonymousManager));

        // Matrimonios (/marry, /divorce): opt-in, apagado de fabrica.
        marriage = new dev.chatalyst.marriage.MarriageModule(this, storageManager, nickManager);
        modules.enable(marriage);

        chatColorManager = new ChatColorManager(storageManager);
        ChatRenderer.setChatColorProvider(chatColorManager::getColorTag);
        applyMentionHighlight();

        // Cache del item en mano (main thread) para el token [item] del chat (async).
        heldItemTracker = new dev.chatalyst.chat.HeldItemTracker(this);
        getServer().getPluginManager().registerEvents(heldItemTracker, this);
        heldItemTracker.start();
        getServer().getPluginManager().registerEvents(new ChatColorListener(chatColorManager), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getOnlinePlayers().forEach(chatColorManager::loadPlayer);

        FormatEditManager formatEditManager = new FormatEditManager(this, configManager);
        getServer().getPluginManager().registerEvents(new FormatEditChatListener(this, formatEditManager), this);
        register("formatedit", new FormatEditCommand(this, configManager, formatEditManager));

        getServer().getPluginManager().registerEvents(
                new DiscordListener(this, discordBot), this);

        dev.chatalyst.tablist.TabListModule tabListModule = new dev.chatalyst.tablist.TabListModule(this);
        modules.enable(tabListModule);
        tabListManager = tabListModule.tabList();
        nametagManager = tabListModule.nametags();
        fancyNametagManager = tabListModule.fancyNametags();

        // Repinta el nametag en cuanto alguien se disfraza (Morphy / Lib's Disguises). Sin
        // esto el cambio espera al refresco periodico, que en el nametag clasico es de 30s:
        // medio minuto con el rango delatando a quien se cree oculto.
        dev.chatalyst.hook.DisguiseHook.listen(this, disguised -> {
            // El repintado va un tick por detras del evento: puede haberse ido en medio.
            if (!disguised.isOnline()) return;
            // Vanish + disfraz = modo fantasma: el cuerpo pasa a verse como mob pero sigue
            // fuera del tablist. Hay que reaplicar visibilidad en cuanto cambia el disfraz.
            if (staffModeration != null && staffModeration.vanish() != null) {
                staffModeration.vanish().resync(disguised);
            }
            if (nametagManager != null) nametagManager.update(disguised);
            if (fancyNametagManager != null) fancyNametagManager.refresh(disguised);
        });

        // Orden del pipeline de chat por prioridad de evento:
        //   LOWEST: mute (corta) y reglas (cancelan/reemplazan)
        //   LOW:    anti-spam (corta) y grammar (corrige)
        //   NORMAL: formateo final
        //   MONITOR: menciones (solo sonido)
        chatModeration = new ChatModeration();
        messageDeleteManager = new MessageDeleteManager(this);
        // Needs muteManager + messageDeleteManager (both already created).
        spamPunisher = new dev.chatalyst.antispam.SpamPunisher(this, muteManager, messageDeleteManager);
        spamPunisher.load();

        proxyManager = new ProxyManager(this, channelManager);
        proxyManager.bindStorage(storageManager);
        proxyManager.enable();
        networkMonitor = new dev.chatalyst.network.NetworkMonitor(this, proxyManager);
        proxyManager.bindNetworkMonitor(networkMonitor);
        getServer().getPluginManager().registerEvents(networkMonitor.identityListener(), this);
        getServer().getPluginManager().registerEvents(networkMonitor.watches(), this);
        supportConversations = new dev.chatalyst.support.SupportConversationManager(
                this, channelManager, proxyManager);
        proxyManager.bindSupport(supportConversations);
        supportConversations.start();
        staffDirectory = new dev.chatalyst.staff.StaffDirectoryManager(
                this, proxyManager, supportConversations);
        proxyManager.bindStaffDirectory(staffDirectory);
        getServer().getPluginManager().registerEvents(staffDirectory, this);
        staffDirectory.start();
        networkPlayerDirectory = new dev.chatalyst.staff.NetworkPlayerDirectory(
                this, proxyManager, staffDirectory);
        proxyManager.bindPlayerDirectory(networkPlayerDirectory);
        getServer().getPluginManager().registerEvents(networkPlayerDirectory, this);
        networkPlayerDirectory.start();

        getServer().getPluginManager().registerEvents(new MuteChatListener(muteManager), this);
        // Sanitizer runs first (LOWEST) so every other filter sees clean text.
        chatSanitizerListener = new dev.chatalyst.security.ChatSanitizerListener(this);
        getServer().getPluginManager().registerEvents(chatSanitizerListener, this);
        getServer().getPluginManager().registerEvents(new RuleChatListener(ruleEngine), this);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.swear.SwearChatListener(swearFilter, warningPoints), this);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.swear.SwearNameAlertListener(this, swearFilter), this);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.rules.RuleContentListener(ruleEngine, swearFilter), this);

        antiBotManager = new dev.chatalyst.antibot.AntiBotManager(this);
        antiBotManager.load();
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.antibot.AntiBotListener(antiBotManager), this);

        dev.chatalyst.log.LogModule logModule = new dev.chatalyst.log.LogModule(this, privateMessages);
        modules.enable(logModule);
        logManager = logModule.manager();
        ruleEngine.bindLog(logManager);
        mailManager.setLog(logManager);
        // Auditoria del disfraz: la unica constancia real -> alias (ver Log.Anonymous).
        anonymousManager.setLog(logManager);

        tabCompleteListener = new dev.chatalyst.tabcomplete.TabCompleteListener(this);
        getServer().getPluginManager().registerEvents(tabCompleteListener, this);
        // Guia de argumentos en el TAB (<jugador> <formato>...) sacada del usage de plugin.yml.
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.tabcomplete.UsageHintListener(this), this);

        newcomerListener = new dev.chatalyst.newcomer.NewcomerListener(this);
        getServer().getPluginManager().registerEvents(newcomerListener, this);

        commandCooldownListener = new dev.chatalyst.cooldown.CommandCooldownListener(this);
        getServer().getPluginManager().registerEvents(commandCooldownListener, this);

        commandBlockerListener = new dev.chatalyst.security.CommandBlockerListener(this);
        getServer().getPluginManager().registerEvents(commandBlockerListener, this);
        // El error de Brigadier ("Comando desconocido o incompleto... <--[AQUI]") con estilo
        // propio. UnknownCommandEvent es de Paper: en Spigot la clase no existe y no se registra.
        if (dev.chatalyst.security.UnknownCommandListener.supported()) {
            unknownCommandListener = new dev.chatalyst.security.UnknownCommandListener(
                    this, tabCompleteListener, commandBlockerListener);
            getServer().getPluginManager().registerEvents(unknownCommandListener, this);
        }
        // Embellecido de los mensajes VANILLA del servidor (/tp, /xp, /kill...), en el
        // idioma de cada cliente. Necesita PacketEvents para tocar esos paquetes.
        vanillaMessages = new dev.chatalyst.vanilla.VanillaMessages(this);
        // Lo que tapa los mensajes fijos de otros plugins y el nombre del software en F3:
        // las dos piezas que faltaban para no necesitar un plugin aparte que esconda el resto.
        messageReplacements = new dev.chatalyst.vanilla.MessageReplacements(this);
        serverBrand = new dev.chatalyst.vanilla.ServerBrand(this);
        packetEventsIntegration = new dev.chatalyst.hook.PacketEventsIntegration(
                this, commandBlockerListener, vanillaMessages, anonymousManager,
                messageReplacements, serverBrand);
        packetEventsIntegration.load();
        staffSecurityListener = new dev.chatalyst.security.StaffSecurityListener(this);
        getServer().getPluginManager().registerEvents(staffSecurityListener, this);

        // Standalone /staff control center and native fancy punishments. It owns its own
        // persisted ban/freeze/vanish/inventory state and never hangs moderation off /cht.
        staffModeration = new dev.chatalyst.staff.StaffModerationModule(this);
        modules.enable(staffModeration);
        // One ban, both sides. Wired HERE and not inside the monetisation module because that
        // one starts right after storage, long before bans exist: asking it for them there
        // returned null and the mirror silently never registered.
        if (monetisation != null && monetisation.links() != null) {
            punishmentRoles = new dev.chatalyst.discord.DiscordPunishmentRoles(
                    this, discordBot, monetisation.links());
            // Marking a punishment once is not enough: time keeps moving, and a ban that ran
            // out would otherwise stay on somebody's Discord profile for ever.
            punishmentRoles.start();
            dev.chatalyst.discord.DiscordBanSync banSync = new dev.chatalyst.discord.DiscordBanSync(
                    this, discordBot, monetisation.links(), punishmentRoles);
            discordBot.bindBanSync(banSync);
            staffModeration.bans().observe(banSync);
        }

        commandAliases = new dev.chatalyst.alias.CommandAliasManager(this);
        commandAliases.apply();

        scriptManager = new dev.chatalyst.script.ScriptManager(this);
        scriptManager.init();
        dev.chatalyst.hook.Placeholders.setScriptEvaluator(scriptManager::eval);
        ruleEngine.bindScriptEvaluator(scriptManager::evalRuleCondition);
        dev.chatalyst.format.FormatCondition.setScriptEvaluator(
                scriptManager::evalFormatCondition);
        loadCustomVariables();

        // Expose Chatalyst data to PlaceholderAPI (%chatalyst_...%) if PAPI is installed.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            dev.chatalyst.hook.ChatalystExpansion.tryRegister(this);
        }
        // Same placeholders registered natively in TAB (no PAPI needed) + /nick sync.
        dev.chatalyst.hook.TabHook.registerPlaceholders(this);
        getServer().getPluginManager().registerEvents(new ChatModListener(chatModeration), this);
        getServer().getPluginManager().registerEvents(new ChatGuardListener(antiSpam, grammar, spamPunisher), this);
        groupOverrides = new dev.chatalyst.chat.GroupOverrides(this);
        groupOverrides.load();
        antiSpam.setGroupOverrides(groupOverrides);
        // La solicitud natural corre en LOW. Una sesion privada se captura en NORMAL antes
        // del renderer, historial y monitor para que nunca pueda filtrarse al chat global.
        staffMentionRouter = new dev.chatalyst.channel.StaffMentionRouter(
                this, channelManager, supportConversations);
        staffMentionRouter.load();
        getServer().getPluginManager().registerEvents(staffMentionRouter, this);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.support.SupportChatListener(supportConversations), this);
        translation.load(getConfig().getConfigurationSection("Translation"));
        getServer().getPluginManager().registerEvents(new ChatListener(configManager, channelManager,
                proxyManager, messageDeleteManager, muteManager, channelHistory, groupOverrides,
                networkMonitor, privateMessages, translation), this);
        // Router inteligente: saca las ofertas/subastas del chat global hacia su canal.
        channelRouter = new dev.chatalyst.channel.ChannelRouterListener(this, channelManager);
        channelRouter.load();
        getServer().getPluginManager().registerEvents(channelRouter, this);
        // FAQ bot AL FINAL de LOW (tras mute/reglas/anti-spam/router): asi respeta lo que ellos
        // cancelan y, en modo privado, corta el mensaje justo antes del ChatListener (NORMAL).
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.autoreply.AutoReplyListener(this, autoResponder), this);
        // Soporte de plugins de mercado: deteccion + anuncios de listados en trade/auction.
        tradePluginsHook = new dev.chatalyst.hook.TradePluginsHook(this, channelManager);
        tradePluginsHook.load();
        tradePluginsHook.logDetected();
        getServer().getPluginManager().registerEvents(tradePluginsHook, this);
        // Mercado NATIVO (ventas/subastas con deposito y doble aceptacion). Se aparta solo
        // si hay un plugin de mercado dedicado (Market.Defer_To_Plugins). Viene APAGADO.
        marketManager = new dev.chatalyst.market.MarketManager(this, storageManager, channelManager, channelHistory);
        marketManager.load();
        marketManager.start();
        // GUI de publicacion (estilo /announce): borrador + escribir el precio por chat.
        var marketPublish = new dev.chatalyst.market.MarketPublishManager(
                storageManager.perServerStore("market_publish_drafts"));
        marketManager.setPublishManager(marketPublish);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.market.MarketComposeListener(this, marketManager, marketPublish), this);
        // Trade directo jugador-a-jugador (/trade). Tambien APAGADO de fabrica; reutiliza el
        // alijo del mercado para no perder items si una parte se desconecta.
        tradeManager = new dev.chatalyst.market.TradeManager(this, marketManager);
        tradeManager.load();
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.market.MarketListener(this, marketManager, tradeManager), this);
        // /reload puede habilitar el plugin con jugadores ya conectados: completa tambien
        // para ellos cualquier journal/marker recuperado, sin esperar un PlayerJoin futuro.
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            dev.chatalyst.util.FoliaTasks.entity(this, online, player -> {
                java.util.UUID id = player.getUniqueId();
                marketPublish.recover(item -> marketManager.hasListingMarker(item)
                        || marketManager.stashFor(id, java.util.List.of(item)), id);
                marketManager.recoverPlayerEscrow(player);
            });
        }
        register("market", new dev.chatalyst.market.MarketCommand(marketManager));
        register("trade", new dev.chatalyst.market.TradeCommand(tradeManager));

        // EssentialsX Chat pisa el renderer moderno (formato plano pegado, sin [x]/hover):
        // no hay coexistencia posible, hay que quitar su jar. Aviso imposible de ignorar.
        if (getServer().getPluginManager().getPlugin("EssentialsChat") != null) {
            getLogger().warning("==========================================================");
            getLogger().warning("EssentialsXChat detected!");
            getLogger().warning("It formats the chat through the legacy event and OVERRIDES");
            getLogger().warning("Chatalyst's format: plain glued messages, no delete button,");
            getLogger().warning("no hovers, no channels.");
            getLogger().warning("FIX: delete plugins/EssentialsXChat.jar (keep Essentials");
            getLogger().warning("itself) - Chatalyst replaces the chat module completely.");
            getLogger().warning("==========================================================");
        }
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.pm.PmListener(privateMessages), this);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.pm.CommandSpyListener(this, privateMessages), this);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.pm.SpyContentListener(this, privateMessages), this);

        // Registro de contenedores/mecanismos con coordenadas + spy en vivo + /containerlog.
        containerLogManager = new dev.chatalyst.containerlog.ContainerLogManager(this, logManager);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.containerlog.ContainerSpyListener(
                        this, privateMessages, containerLogManager), this);
        register("containerlog", new dev.chatalyst.containerlog.ContainerLogCommand(
                this, containerLogManager, logManager));

        // Registro de PvP: quien abrio cada pelea + quien mato a quien -> /combatlog.
        // Va junto al de contenedores porque responde a la misma pregunta ("quien fue") sobre
        // el otro tipo de conflicto que genera reportes: el combate.
        combatLogManager = new dev.chatalyst.combatlog.CombatLogManager(this, logManager);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.combatlog.CombatSpyListener(
                        this, privateMessages, combatLogManager), this);
        register("combatlog", new dev.chatalyst.combatlog.CombatLogCommand(
                this, combatLogManager, logManager));

        // Consentimiento de [inv]/[ec] compartidos en el chat (los botones "Abrir").
        inventoryShareManager = new dev.chatalyst.chat.InventoryShareManager();
        ChatRenderer.setInventoryShare(
                getConfig().getBoolean("Chat.Inventory_Share", true), inventoryShareManager);

        // Cache de region/party (WorldGuard/Parties) actualizada en el hilo principal,
        // para que el chat async no toque esas APIs ni la ubicacion del jugador.
        regionPartyTracker = new dev.chatalyst.channel.RegionPartyTracker(this);
        getServer().getPluginManager().registerEvents(regionPartyTracker, this);
        regionPartyTracker.start();

        // Por si se recarga con jugadores ya conectados.
        getServer().getOnlinePlayers().forEach(p -> {
            channelManager.loadPlayer(p);
            privateMessages.loadPlayer(p);
        });

        register("chatalyst", new ChatalystCommand(this));
        dev.chatalyst.discord.DiscordLinkCommand discordLinkCommand =
                new dev.chatalyst.discord.DiscordLinkCommand(
                        this, monetisation.links(), monetisation.rankSync(),
                        monetisation.linkGate());
        register("discord", discordLinkCommand);
        getServer().getPluginManager().registerEvents(discordLinkCommand, this);
        // Registrado en runtime como /ranks, y por el mismo motivo: un server sin tienda no
        // reserva la etiqueta, y el dueño puede renombrarla si ya se la lleva otro plugin.
        dev.chatalyst.store.StoreCommand storeCommand =
                new dev.chatalyst.store.StoreCommand(monetisation.store(), monetisation.shop());
        storeCommands = new dev.chatalyst.command.RuntimeCommandRegistrar(this,
                new dev.chatalyst.command.RuntimeCommandRegistrar.Spec(
                        "Store.Command", "Store.Command_Aliases",
                        "store", java.util.List.of("tienda", "claimitems"),
                        "Purchase collection", "chatalyst.command.store",
                        () -> monetisation.store() != null && monetisation.store().enabled()),
                new dev.chatalyst.command.RuntimeCommandRegistrar.Handler() {
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender,
                                           String label, String[] args) {
                        return storeCommand.onCommand(sender, null, label, args);
                    }

                    @Override
                    public java.util.List<String> complete(
                            org.bukkit.command.CommandSender sender, String[] args) {
                        return storeCommand.onTabComplete(sender, null, "store", args);
                    }
                });
        getServer().getPluginManager().registerEvents(storeCommands, this);
        storeCommands.apply();
        register("channel", new ChannelCommand(channelManager, channelHistory, frequencyManager));
        register("frequency", new dev.chatalyst.channel.FrequencyCommand(frequencyManager));
        register("f", new dev.chatalyst.channel.FrequencyChatCommand(channelManager));
        register("staffchat", new dev.chatalyst.channel.StaffChatCommand(this));
        register("support", new dev.chatalyst.support.SupportCommand(supportConversations));
        PmCommands pmCommands = new PmCommands(this, privateMessages);
        register("msg", pmCommands);
        register("reply", pmCommands);
        register("ignore", pmCommands);
        register("spy", pmCommands);
        MuteCommands muteCommands = new MuteCommands(muteManager, staffModeration);
        register("mute", muteCommands);
        register("unmute", muteCommands);
        AnnounceCommands announceCommands = new AnnounceCommands(this);
        register("me", announceCommands);
        register("say", announceCommands);
        register("broadcast", announceCommands);
        // GUI de anuncios de administracion (display + sonido + estilo + preview + envio).
        announceDraftManager = new dev.chatalyst.announce.AnnounceDraftManager(this);
        proxyManager.bindAnnouncements(announceDraftManager);
        getServer().getPluginManager().registerEvents(
                new dev.chatalyst.announce.AnnounceComposeListener(this, announceDraftManager), this);
        register("announce", new dev.chatalyst.announce.AnnounceGuiCommand(announceDraftManager));
        register("toggle", new ToggleCommand(toggleManager, translation));
        // /perfil: datos del jugador + los mismos interruptores de /toggle + accesos directos.
        register("perfil", new dev.chatalyst.profile.ProfileCommand(this));
        register("motd", new dev.chatalyst.messages.MotdCommand(serverMessages));
        register("nick", new NickCommand(nickManager, getConfig().getInt("Nick.Max_Length", 16), ruleEngine, swearFilter));
        register("tag", new dev.chatalyst.tag.TagCommand(this, tagManager,
                getConfig().getInt("Tag.Max_Length", 16), ruleEngine, swearFilter));
        register("tagcontador", new dev.chatalyst.tag.TagCounterCommand(
                this, tagManager, titleCounters));
        dev.chatalyst.tablist.NametagTitleCommand nametagTitleCommand =
                new dev.chatalyst.tablist.NametagTitleCommand(nametagTitleManager,
                        getConfig().getInt("Nametag.Fancy.Title.Max_Length", 32),
                        ruleEngine, swearFilter);
        register("nametagtitle", nametagTitleCommand);
        // Editor por GUI (captura por chat) del titulo/iconos del nametag.
        nametagTitleEditor = new dev.chatalyst.tablist.NametagTitleEditor(this, nametagTitleCommand);
        getServer().getPluginManager().registerEvents(nametagTitleEditor, this);
        register("itemname", new dev.chatalyst.item.ItemNameCommand(this, ruleEngine, swearFilter, storageManager));
        register("realname", new RealNameCommand(nickManager));
        register("nickcolor", new dev.chatalyst.nick.NickColorCommand(this, nickManager));
        register("chatcolor", new ChatColorCommand(this, chatColorManager));
        register("book", new dev.chatalyst.book.BookCommand(this, bookManager));
        dev.chatalyst.report.ReportsModule reportsModule =
                new dev.chatalyst.report.ReportsModule(this, storageManager);
        modules.enable(reportsModule);
        reportManager = reportsModule.reports();
        ticketConversation = reportsModule.conversation();
        reportActions = reportsModule.actions();
        dynmapIntegration = reportsModule.dynmap();
        ChatModCommands chatMod = new ChatModCommands(this, chatModeration);
        register("clearchat", chatMod);
        register("chatlock", chatMod);
        register("chatunlock", chatMod);
        register("slowmode", chatMod);
        register("warnings", new WarningsCommand(warningPoints));

        // AFK: detecta inactividad, avisa solo al jugador y lo marca en la tab list.
        dev.chatalyst.afk.AfkModule afkModule = new dev.chatalyst.afk.AfkModule(this);
        modules.enable(afkModule);
        afkManager = afkModule.manager();
        register("players", new PlayerListCommand(this));

        // Sorteos: la ruleta animada, la inmunidad de quienes la miran y la entrega del
        // premio. Va DESPUES de AFK porque decide a quien se sortea preguntandole si esta
        // ausente, y despues de monetizacion porque los premios de rango y de tienda pasan
        // por esos servicios.
        giveaways = new dev.chatalyst.giveaway.GiveawayModule(this);
        modules.enable(giveaways);

        // Carteles flotantes (/cartel): TextDisplay/ItemDisplay del staff con los estilos y
        // animaciones de Chatalyst. Guardados por servidor en data/holograms.yml.
        modules.enable(new dev.chatalyst.hologram.HologramModule(this));

        // Baltop: ranking de dinero (via Vault), calculado async y cacheado.
        dev.chatalyst.economy.BalanceTopModule balanceTopModule =
                new dev.chatalyst.economy.BalanceTopModule(this);
        modules.enable(balanceTopModule);
        balanceTop = balanceTopModule.balanceTop();

        // Vigilancia de objetos: avisa al staff de cantidades absurdas, ganancias demasiado
        // rapidas y stacks imposibles (posibles dupes). Apagada de fabrica.
        modules.enable(new dev.chatalyst.itemwatch.ItemWatchModule(this));

        // Detector de autoclick para el staff: avisa de clics roboticos o imposibles. Despues
        // del staff (escribe en su historial). Apagado de fabrica.
        modules.enable(new dev.chatalyst.staff.autoclick.AutoclickModule(this));
        // Anti-exploit propio (moderation.yml -> Anti_Exploit): limites por paquete, objetos bomba, carteles.
        modules.enable(new dev.chatalyst.security.exploit.AntiExploitModule(this));
        // Registro de lo que el staff saca de la nada (creativo, /give, kits) y a quien le
        // llega, mas el control de gamemode por permisos. Apagado de fabrica.
        modules.enable(new dev.chatalyst.staff.itemaudit.ItemAuditModule(this));

        // Login asincrono del bot propio. A esta altura reportes, reglas, filtros y canales
        // ya estan listos si Discord entrega un mensaje inmediatamente.
        discordBot.start();

        // Aviso de nuevas versiones (async, no descarga) + aviso a admins al entrar.
        updateChecker = new dev.chatalyst.update.UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.check();

        // Command ownership can only be judged once every plugin has registered.
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
                dev.chatalyst.config.CommandConflictReport.logStartupSummary(ChatalystPlugin.this);
            }
        }, this);

        printBanner();
    }

    /** Prints a colored startup banner to the console. */
    private void printBanner() {
        var log = getComponentLogger();
        String version = getPluginMeta().getVersion();

        java.util.List<String> hooks = new java.util.ArrayList<>();
        for (String h : java.util.List.of("LuckPerms", "PlaceholderAPI", "Vault",
                "WorldGuard", "Parties", "ItemsAdder", "Nexo", "TAB",
                "dynmap", "voicechat", "PlasmoVoice", "packetevents")) {
            if (getServer().getPluginManager().getPlugin(h) != null) hooks.add(h);
        }
        String hookStr = hooks.isEmpty() ? "none" : String.join(", ", hooks);
        String storage = storageManager.usingDatabase()
                ? getConfig().getString("Database.Type", "SQLITE")
                : "YAML";

        String content = ruleEngine.ruleCount() + " <#C0B3D9>rules <#7B68A0>· <#FBF2FF>"
                + channelManager.all().size() + " <#C0B3D9>channels <#7B68A0>· <#FBF2FF>"
                + bookManager.names().size() + " <#C0B3D9>books";

        // Nota: el estado de actualizacion NO se muestra aqui porque la comprobacion es
        // asincrona y raramente ha terminado al imprimir el banner; el aviso llega por el
        // log y a los admins al entrar.
        String mcVersion;
        try {
            mcVersion = getServer().getMinecraftVersion();
        } catch (Throwable t) {
            mcVersion = getServer().getBukkitVersion();
        }

        // Reglas arriba y abajo en vez de un recuadro: la consola antepone
        // "[HH:MM:SS INFO]: [Chatalyst]" a CADA linea, asi que un borde izquierdo nunca
        // alinea y el cuadro se ve partido. Dos reglas separan del resto de plugins sin
        // depender de esa alineacion.
        String regla = "<gradient:#240046:#9D4EDD:#E0AAFF>"
                + "─".repeat(58) + "</gradient>";

        log.info(dev.chatalyst.util.MM.full(""));
        log.info(dev.chatalyst.util.MM.full(regla));
        log.info(dev.chatalyst.util.MM.full(""));
        log.info(dev.chatalyst.util.MM.full("<gradient:#240046:#9D4EDD:#E0AAFF>  █▀▀ █░█ ▄▀█ ▀█▀ ▄▀█ █░░ █▄█ █▀ ▀█▀</gradient>"));
        log.info(dev.chatalyst.util.MM.full("<gradient:#240046:#9D4EDD:#E0AAFF>  █▄▄ █▀█ █▀█ ░█░ █▀█ █▄▄ ░█░ ▄█ ░█░</gradient>  <#E4A8FF>v"
                + version));
        log.info(dev.chatalyst.util.MM.full("  <italic><#C0B3D9>Premium chat suite for Paper & Purpur</italic>"));
        log.info(dev.chatalyst.util.MM.full(""));
        log.info(dev.chatalyst.util.MM.full(fila("Server", getServer().getName()
                + " <#7B68A0>" + mcVersion)));
        log.info(dev.chatalyst.util.MM.full(fila("Content", content)));
        log.info(dev.chatalyst.util.MM.full(fila("Storage", storage + " <#7B68A0>· <#C0B3D9>scripting <#FBF2FF>"
                + (scriptManager.isAvailable() ? "on" : "off") + " <#7B68A0>· <#C0B3D9>lang <#FBF2FF>en<#7B68A0>+<#FBF2FF>es")));
        log.info(dev.chatalyst.util.MM.full(fila("Hooks", "<#B56CFF>" + hookStr)));
        log.info(dev.chatalyst.util.MM.full(fila("License", "<#FBF2FF><bold>" + license.buyer()
                + "</bold> <#7B68A0>· <#C0B3D9>" + license.id() + " <#7B68A0>· <#C0B3D9>" + license.issued())));

        // Estado de las features: para que quien administra VEA en consola que esta apagado
        // (con su clave/archivo) en vez de adivinar. El detalle completo va en /chatalyst features.
        var features = dev.chatalyst.config.FeatureReport.evaluate(this);
        long onCount = features.stream().filter(dev.chatalyst.config.FeatureReport.Result::on).count();
        var off = dev.chatalyst.config.FeatureReport.disabled(features);
        // Conciso, al ritmo del banner: el conteo de activas (la lista completa la da el
        // comando) y SOLO las apagadas envueltas en filas alineadas, que es lo accionable.
        if (off.isEmpty()) {
            log.info(dev.chatalyst.util.MM.full(fila("Features",
                    "<#3DFFA8>" + onCount + " on <#7B68A0>- <#3DFFA8>all enabled")));
        } else {
            log.info(dev.chatalyst.util.MM.full(fila("Features", "<#3DFFA8>" + onCount
                    + " on <#7B68A0>- <#FF9F1C>" + off.size() + " off <#7B68A0>(run "
                    + "<#C0B3D9>/chatalyst features<#7B68A0>)")));
            java.util.List<String> offNames = off.stream()
                    .map(dev.chatalyst.config.FeatureReport.Result::label).toList();
            for (String line : wrapFila("Off", offNames, "<#C0B3D9>")) {
                log.info(dev.chatalyst.util.MM.full(line));
            }
        }

        log.info(dev.chatalyst.util.MM.full(""));
        log.info(dev.chatalyst.util.MM.full("  <#C0B3D9>Enabled successfully."
                + " <#7B68A0>Thanks for using Chatalyst."));
        log.info(dev.chatalyst.util.MM.full(regla));
        log.info(dev.chatalyst.util.MM.full(""));
    }

    /**
     * Envuelve una lista larga (features) en varias {@link #fila filas} alineadas: la primera
     * lleva la etiqueta, las de continuacion van sin ella (indentadas al mismo ancho), asi el
     * bloque respeta la rejilla del banner en vez de derramarse en una sola linea gigante. El
     * ancho se mide sobre el texto VISIBLE (las etiquetas no llevan tags MiniMessage).
     */
    private static java.util.List<String> wrapFila(String etiqueta, java.util.List<String> items,
                                                    String color) {
        final int width = 52; // caracteres visibles por linea, alineado con la regla del banner
        java.util.List<java.util.List<String>> lines = new java.util.ArrayList<>();
        java.util.List<String> current = new java.util.ArrayList<>();
        int len = 0;
        for (String item : items) {
            int addition = current.isEmpty() ? item.length() : 2 + item.length();
            if (!current.isEmpty() && len + addition > width) {
                lines.add(current);
                current = new java.util.ArrayList<>();
                len = 0;
                addition = item.length();
            }
            current.add(item);
            len += addition;
        }
        if (!current.isEmpty()) lines.add(current);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String joined = color + String.join("<#7B68A0>, " + color, lines.get(i));
            out.add(fila(i == 0 ? etiqueta : "", joined));
        }
        return out;
    }

    /** Fila del banner: etiqueta lila a ancho fijo + valor. El ancho fijo es lo que alinea. */
    private static String fila(String etiqueta, String valor) {
        return "  <#C77DFF>" + String.format("%-10s", etiqueta) + "<#FBF2FF>" + valor;
    }

    public RuleEngine getRuleEngine() {
        return ruleEngine;
    }

    public dev.chatalyst.swear.SwearFilter getSwearFilter() {
        return swearFilter;
    }

    public dev.chatalyst.tagfx.TagEffects getTagEffects() {
        return tagEffects;
    }

    public dev.chatalyst.book.BookManager getBookManager() {
        return bookManager;
    }

    public dev.chatalyst.script.ScriptManager getScriptManager() {
        return scriptManager;
    }

    public dev.chatalyst.antibot.AntiBotManager getAntiBotManager() {
        return antiBotManager;
    }

    public dev.chatalyst.log.LogManager getLogManager() {
        return logManager;
    }

    public dev.chatalyst.combatlog.CombatLogManager getCombatLogManager() {
        return combatLogManager;
    }

    public dev.chatalyst.containerlog.ContainerLogManager getContainerLogManager() {
        return containerLogManager;
    }

    public dev.chatalyst.chat.InventoryShareManager getInventoryShareManager() {
        return inventoryShareManager;
    }

    public dev.chatalyst.anonymous.AnonymousManager getAnonymousManager() {
        return anonymousManager;
    }

    public dev.chatalyst.marriage.MarriageManager getMarriageManager() {
        return marriage == null ? null : marriage.marriages();
    }

    public dev.chatalyst.tabcomplete.TabCompleteListener getTabCompleteListener() {
        return tabCompleteListener;
    }

    public dev.chatalyst.newcomer.NewcomerListener getNewcomerListener() {
        return newcomerListener;
    }

    public dev.chatalyst.cooldown.CommandCooldownListener getCommandCooldownListener() {
        return commandCooldownListener;
    }

    public dev.chatalyst.security.ChatSanitizerListener getChatSanitizerListener() {
        return chatSanitizerListener;
    }

    public dev.chatalyst.security.CommandBlockerListener getCommandBlockerListener() {
        return commandBlockerListener;
    }

    /** Applies the mention-highlight and clickable-link settings to the chat renderer. */
    public void applyMentionHighlight() {
        ChatRenderer.setMentionHighlight(
                getConfig().getBoolean("Mentions.Highlight.Enabled", true),
                getConfig().getString("Mentions.Highlight.Color", "yellow"),
                getConfig().getBoolean("Mentions.Highlight.Bold", true),
                getConfig().getBoolean("Mentions.Require_At", false));
        ChatRenderer.setClickableLinks(getConfig().getBoolean("Chat.Clickable_Links", true));
        ChatRenderer.setItemPlaceholder(getConfig().getBoolean("Chat.Item_Placeholder", true));
        ChatRenderer.setItemCustomIds(getConfig().getBoolean("Chat.Item_Custom_Ids", true));
        // En el arranque el manager aun no existe (se instala mas tarde); en los reloads
        // este camino refresca el toggle sin perder los grants vigentes.
        if (inventoryShareManager != null) {
            ChatRenderer.setInventoryShare(
                    getConfig().getBoolean("Chat.Inventory_Share", true), inventoryShareManager);
        }
        if (containerLogManager != null) {
            containerLogManager.load();
        }
        if (combatLogManager != null) {
            combatLogManager.load();
        }
        if (anonymousManager != null) {
            anonymousManager.load();
        }
        // El reload de matrimonios se fue a MarriageModule.reload(): lo dispara reloadAll()
        // unas lineas mas abajo en el mismo /chatalyst reload, y nada entre medias lo mira.
        if (vanillaMessages != null) {
            vanillaMessages.load();
        }
        if (unknownCommandListener != null) {
            unknownCommandListener.load();
        }
        ChatRenderer.setPixelImages(
                getConfig().getBoolean("Chat.Pixel_Images.Enabled", true),
                getConfig().getConfigurationSection("Chat.Pixel_Images.Tokens"));
        ChatRenderer.setEmojis(
                getConfig().getBoolean("Chat.Emojis.Enabled", true),
                getConfig().getConfigurationSection("Chat.Emojis.Custom"));
        dev.chatalyst.hook.Placeholders.setPartyTagTemplate(
                getConfig().getString("Chat.Party_Tag", "<#7B68A0>[<#B56CFF>{party}<#7B68A0>] "));
    }

    /** Carga las variables personalizadas de config y las instala en Placeholders. */
    public void loadCustomVariables() {
        java.util.Map<String, String> vars = new java.util.HashMap<>();
        org.bukkit.configuration.ConfigurationSection sec = getConfig().getConfigurationSection("Variables");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                vars.put(key, sec.getString(key, ""));
            }
        }
        dev.chatalyst.hook.Placeholders.setCustomVariables(vars);
    }

    public ChatModeration getChatModeration() {
        return chatModeration;
    }

    public ServerMessages getServerMessages() {
        return serverMessages;
    }

    public dev.chatalyst.messages.FancyJoin getFancyJoin() {
        return fancyJoin;
    }

    public WarningPoints getWarningPoints() {
        return warningPoints;
    }

    public dev.chatalyst.antispam.SpamPunisher getSpamPunisher() {
        return spamPunisher;
    }

    public AntiSpam getAntiSpam() {
        return antiSpam;
    }

    public Grammar getGrammar() {
        return grammar;
    }

    public dev.chatalyst.events.EventsManager getEventsManager() {
        return eventsManager;
    }

    public dev.chatalyst.autoreply.AutoResponder getAutoResponder() {
        return autoResponder;
    }

    /** Registro de comandos accesible para los modulos (el comando ya existe en plugin.yml). */
    public void registerCommand(String name, Object handler) {
        register(name, handler);
    }

    /**
     * Cierra las GUIs de Chatalyst que sigan abiertas.
     *
     * <p>En Folia no se hace: el apagado detiene los ticks regionales ANTES de onDisable, y
     * tocar jugadores de varias regiones desde aqui seria ilegal. Alli la desconexion cierra
     * los inventarios de todos modos.
     */
    private void closeOpenMenus() {
        if (dev.chatalyst.util.FoliaTasks.isFolia()) return;
        int cerrados = 0;
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            try {
                if (player.getOpenInventory().getTopInventory().getHolder()
                        instanceof dev.chatalyst.gui.MenuHolder) {
                    player.closeInventory();
                    cerrados++;
                }
            } catch (Throwable ignored) {
                // Un jugador que se va justo ahora no puede impedir el resto del apagado.
            }
        }
        if (cerrados > 0) {
            getLogger().info("Closed " + cerrados + " open menu(s) so their contents could not"
                    + " be taken once the click guard went down.");
        }
    }

    /** Reclama un comando declarado en plugin.yml. Publico para que lo usen los modulos. */
    public void register(String name, Object handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor((org.bukkit.command.CommandExecutor) handler);
            if (handler instanceof org.bukkit.command.TabCompleter tc) {
                cmd.setTabCompleter(tc);
            }
        }
    }

    @Override
    public void onDisable() {
        // ANTES que nada: cerrar los menus abiertos.
        //
        // Bukkit desregistra los listeners del plugin al apagarlo pero NO cierra los
        // inventarios. El MenuHolder sigue ahi y MenuListener ya no existe, asi que los
        // clicks dejan de cancelarse y el jugador puede SACARSE los items del menu. En la
        // ruleta de un sorteo eso son cabezas decorativas, pero en el mercado y en el correo
        // son objetos de verdad. Se dispara con cualquier /reload, con un PlugMan, o si el
        // plugin se desactiva por un error al arrancar.
        closeOpenMenus();
        if (activationReporter != null) {
            activationReporter.stop();
            activationReporter = null;
        }
        if (chatalystApi != null) {
            getServer().getServicesManager().unregister(
                    dev.chatalyst.api.ChatalystApi.class, chatalystApi);
        }
        // Stop external callbacks before any manager they can reach begins shutting down.
        if (punishmentRoles != null) {
            // Before the bot: its sweep reads bans and mutes and then talks to Discord, so it
            // must fall silent while both ends are still there to answer.
            shutdownSafely("punishment roles", punishmentRoles::stop);
        }
        if (discordBot != null) {
            shutdownSafely("Discord bot", discordBot::stop);
        }
        // Aqui y no en disableAll(): el registro apaga en orden inverso al de encendido, o
        // sea al final, y estos sondeos (Discord, LuckPerms, red) tienen que callarse ANTES
        // que los managers a los que sus callbacks llegan. La llamada es idempotente, asi que
        // el disableAll() de mas abajo la encuentra hecha.
        if (monetisation != null) {
            shutdownSafely("monetisation", monetisation::disable);
        }
        if (eventsManager != null) {
            shutdownSafely("scheduled events", eventsManager::stop);
        }
        if (networkPlayerDirectory != null) {
            shutdownSafely("network player directory", networkPlayerDirectory::stop);
        }
        if (staffDirectory != null) {
            shutdownSafely("staff directory", staffDirectory::stop);
        }
        if (supportConversations != null) {
            shutdownSafely("support conversations", supportConversations::stop);
        }
        if (channelManager != null) {
            shutdownSafely("channels", channelManager::shutdown);
        }
        if (privateMessages != null) {
            shutdownSafely("private messages", privateMessages::shutdown);
        }
        if (muteManager != null) {
            shutdownSafely("mutes", muteManager::shutdown);
        }
        if (announcer != null) {
            shutdownSafely("announcer", announcer::stop);
        }
        if (tradeManager != null) {
            // Antes que storage: los trades en curso devuelven su deposito (al alijo si offline).
            shutdownSafely("direct trades", tradeManager::shutdown);
        }
        if (marketManager != null) {
            // Antes que storage: devuelve el trueque en deposito al alijo y este aun persiste.
            shutdownSafely("market", marketManager::shutdown);
        }
        if (toggleManager != null) {
            shutdownSafely("toggles", toggleManager::shutdown);
        }
        if (nickManager != null) {
            shutdownSafely("nicknames", nickManager::shutdown);
        }
        if (anonymousManager != null) {
            shutdownSafely("anonymous mode", anonymousManager::shutdown);
        }
        // Matrimonios se apagan en MarriageModule.disable(), via disableAll() mas abajo:
        // es una hoja y solo necesita cerrar antes que storage, que se apaga el ultimo.
        if (proxyManager != null && proxyManager.isEnabled()) {
            shutdownSafely("proxy sync", proxyManager::disable);
        }
        if (warningPoints != null) {
            shutdownSafely("warning points", warningPoints::shutdown);
        }
        if (chatColorManager != null) {
            shutdownSafely("chat colors", chatColorManager::shutdown);
        }
        // Modulos en orden inverso al de encendido: reports y mail hacen su flush primero y
        // storage se apaga al final (flush final de stores + cierre de la base de datos).
        if (modules != null) {
            shutdownSafely("modules", modules::disableAll);
        }
        if (regionPartyTracker != null) {
            shutdownSafely("region/party tracker", regionPartyTracker::stop);
        }
        if (heldItemTracker != null) {
            shutdownSafely("held-item tracker", heldItemTracker::stop);
        }
        if (packetEventsIntegration != null) {
            shutdownSafely("PacketEvents", packetEventsIntegration::disable);
        }
        // Bossbars de anuncios animados aun en vuelo: sin esto quedarian visibles hasta relog.
        shutdownSafely("announce animations", () -> dev.chatalyst.announce.AnnounceAnimator.shutdownAll(this));
        instance = null;
    }

    /** Un fallo de un modulo nunca debe impedir el flush del storage ni los demas cierres. */
    private void shutdownSafely(String component, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            getLogger().severe("Failed to shut down " + component + ": " + t.getMessage());
        }
    }

    public TabListManager getTabListManager() {
        return tabListManager;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }

    public dev.chatalyst.tablist.FancyNametagManager getFancyNametags() {
        return fancyNametagManager;
    }

    public MessageDeleteManager getMessageDeleteManager() {
        return messageDeleteManager;
    }

    public Announcer getAnnouncer() {
        return announcer;
    }

    public PrivateMessages getPrivateMessages() {
        return privateMessages;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    public ProxyManager getProxyManager() {
        return proxyManager;
    }

    public dev.chatalyst.network.NetworkMonitor getNetworkMonitor() {
        return networkMonitor;
    }

    public dev.chatalyst.announce.AnnounceDraftManager getAnnounceDraftManager() {
        return announceDraftManager;
    }

    public dev.chatalyst.channel.ChannelRouterListener getChannelRouter() {
        return channelRouter;
    }

    public dev.chatalyst.channel.StaffMentionRouter getStaffMentionRouter() {
        return staffMentionRouter;
    }

    public dev.chatalyst.support.SupportConversationManager getSupportConversations() {
        return supportConversations;
    }

    public dev.chatalyst.staff.StaffDirectoryManager getStaffDirectory() {
        return staffDirectory;
    }

    public dev.chatalyst.staff.NetworkPlayerDirectory getNetworkPlayerDirectory() {
        return networkPlayerDirectory;
    }

    public dev.chatalyst.channel.ChannelHistory getChannelHistory() {
        return channelHistory;
    }

    public dev.chatalyst.channel.FrequencyManager getFrequencyManager() {
        return frequencyManager;
    }

    public dev.chatalyst.chat.GroupOverrides getGroupOverrides() {
        return groupOverrides;
    }

    public dev.chatalyst.tag.TagManager getTagManager() {
        return tagManager;
    }

    public dev.chatalyst.tag.TitleCounters getTitleCounters() {
        return titleCounters;
    }

    public dev.chatalyst.tag.TagCatalog getTagCatalog() {
        return tagCatalog;
    }

    public dev.chatalyst.tag.TagAdminPrompt getTagAdminPrompt() {
        return tagAdminPrompt;
    }

    /** Stable integration facade. Prefer Bukkit's ServicesManager from external plugins. */
    public dev.chatalyst.api.ChatalystApi getApi() {
        return chatalystApi;
    }

    public dev.chatalyst.tablist.NametagTitleManager getNametagTitleManager() {
        return nametagTitleManager;
    }

    public dev.chatalyst.tablist.NametagTitleEditor getNametagTitleEditor() {
        return nametagTitleEditor;
    }

    public dev.chatalyst.channel.ChatRegions getChatRegions() {
        return chatRegions;
    }

    public dev.chatalyst.hook.TradePluginsHook getTradePluginsHook() {
        return tradePluginsHook;
    }

    public dev.chatalyst.market.MarketManager getMarketManager() {
        return marketManager;
    }

    public dev.chatalyst.market.TradeManager getTradeManager() {
        return tradeManager;
    }

    public NickManager getNickManager() {
        return nickManager;
    }

    public MuteManager getMuteManager() {
        return muteManager;
    }

    public dev.chatalyst.db.StorageManager getStorageManager() {
        return storageManager;
    }

    public dev.chatalyst.staff.StaffModerationModule getStaffModeration() {
        return staffModeration;
    }

    public dev.chatalyst.gui.ChatColorManager getChatColorManager() {
        return chatColorManager;
    }

    public dev.chatalyst.discord.StaffNotifier getStaffNotifier() {
        return staffNotifier;
    }

    public DiscordBot getDiscordBot() {
        return discordBot;
    }

    /** Reconnects the embedded bot after integrations.yml changes. */
    public void reloadDiscordBot() {
        dev.chatalyst.hook.LuckPermsHook.reset();
        if (getDiscordRankSync() != null) getDiscordRankSync().reload();
        if (discordBot != null) discordBot.reloadInBackground();
        if (getDiscordLinkGate() != null) getDiscordLinkGate().reload();
        // Picks up a renamed or newly enabled storefront command without a restart.
        // Isolated: re-registering a command touches server internals that differ between
        // Paper builds, and one of them failing must not abort the whole reload — chat,
        // moderation and storage have nothing to do with a command label.
        // An edited kit must show its new contents without a restart.
        if (getTebexShop() != null) getTebexShop().invalidateKitCache();
        applySafely("rank storefront command",
                monetisation == null ? null : monetisation.shopCommands());
        applySafely("store command", storeCommands);
        // Un alias editado (Command_Aliases) debe valer sin reiniciar, igual que el resto.
        applySafely("the configured command aliases", commandAliases);
    }

    private void applySafely(String what, dev.chatalyst.alias.CommandAliasManager aliases) {
        if (aliases == null) return;
        try {
            aliases.apply();
        } catch (RuntimeException failure) {
            getLogger().warning("Could not refresh " + what + " on reload: " + failure
                    + ". Everything else was reloaded; restart to apply an edited alias.");
        }
    }

    private void applySafely(String what, dev.chatalyst.command.RuntimeCommandRegistrar registrar) {
        if (registrar == null) return;
        try {
            registrar.apply();
        } catch (RuntimeException failure) {
            getLogger().warning("Could not refresh the " + what + " on reload: " + failure
                    + ". Everything else was reloaded; restart to apply a renamed command.");
        }
    }

    // Delegan en el modulo de monetizacion. Devuelven null antes de que arranque (igual que
    // el campo suelto que reemplazan), asi que quien llamaba sigue viendo lo mismo.
    public dev.chatalyst.discord.DiscordLinkManager getDiscordLinks() {
        return monetisation == null ? null : monetisation.links();
    }

    public dev.chatalyst.discord.DiscordLinkGate getDiscordLinkGate() {
        return monetisation == null ? null : monetisation.linkGate();
    }

    public dev.chatalyst.store.StoreService getStoreService() {
        return monetisation == null ? null : monetisation.store();
    }

    public dev.chatalyst.tebex.TebexReconciler getTebexReconciler() {
        return monetisation == null ? null : monetisation.reconciler();
    }

    public dev.chatalyst.discord.DiscordPunishmentRoles getPunishmentRoles() {
        return punishmentRoles;
    }

    public dev.chatalyst.discord.DiscordRankSync getDiscordRankSync() {
        return monetisation == null ? null : monetisation.rankSync();
    }

    public dev.chatalyst.tebex.TebexRankService getTebexRanks() {
        return monetisation == null ? null : monetisation.ranks();
    }

    public dev.chatalyst.tebex.TebexPresentationService getTebexPresentations() {
        return monetisation == null ? null : monetisation.presentations();
    }

    public dev.chatalyst.tebex.TebexPresentationPrompt getTebexPresentationPrompt() {
        return monetisation == null ? null : monetisation.presentationPrompt();
    }

    public dev.chatalyst.tebex.TebexShopService getTebexShop() {
        return monetisation == null ? null : monetisation.shop();
    }

    public dev.chatalyst.report.ReportActions getReportActions() {
        return reportActions;
    }

    public dev.chatalyst.module.ModuleRegistry getModules() {
        return modules;
    }

    public dev.chatalyst.afk.AfkManager getAfkManager() {
        return afkManager;
    }

    /** El modulo de sorteos, para quien necesite consultar si hay uno en marcha. */
    public dev.chatalyst.giveaway.GiveawayModule getGiveaways() {
        return giveaways;
    }

    public dev.chatalyst.economy.BalanceTop getBalanceTop() {
        return balanceTop;
    }

    public MailNotifier getMailNotifier() {
        return mailNotifier;
    }

    public dev.chatalyst.chat.HeldItemTracker getHeldItemTracker() {
        return heldItemTracker;
    }

    public dev.chatalyst.hook.VoiceIntegration getVoiceIntegration() {
        return voiceIntegration;
    }

    public dev.chatalyst.hook.DynmapIntegration getDynmapIntegration() {
        return dynmapIntegration;
    }

    public dev.chatalyst.hook.PacketEventsIntegration getPacketEventsIntegration() {
        return packetEventsIntegration;
    }

    public dev.chatalyst.security.StaffSecurityListener getStaffSecurityListener() {
        return staffSecurityListener;
    }
}
