package dev.chatalyst.hook;

import dev.chatalyst.util.Legacy;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Pattern;

/**
 * Normaliza el VALOR que devuelve un placeholder de PlaceholderAPI antes de meterlo en un
 * formato MiniMessage.
 *
 * <p>Casi todos los plugins devuelven sus colores en legacy: el tag de UltimateClans llega como
 * {@code &f&lDios}. MiniMessage no entiende '&', asi que el chat lo pintaba literal
 * ({@code [&f&lDios]}). Aqui SOLO se toca lo que devolvio el placeholder, nunca el formato ni
 * el mensaje del jugador: en un texto escrito, "&" es texto legitimo.
 *
 * <p>Si el valor es legacy puro se convierte con Adventure, que cierra lo que abre
 * ({@code <white><bold>Dios</bold></white>}): un &l del clan no se come el "]" que le sigue en
 * el formato. Si ademas trae etiquetas MiniMessage propias, pasar por el serializer las
 * escaparia, asi que se conservan y solo se traducen los codigos.
 */
final class PapiValues {

    /** &0-9a-fk-or, &#RRGGBB y la forma &x&R&R&G&G&B&B, con '&' o '§'. */
    private static final Pattern LEGACY_CODE =
            Pattern.compile("(?i)[&§](?:[0-9a-fk-orx]|#[0-9a-f]{6})");

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private PapiValues() {
    }

    static String toMini(String value) {
        if (value == null || value.isEmpty()) return value;
        boolean legacy = LEGACY_CODE.matcher(value).find();
        if (value.indexOf('<') >= 0) {
            // El valor trae MiniMessage propio, y muchas veces lo escribio un JUGADOR (el tag
            // de un clan, un apodo de otro plugin). Entraba tal cual en el formato, asi que un
            // lider de clan podia meter <click:run_command:'/pay ...'> en el chat de todos. Se
            // conservan colores y estilos; click, hover, insertion y demas acciones se quitan.
            // <flujo>/<brillo>/<glitch> (titulos, %chatalyst_title_text_anim%) se apartan del
            // filtro: no los conoce y los borraba, y el titulo flotante salia en blanco.
            return dev.chatalyst.util.TitleEffects.sanitizeKeeping(
                    legacy ? Legacy.toMini(value) : value,
                    dev.chatalyst.util.MM::sanitizeInteraction);
        }
        if (!legacy) return value; // el caso normal, sin coste
        return MiniMessage.miniMessage().serialize(LEGACY.deserialize(value.replace('§', '&')));
    }
}
