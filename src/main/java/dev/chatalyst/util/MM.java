package dev.chatalyst.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

/**
 * Punto unico para parsear MiniMessage.
 *
 * <p>Dos modos:
 * <ul>
 *   <li>{@link #full()} para formatos definidos por el staff en la config: permite todo
 *       (colores, gradients, hover, click, etc.).</li>
 *   <li>{@link #safe()} para el mensaje que escribe un jugador: solo colores y decoraciones,
 *       nunca click/hover/insertion. Asi un jugador no puede inyectar acciones peligrosas.</li>
 * </ul>
 */
public final class MM {

    private static final MiniMessage FULL = MiniMessage.miniMessage();

    // Solo color + decoracion + gradient/rainbow + reset. Sin click, hover, insertion, ni selector.
    private static final MiniMessage SAFE = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.reset()
            ))
            .build();

    private static final java.util.regex.Pattern PLAYER_TAG = java.util.regex.Pattern.compile(
            "(?i)<(/?)([a-z0-9_#!-]+)(?::[^<>]*)?>");
    private static final java.util.Set<String> PLAYER_STYLE_TAGS = java.util.Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white", "color", "c", "gradient", "rainbow",
            "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st",
            "obfuscated", "obf", "reset");

    private MM() {
    }

    public static Component full(String input, TagResolver... resolvers) {
        if (input == null) return Component.empty();
        return FULL.deserialize(withoutLegacyCodes(TitleEffects.toStatic(input)), resolvers);
    }

    /**
     * Neutraliza los codigos legacy antes de parsear.
     *
     * <p>MiniMessage no los tolera: en cuanto ve un '§' lanza {@code ParsingException}. Y a
     * {@link #full} llegan formatos del staff con valores de TERCEROS ya insertados dentro —
     * el prefijo de LuckPerms o Vault, el resultado de un placeholder de PlaceholderAPI, lo
     * que pinte TAB — que vienen con '§' de serie. En produccion eso reventaba el
     * AsyncChatEvent entero: el mensaje salia sin formato y la consola escupia una traza por
     * cada linea de chat, por un prefijo que ni siquiera es nuestro.
     *
     * <p>Se convierten a su equivalente MiniMessage en vez de borrarlos, para que el color que
     * el otro plugin queria siga viendose. Lo que quede sin equivalente (un '§' suelto, un
     * codigo que no existe) se cae junto a su caracter, que es como se comporta el juego.
     * Solo mira '§': un '&amp;' es texto legitimo y no puede volverse un color.
     */
    private static String withoutLegacyCodes(String input) {
        if (input.indexOf('§') < 0) return input; // el caso normal, sin coste
        return Legacy.sectionToMini(input).replaceAll("§.?", "");
    }

    /** Serializes an Adventure component for storage in staff-authored MiniMessage files. */
    public static String serialize(Component component) {
        return component == null ? "" : FULL.serialize(component);
    }

    public static Component safe(String input) {
        if (input == null) return Component.empty();
        // Nunca permitir el caracter de seccion crudo en texto de jugadores: es el codigo
        // interno de color de Minecraft y dejarlo pasar permite saltarse el filtro de color.
        String clean = input.replace('§', ' ');
        return SAFE.deserialize(clean);
    }

    /**
     * Como {@link #safe(String)}, respetando lo que el jugador puede usar. Los colores con nombre
     * y las decoraciones van con el permiso general; el hex y los degradados son niveles aparte.
     * Lo que no puede usar se queda como texto visible, para que vea que no se aplico.
     */
    public static Component safe(String input, boolean hex, boolean gradient) {
        if (input == null) return Component.empty();
        return safe(withoutLockedStyles(input, hex, gradient));
    }

    static String withoutLockedStyles(String input, boolean hex, boolean gradient) {
        if ((hex && gradient) || input.indexOf('<') < 0) return input;
        java.util.regex.Matcher matcher = PLAYER_TAG.matcher(input);
        StringBuilder out = new StringBuilder(input.length() + 8);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > 0 && input.charAt(matcher.start() - 1) == '\\') continue;
            if (isLockedStyle(matcher.group(), matcher.group(2), hex, gradient)) {
                out.append(input, last, matcher.start()).append('\\').append(matcher.group());
                last = matcher.end();
            }
        }
        return out.append(input, last, input.length()).toString();
    }

    private static boolean isLockedStyle(String tag, String rawName, boolean hex, boolean gradient) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("!")) name = name.substring(1);
        if (!gradient && (name.equals("gradient") || name.equals("rainbow"))) return true;
        if (hex) return false;
        if (name.startsWith("#")) return true;
        if (!name.equals("color") && !name.equals("colour") && !name.equals("c")) return false;
        int colon = tag.indexOf(':');
        if (colon < 0) return false;
        String value = tag.substring(colon + 1).replace("'", "").replace("\"", "").trim();
        return value.startsWith("#");
    }

    public static String escape(String input) {
        if (input == null) return "";
        return FULL.escapeTags(input);
    }

    /**
     * Quita los eventos de interaccion (click, hover, insertion) de un texto MiniMessage
     * conservando color, decoracion y gradiente. Para texto que ELIGE un jugador (nick,
     * /tag) y que luego se inserta en un formato que se parsea con {@link #full}: con el
     * permiso de color un jugador solo debe poder ponerse colores, nunca inyectar
     * {@code <click:run_command>} o {@code <hover>} en el chat que ven los demas (al pulsar,
     * el comando correria como quien pulsa). Reserializa, asi los gradientes quedan como
     * colores por caracter — mismo aspecto, sin acciones.
     */
    /**
     * Quita los tags que no son de estilo (click, hover, insertion, font...) SIN reescribir el
     * resto: a diferencia de {@link #sanitizeInteraction}, no expande un gradiente a un color
     * por letra, asi que un {placeholder} dentro de el sigue entero. Para PLANTILLAS que se
     * rellenan despues; el resultado final se pasa igualmente por sanitizeInteraction.
     *
     * <p>Se repite hasta que no cambia nada: quitar {@code <red>} de {@code <cl<red>ick:...>}
     * dejaria un {@code <click:...>} entero.
     */
    public static String stripNonStyleTags(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        String current = input.replace('§', ' ').replace("\r", "").replace("\n", "");
        for (int pass = 0; pass < 16; pass++) {
            java.util.regex.Matcher matcher = PLAYER_TAG.matcher(current);
            StringBuffer filtered = new StringBuffer(current.length());
            while (matcher.find()) {
                String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
                boolean allowed = name.startsWith("#") || PLAYER_STYLE_TAGS.contains(name);
                matcher.appendReplacement(filtered,
                        java.util.regex.Matcher.quoteReplacement(allowed ? matcher.group() : ""));
            }
            matcher.appendTail(filtered);
            String next = filtered.toString();
            if (next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    public static String sanitizeInteraction(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        // Parsear directamente con el resolver permitido impide no solo click/hover, sino
        // tambien <newline>/<br>, selector, font y cualquier tag estandar no destinado a
        // estilos de jugador. Al serializar, los tags desconocidos quedan escapados.
        String clean = input.replace('§', ' ').replace("\r", "").replace("\n", "");
        java.util.regex.Matcher matcher = PLAYER_TAG.matcher(clean);
        StringBuffer filtered = new StringBuffer(clean.length());
        while (matcher.find()) {
            String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            boolean allowed = name.startsWith("#") || PLAYER_STYLE_TAGS.contains(name);
            matcher.appendReplacement(filtered,
                    java.util.regex.Matcher.quoteReplacement(allowed ? matcher.group() : ""));
        }
        matcher.appendTail(filtered);
        Component safe = SAFE.deserialize(filtered.toString());
        return FULL.serialize(safe).replace("\r", "").replace("\n", "");
    }

    /**
     * Cierra los tags de estilo que {@code input} deja abiertos, para poder insertarlo en medio
     * de un formato sin que su estilo siga mas alla.
     *
     * <p>Un apodo se guarda como {@code <gradient:dark_red:red>Pepe}, sin cierre. Metido en
     * {@code {player_prefix}{player_nick}{afk_tag}} o en el formato del chat, el gradiente no
     * terminaba en "Pepe": se repartia sobre todo lo que venia detras (la etiqueta AFK, el
     * mensaje entero), y al apodo le tocaba solo el primer tramo — se veia de un color casi
     * plano. Lo mismo con una negrita, que se colaba al resto de la linea.
     *
     * <p>Cada cierre repite el nombre de su apertura ({@code </gradient>}, {@code </#FF6B9B>}):
     * un cierre que no casa MiniMessage lo imprime literal.
     */
    public static String closed(String input) {
        if (input == null || input.indexOf('<') < 0) return input;
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        java.util.regex.Matcher matcher = PLAYER_TAG.matcher(input);
        while (matcher.find()) {
            // "\<" es un '<' escapado (MM.escape): texto, no un tag.
            if (matcher.start() > 0 && input.charAt(matcher.start() - 1) == '\\') continue;
            String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            if (!name.startsWith("#") && !PLAYER_STYLE_TAGS.contains(name)) continue;
            if (!matcher.group(1).isEmpty()) {
                // Cerrar un tag cierra tambien lo abierto DENTRO de el ("<red><bold>Pepe</red>"
                // deja la negrita cerrada). Quitar solo el nombrado dejaba "bold" en la pila,
                // y el "</bold>" anadido al final salia literal en el apodo.
                if (open.contains(name)) {
                    while (!open.isEmpty() && !open.pop().equals(name)) {
                        // desapila hasta el cerrado, incluido
                    }
                }
            } else if (name.equals("reset")) {
                open.clear();
            } else {
                open.push(name);
            }
        }
        if (open.isEmpty()) return input;
        StringBuilder out = new StringBuilder(input);
        for (String name : open) out.append("</").append(name).append('>');
        return out.toString();
    }

    /**
     * Quita cualquier tag MiniMessage, devolviendo texto plano.
     *
     * <p>Se repite hasta que no queda nada que quitar. Una sola pasada no basta y era un
     * agujero: {@code <cl<red>ick:run_command:'/op x'>Pulsa} pierde el {@code <red>} de
     * dentro y lo que QUEDA es un {@code <click:...>} entero, y {@code \<click:...>} sale
     * sin su barra. El "texto plano" resultante se mete despues en plantillas que si se
     * parsean (lang, lores, formatos), asi que quien controlara ese texto —un apodo, el
     * cuerpo de un correo— plantaba un boton vivo en la pantalla de otro.
     */
    public static String strip(String input) {
        if (input == null) return "";
        String current = TitleEffects.toStatic(input);
        for (int pass = 0; pass < STRIP_PASSES; pass++) {
            String next = FULL.stripTags(current);
            if (next.equals(current)) return next;
            current = next;
        }
        // Algo construido para seguir destapando etiquetas pasada tras pasada: se escapa lo
        // que quede en vez de devolverlo con una etiqueta todavia dentro.
        return FULL.escapeTags(current);
    }

    private static final int STRIP_PASSES = 8;

    /**
     * Quita solo los colores de un texto MiniMessage y conserva sus decoraciones. Se usa al
     * mostrar un nick dentro de un contexto donde el color del rango debe tener prioridad:
     * un nick azul puede seguir siendo bold/italic, pero ya no puede pisar el rojo del rango.
     */
    public static String stripColors(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        return FULL.serialize(stripColors(SAFE.deserialize(TitleEffects.toStatic(input).replace('§', ' '))));
    }

    private static Component stripColors(Component component) {
        Component out = component.color(null);
        if (component.children().isEmpty()) return out;
        java.util.List<Component> children = new java.util.ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(stripColors(child));
        }
        return out.children(children);
    }

    /**
     * Primer color visible de un texto MiniMessage (en un gradiente, el color de la primera
     * letra), o null si no tiene color. Util para derivar un color "primario" de un nick.
     */
    public static TextColor firstColor(String input) {
        if (input == null || input.isEmpty()) return null;
        return firstColor(safe(input));
    }

    private static TextColor firstColor(Component component) {
        if (component.color() != null) return component.color();
        for (Component child : component.children()) {
            TextColor found = firstColor(child);
            if (found != null) return found;
        }
        return null;
    }
}
