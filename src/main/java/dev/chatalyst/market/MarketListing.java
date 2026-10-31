package dev.chatalyst.market;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Un anuncio del mercado nativo: una VENTA (precio fijo, se negocia con dinero y/o trueque)
 * o una SUBASTA (puja mas alta al cerrar). El item esta EN DEPOSITO: se quito de la mano del
 * vendedor al publicar y solo sale de aqui hacia el comprador, o de vuelta al vendedor si se
 * cancela o expira.
 *
 * <p>El item viaja serializado en Base64 (bytes de Paper) para poder persistir el anuncio tal
 * cual en el {@link dev.chatalyst.db.Store}; el ItemStack se decodifica perezosamente. El
 * codec de texto ({@link #encode()} / {@link #decode(String)}) no toca la API de Bukkit, asi
 * los tests lo cubren sin servidor.
 */
public final class MarketListing {

    public enum Type { SALE, AUCTION }

    // Versiones del formato persistido: v1 (12), v2 (13), v3 (16), v4 (17),
    // v5 (+terminalOwner=18). Se leen todas; se escribe siempre la ultima.
    private static final String VERSION = "5";
    private static final String SEP = "|";
    private static final String SEP_RE = "\\|";

    public final int id;
    public final Type type;
    public final UUID seller;
    public final String sellerName;
    public final double price;      // venta: precio pedido · subasta: puja inicial
    public final long created;
    public final long expires;      // epoch ms; 0 = sin caducidad
    private final String itemB64;

    // Estado de subasta (solo Type.AUCTION). Se muta bajo el lock del MarketManager.
    public double topBid;
    public UUID topBidder;
    public String topBidderName = "";

    // Color/estilo MiniMessage del nombre del producto (highlight por rango del vendedor),
    // fijado al publicar y persistido; "" = sin resaltado (color normal del item tag).
    public String nameColor = "";

    // Texto libre del vendedor mostrado en el anuncio y la GUI ("" = ninguno).
    public String description = "";
    // Publicacion programada: si publishAt > now el anuncio esta EN ESPERA (no visible ni
    // comprable) hasta esa hora, cuando el barrido lo anuncia. 0 = inmediato.
    public long publishAt;
    // Ya se anuncio (evita re-anunciar un programado y distingue "en espera" de "activo").
    public boolean announced = true;
    // El anuncio ya es durable pero aun debe confirmar que el item marcado salio y se guardo
    // fuera del inventario del vendedor. Mientras sea true no se muestra ni se puede comprar.
    public boolean handoffPending;
    // Destino final ya decidido durablemente. Un listing terminal nunca vuelve al mercado;
    // start() reintenta pasarlo al alijo del dueño y luego consume la fila.
    public UUID terminalOwner;

    private transient ItemStack cachedItem;

    public MarketListing(int id, Type type, UUID seller, String sellerName, double price,
                         long created, long expires, String itemB64) {
        this.id = id;
        this.type = type;
        this.seller = seller;
        this.sellerName = sellerName;
        this.price = price;
        this.created = created;
        this.expires = expires;
        this.itemB64 = itemB64;
    }

    /** Serializa un ItemStack a Base64 (bytes nativos de Paper, sobreviven updates). */
    public static String itemToB64(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /** Copia del item en deposito (decodificado una vez y cacheado). */
    public ItemStack item() {
        if (cachedItem == null) {
            cachedItem = ItemStack.deserializeBytes(Base64.getDecoder().decode(itemB64));
        }
        return cachedItem.clone();
    }

    public boolean isAuction() {
        return type == Type.AUCTION;
    }

    public boolean expired(long now) {
        return expires > 0 && now >= expires;
    }

    /** En espera de su hora de publicacion programada (no visible ni comprable todavia). */
    public boolean scheduled(long now) {
        return !announced && publishAt > now;
    }

    /** Puja minima aceptable ahora mismo (la inicial si nadie ha pujado). */
    public double minBid(double incrementPercent) {
        if (topBidder == null) return price;
        double safePercent = Double.isFinite(incrementPercent) ? Math.max(0, incrementPercent) : 0;
        double step = Math.max(1, topBid * safePercent / 100.0);
        return topBid + step;
    }

    // ---- Codec de texto (sin Bukkit; testeable) -------------------------------------

    public String encode() {
        // description en Base64: es texto libre del vendedor y podria traer el separador '|'.
        String descB64 = description == null || description.isEmpty()
                ? "" : Base64.getEncoder().encodeToString(description.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return String.join(SEP, VERSION, String.valueOf(id), type.name(), seller.toString(),
                field(sellerName), String.valueOf(price), String.valueOf(created),
                String.valueOf(expires),
                String.valueOf(topBid), topBidder == null ? "" : topBidder.toString(),
                field(topBidderName), itemB64,
                nameColor == null ? "" : nameColor,
                descB64, String.valueOf(publishAt), announced ? "1" : "0",
                handoffPending ? "1" : "0",
                terminalOwner == null ? "" : terminalOwner.toString());
    }

    /**
     * Un nombre que va en claro entre separadores. Los nombres mostrados salen del apodo, y un
     * apodo puede llevar '|': sin esto el anuncio se guardaba con un campo de mas, al reiniciar
     * no se podia leer y el objeto en deposito se perdia con el. Se cambia por una barra
     * partida, que se lee igual y no parte la linea.
     */
    static String field(String name) {
        return name == null ? "" : name.replace(SEP, "¦");
    }

    /** Reconstruye un anuncio persistido; null si el formato no se reconoce. */
    public static MarketListing decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] f = raw.split(SEP_RE, -1);
        // v1 = 12, v2 = 13, v3 = 16, v4 = 17, v5 = 18 (+terminalOwner).
        if (f.length != 12 && f.length != 13 && f.length != 16
                && f.length != 17 && f.length != 18) return null;
        if (!"1".equals(f[0]) && !"2".equals(f[0])
                && !"3".equals(f[0]) && !"4".equals(f[0])
                && !"5".equals(f[0])) return null;
        try {
            int id = Integer.parseInt(f[1]);
            double price = Double.parseDouble(f[5]);
            long created = Long.parseLong(f[6]);
            long expires = Long.parseLong(f[7]);
            double topBid = Double.parseDouble(f[8]);
            long publishAt = f.length >= 16 ? Long.parseLong(f[14]) : 0;
            if (id <= 0 || !validMoney(price) || !validMoney(topBid)
                    || created < 0 || expires < 0 || publishAt < 0 || f[11].isEmpty()) {
                return null;
            }
            // Valida el sobre Base64 al cargar. La deserializacion Bukkit completa se hace
            // perezosamente, pero basura textual no debe sobrevivir hasta una GUI/barrido.
            Base64.getDecoder().decode(f[11]);
            MarketListing l = new MarketListing(id,
                    Type.valueOf(f[2].toUpperCase(Locale.ROOT)), UUID.fromString(f[3]), f[4],
                    price, created, expires, f[11]);
            l.topBid = topBid;
            l.topBidder = f[9].isEmpty() ? null : UUID.fromString(f[9]);
            if (l.topBidder != null && topBid <= 0) return null;
            l.topBidderName = f[10];
            l.nameColor = f.length >= 13 ? f[12] : "";
            if (f.length >= 16) {
                l.description = f[13].isEmpty() ? ""
                        : dev.chatalyst.util.Sanitize.limit(new String(
                                Base64.getDecoder().decode(f[13]),
                                java.nio.charset.StandardCharsets.UTF_8),
                                MarketManager.MAX_DESCRIPTION_LENGTH);
                l.publishAt = publishAt;
                l.announced = "1".equals(f[15]);
            }
            if (f.length >= 17) l.handoffPending = "1".equals(f[16]);
            if (f.length >= 18 && !f[17].isEmpty()) l.terminalOwner = UUID.fromString(f[17]);
            return l;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean validMoney(double amount) {
        return Double.isFinite(amount) && amount >= 0;
    }
}
