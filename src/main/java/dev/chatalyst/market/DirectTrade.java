package dev.chatalyst.market;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un intercambio DIRECTO entre dos jugadores (/trade): cada lado ofrece items y/o dinero y
 * ambos deben confirmar. Es simetrico — no hay "vendedor": el lado A es quien inicio y el B
 * quien acepto, pero los dos ponen y reciben. Cualquier cambio en cualquier lado borra las
 * DOS confirmaciones, para que nadie confirme algo distinto de lo que vio.
 *
 * <p>Los items ofrecidos estan EN DEPOSITO (salieron del inventario del que los puso) y
 * vuelven a el si el trade se cancela. Estado transitorio: un trade no sobrevive a un
 * reinicio ni a que una parte se desconecte. La logica no toca Bukkit (testeable sin server).
 */
public final class DirectTrade {

    /** Slots de trueque por lado (las 12 casillas de cada mitad de la GUI). */
    public static final int MAX_ITEMS = 12;

    public enum Side { A, B }

    /** Resultado atomico de confirmar un lado. SEALED significa que la oferta ya no cambia. */
    public enum Confirmation { TOO_RECENT, ALREADY_READY, WAITING, SEALED }

    public final UUID a;
    public final UUID b;
    public final String nameA;
    public final String nameB;

    private final List<ItemStack> itemsA = new ArrayList<>();
    private final List<ItemStack> itemsB = new ArrayList<>();
    private double moneyA;
    private double moneyB;
    private boolean readyA;
    private boolean readyB;
    private boolean closed;
    // Momento del ultimo cambio de oferta (nanoTime): anti-estafa "cambiar e insta-confirmar".
    private long lastChangeNanos;

    public DirectTrade(UUID a, String nameA, UUID b, String nameB) {
        this.a = a;
        this.nameA = nameA;
        this.b = b;
        this.nameB = nameB;
    }

    public Side sideOf(UUID who) {
        if (a.equals(who)) return Side.A;
        if (b.equals(who)) return Side.B;
        return null;
    }

    public UUID uuid(Side s) {
        return s == Side.A ? a : b;
    }

    public String name(Side s) {
        return s == Side.A ? nameA : nameB;
    }

    public boolean isParty(UUID who) {
        return a.equals(who) || b.equals(who);
    }

    public UUID other(UUID who) {
        return a.equals(who) ? b : a;
    }

    private List<ItemStack> items(Side s) {
        return s == Side.A ? itemsA : itemsB;
    }

    // ---- Oferta (todo synchronized: las dos partes corren en hilos de region distintos) ----

    /** Copia de los items del lado (para pintarlos). */
    public synchronized List<ItemStack> view(Side s) {
        return List.copyOf(items(s));
    }

    public synchronized int itemCount(Side s) {
        return items(s).size();
    }

    public synchronized double money(Side s) {
        return s == Side.A ? moneyA : moneyB;
    }

    /**
     * Añade un stack al lado. False si ya no caben mas O si el trade ya cerro. El chequeo de
     * cierre evita una perdida real: tras tryClose() el ejecutor drena las listas en otro hilo
     * de region; un click tardio que depositara aqui dejaria el item en un objeto abandonado
     * (y el caller ya lo saco del inventario).
     */
    public synchronized boolean addItem(Side s, ItemStack item) {
        if (closed) return false;
        List<ItemStack> list = items(s);
        if (list.size() >= MAX_ITEMS) return false;
        list.add(item);
        resetReady();
        return true;
    }

    /**
     * Saca el stack {@code index} del lado (para devolverlo), o null. Con el trade cerrado
     * devuelve null: entre tryClose() y drain() un retiro daria al jugador su item de vuelta
     * mientras la otra parte recibe el intercambio confirmado incompleto (estafa).
     */
    public synchronized ItemStack removeItem(Side s, int index) {
        if (closed) return null;
        List<ItemStack> list = items(s);
        if (index < 0 || index >= list.size()) return null;
        resetReady();
        return list.remove(index);
    }

    /** Vista del stack que puede retirarse mientras el caller conserva este mismo monitor. */
    synchronized ItemStack removableItem(Side side, int index) {
        if (closed) return null;
        List<ItemStack> list = items(side);
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** Ajusta el dinero del lado (clamp a 0). Resetea confirmaciones. No-op si ya cerro:
     *  cambiar el monto entre tryClose() y la lectura del ejecutor alteraria lo confirmado. */
    public synchronized void adjustMoney(Side s, double delta) {
        if (closed) return;
        if (!Double.isFinite(delta)) return;
        double adjusted = money(s) + delta;
        if (!Double.isFinite(adjusted)) return;
        setMoney(s, adjusted);
    }

    /** Fija una oferta monetaria exacta. El manager aplica antes el tope del saldo de Vault. */
    public synchronized void setMoney(Side s, double amount) {
        if (closed || !Double.isFinite(amount)) return;
        double safe = Math.max(0, amount);
        double current = s == Side.A ? moneyA : moneyB;
        if (Double.compare(current, safe) == 0) return;
        if (s == Side.A) moneyA = safe;
        else moneyB = safe;
        resetReady();
    }

    /** Vacia la oferta de un lado devolviendo sus stacks (cancelacion). */
    public synchronized List<ItemStack> drain(Side s) {
        List<ItemStack> out = new ArrayList<>(items(s));
        items(s).clear();
        return out;
    }

    // ---- Confirmacion ----------------------------------------------------------------------

    /**
     * Marca listo el lado y, si ambos lo estan, sella el trade en LA MISMA seccion critica.
     * Asi ninguna region puede cambiar items/dinero entre observar ambas confirmaciones y
     * cerrar; SEALED solo se entrega una vez.
     */
    public synchronized Confirmation confirm(Side s) {
        if (closed) return Confirmation.ALREADY_READY;
        if (s == Side.A) {
            if (readyA) return Confirmation.ALREADY_READY;
            readyA = true;
        } else {
            if (readyB) return Confirmation.ALREADY_READY;
            readyB = true;
        }
        if (!readyA || !readyB) return Confirmation.WAITING;
        closed = true;
        return Confirmation.SEALED;
    }

    /**
     * Confirma solo si la oferta lleva visible al menos {@code reviewMillis}. El gate y el
     * sellado viven en la misma seccion critica para que una edicion desde otra region Folia
     * no pueda colarse entre ambos pasos.
     */
    public synchronized Confirmation confirmIfStable(Side side, long reviewMillis) {
        if (changedWithin(Math.max(0, reviewMillis))) return Confirmation.TOO_RECENT;
        return confirm(side);
    }

    public synchronized boolean isReady(Side s) {
        return s == Side.A ? readyA : readyB;
    }

    public synchronized boolean bothReady() {
        return readyA && readyB;
    }

    private synchronized void resetReady() {
        readyA = false;
        readyB = false;
        lastChangeNanos = System.nanoTime();
    }

    /**
     * True si la oferta cambio hace menos de {@code millis}. El refresco de la GUI del otro
     * lado es asincrono: sin esta ventana, un lado podria cambiar su oferta y confirmar antes
     * de que el otro VEA el cambio, y el click "listo" tardio confirmaria una oferta distinta
     * de la observada (estafa clasica de trade).
     */
    public synchronized boolean changedWithin(long millis) {
        if (lastChangeNanos == 0 || millis <= 0) return false;
        long window = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        return System.nanoTime() - lastChangeNanos < window;
    }

    /** Gate de cierre de un solo disparo: solo el primer llamador obtiene true. */
    public synchronized boolean tryClose() {
        if (closed) return false;
        closed = true;
        return true;
    }
}
