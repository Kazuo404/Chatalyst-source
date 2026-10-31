package dev.chatalyst.chatgame;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.SoundUtil;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Las rondas de preguntas del chat.
 *
 * <p>Un reloj de un segundo en la region global decide cuando sale una pregunta y cuando se
 * acaba el tiempo. Las respuestas llegan por el chat, que es ASINCRONO y puede traer a dos
 * jugadores acertando en el mismo milisegundo: la ronda se reclama con un compare-and-set, asi
 * que solo uno se lleva el premio y nunca se paga dos veces.
 */
public final class ChatGameService {

    /** Una pregunta en el aire, con la foto de la configuracion con la que salio. */
    record Round(ChatGameQuestion question, ChatGameSettings.Category category, long endsAt,
                 ChatGameSettings settings) {
    }

    private final ChatalystPlugin plugin;
    private final AtomicReference<Round> current = new AtomicReference<>();
    /** Las ultimas preguntas que salieron, para no repetirlas pronto. Solo se toca desde el reloj/comando. */
    private final java.util.Deque<String> recent = new java.util.ArrayDeque<>();
    private static final int RECENT = 40;
    private volatile ChatGameSettings settings;
    private volatile long nextAt;
    private volatile boolean running;

    public ChatGameService(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload();
        running = true;
        scheduleNext(System.currentTimeMillis());
        FoliaTasks.globalLater(plugin, this::tick, 20L);
    }

    public void stop() {
        running = false;
        current.set(null);
    }

    public void reload() {
        settings = ChatGameSettings.read(plugin.getConfig(), plugin.getLogger());
        if (!settings.enabled()) current.set(null);
    }

    public ChatGameSettings settings() {
        return settings;
    }

    private void scheduleNext(long now) {
        ChatGameSettings s = settings;
        int minutes = s.minMinutes() >= s.maxMinutes() ? s.minMinutes()
                : s.minMinutes() + ThreadLocalRandom.current().nextInt(s.maxMinutes() - s.minMinutes() + 1);
        nextAt = now + minutes * 60_000L;
    }

    private void tick() {
        if (!running) return;
        try {
            long now = System.currentTimeMillis();
            Round round = current.get();
            if (round != null && now >= round.endsAt() && current.compareAndSet(round, null)) {
                broadcast(round.settings(), "Timeout", round, null);
                scheduleNext(now);
            } else if (round == null && settings.enabled() && now >= nextAt) {
                long playing = plugin.getServer().getOnlinePlayers().stream()
                        .filter(pl -> settings.playsIn(pl.getWorld().getName())).count();
                if (playing >= settings.minPlayers()) {
                    launch(null);
                } else {
                    nextAt = now + 60_000L;
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Chat games tick failed: " + e.getMessage());
        } finally {
            if (running) FoliaTasks.globalLater(plugin, this::tick, 20L);
        }
    }

    /**
     * Lanza una pregunta ya. {@code categoryId} null = categoria al azar por peso.
     *
     * @return la ronda, o vacio si ya habia una o no hay nada que preguntar
     */
    public Optional<Round> launch(String categoryId) {
        ChatGameSettings s = settings;
        Random random = ThreadLocalRandom.current();
        Optional<ChatGameSettings.Category> category = categoryId == null
                ? s.pickCategory(random) : s.category(categoryId);
        if (category.isEmpty()) return Optional.empty();
        ChatGameQuestion question;
        synchronized (recent) {
            question = category.get().pick(random, q -> recent.contains(q.promptEs()));
            recent.addLast(question.promptEs());
            while (recent.size() > RECENT) recent.removeFirst();
        }
        Round round = new Round(question, category.get(),
                System.currentTimeMillis() + s.answerSecs() * 1000L, s);
        if (!current.compareAndSet(null, round)) return Optional.empty();
        broadcast(s, "Question", round, null);
        Sound sound = SoundUtil.sound(s.questionSound(), "BLOCK_NOTE_BLOCK_PLING", 0.8f, 1.4f);
        FoliaTasks.onlinePlayers(plugin, p -> s.playsIn(p.getWorld().getName()), p -> SoundUtil.play(plugin, p, sound));
        return Optional.of(round);
    }

    /** Cancela la pregunta en el aire sin premio. */
    public boolean skip() {
        Round round = current.getAndSet(null);
        if (round == null) return false;
        scheduleNext(System.currentTimeMillis());
        return true;
    }

    public Optional<Round> current() {
        return Optional.ofNullable(current.get());
    }

    public long millisToNext() {
        return Math.max(0, nextAt - System.currentTimeMillis());
    }

    /**
     * Lo que alguien escribio en el chat. Se llama desde el hilo del chat (asincrono).
     *
     * @return true si acerto y se llevo la ronda
     */
    public boolean attempt(Player player, String text) {
        Round round = current.get();
        if (round == null || System.currentTimeMillis() > round.endsAt()) return false;
        if (!round.settings().playsIn(player.getWorld().getName())) return false;
        java.util.List<String> valid = round.category().strictLanguage()
                ? round.question().acceptedFor(dev.chatalyst.lang.Lang.isSpanish(player))
                : round.question().accepted();
        if (!AnswerMatcher.matches(text, valid)) return false;
        if (!current.compareAndSet(round, null)) return false;
        scheduleNext(System.currentTimeMillis());
        reward(player, round);
        return true;
    }

    private void reward(Player player, Round round) {
        ChatGameSettings.Category category = round.category();
        ChatGameSettings.Prize prize = round.settings().prize(category, round.question());
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        // Se anuncia despues del mensaje del jugador: un tick de margen para que salga primero.
        FoliaTasks.globalLater(plugin, () -> broadcast(round.settings(), "Winner", round, name), 2L);
        Sound sound = SoundUtil.sound(round.settings().winSound(), "ENTITY_PLAYER_LEVELUP", 1f, 1.2f);
        SoundUtil.play(plugin, player, sound);
        if (prize.money() > 0) {
            FoliaTasks.global(plugin, () -> {
                if (!VaultHook.hasEconomy()) {
                    plugin.getLogger().warning("Chat games: " + name + " won " + prize.money()
                            + " but no Vault economy is installed.");
                } else if (!VaultHook.deposit(player, prize.money())) {
                    plugin.getLogger().warning("Chat games: the economy refused " + prize.money() + " for " + name + ".");
                }
            });
        }
        for (String command : prize.commands()) {
            String line = command.replace("{player}", name).replace("{uuid}", uuid).strip();
            if (line.startsWith("/")) line = line.substring(1);
            if (line.isEmpty()) continue;
            String run = line;
            FoliaTasks.global(plugin, () -> {
                try {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), run);
                } catch (Throwable failure) {
                    plugin.getLogger().warning("Chat games reward command failed: /" + run + " (" + failure.getMessage() + ")");
                }
            });
        }
        plugin.getLogger().info("Chat games: " + name + " answered '" + round.question().promptEs() + "' ("
                + category.id() + ").");
    }

    /** Cada jugador lo lee en su idioma; la consola, en espanol. */
    private void broadcast(ChatGameSettings s, String key, Round round, String player) {
        net.kyori.adventure.text.Component es = render(s, key, round, player, true);
        net.kyori.adventure.text.Component en = render(s, key, round, player, false);
        if (es == null && en == null) return;
        FoliaTasks.onlinePlayers(plugin, p -> s.playsIn(p.getWorld().getName()), p -> {
            net.kyori.adventure.text.Component m = dev.chatalyst.lang.Lang.isSpanish(p) ? es : en;
            if (m != null) p.sendMessage(m);
        });
        if (es != null) FoliaTasks.global(plugin, () -> plugin.getServer().getConsoleSender().sendMessage(es));
    }

    private net.kyori.adventure.text.Component render(ChatGameSettings s, String key, Round round, String player,
                                                      boolean spanish) {
        String text = s.message(key, spanish);
        return text == null || text.isBlank() ? null : MM.full(fill(text, round, player, s, spanish));
    }

    /** Rellena los marcadores. Lo que escribe un jugador o la pregunta van escapados. */
    String fill(String text, Round round, String player, ChatGameSettings s, boolean spanish) {
        long secs = Math.max(0, (round.endsAt() - System.currentTimeMillis() + 999) / 1000);
        ChatGameSettings.Prize prize = s.prize(round.category(), round.question());
        Map<String, String> values = Map.of(
                "{difficulty}", prize.label(spanish),
                "{category}", round.category().name(spanish),
                "{question}", MM.escape(round.question().prompt(spanish)),
                "{answer}", MM.escape(round.question().shownAnswer(spanish)),
                "{reward}", prize.rewardText(spanish).isBlank() ? "-" : prize.rewardText(spanish),
                "{seconds}", Long.toString(secs == 0 ? s.answerSecs() : secs),
                "{player}", player == null ? "" : MM.escape(player));
        String out = text;
        for (Map.Entry<String, String> e : values.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    boolean isRunning() {
        return running;
    }
}
