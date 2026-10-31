package dev.chatalyst.rental;

import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * El vale: un objeto que se compra en la tienda de otro plugin y aqui se convierte en alquiler.
 *
 * <p>Existe por una limitacion concreta y comprobada: EconomyShopGUI en su version gratuita solo
 * sabe vender OBJETOS — su constructor de items no conoce comandos ni permisos, eso es de la
 * version de pago. Asi que la tienda vende un papel y Chatalyst lo canjea. De paso sale ganando
 * algo que un permiso no da: un vale se puede guardar y se puede regalar.
 *
 * <p>La marca va en el LORE y no en el nombre. El nombre se puede cambiar en un yunque; el lore
 * NO se puede escribir en supervivencia por ningun medio vanilla, asi que un jugador no puede
 * fabricarse un vale. Quien pueda editar NBT ya podia darse el rango directamente.
 */
public final class RentalVoucher {

    /** Lo que hace que una linea de lore sea una marca. Se compara sin colores ni mayusculas. */
    private static final String MARK = "vale:";

    private RentalVoucher() {
    }

    /** El id de oferta que lleva este objeto, o null si no es un vale. */
    public static String offerIn(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        List<Component> lore = item.getItemMeta().lore();
        if (lore == null) return null;
        for (Component line : lore) {
            // Se serializa a texto plano: asi da igual que la tienda escribiera el lore con
            // codigos & o con MiniMessage, y da igual el color que le pusiera.
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(line);
            String flat = MM.stripColors(plain).trim().toLowerCase(Locale.ROOT);
            int at = flat.indexOf(MARK);
            if (at < 0) continue;
            String id = flat.substring(at + MARK.length()).trim();
            // El id es lo que queda tras la marca. Se valida con la MISMA regla que un id de
            // oferta: si aqui colara algo que el catalogo rechaza, el vale seria incanjeable y
            // el jugador se quedaria con un papel que pago.
            String normalized = RentalOffer.normalize(id);
            if (normalized != null) return normalized;
        }
        return null;
    }
}
