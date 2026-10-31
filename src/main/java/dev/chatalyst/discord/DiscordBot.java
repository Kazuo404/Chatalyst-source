package dev.chatalyst.discord;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.channel.Channel;
import dev.chatalyst.channel.ChannelType;
import dev.chatalyst.hook.DiscordStaffAuthorization;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Bot Discord owned by Chatalyst. It connects and disconnects with the plugin and provides
 * the complete two-way chat bridge without DiscordSRV or webhooks.
 *
 * <p>JDA callbacks never touch player APIs directly. Every Minecraft operation is dispatched
 * through {@link FoliaTasks}, so the same implementation is safe on Paper and Folia.
 */
public final class DiscordBot extends ListenerAdapter {

    private static final int DISCORD_MESSAGE_LIMIT = 2000;
    private static final int MAX_PENDING_SENDS = 128;

    private final ChatalystPlugin plugin;
    private final ConcurrentLinkedQueue<PendingSend> pending = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> unlinkedNotices = new ConcurrentHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final Map<String, AtomicLong> lastWarnings = new ConcurrentHashMap<>();
    private final AtomicLong sessions = new AtomicLong();
    /** Punishment roles being created (or created), by guild and lower-cased name. */
    private final Map<String, java.util.concurrent.CompletableFuture<Role>> roleCreations =
            new ConcurrentHashMap<>();

    private volatile JDA jda;
    private volatile boolean ready;
    private volatile Settings settings = Settings.disabled();
    private volatile DiscordLinkManager accountLinks;
    private volatile DiscordRankSync rankSync;
    private volatile DiscordBanSync banSync;
    /** /licencia del autor del plugin; null en cualquier copia de comprador. */
    private volatile LicenseSlashCommand licenseCommand;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask presenceTask;

    public DiscordBot(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public void bindAccounts(DiscordLinkManager accountLinks, DiscordRankSync rankSync) {
        this.accountLinks = accountLinks;
        this.rankSync = rankSync;
    }

    public void bindBanSync(DiscordBanSync banSync) {
        this.banSync = banSync;
    }

    /**
     * Starts the configured bot asynchronously. JDA performs the gateway login off the server
     * thread; messages produced before READY are kept in a small bounded queue.
     */
    public synchronized void start() {
        if (jda != null) return;
        ready = false;
        Settings loaded = Settings.load(plugin);
        settings = loaded;
        if (!loaded.enabled()) return;

        List<String> missing = new ArrayList<>();
        if (loaded.token().isBlank()) missing.add("Discord.Bot_Token");
        if (!isSnowflake(loaded.guildId())) missing.add("Discord.Guild_Id");
        if (!isSnowflake(loaded.channelId())) missing.add("Discord.Channel_Id");
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("Discord bot is enabled but not configured: "
                    + String.join(", ", missing) + ". Bot startup was skipped.");
            return;
        }

        try {
            List<GatewayIntent> intents = new ArrayList<>(List.of(
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.DIRECT_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT));
            DiscordRankSync sync = rankSync;
            // Resolver @nombre sin pedirlo a la API en cada mensaje exige tener la lista en cache.
            boolean mentionNames = plugin.getConfig().getBoolean("Discord.Mentions.Enabled", false);
            if ((sync != null && sync.requiresMemberIntent()) || mentionNames) {
                intents.add(GatewayIntent.GUILD_MEMBERS);
            }
            // Without this intent Discord never tells us about its own bans, so the mirror
            // would only ever work in one direction and look broken in the other.
            if (plugin.getConfig().getBoolean("Discord.Ban_Sync.Enabled", false)) {
                intents.add(GatewayIntent.GUILD_MODERATION);
            }
            JDABuilder builder = JDABuilder.createLight(loaded.token(), intents)
                    .addEventListeners(this);
            licenseCommand = LicenseSlashCommand.create(plugin, loaded.guildId());
            if (licenseCommand != null) builder.addEventListeners(licenseCommand);
            if (!loaded.activity().isBlank()) {
                builder.setActivity(Activity.playing(loaded.activity()));
            }
            if (mentionNames) {
                builder.setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.ALL)
                        .setChunkingFilter(net.dv8tion.jda.api.utils.ChunkingFilter.ALL);
            }
            jda = builder.build();
            plugin.getLogger().info("Starting Chatalyst's Discord bot...");
        } catch (RuntimeException failure) {
            jda = null;
            plugin.getLogger().warning("Could not start Discord bot: "
                    + safeFailure(failure) + ". Check the token and MESSAGE CONTENT INTENT.");
        }
    }

    /** Applies token/channel changes made through /chatalyst reload. */
    public synchronized void reload() {
        stop();
        start();
    }

    /**
     * Same, off the calling thread: closing the gateway can take seconds and a reload runs on
     * the main thread. During a shutdown there is no scheduler left, so it stays synchronous.
     */
    public void reloadInBackground() {
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> reload());
        } catch (RuntimeException schedulerClosed) {
            reload();
        }
    }

    /** Stops gateway threads immediately and rejects late callbacks from the old connection. */
    public synchronized void stop() {
        stopPresenceUpdates();
        LicenseSlashCommand licenses = licenseCommand;
        licenseCommand = null;
        if (licenses != null) licenses.close();
        JDA old = jda;
        jda = null;
        ready = false;
        pending.clear();
        pendingCount.set(0);
        if (old != null) {
            try {
                old.shutdownNow();
                // Waited for, not just asked to stop. shutdownNow() returns immediately while
                // JDA's own threads are still unwinding, and on a server shutdown they then
                // race the plugin classloader being closed — the loser is a class that can no
                // longer be read, which surfaces as a "zip file error" stack trace at the end
                // of every stop. Nothing is broken by it, but it is alarming and it hides real
                // errors in the same moment. A couple of seconds is enough for them to finish.
                if (!old.awaitShutdown(java.time.Duration.ofSeconds(3))) {
                    plugin.getLogger().info("The Discord bot is still closing;"
                            + " continuing with shutdown.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // The gateway may already be shutting down after a failed login.
            }
        }
    }

    public boolean isReady() {
        return jda != null && ready;
    }

    /** Read-only access for {@link DiscordSelfTest}; nothing else should reach for the gateway. */
    JDA jdaForDiagnostics() {
        return jda;
    }

    /**
     * True when a player could complete {@code /discord link} right now: the bot is enabled,
     * linking is on, the gateway session is READY and the configured guild is reachable.
     * Enforcement features must gate on this so an outage never locks players out.
     */
    public boolean linkingAvailable() {
        Settings current = settings;
        JDA active = jda;
        return current.enabled() && current.linkingEnabled() && active != null && ready
                && active.getGuildById(current.guildId()) != null;
    }

    @Override
    public void onReady(ReadyEvent event) {
        if (event.getJDA() != jda) return;
        ready = true;
        sessions.incrementAndGet();
        plugin.getLogger().info("Discord bot connected as "
                + event.getJDA().getSelfUser().getName() + " (two-way bridge active).");
        flushPending(event.getJDA());
        startPresenceUpdates();
        DiscordRankSync sync = rankSync;
        if (sync != null) sync.onBotReady();
        LicenseSlashCommand licenses = licenseCommand;
        if (licenses != null) licenses.register(event.getJDA());
    }

    /**
     * Mantiene vivo el estado del bot: cuanta gente hay dentro, ahora mismo.
     *
     * <p>Un estado fijo ("Minecraft chat") envejece mal — dice lo mismo con el servidor lleno
     * que vacio, y en Discord la lista de miembros es lo primero que se mira para decidir si
     * merece la pena entrar. El recuento se lee en el hilo del servidor y se manda desde ahi.
     */
    private synchronized void startPresenceUpdates() {
        if (presenceTask != null) return; // una reconexion no debe dejar dos relojes vivos
        long seconds = Math.max(15L, plugin.getConfig()
                .getLong("Discord.Activity_Refresh_Seconds", 60L));
        presenceTask = plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task -> pushPresence(), 20L, seconds * 20L);
    }

    private synchronized void stopPresenceUpdates() {
        if (presenceTask == null) return;
        presenceTask.cancel();
        presenceTask = null;
    }

    private void pushPresence() {
        JDA active = jda;
        Settings current = settings;
        if (active == null || !ready || current.activity().isBlank()) return;
        int online = (int) plugin.getServer().getOnlinePlayers().stream()
                // Quien esta en vanish no cuenta: el recuento de Discord no puede ser la
                // rendija por la que se ve que hay un moderador mirando.
                .filter(player -> !dev.chatalyst.util.Vanish.isVanished(player))
                .count();
        String text = current.activity()
                .replace("{online}", String.valueOf(online))
                .replace("{max}", String.valueOf(plugin.getServer().getMaxPlayers()));
        try {
            active.getPresence().setActivity(Activity.playing(text));
        } catch (RuntimeException failure) {
            warnThrottled("presence", "Could not update the Discord status: "
                    + safeFailure(failure));
        }
    }

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (event.getJDA() == jda) ready = false;
    }

    @Override
    public void onSessionResume(SessionResumeEvent event) {
        resume(event.getJDA());
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent event) {
        resume(event.getJDA());
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        if (event.getJDA() != jda) return; // intentional stop already detached it
        ready = false;
        jda = null;
        pending.clear();
        pendingCount.set(0);
        warnThrottled("shutdown", "Discord bot connection closed (code "
                + event.getCode() + "). Check the token, gateway intent and network.");
    }

    private void resume(JDA resumed) {
        if (resumed != jda) return;
        ready = true;
        sessions.incrementAndGet();
        flushPending(resumed);
    }

    /**
     * Cuenta las veces que el bot quedo listo (arranque o reconexion). Quien tenga que repasar
     * lo que paso mientras estaba caido compara con el ultimo valor que vio.
     */
    long sessions() {
        return sessions.get();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Settings current = settings;
        if (event.getJDA() != jda || !current.enabled()) return;
        User user = event.getAuthor();
        Message message = event.getMessage();
        if (user == null || user.isBot() || message.isWebhookMessage()) return;
        String raw = message.getContentRaw();
        if (handlePossibleLinkCommand(event, raw, current)) return;
        if (!event.isFromGuild()) return;
        if (!event.getGuild().getId().equals(current.guildId())) return;

        if (handlePossibleStaffCommand(event, raw, current)) return;
        if (!current.discordToMinecraft()) return;

        String gameChannel = current.gameChannelForDiscord(event.getChannel().getId());
        if (gameChannel == null) return; // unrelated Discord channel

        String content = incomingText(message, current.maxInboundLength());
        if (content.isBlank()) return;
        UUID linkedId = linkedMinecraftId(user.getId(), current);
        // Sin vincular no hay a quien atribuir la linea dentro del juego: ni mute, ni reglas,
        // ni nombre real. Con Linked_Only el puente solo deja hablar a quien se vinculo.
        if (current.linkedOnlyToMinecraft() && linkedId == null) {
            rejectUnlinked(message, user.getId(), current);
            return;
        }
        String discordName = event.getMember() != null
                ? event.getMember().getEffectiveName() : user.getName();
        String author = inboundAuthor(
                linkedId != null && current.useMinecraftName()
                        ? plugin.getServer().getOfflinePlayer(linkedId).getName() : null,
                discordName);

        ChatalystPlugin active = ChatalystPlugin.get();
        // Un mute o un /chatlock que no alcanza al puente no es un mute: bastaba con escribir
        // desde Discord para seguir hablando en el juego con el castigo puesto.
        if (silencedInGame(active, user.getId(), current)) {
            message.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("\uD83D\uDD07"))
                    .queue(null, ignored -> { });
            return;
        }
        dev.chatalyst.log.LogManager log = active == null ? null : active.getLogManager();
        dev.chatalyst.swear.SwearFilter swear = active == null ? null : active.getSwearFilter();
        if (swear != null && swear.isEnabled()) {
            dev.chatalyst.swear.SwearFilter.Result filtered = swear.apply(content);
            if (filtered.matched()) {
                boolean deny = swear.getAction() == dev.chatalyst.swear.SwearFilter.Action.DENY;
                if (log != null && log.logsSwear()) {
                    log.log("SWEAR", "[discord] " + author,
                            (deny ? "[denied] " : "[censored] ") + content);
                }
                if (deny) return;
                content = filtered.text();
            }
        }

        String acceptedContent = content;
        applyLinkedDiscordRules(user.getId(), acceptedContent, current,
                accepted -> deliverToMinecraft(author, accepted, gameChannel, current, log));
    }

    // Solo los roles que CAMBIARON en este evento, no la lista entera: con la lista, un rol de
    // rango que el miembro no tenia contaba como "quitado" y Discord podia arrancar en
    // Minecraft un rango que nadie habia tocado en Discord.
    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        handleRoleUpdate(event.getJDA(), event.getGuild(), event.getUser().getId(),
                event.getRoles(), true);
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        handleRoleUpdate(event.getJDA(), event.getGuild(), event.getUser().getId(),
                event.getRoles(), false);
    }

    private void handleRoleUpdate(JDA source, Guild guild, String discordId, List<Role> changed,
                                  boolean added) {
        Settings current = settings;
        DiscordRankSync sync = rankSync;
        if (source != jda || sync == null || !current.enabled()
                || !guild.getId().equals(current.guildId())) {
            return;
        }
        sync.onDiscordRoleChange(discordId,
                changed.stream().map(Role::getId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()), added);
    }

    /** Relays an accepted Minecraft chat line through the bot account. */
    public void sendMinecraftMessage(String gameChannel, String playerName, String message) {
        sendMinecraftMessage(gameChannel, playerName, message, "");
    }

    /** Same, knowing the player's rank group (for the rank tag of the webhook line). */
    public void sendMinecraftMessage(String gameChannel, String playerName, String message,
                                     String rankGroup) {
        sendMinecraftMessage(gameChannel, playerName, message, rankGroup, false, false);
    }

    /**
     * @param styled     the player may use colours, so their tags are style and not text
     * @param canMention the player may notify Discord members with @name
     */
    public void sendMinecraftMessage(String gameChannel, String playerName, String message,
                                     String rankGroup, boolean styled, boolean canMention) {
        Settings current = settings;
        if (!current.enabled() || !current.minecraftToDiscord()) return;

        String channelId = current.discordChannelForGame(gameChannel);
        if (channelId == null) {
            // The public/default bridge only receives global chat. A private/local channel
            // needs an explicit Channel_Ids entry so it cannot leak accidentally.
            if (gameChannel != null && !gameChannel.isBlank()) {
                dev.chatalyst.channel.ChannelManager manager = plugin.getChannelManager();
                Channel channel = manager == null ? null : manager.byName(gameChannel);
                // Fail closed: only a channel positively confirmed as GLOBAL falls back to the
                // public bridge. An unresolvable name (manager not ready yet, channel deleted
                // while someone was writing in it) previously leaked private chat publicly.
                if (channel == null || channel.type != ChannelType.GLOBAL) return;
            }
            channelId = current.channelId();
        }

        DiscordChatText.Result text = DiscordChatText.render(message, styled, current.emojiTable(),
                canMention && current.mentionsEnabled() ? mentionResolver(current) : null,
                current.maxMentions());
        if (current.usesWebhookFor(channelId)) {
            sendAsPlayer(current, playerName, text, rankGroup);
            return;
        }
        String rendered = renderDiscordFormat(current.formatForGame(gameChannel),
                escapeMarkdown(playerName),
                text.text(),
                escapeMarkdown(gameChannel == null ? "" : gameChannel));
        sendChannel(channelId, rendered, text.mentions());
    }

    /** Nombre visible a ID de miembro, solo si es inequivoco y sale de la cache. */
    private java.util.function.Function<String, String> mentionResolver(Settings current) {
        JDA active = jda;
        var guild = active == null || !ready ? null : active.getGuildById(current.guildId());
        if (guild == null) return null;
        return name -> {
            List<net.dv8tion.jda.api.entities.Member> found =
                    guild.getMembersByEffectiveName(name, true);
            return found.size() == 1 ? found.getFirst().getId() : null;
        };
    }

    /**
     * Publica la linea con el nombre y la cabeza del jugador, como si la escribiera el en Discord.
     *
     * <p>La linea del bot («**Jugador** › mensaje»)
     * se leia como un registro. Por webhook cada mensaje lleva su avatar y su nombre. Si el
     * webhook falla (borrado, URL mal copiada) la linea vuelve a salir por el bot: el chat no se
     * pierde por un ajuste de presentacion.
     */
    private void sendAsPlayer(Settings current, String playerName, DiscordChatText.Result message,
                              String rankGroup) {
        JDA active = jda;
        String fallback = renderDiscordFormat(current.toDiscordFormat(), escapeMarkdown(playerName),
                message.text(), "");
        if (active == null || !ready) {
            sendChannel(current.channelId(), fallback, message.mentions());
            return;
        }
        String name = webhookUsername(current.webhookName(), playerName);
        String avatar = current.webhookAvatar().replace("{player}",
                java.net.URLEncoder.encode(playerName, java.nio.charset.StandardCharsets.UTF_8));
        String content = discordBounded(renderedWebhookContent(current.webhookFormat(),
                current.rankTags(), rankGroup, playerName, message.text()));
        try {
            var request = net.dv8tion.jda.api.entities.WebhookClient
                    .createClient(active, current.webhookUrl())
                    .sendMessage(content)
                    .setUsername(name)
                    .setAllowedMentions(Collections.emptyList())
                    .mentionUsers(message.mentions());
            if (!avatar.isBlank()) request = request.setAvatarUrl(avatar);
            request.queue(null, failure -> {
                warnThrottled("webhook", "Discord webhook failed (" + safeFailure(failure)
                        + "); sending through the bot instead.");
                sendChannel(current.channelId(), fallback, message.mentions());
            });
        } catch (RuntimeException invalid) {
            warnThrottled("webhook-url", "Discord.Webhook.Url is not a valid webhook URL: "
                    + safeFailure(invalid));
            sendChannel(current.channelId(), fallback, message.mentions());
        }
    }

    /**
     * Texto del mensaje del webhook. {@code {rank_tag}} es el tag del rango del jugador como
     * emojis de Discord (Discord.Webhook.Rank_Tags, grupo -> emojis); es texto del dueño del
     * servidor, asi que entra sin escapar y ANTES de los valores del jugador, que si se escapan.
     */
    static String webhookContent(String format, Map<String, String> rankTags, String rankGroup,
                                 String playerName, String message) {
        return renderedWebhookContent(format, rankTags, rankGroup, playerName,
                escapeMarkdown(message));
    }

    private static String renderedWebhookContent(String format, Map<String, String> rankTags,
                                                 String rankGroup, String playerName,
                                                 String safeMessage) {
        String tag = rankGroup == null ? ""
                : rankTags.getOrDefault(rankGroup.toLowerCase(java.util.Locale.ROOT), "");
        String withTag = (format == null || format.isBlank() ? "{message}" : format)
                .replace("{rank_tag}", tag);
        return renderDiscordFormat(withTag, escapeMarkdown(playerName), safeMessage, "").strip();
    }

    /**
     * Nombre visible del webhook: 1-80 caracteres y sin las palabras que Discord rechaza en el
     * nombre de un webhook («discord», «clyde»).
     */
    static String webhookUsername(String format, String playerName) {
        String name = (format == null || format.isBlank() ? "{player}" : format)
                .replace("{player}", playerName == null ? "" : playerName).strip();
        name = name.replaceAll("(?i)clyde|discord", "").strip();
        if (name.isEmpty()) name = "Jugador";
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    /** Sends join/quit and other public system lines to the main bridge channel. */
    public void sendSystemMessage(String message) {
        Settings current = settings;
        if (current.enabled() && current.minecraftToDiscord()) {
            sendChannel(current.channelId(), message);
        }
    }

    /** Sends a private staff signal through the bot instead of a webhook. */
    public void sendStaffMessage(String message, boolean priority) {
        Settings current = settings;
        if (!current.enabled() || !current.staffEnabled()) return;
        String channelId = priority && !current.priorityStaffChannelId().isBlank()
                ? current.priorityStaffChannelId() : current.staffChannelId();
        sendChannel(channelId, message);
    }

    public String linkCommand() {
        String command = settings.linkCommand();
        return command == null || command.isBlank() ? "!link" : command;
    }

    private boolean handlePossibleLinkCommand(MessageReceivedEvent event, String raw,
                                              Settings current) {
        DiscordLinkManager links = accountLinks;
        if (!current.linkingEnabled() || links == null || raw == null) return false;
        if (event.isFromGuild()
                && !event.getGuild().getId().equals(current.guildId())) {
            return false;
        }
        // Un canal propio para vincular, si se configuro: el codigo deja de aparecer en
        // mitad de cualquier conversacion. El privado con el bot vale SIEMPRE — es el sitio
        // mas discreto para mandarlo, y cerrarlo obligaria a escribirlo en publico.
        String linkChannel = current.linkChannelId();
        if (event.isFromGuild() && !linkChannel.isBlank()
                && !event.getChannel().getId().equals(linkChannel)) {
            return false;
        }
        String command = current.linkCommand();
        if (command.isBlank()) command = "!link";
        if (!raw.regionMatches(true, 0, command, 0, command.length())) return false;
        if (raw.length() > command.length()
                && !Character.isWhitespace(raw.charAt(command.length()))) {
            return false;
        }
        String code = raw.substring(command.length()).trim();
        if (code.isBlank()) {
            sendReply(event.getChannel(), "Usage: `" + command + " <code>` — generate one"
                    + " in Minecraft with `/discord link`.");
            return true;
        }
        String name = event.isFromGuild() && event.getMember() != null
                ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        links.complete(code, event.getAuthor().getId(), name)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        sendReply(event.getChannel(),
                                "The link could not be saved. Please try again.");
                        return;
                    }
                    switch (result.status()) {
                        case SUCCESS -> {
                            // El nombre se resuelve del UUID, no del que traia el codigo: lo
                            // que se vincula es la CUENTA, y quien se cambiara el nombre entre
                            // pedir el codigo y usarlo leeria aqui un nombre que ya no es suyo.
                            String account = dev.chatalyst.util.PlayerNames.current(
                                    plugin.getServer(), result.minecraftId(),
                                    result.playerName());
                            sendReply(event.getChannel(), "Linked successfully to Minecraft"
                                    + (account == null || account.isBlank() ? "."
                                    : " account **" + escapeMarkdown(account) + "**."));
                            DiscordRankSync sync = rankSync;
                            if (sync != null) {
                                sync.onLinked(result.minecraftId(), event.getAuthor().getId());
                            }
                        }
                        case EXPIRED -> sendReply(event.getChannel(),
                                "That code expired. Generate a new one with `/discord link`.");
                        case ALREADY_USED -> sendReply(event.getChannel(),
                                "That code was already used.");
                        case STORAGE_FAILED -> sendReply(event.getChannel(),
                                "The link could not be persisted. The code is still valid; retry.");
                        default -> sendReply(event.getChannel(),
                                "Invalid link code. Generate one with `/discord link`.");
                    }
                });
        return true;
    }

    /**
     * Applies the desired managed role with one Discord REST request. Kept for callers that
     * think in a single rank (unlink, exclusive mode); multi-rank goes through
     * {@link #applyDiscordRoles}.
     */
    public java.util.concurrent.CompletableFuture<Boolean> applyDiscordRank(
            UUID minecraftId, String discordId, String desiredRoleId,
            Set<String> managedRoleIds, boolean exclusive) {
        if (managedRoleIds == null || managedRoleIds.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        Set<String> desired = desiredRoleId != null && managedRoleIds.contains(desiredRoleId)
                ? Set.of(desiredRoleId) : Set.of();
        return applyDiscordRoles(minecraftId, discordId, desired,
                exclusive ? managedRoleIds : Set.of());
    }

    /**
     * Makes the member hold exactly {@code desired} among {@code removable}: adds what is
     * missing, removes what no longer applies, one REST request. Every change this write makes
     * is announced to {@link DiscordRankSync} first, so the gateway event it causes is not
     * mistaken for a staff member editing roles by hand (and fed back into LuckPerms).
     */
    public java.util.concurrent.CompletableFuture<Boolean> applyDiscordRoles(
            UUID minecraftId, String discordId, Set<String> desired, Set<String> removable) {
        java.util.concurrent.CompletableFuture<Boolean> applied =
                new java.util.concurrent.CompletableFuture<>();
        JDA active = jda;
        Settings current = settings;
        if (active == null || !ready || !DiscordBot.isSnowflake(discordId)) {
            applied.complete(false);
            return applied;
        }
        Guild guild = active.getGuildById(current.guildId());
        if (guild == null) {
            applied.complete(false);
            return applied;
        }
        guild.retrieveMemberById(discordId).queue(member -> {
            if (active != jda) {
                applied.complete(false);
                return;
            }
            List<Role> add = new ArrayList<>();
            List<Role> remove = new ArrayList<>();
            Set<String> existing = member.getRoles().stream().map(Role::getId)
                    .collect(java.util.stream.Collectors.toSet());
            for (String roleId : desired) {
                if (existing.contains(roleId)) continue;
                Role role = guild.getRoleById(roleId);
                if (role != null) add.add(role);
            }
            for (String roleId : removable) {
                if (desired.contains(roleId) || !existing.contains(roleId)) continue;
                Role role = guild.getRoleById(roleId);
                if (role != null) remove.add(role);
            }
            if (add.isEmpty() && remove.isEmpty()) {
                applied.complete(true); // already exactly what the catalog asks for
                return;
            }
            DiscordRankSync sync = rankSync;
            if (sync != null) {
                for (Role role : add) sync.expectBotChange(discordId, role.getId(), true);
                for (Role role : remove) sync.expectBotChange(discordId, role.getId(), false);
            }
            guild.modifyMemberRoles(member, add, remove).queue(
                    ignored -> applied.complete(true),
                    failure -> {
                        warnThrottled("rank-write-" + discordId,
                                "Could not synchronize Discord roles for " + minecraftId
                                        + ": " + safeFailure(failure)
                                        + ". Check Manage Roles and the bot role hierarchy.");
                        applied.complete(false);
                    });
        }, failure -> {
            warnThrottled("rank-member-" + discordId,
                    "Could not find linked Discord member " + discordId
                            + " in the configured guild.");
            applied.complete(false);
        });
        return applied;
    }

    /**
     * Bans the linked Discord account from the guild.
     *
     * <p>{@code done} always runs — success or failure — because it releases the guard that
     * stops the two mirrors from bouncing the same ban back and forth. Leaking it once would
     * quietly stop every later ban for that account from syncing.
     */
    void banFromGuild(String discordId, String reason, Runnable done) {
        JDA active = jda;
        Guild guild = active == null || !ready ? null : active.getGuildById(settings.guildId());
        if (guild == null || !isSnowflake(discordId)) {
            done.run();
            return;
        }
        guild.ban(net.dv8tion.jda.api.entities.UserSnowflake.fromId(discordId),
                        0, java.util.concurrent.TimeUnit.SECONDS)
                .reason(discordBounded(reason))
                .queue(ignored -> done.run(), failure -> {
                    warnThrottled("ban-" + discordId, "Could not ban the linked Discord"
                            + " account " + discordId + ": " + safeFailure(failure)
                            + ". Check the bot's Ban Members permission and role position.");
                    done.run();
                });
    }

    /**
     * Puts a member in exactly one of {@code managed}, creating the role if it is missing.
     *
     * <p>The role is looked up by NAME and only created when absent, so a restart does not
     * pile up duplicates and an administrator who renamed it by hand simply gets a new one
     * rather than having their edit fought over.
     *
     * @param wanted the role this member should end up with, or null to just strip the rest.
     */
    void applyPunishmentRole(String discordId, PunishmentTier wanted, List<String> managed) {
        JDA active = jda;
        Guild guild = active == null || !ready ? null : active.getGuildById(settings.guildId());
        if (guild == null || !isSnowflake(discordId)) return;
        guild.retrieveMemberById(discordId).queue(member -> {
            for (Role role : List.copyOf(member.getRoles())) {
                if (!managed.contains(role.getName())) continue;
                if (wanted != null && role.getName().equals(wanted.name())) continue;
                guild.removeRoleFromMember(member, role).queue(null, failure ->
                        warnThrottled("role-remove", "Could not take the role \""
                                + role.getName() + "\" off " + discordId + ": "
                                + safeFailure(failure) + ". They will keep looking punished"
                                + " until the bot's role sits above it."));
            }
            if (wanted == null) return;
            punishmentRole(guild, wanted).thenAccept(role -> {
                if (role == null) return;
                guild.addRoleToMember(member, role).queue(null, failure ->
                        warnThrottled("role-add", "Could not give the punishment role to "
                                + discordId + ": " + safeFailure(failure)
                                + ". Check Manage Roles and the bot's role position."));
            });
        }, failure -> warnThrottled("member-" + discordId,
                // The likeliest reason by far, and until now the quietest: the linked account
                // is simply not in the Discord server. Silence here meant the whole feature
                // could do nothing at all and never say a word about why.
                "Could not find the linked Discord account " + discordId + " in this server: "
                        + safeFailure(failure) + ". Their punishment role was not applied."));
    }

    /**
     * Quita a este miembro los roles de castigo con estos nombres, si lleva alguno.
     *
     * <p>Para el repaso tras arrancar: recorre cuentas que quiza ya no estan en el servidor,
     * asi que una que no se encuentra no es un aviso — serian cientos de lineas de log.
     */
    void stripPunishmentRoles(String discordId, java.util.Collection<String> names) {
        JDA active = jda;
        Guild guild = active == null || !ready ? null : active.getGuildById(settings.guildId());
        if (guild == null || !isSnowflake(discordId) || names.isEmpty()) return;
        guild.retrieveMemberById(discordId).queue(member -> {
            for (Role role : List.copyOf(member.getRoles())) {
                if (!names.contains(role.getName())) continue;
                guild.removeRoleFromMember(member, role).queue(null, failure ->
                        warnThrottled("role-remove", "Could not take the role \""
                                + role.getName() + "\" off " + discordId + ": "
                                + safeFailure(failure) + ". They will keep looking punished"
                                + " until the bot's role sits above it."));
            }
        }, ignored -> {
            // Ya no esta en el servidor: no hay rol que quitar.
        });
    }

    /**
     * The punishment role with this name, created at most once.
     *
     * <p>The name lookup reads JDA's cache, which only learns about a new role once Discord
     * confirms it. The first sweep after enabling the feature asks for the same role once per
     * punished member in a row, so each call saw "missing" and created its own copy: ten bans,
     * ten identical roles. The creation in flight is shared instead, and kept afterwards for
     * as long as the role it made still exists.
     *
     * @return the role, or null when it could not be created (already logged)
     */
    private java.util.concurrent.CompletableFuture<Role> punishmentRole(Guild guild,
                                                                       PunishmentTier wanted) {
        Role existing = guild.getRolesByName(wanted.name(), false).stream()
                .findFirst().orElse(null);
        if (existing != null) return java.util.concurrent.CompletableFuture.completedFuture(existing);
        String key = guild.getId() + ":" + wanted.name().toLowerCase(Locale.ROOT);
        return roleCreations.compute(key, (ignored, inFlight) -> {
            if (inFlight != null && (!inFlight.isDone() || stillExists(guild, inFlight))) {
                return inFlight;
            }
            java.util.concurrent.CompletableFuture<Role> creation =
                    new java.util.concurrent.CompletableFuture<>();
            if (guild.getRoles().size() >= PunishmentTier.GUILD_ROLE_LIMIT - 1) {
                warnThrottled("role-limit", "This Discord server is at its role limit ("
                        + PunishmentTier.GUILD_ROLE_LIMIT + "); the punishment role \""
                        + wanted.name() + "\" was not created.");
                creation.complete(null); // not cached as a role: the next call checks again
                return creation;
            }
            guild.createRole().setName(wanted.name())
                    .setColor(wanted.color())
                    .setMentionable(false)
                    .queue(creation::complete, failure -> {
                        warnThrottled("role-create",
                                "Could not create the punishment role \"" + wanted.name()
                                        + "\": " + safeFailure(failure)
                                        + ". Check Manage Roles.");
                        creation.complete(null);
                    });
            return creation;
        });
    }

    private static boolean stillExists(Guild guild,
                                       java.util.concurrent.CompletableFuture<Role> creation) {
        Role role = creation.getNow(null);
        return role != null && guild.getRoleById(role.getId()) != null;
    }

    /**
     * Gives or takes one specific role, by id.
     *
     * <p>Idempotent on purpose: Discord accepts adding a role somebody already has, so the
     * repair on join can just say what should be true instead of first asking what is.
     */
    void setRole(String discordId, String roleId, boolean add) {
        JDA active = jda;
        Guild guild = active == null || !ready ? null : active.getGuildById(settings.guildId());
        if (guild == null || !isSnowflake(discordId) || !isSnowflake(roleId)) return;
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            warnThrottled("role-missing-" + roleId, "The Discord role " + roleId
                    + " does not exist in this server; nothing was changed.");
            return;
        }
        var request = add
                ? guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake
                        .fromId(discordId), role)
                : guild.removeRoleFromMember(net.dv8tion.jda.api.entities.UserSnowflake
                        .fromId(discordId), role);
        request.queue(null, failure -> warnThrottled("role-set-" + roleId,
                "Could not " + (add ? "give" : "take") + " the Discord role " + roleId
                        + ": " + safeFailure(failure)
                        + ". Check Manage Roles and that the bot's role sits above it."));
    }

    /**
     * Sends a private message and only then runs {@code after}.
     *
     * <p>The order is the point: once someone is banned from the guild the bot may share no
     * server with them any more, and Discord refuses to open the DM. Telling them why they
     * were banned has to happen while they can still be reached.
     */
    void dmThen(String discordId, String message, Runnable after) {
        JDA active = jda;
        if (active == null || !ready || !isSnowflake(discordId) || message == null) {
            after.run();
            return;
        }
        // Every failure still runs `after`: the ban must not depend on the message arriving.
        // But it is reported, because the DM is the only way the person learns WHY they were
        // removed — and most Discord accounts refuse messages from strangers, so this fails
        // often and quietly. Staff seeing it can tell them another way; not seeing it, they
        // assume the explanation went out.
        Runnable undelivered = () -> {
            warnThrottled("dm-" + discordId, "Could not send the ban explanation to "
                    + discordId + " (their Discord privacy settings likely block messages"
                    + " from this server). They were removed without being told why.");
            after.run();
        };
        active.retrieveUserById(discordId).queue(user -> user.openPrivateChannel().queue(
                        channel -> channel.sendMessage(discordBounded(message))
                                .setAllowedMentions(Collections.emptyList())
                                .queue(ignored -> after.run(), ignored -> undelivered.run()),
                        ignored -> undelivered.run()),
                ignored -> undelivered.run());
    }

    void unbanFromGuild(String discordId, Runnable done) {
        JDA active = jda;
        Guild guild = active == null || !ready ? null : active.getGuildById(settings.guildId());
        if (guild == null || !isSnowflake(discordId)) {
            done.run();
            return;
        }
        guild.unban(net.dv8tion.jda.api.entities.UserSnowflake.fromId(discordId))
                .queue(ignored -> done.run(), failure -> {
                    // "They were not banned" is the ordinary case, not a problem: most unbans
                    // are for people this bot never removed from Discord in the first place.
                    if (!isMissingBan(failure)) {
                        warnThrottled("unban-" + discordId, "Could not lift the Discord ban"
                                + " on " + discordId + ": " + safeFailure(failure)
                                + ". They are unbanned in Minecraft but still shut out of"
                                + " Discord; check Ban Members and the role position.");
                    }
                    done.run();
                });
    }

    private static boolean isMissingBan(Throwable failure) {
        return failure instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException error
                && error.getErrorResponse()
                        == net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_BAN;
    }

    @Override
    public void onGuildBan(net.dv8tion.jda.api.events.guild.GuildBanEvent event) {
        if (event.getJDA() != jda) return;
        if (!event.getGuild().getId().equals(settings.guildId())) return;
        DiscordBanSync sync = banSync;
        if (sync != null) sync.onDiscordBan(event.getUser().getId());
    }

    @Override
    public void onGuildUnban(net.dv8tion.jda.api.events.guild.GuildUnbanEvent event) {
        if (event.getJDA() != jda) return;
        if (!event.getGuild().getId().equals(settings.guildId())) return;
        DiscordBanSync sync = banSync;
        if (sync != null) sync.onDiscordUnban(event.getUser().getId());
    }

    /** Reads current roles through REST, so initial linking does not depend on member caching. */
    public void readDiscordRoles(String discordId) {
        JDA active = jda;
        DiscordRankSync sync = rankSync;
        if (active == null || !ready || sync == null || !isSnowflake(discordId)) return;
        Guild guild = active.getGuildById(settings.guildId());
        if (guild == null) return;
        guild.retrieveMemberById(discordId).queue(member ->
                        sync.onDiscordRoles(discordId, member.getRoles().stream()
                                .map(Role::getId)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())),
                failure -> warnThrottled("roles-read-" + discordId,
                        "Could not read the Discord roles of " + discordId + ": "
                                + safeFailure(failure) + ". Their rank was not synced from"
                                + " Discord; check SERVER MEMBERS INTENT."));
    }

    /**
     * Sends a DM using the explicit UUID -> Discord ID mapping in integrations.yml. This keeps
     * report notifications available without depending on another plugin's account-link store.
     */
    public void dmLinkedUser(UUID minecraftId, String message) {
        if (minecraftId == null || message == null || message.isBlank()) return;
        Settings current = settings;
        DiscordLinkManager links = accountLinks;
        String discordId = links == null ? null : links.discordId(minecraftId);
        if (discordId == null) {
            discordId = current.minecraftToDiscordUsers().get(minecraftId);
        }
        JDA active = jda;
        if (discordId == null || active == null || !ready) {
            return;
        }
        // Reported once per account per minute, not per message: a reporter whose privacy
        // settings refuse strangers never hears back about their own report, and until now
        // the staff had no way to know the answer had not reached them.
        String target = discordId;
        Runnable undelivered = () -> warnThrottled("dm-linked-" + target,
                "Could not send a private message to the linked account " + target
                        + "; their Discord privacy settings likely block it.");
        active.retrieveUserById(discordId).queue(user -> {
            if (user == null) return;
            user.openPrivateChannel().queue(channel ->
                            channel.sendMessage(discordBounded(message))
                                    .setAllowedMentions(Collections.emptyList())
                                    .queue(null, ignored -> undelivered.run()),
                    ignored -> undelivered.run());
        }, ignored -> undelivered.run());
    }

    /**
     * True si lo que escribe esta cuenta de Discord no debe llegar al chat del juego: el chat
     * esta bloqueado para todos, o la cuenta de Minecraft vinculada esta muteada (tambien con
     * mute silencioso, cuyo sentido es justamente que los demas no lo lean).
     */
    private boolean silencedInGame(ChatalystPlugin active, String discordId, Settings current) {
        if (active == null) return false;
        var chat = active.getChatModeration();
        if (chat != null && chat.isLocked()) return true;
        var mutes = active.getMuteManager();
        if (mutes == null) return false;
        DiscordLinkManager links = accountLinks;
        UUID linked = links == null ? null : links.minecraftId(discordId);
        if (linked == null) linked = current.discordToMinecraftUsers().get(discordId);
        return linked != null && mutes.getMute(linked) != null;
    }

    private UUID linkedMinecraftId(String discordId, Settings current) {
        DiscordLinkManager links = accountLinks;
        UUID linked = links == null ? null : links.minecraftId(discordId);
        return linked != null ? linked : current.discordToMinecraftUsers().get(discordId);
    }

    /** Nombre de Minecraft del vinculado si se conoce; si no, el de Discord. */
    static String inboundAuthor(String minecraftName, String discordName) {
        return minecraftName != null && !minecraftName.isBlank() ? minecraftName : discordName;
    }

    /**
     * Avisa a quien escribe sin vincular. Una vez por minuto y por usuario, y el aviso se
     * borra solo: si no, cada linea suya dejaria otra respuesta del bot en el canal.
     */
    private void rejectUnlinked(Message message, String discordId, Settings current) {
        message.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🔗"))
                .queue(null, ignored -> { });
        long now = System.currentTimeMillis();
        Long last = unlinkedNotices.get(discordId);
        if (last != null && now - last < 60_000L) return;
        unlinkedNotices.put(discordId, now);
        String text = current.unlinkedReply().replace("{link_command}", linkCommand());
        if (text.isBlank()) return;
        message.reply(text).queue(
                sent -> sent.delete().queueAfter(20, java.util.concurrent.TimeUnit.SECONDS,
                        null, ignored -> { }),
                ignored -> { });
    }

    private void applyLinkedDiscordRules(String discordId, String content, Settings current,
                                         Consumer<String> accepted) {
        ChatalystPlugin active = ChatalystPlugin.get();
        dev.chatalyst.rules.RuleEngine rules = active == null ? null : active.getRuleEngine();
        DiscordLinkManager links = accountLinks;
        UUID linked = links == null ? null : links.minecraftId(discordId);
        if (linked == null) linked = current.discordToMinecraftUsers().get(discordId);
        if (rules == null || !rules.isEnabled() || linked == null) {
            accepted.accept(content);
            return;
        }
        UUID linkedMinecraftId = linked;

        FoliaTasks.global(plugin, () -> {
            org.bukkit.entity.Player player = plugin.getServer().getPlayer(linkedMinecraftId);
            if (player == null) {
                accepted.accept(content);
                return;
            }
            FoliaTasks.entity(plugin, player, online -> {
                try {
                    dev.chatalyst.rules.RuleResult result = rules.check(
                            online, content, dev.chatalyst.rules.RuleContext.DISCORD);
                    if (!result.cancelled) accepted.accept(result.message);
                } catch (RuntimeException failure) {
                    warnThrottled("rules", "Discord rule evaluation failed safely: "
                            + safeFailure(failure));
                    accepted.accept(content);
                }
            }, () -> accepted.accept(content));
        });
    }

    private void deliverToMinecraft(String author, String content, String gameChannel,
                                    Settings current, dev.chatalyst.log.LogManager log) {
        if (log != null && log.logsDiscord()) {
            log.log("DISCORD", author, content);
        }

        String safeAuthor = author.replace("<", "").replace(">", "");
        String template = current.toMinecraftFormat()
                .replace("{discord_user}", "<chatalyst_discord_user>")
                .replace("{message}", "<chatalyst_discord_message>");
        Component line = MM.full(template,
                Placeholder.unparsed("chatalyst_discord_user", safeAuthor),
                Placeholder.unparsed("chatalyst_discord_message", content));

        if (gameChannel.isEmpty()) {
            FoliaTasks.broadcast(plugin, line);
            return;
        }

        dev.chatalyst.channel.ChannelManager manager = plugin.getChannelManager();
        Channel channel = manager == null ? null : manager.byName(gameChannel);
        if (channel == null) {
            warnThrottled("missing-game-channel-" + gameChannel,
                    "Discord.Channel_Ids maps to unknown game channel '" + gameChannel
                            + "'; inbound messages for it are being dropped.");
            return;
        }
        FoliaTasks.onlinePlayers(plugin, player -> manager.listens(player, channel),
                player -> player.sendMessage(line));
        FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(line));
    }

    private boolean handlePossibleStaffCommand(MessageReceivedEvent event, String raw,
                                               Settings current) {
        if (!current.handleStaffFromDiscord() || raw == null) return false;
        String prefix = current.staffCommandPrefix();
        if (prefix.isBlank() || !raw.startsWith(prefix)) return false;

        // Newlines collapsed before anything reads this. A Discord message can hold them and a
        // command line never legitimately does: left in, they let somebody forge a second line
        // in the console audit trail, and they reach whatever arg parsing the target command
        // happens to do. Bukkit itself splits on spaces, so nothing is lost by removing them.
        String body = raw.substring(prefix.length()).replaceAll("\\s+", " ").trim();
        if (body.isEmpty()) return false;
        String verb = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        boolean reportVerb = List.of("reports", "info", "reply", "claim", "resolve", "pending",
                        "reject", "reopen")
                .contains(verb);
        // Una herramienta de moderacion de verdad (ban, kick, freeze...). Solo se reconoce si
        // el dueño ha abierto esa puerta APARTE: banear desde Discord no es lo mismo que
        // cerrar un reporte, y meterlo bajo el mismo interruptor lo daria por concedido.
        var outside = dev.chatalyst.staff.StaffCommandCatalog.fromOutside(verb);
        boolean staffTool = current.staffCommandsFromDiscord()
                && outside == dev.chatalyst.staff.StaffCommandCatalog.FromOutside.ALLOWED;
        if (!reportVerb && !staffTool) {
            // Sin abrir la puerta, una etiqueta que existe pero se niega merece una
            // explicacion, y la de VERDAD: el silencio parece que el bot esta roto, y un
            // motivo equivocado manda al staff a arreglar algo que no esta mal.
            if (outside != dev.chatalyst.staff.StaffCommandCatalog.FromOutside.UNKNOWN
                    && isAuthorizedStaffCommand(event, current)) {
                // La puerta cerrada se dice ANTES que el motivo del comando: con el
                // interruptor apagado, "necesitas estar dentro" es cierto y aun asi manda al
                // staff a arreglar lo que no esta mal.
                sendReply(event.getChannel(), !current.staffCommandsFromDiscord()
                        ? "Staff commands from Discord are off"
                                + " (`Discord.Staff.Commands_From_Discord`)."
                        : switch (outside) {
                            case NEEDS_A_PLAYER -> "`" + verb + "` needs you to be in the game"
                                    + " (it opens something in front of you or moves you).";
                            case IRREVERSIBLE -> "`" + verb + "` cannot be undone, so it is"
                                    + " not accepted from Discord — an inventory does not come"
                                    + " back. Run it in game, looking at who you are running"
                                    + " it on.";
                            default -> "`" + verb + "` is not available from Discord.";
                        });
                return true;
            }
            return false;
        }

        MessageChannel channel = event.getChannel();
        if (!isAuthorizedStaffCommand(event, current)) {
            sendReply(channel,
                    "You are not authorized to manage reports from this channel.");
            return true;
        }
        if (staffTool) {
            runStaffTool(body, event, channel);
            return true;
        }
        String author = event.getMember() != null
                ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        handleReportCommand(body, author, channel, current);
        return true;
    }

    /**
     * Un boton pulsado en la tarjeta de un reporte.
     *
     * <p>Pasa por la MISMA puerta que los comandos de texto: mismo interruptor
     * ({@code Handle_From_Discord}), mismo canal permitido y mismos usuarios o roles. Un boton
     * es tan poderoso como escribir {@code !resolve}, asi que abrirlo aparte seria dejar una
     * segunda puerta con otra cerradura.
     */
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        DiscordButton.Action requested = DiscordButton.parse(event.getComponentId());
        if (requested == null) return; // no es nuestro
        Settings current = settings;
        if (!current.handleStaffFromDiscord()) {
            event.reply(bridgeText("reports-disabled")).setEphemeral(true).queue();
            return;
        }
        if (!isAuthorized(event.getChannel().getId(), event.getUser().getId(),
                roleIdsOf(event.getMember()), current)) {
            event.reply("You are not authorized to manage reports from this channel.")
                    .setEphemeral(true).queue();
            return;
        }
        if (DiscordButton.needsText(requested.action())) {
            event.replyModal(textPrompt(requested)).queue();
            return;
        }
        // La respuesta se aplaza: el trabajo real corre en el hilo del servidor, y Discord
        // exige contestar en 3 segundos o da el boton por fallido.
        event.deferReply(true).queue();
        String staff = staffNameOf(event.getMember(), event.getUser());
        runReportAction(requested, "", staff,
                text -> event.getHook().sendMessage(text).setEphemeral(true).queue(),
                () -> settleCard(event.getMessage(), requested.action(), staff));
    }

    /** Lo que el staff escribe en la ventana: la respuesta al jugador, o la nota del cierre. */
    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        DiscordButton.Action requested = DiscordButton.parse(event.getModalId());
        if (requested == null) return;
        Settings current = settings;
        // La misma puerta que el boton: si el dueño apago Handle_From_Discord mientras alguien
        // rellenaba la ventana, enviarla no puede ejecutar la accion igualmente.
        if (!current.handleStaffFromDiscord()) {
            event.reply(bridgeText("reports-disabled")).setEphemeral(true).queue();
            return;
        }
        if (!isAuthorized(event.getChannel().getId(), event.getUser().getId(),
                roleIdsOf(event.getMember()), current)) {
            event.reply("You are not authorized to manage reports from this channel.")
                    .setEphemeral(true).queue();
            return;
        }
        var field = event.getValue("text");
        String text = field == null ? "" : field.getAsString().trim();
        event.deferReply(true).queue();
        String staff = staffNameOf(event.getMember(), event.getUser());
        runReportAction(requested, text, staff,
                reply -> event.getHook().sendMessage(reply).setEphemeral(true).queue(),
                () -> settleCard(event.getMessage(), requested.action(), staff));
    }

    /**
     * Rewrites the card once its report has actually moved on.
     *
     * <p>Without this the card kept saying OPEN with all four buttons live, for ever: nobody
     * could tell at a glance which reports were already dealt with, and two moderators could
     * each resolve the same one without ever seeing the other had. Doing the work and leaving
     * the thing everyone is looking at unchanged is only half the job.
     *
     * <p>A reply does not settle a report — it is a message, not an outcome — so that one
     * leaves the card alone.
     */
    private void settleCard(Message card, String action, String staff) {
        if (card == null || action.equals("reply")) return;
        List<MessageEmbed> embeds = card.getEmbeds();
        if (embeds.isEmpty()) return;
        MessageEmbed original = embeds.getFirst();
        EmbedBuilder rebuilt = new EmbedBuilder(original);
        List<MessageEmbed.Field> fields = List.copyOf(rebuilt.getFields());
        rebuilt.clearFields();
        for (MessageEmbed.Field existing : fields) {
            if ("Status".equals(existing.getName())) {
                rebuilt.addField("Status", "`" + action.toUpperCase(Locale.ROOT) + "` by "
                        + escapeMarkdown(staff), existing.isInline());
            } else {
                rebuilt.addField(existing);
            }
        }
        // Buttons go away rather than being disabled: a greyed-out row still invites a click
        // and still takes up the space, on a card whose story is over.
        card.editMessageEmbeds(rebuilt.build()).setComponents().queue(null, failure ->
                warnThrottled("card-edit", "Could not update the report card: "
                        + safeFailure(failure)));
    }

    private Modal textPrompt(DiscordButton.Action requested) {
        boolean isReply = requested.action().equals("reply");
        // JDA 6: el texto y su etiqueta son componentes distintos, y el campo va DENTRO de
        // la etiqueta. Escribir la respuesta al jugador pide varias lineas; una nota de
        // cierre casi nunca pasa de una.
        TextInput input = TextInput.create("text",
                        isReply ? TextInputStyle.PARAGRAPH : TextInputStyle.SHORT)
                .setRequired(isReply)
                .setMaxLength(400)
                .build();
        Label labelled = Label.of(
                isReply ? "Message for the player" : "Note (optional)", input);
        return Modal.create(DiscordButton.PREFIX + requested.action() + ":"
                        + requested.reportId(),
                        "Report #" + requested.reportId())
                .addComponents(labelled)
                .build();
    }

    /**
     * Ejecuta la accion en el hilo del servidor y cuenta como fue.
     *
     * <p>Las respuestas son efimeras a proposito: el canal de staff se llena de tarjetas de
     * reportes, y un "hecho" por cada pulsacion las separaria hasta hacerlas ilegibles.
     */
    private void runReportAction(DiscordButton.Action requested, String text, String staff,
                                 java.util.function.Consumer<String> respond,
                                 Runnable onSuccess) {
        ChatalystPlugin active = ChatalystPlugin.get();
        dev.chatalyst.report.ReportActions actions =
                active == null ? null : active.getReportActions();
        if (actions == null) {
            respond.accept(bridgeText("reports-disabled"));
            return;
        }
        int id = requested.reportId();
        FoliaTasks.global(plugin, () -> {
            var existing = actions.reports().get(id);
            if (existing != null && existing.priority() && !settings.priorityFromDiscord()) {
                respond.accept(bridgeText("priority", "id", id));
                return;
            }
            boolean ok = switch (requested.action()) {
                case "claim" -> actions.claim(id, staff);
                case "reply" -> actions.reply(id, staff, true, text);
                case "resolve" -> actions.resolve(id, staff, text);
                case "reject" -> actions.reject(id, staff, text);
                case "reopen" -> actions.reopen(id, staff);
                default -> false;
            };
            respond.accept(ok
                    ? bridgeText("done", "verb", requested.action(), "id", id)
                    : bridgeText("failed", "id", id));
            // Only a real change rewrites the card: a failed claim must not leave it looking
            // handled when the report is still sitting there open.
            if (ok) onSuccess.run();
        });
    }

    private static List<String> roleIdsOf(Member member) {
        return member == null ? List.of()
                : member.getRoles().stream().map(Role::getId).toList();
    }

    private static String staffNameOf(Member member, User user) {
        return member != null ? member.getEffectiveName() : user.getName();
    }

    private boolean isAuthorized(String channelId, String userId, List<String> roleIds,
                                 Settings current) {
        return DiscordStaffAuthorization.isAuthorized(channelId, userId, roleIds,
                current.allowedStaffChannelIds(), current.allowedStaffUserIds(),
                current.allowedStaffRoleIds());
    }

    /**
     * Ejecuta una herramienta de staff pedida desde Discord y devuelve lo que conteste.
     *
     * <p>Se despacha como CONSOLA, no como el jugador que la pidio: quien escribe en Discord
     * no esta dentro, y fabricar un emisor con sus permisos seria inventarse una autoridad que
     * el servidor nunca le dio. La autorizacion ya se comprobo antes, en Discord, contra el
     * canal y el usuario o rol permitidos.
     *
     * <p>La respuesta del comando se recoge y se devuelve al canal: un {@code /check} cuyo
     * resultado solo saliera en el log del servidor no le sirve de nada a quien pregunto.
     */
    private void runStaffTool(String body, MessageReceivedEvent event, MessageChannel channel) {
        String who = staffNameOf(event.getMember(), event.getAuthor());
        FoliaTasks.global(plugin, () -> {
            // Queda constancia ANTES de ejecutar: si algo va mal a mitad, el rastro de quien
            // lo pidio ya esta escrito.
            // The name comes from Discord too, so it is flattened for the same reason.
            plugin.getLogger().info("[Discord] " + who.replaceAll("\\s+", " ")
                    + " ran /" + body);
            // La consola se salta el bypass; quien escribe en Discord no. Se le trata como
            // staff no operador: el objetivo protegido dentro del juego lo esta tambien aqui.
            String[] words = body.split(" ");
            String bypass = DiscordStaffTargets.bypassFor(words[0]);
            if (bypass == null || words.length < 2
                    || DiscordStaffTargets.looksLikeAddress(words[1])) {
                dispatchStaffTool(body, channel);
                return;
            }
            OfflinePlayer target = dev.chatalyst.nick.NickManager.resolveOffline(words[1]);
            if (target == null) {
                // Sin cuenta no hay bypass que comprobar, y fiarse seria dejar pasar justo
                // al nombre que el comando si sepa encontrar por otro camino.
                sendReply(channel, "Unknown player: `" + escapeMarkdown(words[1]) + "`.");
                return;
            }
            DiscordStaffTargets.canAffect(plugin, target, bypass).whenComplete((allowed, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(allowed)) {
                    sendReply(channel, "`" + escapeMarkdown(words[1]) + "` is protected from `"
                            + escapeMarkdown(words[0]) + "` (`" + bypass + "` or operator)."
                            + " Do it in game with a rank that can.");
                    return;
                }
                FoliaTasks.global(plugin, () -> dispatchStaffTool(body, channel));
            });
        });
    }

    /** Runs the (already authorized) tool as the console and relays what it answered. */
    private void dispatchStaffTool(String body, MessageChannel channel) {
        ConsoleCapture capture = new ConsoleCapture();
        boolean known;
        try {
            known = plugin.getServer().dispatchCommand(capture.sender(), namespaced(body));
        } catch (RuntimeException failure) {
            sendReply(channel, "The command failed: " + safeFailure(failure));
            return;
        }
        if (!known) {
            // Con la suite de staff apagada la etiqueta con namespace no existe, y eso es
            // exactamente lo que hay que contestar: antes se caia en el /ban de otro
            // plugin y Discord daba por hecho que habia sido el nuestro.
            sendReply(channel, "Unknown command: `" + escapeMarkdown(
                    body.split("\\s+", 2)[0]) + "` (is Staff_Moderation enabled?)");
            return;
        }
        // Un tick de gracia antes de leer lo que contesto. Muchos comandos responden desde
        // el planificador de la region del objetivo, o sea al tick siguiente: leyendo aqui
        // mismo salian vacios y Discord contestaba "Done" a algo que ni siquiera habia
        // pasado todavia — un jugador offline incluido.
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                task -> replyWithOutput(channel, body, capture.captured()), 3L);
    }

    /**
     * La misma etiqueta, pero dirigida al comando de Chatalyst.
     *
     * <p>Lo que se autoriza y lo que se ejecuta tienen que ser lo mismo. La etiqueta plana la
     * resuelve el mapa global del servidor, donde {@code ban}, {@code ci}, {@code note} o
     * {@code check} las tienen tambien EssentialsX y media docena mas: se validaba contra el
     * catalogo de Chatalyst y luego se despachaba el comando de otro, como consola y sin que
     * nadie lo hubiera revisado. El registrar publica siempre la forma con namespace, con
     * {@code Command_Priority} y sin el, asi que esta es la unica que no depende de quien gane
     * la carrera por el nombre corto.
     */
    private static String namespaced(String body) {
        return "chatalyst:" + body;
    }

    private void replyWithOutput(MessageChannel channel, String body, List<String> output) {
        if (output.isEmpty()) {
            // Sin una sola linea no se afirma que haya funcionado: el comando puede seguir
            // trabajando, o haber contestado por un camino que no pasa por aqui.
            sendReply(channel, "Ran `/" + escapeMarkdown(body)
                    + "`; it printed no answer here. Check the server console.");
            return;
        }
        StringBuilder reply = new StringBuilder("```\n");
        for (String line : output) {
            if (reply.length() + line.length() > 1_800) break;
            reply.append(line).append('\n');
        }
        sendReply(channel, reply.append("```").toString());
    }

    private boolean isAuthorizedStaffCommand(MessageReceivedEvent event, Settings current) {
        List<String> roleIds = event.getMember() == null ? List.of()
                : event.getMember().getRoles().stream().map(role -> role.getId()).toList();
        return DiscordStaffAuthorization.isAuthorized(
                event.getChannel().getId(),
                event.getAuthor().getId(),
                roleIds,
                current.allowedStaffChannelIds(),
                current.allowedStaffUserIds(),
                current.allowedStaffRoleIds());
    }

    private void handleReportCommand(String body, String author, MessageChannel channel,
                                     Settings current) {
        ChatalystPlugin active = ChatalystPlugin.get();
        dev.chatalyst.report.ReportActions actions =
                active == null ? null : active.getReportActions();
        if (actions == null) {
            sendReply(channel, bridgeText("reports-disabled"));
            return;
        }

        String[] parts = body.split("\\s+", 3);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        FoliaTasks.global(plugin, () -> {
            if (verb.equals("reports")) {
                var open = actions.reports().listOpen().stream()
                        .filter(report -> current.priorityFromDiscord() || !report.priority())
                        .toList();
                if (open.isEmpty()) {
                    sendReply(channel, bridgeText("no-open"));
                    return;
                }
                StringBuilder output = new StringBuilder("**")
                        .append(bridgeText("open-header", "count", open.size()))
                        .append("**\n");
                int shown = 0;
                for (var report : open) {
                    if (shown++ >= 15) break;
                    // Escapado como en !info: el motivo lo escribe un jugador, y sin escapar
                    // un [texto](enlace) salia como un enlace falso publicado por el bot.
                    output.append("`#").append(report.id()).append("` ")
                            .append(report.status()).append(" — ")
                            .append(escapeMarkdown(report.target())).append(" (by ")
                            .append(escapeMarkdown(report.reporter())).append("): ")
                            .append(escapeMarkdown(truncate(report.reason(), 60))).append('\n');
                }
                sendReply(channel, output.toString());
                return;
            }

            int id;
            try {
                id = Integer.parseInt(parts.length > 1 ? parts[1] : "");
            } catch (NumberFormatException badId) {
                sendReply(channel, bridgeText(verb.equals("resolve")
                        ? "usage-note" : "usage", "verb", verb));
                return;
            }
            var existing = actions.reports().get(id);
            if (existing != null && existing.priority() && !current.priorityFromDiscord()) {
                sendReply(channel, bridgeText("priority", "id", id));
                return;
            }
            if (verb.equals("info")) {
                if (existing == null) {
                    sendReply(channel, bridgeText("failed", "id", id));
                    return;
                }
                StringBuilder output = new StringBuilder("**Report #")
                        .append(id).append("** — `").append(existing.status()).append("`\n")
                        .append("**Target:** ").append(escapeMarkdown(existing.targetDisplay()))
                        .append("\n**Reporter:** ").append(escapeMarkdown(existing.reporter()))
                        .append("\n**Reason:** ").append(escapeMarkdown(existing.reason()));
                if (!existing.handler().isBlank()) {
                    output.append("\n**Handler:** ")
                            .append(escapeMarkdown(existing.handler()));
                }
                if (!existing.note().isBlank()) {
                    output.append("\n**Note:** ").append(escapeMarkdown(existing.note()));
                }
                List<dev.chatalyst.report.TicketConversation.Message> messages =
                        actions.conversation().get(id);
                if (!messages.isEmpty()) {
                    output.append("\n\n**Latest replies**");
                    int start = Math.max(0, messages.size() - 8);
                    for (int index = start; index < messages.size(); index++) {
                        var reply = messages.get(index);
                        output.append("\n• **").append(escapeMarkdown(reply.author()))
                                .append(reply.staff() ? " [staff]" : "")
                                .append(":** ").append(escapeMarkdown(
                                        truncate(reply.text(), 240)));
                    }
                }
                sendReply(channel, output.toString());
                return;
            }
            String handler = author + " (Discord)";
            String note = parts.length > 2 ? parts[2] : "";
            if (verb.equals("reply")) {
                if (note.isBlank()) {
                    sendReply(channel, "Usage: `" + current.staffCommandPrefix()
                            + "reply <id> <message>`");
                    return;
                }
                boolean replied = actions.reply(id, handler, true, note);
                sendReply(channel, replied
                        ? bridgeText("done", "id", id, "verb", verb)
                        : bridgeText("failed", "id", id));
                return;
            }
            boolean changed = switch (verb) {
                case "claim" -> actions.claim(id, handler);
                case "resolve" -> actions.resolve(id, handler, note);
                case "pending" -> actions.pending(id, handler, note);
                case "reject" -> actions.reject(id, handler, note);
                case "reopen" -> actions.reopen(id, handler);
                default -> false;
            };
            sendReply(channel, changed
                    ? bridgeText("done", "id", id, "verb", verb)
                    : bridgeText("failed", "id", id));
        });
    }

    private void sendChannel(String channelId, String content) {
        sendChannel(channelId, content, List.of());
    }

    private void sendChannel(String channelId, String content, List<String> mentions) {
        if (content == null || content.isBlank()) return;
        enqueue(channelId, discordBounded(content), null, List.of(), mentions);
    }

    /** Una tarjeta con botones para operarla sin escribir un solo comando. */
    public void sendStaffEmbed(DiscordEmbed embed, List<DiscordButton> buttons,
                               boolean priority) {
        Settings current = settings;
        if (!current.enabled() || !current.staffEnabled() || embed == null) return;
        String channelId = priority && !current.priorityStaffChannelId().isBlank()
                ? current.priorityStaffChannelId() : current.staffChannelId();
        enqueue(channelId, null, embed, buttons == null ? List.of() : buttons);
    }

    /**
     * Envía una tarjeta en vez de una línea de texto.
     *
     * <p>Pasa por la misma cola que todo lo demás: si el bot todavía no está conectado, el
     * aviso espera en lugar de perderse, que es justo lo que hace falta cuando lo que se
     * anuncia es una venta que ya ocurrió.
     */
    public void sendEmbed(String channelId, DiscordEmbed embed) {
        if (embed == null) return;
        Settings current = settings;
        if (!current.enabled()) return;
        enqueue(channelId, null, embed, List.of());
    }

    private void enqueue(String channelId, String content, DiscordEmbed embed,
                         List<DiscordButton> buttons) {
        enqueue(channelId, content, embed, buttons, List.of());
    }

    private void enqueue(String channelId, String content, DiscordEmbed embed,
                         List<DiscordButton> buttons, List<String> mentions) {
        if (!isSnowflake(channelId)) {
            warnThrottled("channel-id-" + channelId, "Discord channel id '" + channelId
                    + "' is not valid; its messages are not being sent.");
            return;
        }
        JDA active = jda;
        if (active == null) return;
        if (!ready) {
            if (pendingCount.incrementAndGet() > MAX_PENDING_SENDS) {
                pendingCount.decrementAndGet();
                warnThrottled("queue", "Discord bot queue is full; dropping messages until"
                        + " the connection recovers.");
                return;
            }
            pending.add(new PendingSend(channelId, content, embed, buttons, mentions));
            return;
        }
        sendNow(active, channelId, content, embed, buttons, mentions);
    }

    private void flushPending(JDA active) {
        PendingSend next;
        while ((next = pending.poll()) != null) {
            pendingCount.decrementAndGet();
            if (active != jda) return;
            sendNow(active, next.channelId(), next.content(), next.embed(), next.buttons(),
                    next.mentions());
        }
    }

    private void sendNow(JDA active, String channelId, String content, DiscordEmbed embed,
                         List<DiscordButton> buttons, List<String> mentions) {
        TextChannel channel = active.getTextChannelById(channelId);
        if (channel == null || !channel.getGuild().getId().equals(settings.guildId())) {
            warnThrottled("channel-" + channelId, "Discord channel " + channelId
                    + " is unavailable to the bot. Check its ID and permissions.");
            return;
        }
        try {
            var request = embed == null
                    ? channel.sendMessage(content)
                    : channel.sendMessageEmbeds(toJda(embed));
            if (buttons != null && !buttons.isEmpty()) {
                request = request.addComponents(net.dv8tion.jda.api.components.actionrow.ActionRow
                        .of(buttons.stream().map(DiscordBot::toJda).toList()));
            }
            // Nada de @everyone ni roles: solo los usuarios resueltos para este mensaje.
            request.setAllowedMentions(Collections.emptyList())
                    .mentionUsers(mentions)
                    .queue(null, failure -> warnThrottled("send",
                            "Failed to send through Discord bot: " + safeFailure(failure)));
        } catch (RuntimeException denied) {
            // JDA comprueba los permisos ANTES de encolar y lanza aqui mismo (MissingAccessException
            // si el bot no ve el canal). Esto se llama desde el hilo del servidor al avisar de un
            // comando de staff: sin el catch, la excepcion subia por PlayerCommandPreprocessEvent.
            warnThrottled("denied-" + channelId, "Discord bot cannot post in channel " + channelId
                    + ": " + safeFailure(denied) + ". Give it View Channel and Send Messages there.");
        }
    }

    private static Button toJda(DiscordButton button) {
        return switch (button.style()) {
            case PRIMARY -> Button.primary(button.id(), button.label());
            case SUCCESS -> Button.success(button.id(), button.label());
            case DANGER -> Button.danger(button.id(), button.label());
            case SECONDARY -> Button.secondary(button.id(), button.label());
        };
    }

    private static MessageEmbed toJda(DiscordEmbed embed) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(embed.color())
                .setTimestamp(java.time.Instant.now());
        if (!embed.title().isBlank()) builder.setTitle(embed.title());
        if (!embed.description().isBlank()) builder.setDescription(embed.description());
        if (!embed.footer().isBlank()) builder.setFooter(embed.footer());
        for (DiscordEmbed.Field field : embed.fields()) {
            builder.addField(field.name(), field.value(), field.inline());
        }
        return builder.build();
    }

    private void sendReply(MessageChannel channel, String content) {
        if (channel == null || content == null || content.isBlank()) return;
        channel.sendMessage(discordBounded(content))
                .setAllowedMentions(Collections.emptyList())
                .queue(null, failure -> warnThrottled("reply",
                        "Failed to reply in Discord: " + safeFailure(failure)));
    }

    private void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        AtomicLong clock = lastWarnings.computeIfAbsent(key, ignored -> new AtomicLong());
        long previous = clock.get();
        if (now - previous < 60_000L || !clock.compareAndSet(previous, now)) return;
        plugin.getLogger().warning(message);
    }

    static String incomingText(Message message, int maxLength) {
        String content = message.getContentDisplay();
        if (content == null) content = "";
        content = content.replace('\n', ' ').replace('\r', ' ').trim();
        if (!message.getAttachments().isEmpty()) {
            String url = message.getAttachments().getFirst().getUrl();
            content = content.isBlank() ? "[attachment] " + url
                    : content + " [attachment] " + url;
        }
        return truncate(content, Math.max(32, Math.min(1024, maxLength)));
    }

    static final String MARKDOWN_CHARS = "\\`*_{}[]()<>#+-.!|~";

    static String escapeMarkdown(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (MARKDOWN_CHARS.indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * El formato con el que este canal del juego se ve en Discord.
     *
     * <p>Varios canales del juego suelen compartir un mismo canal de Discord: es lo natural
     * cuando el servidor tiene comercio, subastas y demás y en Discord hay UNA sala para todo
     * eso. Con un solo formato para todos, quien lee no distingue una venta de una puja —
     * son la misma línea. Un formato propio por canal es lo que hace que se entienda sin
     * tener que deducirlo.
     *
     * <p>Sin entrada propia (o con una vacía) vale el formato general, así que añadir uno
     * nuevo nunca deja un canal sin formato.
     */
    static String formatForGame(Map<String, String> perChannel, String fallback,
                                String gameChannel) {
        if (perChannel == null || gameChannel == null || gameChannel.isBlank()) return fallback;
        String own = perChannel.get(gameChannel.trim().toLowerCase(Locale.ROOT));
        return own == null || own.isBlank() ? fallback : own;
    }

    /**
     * Resolves format tokens in one pass. Player-controlled values are never scanned again,
     * so a nickname literally containing "{message}" cannot duplicate or replace the chat.
     */
    static String renderDiscordFormat(String template, String player, String message,
                                      String channel) {
        if (template == null || template.isEmpty()) return "";
        StringBuilder rendered = new StringBuilder(template.length()
                + player.length() + message.length() + channel.length());
        for (int i = 0; i < template.length(); ) {
            if (template.startsWith("{player}", i)) {
                rendered.append(player);
                i += "{player}".length();
            } else if (template.startsWith("{message}", i)) {
                rendered.append(message);
                i += "{message}".length();
            } else if (template.startsWith("{channel}", i)) {
                rendered.append(channel);
                i += "{channel}".length();
            } else {
                rendered.append(template.charAt(i++));
            }
        }
        return rendered.toString();
    }

    static String truncate(String value, int maxCodePoints) {
        if (value == null || maxCodePoints <= 0) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));
        return value.substring(0, end) + "…";
    }

    /** Discord caps content at 2,000; also cap UTF-16 units for conservative API safety. */
    private static String discordBounded(String value) {
        String byCodePoint = truncate(value, DISCORD_MESSAGE_LIMIT);
        if (byCodePoint.length() <= DISCORD_MESSAGE_LIMIT) return byCodePoint;
        int end = DISCORD_MESSAGE_LIMIT - 1;
        if (end > 0 && Character.isHighSurrogate(byCodePoint.charAt(end - 1))) end--;
        return byCodePoint.substring(0, Math.max(0, end)) + "…";
    }

    public static boolean isSnowflake(String value) {
        if (value == null || value.length() < 17 || value.length() > 20) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static String bridgeText(String key, Object... replacements) {
        return MM.strip(dev.chatalyst.lang.Lang.raw(
                "discord-bridge." + key, replacements));
    }

    private static String safeFailure(Throwable failure) {
        if (failure == null) return "unknown error";
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private record PendingSend(String channelId, String content, DiscordEmbed embed,
                               List<DiscordButton> buttons, List<String> mentions) {
    }

    private record Settings(
            boolean enabled,
            String token,
            String guildId,
            String channelId,
            String activity,
            boolean minecraftToDiscord,
            boolean discordToMinecraft,
            String toDiscordFormat,
            String toMinecraftFormat,
            int maxInboundLength,
            Map<String, String> gameToDiscordChannels,
            Map<String, String> gameChannelFormats,
            Map<String, String> discordToGameChannels,
            Map<UUID, String> minecraftToDiscordUsers,
            Map<String, UUID> discordToMinecraftUsers,
            boolean linkingEnabled,
            String linkCommand,
            String linkChannelId,
            boolean staffEnabled,
            String staffChannelId,
            String priorityStaffChannelId,
            boolean handleStaffFromDiscord,
            boolean staffCommandsFromDiscord,
            String staffCommandPrefix,
            List<String> allowedStaffChannelIds,
            List<String> allowedStaffUserIds,
            List<String> allowedStaffRoleIds,
            boolean priorityFromDiscord,
            boolean linkedOnlyToMinecraft,
            boolean useMinecraftName,
            String unlinkedReply,
            String webhookUrl,
            String webhookName,
            String webhookAvatar,
            String webhookFormat,
            Map<String, String> rankTags,
            DiscordChatText.EmojiTable emojiTable,
            boolean mentionsEnabled,
            int maxMentions) {

        static Settings disabled() {
            return new Settings(false, "", "", "", "", true, true,
                    "**{player}**: {message}",
                    "<#5865F2>[Discord] <#FBF2FF>{discord_user}"
                            + "<#C0B3D9>: <reset>{message}",
                    256, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    true, "!link", "", false, "", "", false, false, "!", List.of(), List.of(),
                    List.of(), false, false, true, "", "", "{player}", "", "{message}", Map.of(),
                    DiscordChatText.EmojiTable.EMPTY, false, 0);
        }

        static Settings load(ChatalystPlugin plugin) {
            var config = plugin.getConfig();
            Map<String, String> gameChannels = readStringMap(
                    config.getConfigurationSection("Discord.Channel_Ids"), true);
            // Claves normalizadas igual que en Channel_Ids: quien escriba "Trade" en un sitio
            // y "trade" en el otro espera que sea el mismo canal, y lo es.
            Map<String, String> channelFormats = readStringMap(
                    config.getConfigurationSection("Discord.Channel_Formats"), true);
            Map<String, String> reverseChannels = new LinkedHashMap<>();
            for (var entry : gameChannels.entrySet()) {
                if (isSnowflake(entry.getValue())) {
                    reverseChannels.putIfAbsent(entry.getValue(), entry.getKey());
                }
            }

            Map<UUID, String> linkedUsers = new LinkedHashMap<>();
            ConfigurationSection links =
                    config.getConfigurationSection("Discord.Linked_Accounts");
            if (links != null) {
                for (String rawUuid : links.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(rawUuid.trim());
                        String discordId = links.getString(rawUuid, "").trim();
                        if (isSnowflake(discordId)) linkedUsers.put(uuid, discordId);
                    } catch (IllegalArgumentException ignored) {
                        // Invalid manual mappings simply remain inactive; /cht doctor can
                        // still point the administrator at the documented UUID format.
                    }
                }
            }
            Map<String, UUID> reverseUsers = new LinkedHashMap<>();
            for (var entry : linkedUsers.entrySet()) {
                reverseUsers.putIfAbsent(entry.getValue(), entry.getKey());
            }

            return new Settings(
                    config.getBoolean("Discord.Enabled", false),
                    config.getString("Discord.Bot_Token", "").trim(),
                    config.getString("Discord.Guild_Id", "").trim(),
                    config.getString("Discord.Channel_Id", "").trim(),
                    config.getString("Discord.Activity", "Minecraft chat").trim(),
                    config.getBoolean("Discord.Minecraft_To_Discord", true),
                    config.getBoolean("Discord.Discord_To_Minecraft", true),
                    config.getString("Discord.To_Discord_Format",
                            "**{player}**: {message}"),
                    config.getString("Discord.To_Minecraft_Format",
                            "<#5865F2>[Discord] <#FBF2FF>{discord_user}"
                                    + "<#C0B3D9>: <reset>{message}"),
                    config.getInt("Discord.Max_Inbound_Length", 256),
                    Map.copyOf(gameChannels),
                    Map.copyOf(channelFormats),
                    Map.copyOf(reverseChannels),
                    Map.copyOf(linkedUsers),
                    Map.copyOf(reverseUsers),
                    config.getBoolean("Discord.Linking.Enabled", true),
                    normalizedLinkCommand(config.getString(
                            "Discord.Linking.Command", "!link")),
                    config.getString("Discord.Linking.Channel_Id", "").trim(),
                    config.getBoolean("Discord.Staff.Enabled", false),
                    config.getString("Discord.Staff.Channel_Id", "").trim(),
                    config.getString("Discord.Staff.Priority_Channel_Id", "").trim(),
                    config.getBoolean("Discord.Staff.Handle_From_Discord", false),
                    config.getBoolean("Discord.Staff.Commands_From_Discord", false),
                    config.getString("Discord.Staff.Command_Prefix", "!"),
                    List.copyOf(config.getStringList(
                            "Discord.Staff.Allowed_Channel_Ids")),
                    List.copyOf(config.getStringList(
                            "Discord.Staff.Allowed_User_Ids")),
                    List.copyOf(config.getStringList(
                            "Discord.Staff.Allowed_Role_Ids")),
                    config.getBoolean("Discord.Staff.Priority_From_Discord", false),
                    config.getBoolean("Discord.Inbound.Linked_Only", false),
                    config.getBoolean("Discord.Inbound.Use_Minecraft_Name", true),
                    config.getString("Discord.Inbound.Unlinked_Reply",
                            "Link your account to talk to the server: run **/discord link**"
                                    + " in game, then `{link_command} <code>` here."),
                    config.getString("Discord.Webhook.Url", "").trim(),
                    config.getString("Discord.Webhook.Name_Format", "{player}"),
                    config.getString("Discord.Webhook.Avatar_Url",
                            "https://mc-heads.net/avatar/{player}/128").trim(),
                    config.getString("Discord.Webhook.Format", "{message}"),
                    Map.copyOf(readStringMap(
                            config.getConfigurationSection("Discord.Webhook.Rank_Tags"), true)),
                    DiscordChatText.EmojiTable.parse(readStringMap(
                            config.getConfigurationSection("Discord.Emoji_Replacements"), false)),
                    config.getBoolean("Discord.Mentions.Enabled", false),
                    Math.max(0, config.getInt("Discord.Mentions.Max_Per_Message", 3)));
        }

        /** El chat global sale por el webhook (nombre y cabeza del jugador) si hay URL. */
        boolean usesWebhookFor(String targetChannelId) {
            return !webhookUrl.isBlank() && channelId.equals(targetChannelId);
        }

        private static String normalizedLinkCommand(String command) {
            if (command == null) return "!link";
            String value = command.trim();
            if (!value.matches("[!./?][A-Za-z0-9_-]{1,24}")) return "!link";
            return value;
        }

        String discordChannelForGame(String gameChannel) {
            if (gameChannel == null || gameChannel.isBlank()) return channelId;
            return gameToDiscordChannels.get(normalizeChannel(gameChannel));
        }

        /**
         * Con qué aspecto sale este canal del juego en Discord.
         *
         * <p>Varios canales del juego pueden compartir un mismo canal de Discord — es lo normal
         * cuando el servidor tiene comercio, subastas y demás y en Discord hay UNA sala para
         * todo eso. Con un único formato para todos, quien lo lee no distingue una venta de una
         * puja: son la misma línea. Un formato propio por canal es lo que hace que se entienda
         * sin tener que adivinarlo.
         */
        String formatForGame(String gameChannel) {
            return DiscordBot.formatForGame(gameChannelFormats, toDiscordFormat, gameChannel);
        }

        /**
         * Empty string means the main/global game chat; null means this Discord channel is
         * unrelated to the bridge.
         */
        String gameChannelForDiscord(String discordChannelId) {
            if (channelId.equals(discordChannelId)) return "";
            return discordToGameChannels.get(discordChannelId);
        }

        private static Map<String, String> readStringMap(ConfigurationSection section,
                                                         boolean normalizeKeys) {
            if (section == null) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                String value = section.getString(key, "").trim();
                if (!value.isEmpty()) {
                    result.put(normalizeKeys ? normalizeChannel(key) : key, value);
                }
            }
            return result;
        }

        private static String normalizeChannel(String channel) {
            return channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        }
    }
}
