package dev.chatalyst.market;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.discord.DiscordEmbed;

import java.util.List;
import java.util.Locale;

/**
 * Lo que el mercado cuenta en Discord cuando un trato termina.
 *
 * <p>El anuncio del juego es una línea de chat que se pierde en cuanto alguien escribe: sirve
 * para enterarse en el momento, no para consultarlo. En Discord queda escrito, así que aquí se
 * cuenta la operación entera — quién vendió, quién compró, qué salió y qué se pagó — en una
 * tarjeta que se lee de un vistazo y sigue ahí mañana.
 *
 * <p>El texto va en INGLÉS a propósito y no pasa por {@code lang/}: no hay un espectador de
 * quien deducir el idioma. Un canal de Discord lo lee todo el servidor a la vez, y media sala
 * leyendo un idioma que no eligió es peor que un solo idioma, elegido y estable.
 */
public final class MarketDiscordFeed {

    /** Los mismos morados de la casa, para que la tarjeta no desentone con el resto. */
    private static final int COLOR_SALE = 0xB56CFF;
    private static final int COLOR_AUCTION = 0xC77DFF;
    private static final int COLOR_RETURN = 0x7B68A0;

    private final ChatalystPlugin plugin;

    public MarketDiscordFeed(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    /** Una venta cerrada: dinero, trueque, o las dos cosas. */
    public void sold(String seller, String buyer, String item, String price) {
        publish("Sales", saleEmbed("Item sold", COLOR_SALE, seller, buyer, item, price));
    }

    /**
     * Lo que se pagó, en texto llano.
     *
     * <p>El resumen del juego sale de {@code lang/} y viene con etiquetas de MiniMessage
     * dentro; en Discord eso se leería crudo. Aquí se arma aparte, que además permite decir
     * "dinero Y objetos" en un trueque mixto en vez de quedarse con la mitad.
     */
    static String paid(String money, int barterItems) {
        String amount = plain(money);
        boolean hasMoney = !amount.isEmpty();
        boolean hasItems = barterItems > 0;
        String items = barterItems + (barterItems == 1 ? " item" : " items");
        if (hasMoney && hasItems) return amount + " + " + items + " (barter)";
        if (hasItems) return items + " (barter)";
        return hasMoney ? amount : "nothing";
    }

    /** Una subasta adjudicada. */
    public void auctionWon(String seller, String winner, String item, String amount) {
        publish("Auctions", saleEmbed("Auction won", COLOR_AUCTION, seller, winner,
                item, amount));
    }

    /**
     * Algo que vuelve a su dueño sin haber cambiado de manos: caducó, se retiró, una subasta
     * sin pujas, o un ganador que no pudo pagar.
     */
    public void returned(String owner, String item, String reason) {
        publish("Returns", DiscordEmbed.titled("Item returned", COLOR_RETURN)
                .description("Nothing changed hands. The item went back to its owner.")
                .field("Owner", code(owner), true)
                .field("Item", code(item), true)
                .field("Reason", plain(reason), false)
                .footer("Chatalyst market")
                .build());
    }

    private DiscordEmbed saleEmbed(String title, int color, String seller, String buyer,
                                   String item, String price) {
        return DiscordEmbed.titled(title, color)
                // Vendedor y comprador en linea, uno al lado del otro: es lo primero que se
                // busca al leer un aviso de venta, y asi cabe en un renglon.
                .field("Seller", code(seller), true)
                .field("Buyer", code(buyer), true)
                .field("Item", code(item), false)
                .field("Paid", plain(price), false)
                .footer("Chatalyst market")
                .build();
    }

    private void publish(String eventKey, DiscordEmbed embed) {
        var bot = plugin.getDiscordBot();
        if (bot == null) return;
        var config = plugin.getConfig();
        if (!config.getBoolean("Discord.Market.Enabled", false)) return;
        // Cada tipo de aviso se puede apagar por separado: un mercado con movimiento llena un
        // canal de Discord en minutos, y quien solo quiere ver ventas no deberia tener que
        // aguantar cada anuncio caducado para conseguirlo.
        if (!config.getBoolean("Discord.Market." + eventKey, defaultFor(eventKey))) return;
        String channelId = config.getString("Discord.Market.Channel_Id", "").trim();
        if (channelId.isEmpty()) {
            // Sin canal propio cae en el del chat principal, que es donde ya se esta leyendo
            // el servidor. Mejor eso que no publicar nada por una clave sin rellenar.
            channelId = config.getString("Discord.Channel_Id", "").trim();
        }
        bot.sendEmbed(channelId, embed);
    }

    /** Las devoluciones son las mas ruidosas y las menos noticia: apagadas de fabrica. */
    static boolean defaultFor(String eventKey) {
        return !eventKey.equals("Returns");
    }

    /**
     * Texto de jugador dentro de un bloque de codigo.
     *
     * <p>Un nick puede traer asteriscos, barras bajas o backticks, y en Discord eso es formato:
     * bastaria para deformar la tarjeta o hacerla pasar por otra cosa. Dentro de un bloque no
     * hay formato que valga, y los backticks propios se quitan para que nadie cierre el bloque
     * antes de tiempo.
     */
    static String code(String value) {
        String flat = plain(value);
        if (flat.isBlank()) return "";
        return "`" + flat.replace("`", "'") + "`";
    }

    /**
     * Una sola linea, sin huecos dobles.
     *
     * <p>Los espacios en blanco seguidos se juntan en uno: un CRLF son DOS caracteres, y
     * cambiar cada uno por un espacio deja un hueco doble en mitad del campo. Discord no lo
     * recorta por su cuenta, asi que se nota.
     */
    static String plain(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    /** Resumen legible de un ItemStack ya aplanado: "64x Diamond". */
    static String describe(String itemName, int amount) {
        String name = plain(itemName);
        if (name.isEmpty()) name = "unknown item";
        return amount > 1 ? amount + "x " + name : name;
    }

    /** Motivos con forma de frase, no de constante. */
    static String reason(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "expired" -> "The listing expired";
            case "cancelled" -> "The seller withdrew the listing";
            case "cancelled-by-staff" -> "A moderator withdrew the listing";
            case "no-bids" -> "The auction closed without bids";
            case "payment-failed" -> "The winner could not pay";
            default -> "The listing ended";
        };
    }

    /** Solo para tests: los tipos de evento que existen, tal cual se leen en la config. */
    static List<String> eventKeys() {
        return List.of("Sales", "Auctions", "Returns");
    }
}
