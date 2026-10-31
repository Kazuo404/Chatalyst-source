package dev.chatalyst.api;

import org.bukkit.Bukkit;

/** Keeps public event construction safe in unit tests and during early shutdown. */
final class ApiEventThread {

    private ApiEventThread() {
    }

    static boolean asynchronous() {
        try {
            return !Bukkit.isPrimaryThread();
        } catch (Throwable unavailable) {
            return true;
        }
    }
}
