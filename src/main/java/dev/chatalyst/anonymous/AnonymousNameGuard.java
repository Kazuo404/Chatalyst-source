package dev.chatalyst.anonymous;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ultima linea: tapa el nombre real de un anonimo en texto que Chatalyst NO escribio.
 *
 * <p>El plugin puede arreglar sus propios mensajes y hasta los del servidor, pero no los de los
 * demas: una tumba de GravesX con tu nombre encima, el aviso de un anticheat, el mensaje de
 * muerte de otro plugin de chat. Cualquiera de esos delata al anonimo igual de bien que todo lo
 * que hay montado para evitarlo, y no hay forma de arreglarlos uno a uno — ni de saber cuales
 * habra instalados mañana.
 *
 * <p>Asi que se corrige al final del camino: justo antes de que el texto salga hacia el
 * cliente, el nombre real se sustituye por el alias que ese jugador lleva puesto. No es
 * censura, es traduccion — el mensaje llega entero y con sentido, solo que nombrando a la
 * persona como la conoce quien lo lee.
 *
 * <p>Trabaja sobre COMPONENTES, que es lo unico que se puede interceptar de camino al cliente.
 * Un holograma dibujado a base de paquetes crudos, o un texto que otro plugin escriba a un
 * fichero o a una web, no pasa por aqui y no hay forma de que pase.
 *
 * <p>Al staff que puede usar {@code /realname} no se le toca nada: ya tiene permitido resolver
 * identidades, y un aviso de moderacion con el nombre cambiado le complicaria el trabajo sin
 * esconder nada que no pudiera averiguar igual.
 *
 * <p>Sustituye por PALABRA COMPLETA. Un reemplazo por subcadena convertiria a "Kazu" dentro de
 * "Kazuki" y ensuciaria mensajes que no hablaban de nadie.
 */
public final class AnonymousNameGuard {

    private AnonymousNameGuard() {
    }

    /**
     * Marca de "esto lo tecleo un jugador": una insercion VACIA. No se ve, no hace nada al
     * pulsar con shift, y viaja con el componente hasta el paquete, que es donde se mira.
     */
    private static final String AUTHORED = "";

    /**
     * Marca el texto que escribio un jugador para que {@link #rewrite} no lo toque.
     *
     * <p>Este guardian existe para lo que escriben OTROS PLUGINS. Aplicado tambien a lo que
     * teclea un jugador era justo lo contrario de lo que promete: bastaba escribir
     * {@code /me DevKazuo} para leer de vuelta {@code * Yo illiolil} y saber en un segundo
     * quien llevaba el disfraz. Lo que un jugador escribe tiene que salir tal cual lo escribio,
     * exista el guardian o no — cualquier diferencia visible es una pista.
     *
     * <p>Si el componente ya trae una insercion propia se deja sin marcar: quedarse con el
     * comportamiento de antes en ese caso raro es mejor que pisar algo que alguien configuro.
     */
    public static Component authored(Component text) {
        if (text == null || text.insertion() != null) return text;
        return text.insertion(AUTHORED);
    }

    private static boolean isAuthored(Component component) {
        return AUTHORED.equals(component.insertion());
    }

    /** True si en los hijos (a cualquier profundidad) hay texto marcado como de jugador. */
    private static boolean holdsAuthored(Component component) {
        for (Component child : component.children()) {
            if (isAuthored(child) || holdsAuthored(child)) return true;
        }
        return false;
    }

    /**
     * Devuelve el componente con los nombres reales sustituidos, o el MISMO objeto si no habia
     * nada que cambiar. Quien llama usa esa identidad para no re-encodear paquetes en vano.
     */
    public static Component rewrite(Component message, Map<String, String> disguised) {
        if (message == null || disguised.isEmpty()) return message;
        Compiled compiled = compiled(disguised);
        // Casi ningun paquete nombra a un disfrazado: si ninguna cadena del componente contiene
        // un nombre (busqueda exacta, la misma distincion de mayusculas que el patron), no hay
        // nada que reemplazar ni click que quitar y se devuelve el mismo objeto.
        if (!containsAnyName(message, compiled.names())) return message;
        return stripLeakyClicks(replaceOutsideAuthored(message, compiled.configs()), compiled);
    }

    /**
     * Patrones y reemplazos ya compilados para un conjunto concreto de disfrazados. Se llama
     * desde el hilo de red por cada paquete; compilar un Pattern por nombre en cada uno era la
     * mayor parte del coste.
     */
    record Compiled(Map<String, String> disguised, List<String> names,
                    List<Pattern> patterns, List<TextReplacementConfig> configs) {
    }

    /** Ultimo conjunto compilado. Inmutable y volatile: seguro entre hilos de red sin locks. */
    private static volatile Compiled lastCompiled;

    /**
     * Reutiliza lo compilado mientras el conjunto (nombre real -> alias) sea el mismo. Un alias
     * nuevo cambia el mapa, asi que nunca se reemplaza con un alias viejo.
     */
    static Compiled compiled(Map<String, String> disguised) {
        Compiled current = lastCompiled;
        if (current != null && current.disguised().equals(disguised)) return current;
        // Copia propia (sin nulos) para comparar en el proximo paquete aunque el llamante mute
        // su mapa; un alias nulo se reemplaza por vacio, como antes.
        Map<String, String> clean = new java.util.HashMap<>();
        disguised.forEach((real, alias) -> {
            if (real != null) clean.put(real, alias == null ? "" : alias);
        });
        Map<String, String> snapshot = Map.copyOf(clean);
        List<String> names = new ArrayList<>(snapshot.size());
        List<Pattern> patterns = new ArrayList<>(snapshot.size());
        List<TextReplacementConfig> configs = new ArrayList<>(snapshot.size());
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String real = entry.getKey();
            if (real == null || real.isEmpty()) continue;
            Pattern pattern = word(real);
            names.add(real);
            patterns.add(pattern);
            configs.add(config(pattern, entry.getValue()));
        }
        Compiled fresh = new Compiled(snapshot, List.copyOf(names), List.copyOf(patterns),
                List.copyOf(configs));
        lastCompiled = fresh;
        return fresh;
    }

    /**
     * True si alguna cadena que el reemplazo o el filtro de clicks llegarian a mirar contiene
     * un nombre: texto, argumentos de traduccion, hovers (texto y nombre de entidad), clicks e
     * hijos. Es un superconjunto de lo que encontraria el patron por palabra, asi que un false
     * garantiza que rewrite no cambiaria nada.
     */
    static boolean containsAnyName(Component component, List<String> names) {
        if (component == null || names.isEmpty()) return false;
        if (component instanceof net.kyori.adventure.text.TextComponent text
                && containsAny(text.content(), names)) return true;
        ClickEvent click = component.style().clickEvent();
        if (click != null && containsAny(click.value(), names)) return true;
        HoverEvent<?> hover = component.style().hoverEvent();
        if (hover != null) {
            Object value = hover.value();
            if (value instanceof Component inner && containsAnyName(inner, names)) return true;
            if (value instanceof HoverEvent.ShowEntity entity
                    && containsAnyName(entity.name(), names)) return true;
        }
        if (component instanceof TranslatableComponent translatable) {
            for (TranslationArgument arg : translatable.arguments()) {
                if (arg.value() instanceof Component inner && containsAnyName(inner, names)) {
                    return true;
                }
            }
        }
        for (Component child : component.children()) {
            if (containsAnyName(child, names)) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> names) {
        if (text == null || text.isEmpty()) return false;
        for (String name : names) {
            if (text.contains(name)) return true;
        }
        return false;
    }

    /**
     * Sustituye los nombres en todo el componente SALVO en lo que tecleo un jugador.
     *
     * <p>El reemplazo de Adventure no sabe saltarse una rama, asi que cuando hay texto de
     * jugador dentro se baja a mano: cada pieza se trata sin sus hijos y los hijos se
     * resuelven uno a uno. Cuando no lo hay — casi siempre: un aviso de otro plugin no lleva
     * nada marcado — se usa el camino directo de siempre.
     */
    private static Component replaceOutsideAuthored(Component node,
                                                    List<TextReplacementConfig> configs) {
        if (isAuthored(node)) return node;
        if (!holdsAuthored(node)) return replaceAll(node, configs);

        List<Component> children = node.children();
        Component alone = node.children(List.of());
        Component replaced = replaceAll(alone, configs);
        List<Component> rebuilt = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component safe = replaceOutsideAuthored(child, configs);
            if (safe == child) continue;
            if (rebuilt == null) rebuilt = new ArrayList<>(children);
            rebuilt.set(i, safe);
        }
        if (replaced == alone && rebuilt == null) return node;
        // Lo que el reemplazo haya partido en hijos nuevos va delante: era el texto propio de
        // la pieza, que siempre se lee antes que sus hijos.
        List<Component> all = new ArrayList<>(replaced.children());
        all.addAll(rebuilt != null ? rebuilt : children);
        return replaced.children(all);
    }

    private static Component replaceAll(Component component, List<TextReplacementConfig> configs) {
        Component out = component;
        for (TextReplacementConfig config : configs) out = out.replaceText(config);
        return out;
    }

    /**
     * Quita el click de las piezas cuya ACCION nombra a un anonimo.
     *
     * <p>El reemplazo de texto de Adventure recorre hijos, argumentos de traduccion y hovers
     * — eso esta comprobado — pero no entra en el click: un {@code [Reclamar tumba]} que
     * sugiere {@code /msg DevKazuo} sale con el texto ya tapado y el nombre real intacto
     * dentro, listo para aparecer en la caja de chat en cuanto alguien pulse.
     *
     * <p>Se quita en vez de traducirse, al reves que el texto. Un comando con el alias dentro
     * no seria el mismo comando: en el mejor caso no encuentra a nadie, y en el peor da con
     * OTRA cuenta que de verdad se llama asi. Un enlace o un portapapeles con el nombre
     * cambiado tienen el mismo problema. Sin click el mensaje se sigue leyendo entero; lo
     * unico que se pierde es un atajo que llevaba a una persona que ahora mismo no existe.
     */
    private static Component stripLeakyClicks(Component component, Compiled disguised) {
        // Tampoco los enlaces que pego un jugador: un enlace que deja de poder pulsarse justo
        // cuando contiene cierto nombre delata ese nombre igual que cambiarlo.
        if (isAuthored(component)) return component;
        Component out = component;

        ClickEvent click = out.style().clickEvent();
        if (click != null && mentions(click.value(), disguised)) {
            out = out.style(out.style().clickEvent(null));
        }

        // Un hover de texto es un componente completo y puede llevar su propio click dentro.
        HoverEvent<?> hover = out.style().hoverEvent();
        if (hover != null && hover.value() instanceof Component inner) {
            Component safe = stripLeakyClicks(inner, disguised);
            if (safe != inner) out = out.style(out.style().hoverEvent(HoverEvent.showText(safe)));
        }

        List<Component> children = out.children();
        List<Component> rebuiltChildren = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component safe = stripLeakyClicks(child, disguised);
            if (safe == child) continue;
            if (rebuiltChildren == null) rebuiltChildren = new ArrayList<>(children);
            rebuiltChildren.set(i, safe);
        }
        if (rebuiltChildren != null) out = out.children(rebuiltChildren);

        if (out instanceof TranslatableComponent translatable) {
            List<TranslationArgument> args = translatable.arguments();
            List<ComponentLike> rebuiltArgs = null;
            for (int i = 0; i < args.size(); i++) {
                TranslationArgument arg = args.get(i);
                if (!(arg.value() instanceof Component inner)) continue;
                Component safe = stripLeakyClicks(inner, disguised);
                if (safe == inner) continue;
                if (rebuiltArgs == null) rebuiltArgs = new ArrayList<>(args);
                rebuiltArgs.set(i, safe);
            }
            if (rebuiltArgs != null) out = translatable.arguments(rebuiltArgs);
        }

        return out;
    }



    /**
     * True si en ese texto plano aparece el nombre real de alguien disfrazado. Privado: lo unico
     * que lo necesita es el filtro de clicks, cuyo valor es una cadena y no un componente.
     */
    private static boolean mentions(String text, Compiled disguised) {
        if (text == null || text.isEmpty()) return false;
        for (Pattern pattern : disguised.patterns()) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    private static TextReplacementConfig config(Pattern real, String alias) {
        return TextReplacementConfig.builder()
                .match(real)
                .replacement(alias == null ? "" : alias)
                .build();
    }

    static Pattern word(String real) {
        return Pattern.compile("\\b" + Pattern.quote(real) + "\\b");
    }
}
