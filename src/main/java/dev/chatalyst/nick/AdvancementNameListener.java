package dev.chatalyst.nick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Un logro anunciaba el nombre REAL, y lo anunciaba solo.
 *
 * <p>Es el mismo agujero que tenia {@code /list} — el servidor arma la linea con el display
 * name de Minecraft, que es el nombre de la cuenta — pero peor de carácter: {@code /list} hay
 * que escribirlo, y un logro sale disparado a todo el chat sin que nadie pida nada. Un anonimo
 * que juega una tarde encadena logros, y basta el primero para saber quien es.
 *
 * <p>Se REESCRIBE, no se calla: el logro es del jugador y merece contarse. La linea es un
 * componente traducible ({@code chat.type.advancement.*}) cuyo primer argumento es el nombre,
 * asi que se cambia ese argumento y se deja el resto — el logro conserva su hover y cada
 * cliente lo sigue leyendo en su idioma.
 *
 * <p>Si el mensaje ya no tiene esa forma (otro plugin lo reemplazo) no hay donde cortar: al
 * anonimo se le calla el anuncio, porque para el la alternativa es delatarlo, y a quien solo
 * lleva un {@code /nick} se le deja pasar, que ahi lo unico en juego es la coherencia.
 *
 * <p>No lleva interruptor a proposito: el nombre mostrado ya manda en el chat, en el tab, en
 * los nametags y en las entradas y salidas. Que el logro fuera el unico sitio con el nombre de
 * la cuenta no era una opcion del dueño, era el descuido.
 */
public final class AdvancementNameListener implements Listener {

    /**
     * HIGH: por delante del MONITOR con el que el modo anonimo silencia los anuncios durante
     * el hueco de una transicion. Reescribir primero y callar despues da el mismo resultado
     * que callar, mientras que al reves se reescribiria un mensaje ya retirado.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component message = event.message();
        if (message == null) return; // ya lo retiro alguien
        Player player = event.getPlayer();
        if (NickManager.plainName(player).equals(player.getName())) return; // sin nada que tapar
        // El nombre MOSTRADO, no el display name de Bukkit: con Nick.Set_Display_Name en
        // false ese sigue siendo el nombre de la cuenta, y el logro de un anonimo lo
        // anunciaba entero.
        Component shown = dev.chatalyst.util.MM.safe(dev.chatalyst.util.MM.closed(
                NickManager.shownName(player.getUniqueId().toString(), player.getName())));
        Component renamed = renamed(message, shown);
        if (renamed != null) {
            event.message(renamed);
            return;
        }
        if (isAnonymous(player)) event.message(null);
    }

    /**
     * Cambia el nombre del anuncio dejando intacto el resto. Null si el mensaje no tiene la
     * forma esperada, que es la señal de que aqui ya no se puede tocar nada con seguridad.
     */
    static Component renamed(Component message, Component shown) {
        if (!(message instanceof TranslatableComponent translatable)) return null;
        List<TranslationArgument> arguments = new ArrayList<>(translatable.arguments());
        if (arguments.isEmpty()) return null;
        arguments.set(0, TranslationArgument.component(shown));
        return translatable.arguments(arguments);
    }

    private static boolean isAnonymous(Player player) {
        var running = dev.chatalyst.ChatalystPlugin.get();
        return running != null && running.getAnonymousManager() != null
                && running.getAnonymousManager().isAnonymous(player.getUniqueId());
    }
}
