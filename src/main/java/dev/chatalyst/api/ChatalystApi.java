package dev.chatalyst.api;

import org.bukkit.Bukkit;

import java.util.Optional;

/**
 * Stable entry point for integrations with Chatalyst.
 *
 * <p>The recommended lookup is {@code ChatalystApi.get()}. The service is registered after
 * Chatalyst finishes loading its durable stores and is unregistered during shutdown.
 */
public interface ChatalystApi {

    String API_VERSION = "1";

    /** Version of the public contract, independent from the plugin release version. */
    default String apiVersion() {
        return API_VERSION;
    }

    /** Titles, achievements, collections and progression. */
    TitlesApi titles();

    /** Resolves the active Chatalyst service without casting the plugin main class. */
    static Optional<ChatalystApi> get() {
        try {
            return Optional.ofNullable(
                    Bukkit.getServicesManager().load(ChatalystApi.class));
        } catch (Throwable unavailable) {
            return Optional.empty();
        }
    }
}
