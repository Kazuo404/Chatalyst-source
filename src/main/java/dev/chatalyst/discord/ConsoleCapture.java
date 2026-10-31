package dev.chatalyst.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Una consola que ademas apunta lo que le contestan.
 *
 * <p>Un comando ejecutado desde Discord se despacha como consola, y ahi esta el problema:
 * {@code /check} o {@code /seen} existen POR su respuesta, y esa respuesta acababa en el log
 * del servidor — o sea, en el unico sitio donde no puede verla quien pregunto desde Discord.
 *
 * <p>Esto envuelve al emisor real de la consola y se queda con lo que le mandan, para poder
 * devolverlo. Todo lo demas — permisos, nombre, identidad — lo sigue resolviendo la consola de
 * verdad: no es un emisor falso con permisos regalados, es el mismo escuchando.
 *
 * <p>Se usa un proxy porque {@code ConsoleCommandSender} tiene decenas de metodos y solo dos
 * nos interesan; implementarla entera solo para interceptar {@code sendMessage} seria copiar
 * la API de Bukkit dentro del plugin y quedarse desactualizado en la siguiente version.
 */
final class ConsoleCapture {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();
    /** Discord corta a 2000; el resto se recorta al enviar. */
    private static final int MAX_LINES = 40;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final ConsoleCommandSender sender;

    ConsoleCapture() {
        ConsoleCommandSender real = Bukkit.getConsoleSender();
        this.sender = (ConsoleCommandSender) Proxy.newProxyInstance(
                ConsoleCapture.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null) {
                        record(args);
                    }
                    // Se reenvia SIEMPRE, tambien el mensaje: la consola del servidor debe
                    // seguir teniendo el rastro completo de lo que se ejecuto desde fuera.
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException wrapped) {
                        // Se relanza lo que fallo DE VERDAD. Sin esto el proxy envuelve la
                        // excepcion en una UndeclaredThrowableException, y quien la recoge
                        // arriba para contarsela a Discord lee "null" donde deberia estar el
                        // motivo del fallo.
                        throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
                    }
                });
    }

    ConsoleCommandSender sender() {
        return sender;
    }

    /** Lo que contesto el comando, o vacio si no dijo nada. */
    List<String> captured() {
        return List.copyOf(lines);
    }

    private void record(Object[] args) {
        for (Object argument : args) {
            if (lines.size() >= MAX_LINES) return;
            if (argument instanceof Component component) {
                add(PLAIN.serialize(component));
            } else if (argument instanceof String text) {
                add(text);
            } else if (argument instanceof String[] many) {
                for (String text : many) add(text);
            } else if (argument instanceof Component[] many) {
                for (Component component : many) add(PLAIN.serialize(component));
            }
        }
    }

    private void add(String text) {
        if (text == null) return;
        String flat = text.replace('\r', ' ').trim();
        if (!flat.isEmpty() && lines.size() < MAX_LINES) lines.add(flat);
    }
}
