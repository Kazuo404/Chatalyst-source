package dev.chatalyst.marriage;

import dev.chatalyst.db.StorageManager;
import dev.chatalyst.db.Store;
import dev.chatalyst.db.StoreCas;
import dev.chatalyst.hook.VaultHook;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Banco del matrimonio (/marry bank): una CUENTA de dinero aparte en la que ingresan y de la
 * que retiran los dos conyuges. Los objetos van en {@link MarriageStorage} (/marry storage).
 *
 * <p>El saldo se guarda en centimos (un {@code long}) en el store {@code marriage_money}, una
 * fila por pareja con la misma clave ordenada que el almacen. Nada de {@code double}: sumar
 * 0.1 diez veces no puede dejar la cuenta en 0.9999999.
 *
 * <p><b>Lo critico es que el dinero no se cree ni se destruya.</b> Cada operacion mueve el
 * dinero en dos pasos (la economia del jugador y la fila de la cuenta) y deshace el primero si
 * el segundo falla. El orden es siempre "primero quitar, luego dar": si el servidor muere
 * entre los dos, se pierde un movimiento, nunca se duplica.
 */
public final class MarriageBank {

    /** Resultado de un movimiento; el comando lo traduce a un mensaje. */
    public enum Result { OK, NOT_MARRIED, PLAYER_FUNDS, ACCOUNT_FUNDS, LIMIT, FAILED }

    /** La economia del servidor. Interfaz propia para poder probar el banco sin Vault. */
    interface Wallet {
        boolean available();

        double balance(Player player);

        boolean take(Player player, double amount);

        boolean give(Player player, double amount);

        String format(double amount);
    }

    private static final Wallet VAULT = new Wallet() {
        @Override
        public boolean available() {
            return VaultHook.hasEconomy();
        }

        @Override
        public double balance(Player player) {
            return VaultHook.balance(player);
        }

        @Override
        public boolean take(Player player, double amount) {
            return VaultHook.withdraw(player, amount);
        }

        @Override
        public boolean give(Player player, double amount) {
            return VaultHook.deposit(player, amount);
        }

        @Override
        public String format(double amount) {
            return VaultHook.format(amount);
        }
    };

    /** Tope de lo que se puede escribir como cantidad: evita desbordar el long de centimos. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private final JavaPlugin plugin;
    private final MarriageManager marriages;
    private final Store store;
    private final Wallet wallet;

    private volatile boolean enabled;
    private volatile long maxCents;

    public MarriageBank(JavaPlugin plugin, MarriageManager marriages, StorageManager storage) {
        // Por servidor: son centimos de la economia Vault de ESTE backend.
        this(plugin, marriages, storage.perServerStore("marriage_money"), VAULT);
    }

    MarriageBank(JavaPlugin plugin, MarriageManager marriages, Store store, Wallet wallet) {
        this.plugin = plugin;
        this.marriages = marriages;
        this.store = store;
        this.wallet = wallet;
    }

    public void load() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("Marriage.Perks.Bank.Enabled", true);
        maxCents = toCents(BigDecimal.valueOf(Math.max(0D, cfg.getDouble("Marriage.Perks.Bank.Max_Balance", 0D))));
    }

    /** Para tests: encender el banco sin config. */
    void configureForTest(boolean on, long limitCents) {
        enabled = on;
        maxCents = limitCents;
    }

    /** Encendido en config Y con una economia a la que mover el dinero. */
    public boolean isEnabled() {
        return enabled && wallet.available();
    }

    private String keyFor(UUID id) {
        MarriageManager.Marriage marriage = marriages.marriage(id);
        return marriage == null ? null : MarriageStorage.key(id, marriage.partner());
    }

    /** Saldo de la cuenta de la pareja en centimos (0 si esta soltero o no hay cuenta). */
    public long balance(UUID id) {
        String key = keyFor(id);
        return key == null ? 0L : parseStored(store.get(key));
    }

    /** True si la pareja aun tiene dinero (bloquea el divorcio para que no se pierda). */
    public boolean hasMoney(UUID id) {
        return balance(id) > 0L;
    }

    /** Todo lo que el jugador puede ingresar ahora mismo, redondeado hacia abajo al centimo. */
    public long walletCents(Player player) {
        double balance = wallet.balance(player);
        if (!Double.isFinite(balance) || balance <= 0D) return 0L;
        return toCents(BigDecimal.valueOf(balance).min(MAX_AMOUNT));
    }

    public String format(long cents) {
        return wallet.format(toAmount(cents));
    }

    /** Pasa dinero del jugador a la cuenta de la pareja. */
    public Result deposit(Player player, long cents) {
        String key = keyFor(player.getUniqueId());
        if (key == null) return Result.NOT_MARRIED;
        if (cents <= 0L) return Result.FAILED;
        long limit = maxCents;
        if (limit > 0L && parseStored(store.get(key)) + cents > limit) return Result.LIMIT;
        if (cents > walletCents(player)) return Result.PLAYER_FUNDS;
        double amount = toAmount(cents);
        if (!wallet.take(player, amount)) return Result.PLAYER_FUNDS;
        if (add(key, cents, limit)) return Result.OK;
        // La cuenta no se pudo escribir: el dinero vuelve al jugador.
        if (!wallet.give(player, amount)) {
            severe("CRITICAL: could not refund " + amount + " to " + player.getName()
                    + " after a failed marriage bank deposit; refund it by hand.");
        }
        return Result.FAILED;
    }

    /** Pasa dinero de la cuenta de la pareja al jugador. */
    public Result withdraw(Player player, long cents) {
        String key = keyFor(player.getUniqueId());
        if (key == null) return Result.NOT_MARRIED;
        if (cents <= 0L) return Result.FAILED;
        if (cents > parseStored(store.get(key))) return Result.ACCOUNT_FUNDS;
        if (!add(key, -cents, 0L)) {
            // O el conyuge retiro justo antes y ya no alcanza, o el backend fallo.
            return cents > parseStored(store.get(key)) ? Result.ACCOUNT_FUNDS : Result.FAILED;
        }
        double amount = toAmount(cents);
        if (wallet.give(player, amount)) return Result.OK;
        // La economia rechazo el ingreso: el dinero vuelve a la cuenta.
        if (!add(key, cents, 0L)) {
            severe("CRITICAL: could not return " + amount + " to the marriage bank " + key
                    + " after the economy rejected a withdrawal; restore it by hand.");
        }
        return Result.FAILED;
    }

    /**
     * Suma (o resta) centimos con CAS durable: dos conyuges operando a la vez, incluso desde
     * dos servidores de la red, no pueden pisarse el saldo ni dejarlo en negativo.
     */
    private boolean add(String key, long delta, long limit) {
        return StoreCas.updateDurably(store, key, current -> {
            long before = parseStored(current);
            long after;
            try {
                after = Math.addExact(before, delta);
            } catch (ArithmeticException overflow) {
                return null;
            }
            if (after < 0L || (delta > 0L && limit > 0L && after > limit)) return null;
            return Long.toString(after);
        });
    }

    /** Borra la fila al divorciarse, solo si esta a cero: con dinero dentro se conserva. */
    void clear(UUID a, UUID b) {
        String key = MarriageStorage.key(a, b);
        String raw = store.get(key);
        if (raw != null && parseStored(raw) == 0L) store.compareAndRemoveDurably(key, raw);
    }

    public void shutdown() {
        store.flushNow();
    }

    /**
     * Cantidad escrita por un jugador, en centimos, o -1 si no vale: tiene que ser un numero
     * positivo con dos decimales como mucho. "1e9", "NaN", negativos y fracciones de centimo
     * se rechazan en vez de redondearse a algo que el jugador no escribio.
     */
    static long parseCents(String raw) {
        if (raw == null) return -1L;
        String text = raw.trim().replace(',', '.');
        if (text.isEmpty() || !text.matches("\\d{1,13}(\\.\\d{1,2})?")) return -1L;
        BigDecimal value = new BigDecimal(text);
        if (value.signum() <= 0 || value.compareTo(MAX_AMOUNT) > 0) return -1L;
        return toCents(value);
    }

    private static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.DOWN).longValue();
    }

    private static double toAmount(long cents) {
        return BigDecimal.valueOf(cents, 2).doubleValue();
    }

    /** Saldo guardado; una fila ilegible cuenta como 0 y nunca como negativo. */
    static long parseStored(String raw) {
        if (raw == null || raw.isEmpty()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException corrupt) {
            return 0L;
        }
    }

    private void severe(String message) {
        if (plugin != null) plugin.getLogger().severe(message);
    }
}
