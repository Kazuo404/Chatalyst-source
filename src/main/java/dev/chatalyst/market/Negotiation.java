package dev.chatalyst.market;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Negociacion de una VENTA entre vendedor y comprador: la oferta del comprador (dinero
 * ajustable y/o items de trueque) y el estado de aceptacion de AMBOS lados. La venta solo
 * se ejecuta cuando los dos han aceptado; cualquier cambio de la oferta borra las dos
 * aceptaciones, para que nadie acepte algo distinto de lo que vio.
 *
 * <p>Los items ofrecidos estan EN DEPOSITO: se quitaron del inventario del comprador al
 * añadirlos y vuelven a el si la negociacion se cancela o rechaza. Es estado transitorio
 * (una negociacion no sobrevive a un reinicio: el deposito se devuelve al apagar).
 *
 * <p>La logica de aceptacion no toca la API de Bukkit, asi los tests la cubren sin servidor.
 */
public final class Negotiation {

    /** Maximo de stacks de trueque en la oferta (los slots de la GUI). */
    public static final int MAX_OFFER_ITEMS = 7;

    public final int listingId;
    public final UUID seller;
    public final UUID buyer;
    public final String sellerName;
    public final String buyerName;

    private double moneyOffer;
    private final List<ItemStack> itemOffer = new ArrayList<>();
    private boolean buyerAccepted;
    private boolean sellerAccepted;
    // La segunda aceptacion sella la oferta dentro del mismo monitor. Solo se reabre si el
    // cobro falla antes de reclamar los depositos.
    private boolean sealed;
    // Tiempo monotono del ultimo cambio: impide confirmar una oferta que la otra parte aun
    // no alcanzo a ver repintada en su region de Folia.
    private long lastChangeNanos;

    public Negotiation(int listingId, UUID seller, String sellerName, UUID buyer, String buyerName,
                       double initialMoneyOffer) {
        this.listingId = listingId;
        this.seller = seller;
        this.sellerName = sellerName;
        this.buyer = buyer;
        this.buyerName = buyerName;
        this.moneyOffer = finiteNonNegative(initialMoneyOffer) ? initialMoneyOffer : 0;
    }

    // Las dos partes viven en hilos de region distintos (Folia): los metodos que tocan la
    // oferta o las aceptaciones son synchronized; el MarketManager ademas compone
    // "comprobar y actuar" bajo synchronized(neg) cuando necesita atomicidad mayor.

    public synchronized double moneyOffer() {
        return moneyOffer;
    }

    /** Ajusta el dinero ofrecido (clamp a 0) y resetea las aceptaciones. */
    public synchronized void adjustMoney(double delta) {
        if (sealed || !Double.isFinite(delta)) return;
        double adjusted = moneyOffer + delta;
        if (!Double.isFinite(adjusted)) return;
        setMoney(adjusted);
    }

    public synchronized void setMoney(double amount) {
        if (sealed || !Double.isFinite(amount)) return;
        double safe = Math.max(0, amount);
        if (Double.compare(moneyOffer, safe) == 0) return;
        moneyOffer = safe;
        resetAccepts();
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    /** Copia de los items ofrecidos (el deposito real sigue dentro). */
    public synchronized List<ItemStack> items() {
        return List.copyOf(itemOffer);
    }

    public synchronized int itemCount() {
        return itemOffer.size();
    }

    /** Añade un stack a la oferta. False si ya no caben mas (el caller no lo quita del inventario). */
    public synchronized boolean addItem(ItemStack item) {
        if (sealed || itemOffer.size() >= MAX_OFFER_ITEMS) return false;
        itemOffer.add(item);
        resetAccepts();
        return true;
    }

    /** Saca el stack {@code index} de la oferta (para devolverlo), o null si no existe. */
    public synchronized ItemStack removeItem(int index) {
        if (sealed || index < 0 || index >= itemOffer.size()) return null;
        resetAccepts();
        return itemOffer.remove(index);
    }

    /** Vista del stack que puede retirarse mientras el caller conserva este mismo monitor. */
    synchronized ItemStack removableItem(int index) {
        if (sealed) return null;
        return index >= 0 && index < itemOffer.size() ? itemOffer.get(index) : null;
    }

    /** Vacia la oferta devolviendo todos los stacks (cancelacion/rechazo). */
    public synchronized List<ItemStack> drainItems() {
        List<ItemStack> out = new ArrayList<>(itemOffer);
        itemOffer.clear();
        return out;
    }

    public boolean isParty(UUID who) {
        return seller.equals(who) || buyer.equals(who);
    }

    public boolean isSeller(UUID who) {
        return seller.equals(who);
    }

    /**
     * Marca la aceptacion del lado de {@code who}. Devuelve true solo si el estado CAMBIO:
     * un doble click (o dos hilos) no puede disparar la venta dos veces.
     */
    public synchronized boolean accept(UUID who) {
        if (sealed) return false;
        if (seller.equals(who)) {
            if (sellerAccepted) return false;
            sellerAccepted = true;
        } else if (buyer.equals(who)) {
            if (buyerAccepted) return false;
            buyerAccepted = true;
        } else {
            return false;
        }
        if (buyerAccepted && sellerAccepted) sealed = true;
        return true;
    }

    public synchronized boolean buyerAccepted() {
        return buyerAccepted;
    }

    public synchronized boolean sellerAccepted() {
        return sellerAccepted;
    }

    public synchronized boolean bothAccepted() {
        return buyerAccepted && sellerAccepted;
    }

    /** Cualquier cambio de la oferta invalida lo aceptado hasta ahora. */
    public synchronized void resetAccepts() {
        sealed = false;
        buyerAccepted = false;
        sellerAccepted = false;
        lastChangeNanos = System.nanoTime();
    }

    public synchronized boolean changedWithin(long millis) {
        if (lastChangeNanos == 0 || millis <= 0) return false;
        long window = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        return System.nanoTime() - lastChangeNanos < window;
    }
}
