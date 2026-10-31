package dev.chatalyst.itemwatch;

import org.bukkit.Material;

import java.util.List;

/**
 * Lo que la vigilancia necesita saber de un stack, sin depender del servidor.
 *
 * <p>Se copia desde el {@code ItemStack} en el hilo de su dueño y luego se cuenta en cualquier
 * hilo: asi las mochilas (que se leen de disco) no tienen que contarse en la region del
 * jugador, y la logica entera se prueba sin arrancar Paper.
 *
 * @param contents lo que lleva DENTRO (shulker, bundle); vacio si no es un contenedor
 */
public record WatchedStack(Material type, int amount, int maxStack, String name,
                           List<String> lore, List<WatchedStack> contents) {

    public WatchedStack {
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    public static WatchedStack of(Material type, int amount) {
        return new WatchedStack(type, amount, 64, "", List.of(), List.of());
    }
}
