package dev.chatalyst.gui;

import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Identidad visual comun de las interfaces de inventario.
 *
 * <p>El contenido del titulo sigue viniendo del archivo de idioma de cada menu. Esta clase
 * solo aplica la presentacion espacial de Chatalyst en un unico punto, por lo que una GUI
 * nueva no puede reaparecer accidentalmente con la paleta antigua.
 */
public final class MenuTheme {

    static final String TITLE_GRADIENT = "<gradient:#240046:#9D4EDD:#E0AAFF><bold>";
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private MenuTheme() {
    }

    public static Component inventoryTitle(Component localizedTitle) {
        String plain = PLAIN.serialize(localizedTitle == null ? Component.empty() : localizedTitle);
        return MM.full(decorateTitle(plain));
    }

    /** Nucleo puro y testeable del estilo de titulo. */
    static String decorateTitle(String title) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty()) clean = "Chatalyst";
        String framed = clean.indexOf('✦') >= 0 ? clean : "✦ " + clean + " ✦";
        return TITLE_GRADIENT + MM.escape(framed) + "</bold></gradient>";
    }
}
