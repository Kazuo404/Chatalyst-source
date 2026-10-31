package dev.chatalyst.command;

import dev.chatalyst.util.FoliaTasks;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Resends the command tree to the clients ONCE per batch, never once per registration.
 *
 * <p>Every runtime command registration used to end with its own resync. That resync is not
 * free and it is not local: {@code CraftServer.syncCommands()} on this server does nothing but
 * call {@code Commands.sendCommands(player)} for every online player, and Paper builds each of
 * those trees on its <b>async command builder pool</b>. So a resync leaves N background threads
 * walking the Brigadier node graph while the main thread carries on enabling modules -- and the
 * next module's registration writes into that same graph, because putting a label into Paper's
 * known-commands map registers a literal into the dispatcher root. The async walk hashes a node
 * by iterating its {@code children} map; a write during that iteration is a
 * {@link java.util.ConcurrentModificationException} out of a thread nobody catches.
 *
 * <p>It was reproducible on every startup with players connected: three registrations in the
 * same second, four dead pool threads, and the clients left with the command tree they had.
 *
 * <p>So requests are coalesced. Whoever changes the command map asks for a resend and keeps
 * going; the resend happens one tick later, when the batch has finished and nothing is writing
 * any more. A tick of delay is invisible -- the tree only decides what the client offers when
 * tab-completing, and dispatch never went through it.
 *
 * <p>Paper's own resync already sends to every player, so nobody should follow it with
 * {@code Player#updateCommands}: that sent the whole thing twice and doubled the window this
 * class exists to close.
 */
public final class CommandTreeSync {

    private static final Object LOCK = new Object();
    private static CommandTreeSync shared;
    private static JavaPlugin owner;

    private final AtomicBoolean pending = new AtomicBoolean();
    private final Consumer<Runnable> scheduler;
    private final Runnable resend;

    CommandTreeSync(Consumer<Runnable> scheduler, Runnable resend) {
        this.scheduler = scheduler;
        this.resend = resend;
    }

    /** Asks for a resend after the current batch of command-map changes. */
    public static void request(JavaPlugin plugin) {
        CommandTreeSync sync;
        synchronized (LOCK) {
            // A reload builds a new plugin instance; a coalescer holding the old one would
            // schedule onto a scheduler that no longer accepts tasks.
            if (shared == null || owner != plugin) {
                owner = plugin;
                shared = new CommandTreeSync(
                        action -> FoliaTasks.globalLater(plugin, action, 1L),
                        () -> resend(plugin));
            }
            sync = shared;
        }
        sync.request();
    }

    /** Package-private for the test: the coalescing itself, with no server in sight. */
    void request() {
        // The flag is cleared BEFORE resending, so a change made during the resend books the
        // next one instead of being swallowed by the batch that is already leaving.
        if (!pending.compareAndSet(false, true)) return;
        scheduler.accept(() -> {
            pending.set(false);
            resend.run();
        });
    }

    private static void resend(JavaPlugin plugin) {
        try {
            plugin.getServer().getClass().getMethod("syncCommands").invoke(plugin.getServer());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().warning("Could not resync the Brigadier command tree: "
                    + failure);
        }
    }
}
